package com.leaden2.app;

import android.content.Intent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.util.Log;
import android.speech.SpeechRecognizer;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.MediaSessionService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class PlaybackService extends MediaSessionService {
    public static final String ACTION_ACTIVATE = "com.leaden2.app.ACTION_ACTIVATE";
    public static final String ACTION_ASSISTANT = "com.leaden2.app.ACTION_ASSISTANT";
    public static final String ACTION_ENABLE = "com.leaden2.app.ACTION_ENABLE";
    public static final String ACTION_DEACTIVATE = "com.leaden2.app.ACTION_DEACTIVATE";
    public static final String ACTION_PLAY_LIBRARY_AUDIO = "com.leaden2.app.ACTION_PLAY_LIBRARY_AUDIO";
    public static final String ACTION_STOP_LIBRARY_AUDIO = "com.leaden2.app.ACTION_STOP_LIBRARY_AUDIO";
    public static final String ACTION_WHATSAPP_ALERT = "com.leaden2.app.ACTION_WHATSAPP_ALERT";
    public static final String EXTRA_WHATSAPP_SENDER = "com.leaden2.app.EXTRA_WHATSAPP_SENDER";
    public static final String EXTRA_WHATSAPP_TEXT = "com.leaden2.app.EXTRA_WHATSAPP_TEXT";
    public static final String EXTRA_AUDIO_URI = "audio_uri";
    public static final String EXTRA_AUDIO_ID = "audio_id";
    private static final String TAG = "ORVEXPlayback";
    private static final String AUDIO_CHANNEL_ID = "leaden2_audio_playback";
    private static final int AUDIO_FOREGROUND_NOTIFICATION_ID = 3305;

    private ExoPlayer exoPlayer;
    private MediaSession mediaSession;
    private boolean activated = false;
    private boolean handlingAssistantAction = false;
    private boolean listenAfterCue = false;
    private boolean libraryAudioPlaying = false;
    private boolean consumingDuplicateMediaAction = false;
    private String pendingWhatsAppSender;
    private String pendingWhatsAppText;
    private final Handler mediaActionHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build();

        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.setAudioAttributes(audioAttributes, true);
        exoPlayer.setHandleAudioBecomingNoisy(true);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && libraryAudioPlaying) {
                    libraryAudioPlaying = false;
                    Log.i(TAG, "La pista de la biblioteca terminó.");
                }
                if (playbackState == Player.STATE_ENDED && listenAfterCue) {
                    listenAfterCue = false;
                    if (pendingWhatsAppText != null) {
                        String sender = pendingWhatsAppSender;
                        String text = pendingWhatsAppText;
                        pendingWhatsAppSender = null;
                        pendingWhatsAppText = null;
                        requestWhatsAppConfirmation(sender, text);
                    } else {
                        requestVoiceListening();
                    }
                } else if (playbackState == Player.STATE_ENDED && pendingWhatsAppText != null) {
                    String sender = pendingWhatsAppSender;
                    String text = pendingWhatsAppText;
                    pendingWhatsAppSender = null;
                    pendingWhatsAppText = null;
                    playWhatsAppAlert(sender, text);
                }
            }
        });

        Player sessionPlayer = new ForwardingPlayer(exoPlayer) {
            @Override
            public void play() {
                if (consumeDuplicateMediaAction()) return;
                if (libraryAudioPlaying) {
                    stopLibraryAudioFromGlasses();
                } else if (activated && !handlingAssistantAction) {
                    runAssistantAction("PLAY");
                } else {
                    super.play();
                }
            }

            @Override
            public void pause() {
                if (consumeDuplicateMediaAction()) return;
                if (libraryAudioPlaying) {
                    stopLibraryAudioFromGlasses();
                } else if (activated && !handlingAssistantAction) {
                    runAssistantAction("PAUSE");
                } else {
                    super.pause();
                }
            }

            @Override
            public void setPlayWhenReady(boolean playWhenReady) {
                if (consumeDuplicateMediaAction()) return;
                if (libraryAudioPlaying) {
                    stopLibraryAudioFromGlasses();
                } else if (activated && !handlingAssistantAction) {
                    runAssistantAction(playWhenReady ? "PLAY_WHEN_READY" : "PAUSE_WHEN_READY");
                } else {
                    super.setPlayWhenReady(playWhenReady);
                }
            }
        };

        mediaSession = new MediaSession.Builder(this, sessionPlayer).build();
        Log.i(TAG, "MediaSession nativa creada.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android exige que un servicio iniciado con startForegroundService()
        // se promueva inmediatamente. MediaSessionService puede esperar a que
        // el reproductor cambie de estado; esta notificación evita ese timeout.
        if (intent != null && ACTION_PLAY_LIBRARY_AUDIO.equals(intent.getAction())) {
            promoteAudioPlaybackToForeground();
        }
        int result = super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_ACTIVATE.equals(action)) {
                activateAssistant();
            } else if (ACTION_ASSISTANT.equals(action)) {
                runAssistantAction("LOCAL");
            } else if (ACTION_ENABLE.equals(action)) {
                activated = true;
                Log.i(TAG, "ORVEX habilitado sin reproducir el audio inicial.");
            } else if (ACTION_DEACTIVATE.equals(action)) {
                deactivateAssistant();
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_PLAY_LIBRARY_AUDIO.equals(action)) {
                String audioUri = intent.getStringExtra(EXTRA_AUDIO_URI);
                playLibraryAudio(audioUri, intent.getIntExtra(EXTRA_AUDIO_ID, -1));
            } else if (ACTION_STOP_LIBRARY_AUDIO.equals(action)) {
                stopLibraryAudioFromGlasses();
            } else if (ACTION_WHATSAPP_ALERT.equals(action)) {
                if (!activated) {
                    Log.i(TAG, "Aviso de WhatsApp ignorado: ORVEX no está activo.");
                    return result;
                }
                playWhatsAppAlert(intent.getStringExtra(EXTRA_WHATSAPP_SENDER),
                    intent.getStringExtra(EXTRA_WHATSAPP_TEXT));
            }
        }
        return result;
    }

    private void activateAssistant() {
        try {
            activated = true;
            File initialAudio = copyAssetIfNeeded("comienza_el_ensamblaje.mp3");
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(initialAudio)));
            exoPlayer.prepare();
            exoPlayer.play();
            Log.i(TAG, "ORVEX activado: reproduciendo audio inicial.");
        } catch (IOException e) {
            Log.e(TAG, "No se pudo preparar el audio inicial.", e);
        }
    }

    private void deactivateAssistant() {
        activated = false;
        libraryAudioPlaying = false;
        listenAfterCue = false;
        handlingAssistantAction = false;
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        stopService(new Intent(this, VoiceAssistantService.class));
        Log.i(TAG, "ORVEX desactivado por el usuario.");
    }

    private void runAssistantAction(String source) {
        if (libraryAudioPlaying) {
            stopLibraryAudioFromGlasses();
            return;
        }
        if (handlingAssistantAction) return;
        handlingAssistantAction = true;
        try {
            File actionAudio = copyAssetIfNeeded("orden.mp3");
            listenAfterCue = true;
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(actionAudio)));
            exoPlayer.prepare();
            exoPlayer.play();
            Log.i(TAG, "Acción del asistente iniciada. Origen: " + source);
        } catch (IOException e) {
            listenAfterCue = false;
            Log.e(TAG, "No se pudo reproducir orden.mp3.", e);
            requestVoiceListening();
        } finally {
            handlingAssistantAction = false;
        }
    }


    private void playWhatsAppAlert(String sender, String text) {
        if (text == null || text.trim().isEmpty()) return;
        pendingWhatsAppSender = sender == null ? "" : sender.trim();
        pendingWhatsAppText = text.trim();
        if (exoPlayer != null && exoPlayer.isPlaying()) {
            Log.i(TAG, "Hay audio de ORVEX en reproducción; el aviso de WhatsApp queda en cola para no interrumpirlo.");
            return;
        }
        try {
            File alertAudio = copyAssetIfNeeded("mensaje_whatsapp.mp3");
            listenAfterCue = true;
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(alertAudio)));
            exoPlayer.prepare();
            exoPlayer.play();
            Log.i(TAG, "Aviso de WhatsApp reproducido; esperando confirmación de voz.");
        } catch (IOException e) {
            listenAfterCue = false;
            String pendingSender = pendingWhatsAppSender;
            String pendingText = pendingWhatsAppText;
            pendingWhatsAppSender = null;
            pendingWhatsAppText = null;
            Log.e(TAG, "No se pudo reproducir mensaje_whatsapp.mp3.", e);
            requestWhatsAppConfirmation(pendingSender, pendingText);
        }
    }

    private void requestWhatsAppConfirmation(String sender, String text) {
        try {
            Intent voiceIntent = new Intent(this, VoiceAssistantService.class);
            voiceIntent.setAction(VoiceAssistantService.ACTION_WHATSAPP_CONFIRM);
            voiceIntent.putExtra(VoiceAssistantService.EXTRA_WHATSAPP_SENDER, sender == null ? "" : sender);
            voiceIntent.putExtra(VoiceAssistantService.EXTRA_WHATSAPP_TEXT, text == null ? "" : text);
            startService(voiceIntent);
            Log.i(TAG, "Solicitud de confirmación de WhatsApp enviada a VoiceAssistantService.");
        } catch (Exception e) {
            Log.e(TAG, "No se pudo solicitar confirmación de WhatsApp.", e);
        }
    }

    private void requestVoiceListening() {
        try {
            Intent voiceIntent = new Intent(this, VoiceAssistantService.class);
            voiceIntent.setAction(VoiceAssistantService.ACTION_LISTEN);
            startService(voiceIntent);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo activar el reconocimiento de voz.", e);
        }
    }

    private void promoteAudioPlaybackToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                AUDIO_CHANNEL_ID,
                "Reproducción de audios ORVEX",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantiene activa la reproducción de canciones y audiolibros.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, AUDIO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ORVEX está reproduciendo un audio")
            .setContentText("La reproducción se controla con el botón central de las gafas.")
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AUDIO_FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        } else {
            startForeground(AUDIO_FOREGROUND_NOTIFICATION_ID, notification);
        }
        Log.i(TAG, "Servicio promovido a primer plano para reproducir audio de biblioteca.");
    }

    private void playLibraryAudio(String audioUri, int audioId) {
        if (audioUri == null || audioUri.trim().isEmpty()) {
            Log.e(TAG, "No se recibió una URI válida para la pista.");
            return;
        }
        try {
            listenAfterCue = false;
            handlingAssistantAction = false;
            stopService(new Intent(this, VoiceAssistantService.class));
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(audioUri)));
            exoPlayer.prepare();
            libraryAudioPlaying = true;
            exoPlayer.play();
            Log.i(TAG, "Reproduciendo pista de biblioteca id=" + audioId);
        } catch (Exception e) {
            libraryAudioPlaying = false;
            Log.e(TAG, "No se pudo reproducir la pista de la biblioteca.", e);
        }
    }

    private boolean consumeDuplicateMediaAction() {
        if (!consumingDuplicateMediaAction) return false;
        consumingDuplicateMediaAction = false;
        mediaActionHandler.removeCallbacksAndMessages(null);
        return true;
    }

    private void stopLibraryAudioFromGlasses() {
        if (!libraryAudioPlaying) return;
        libraryAudioPlaying = false;
        consumingDuplicateMediaAction = true;
        mediaActionHandler.removeCallbacksAndMessages(null);
        mediaActionHandler.postDelayed(() -> consumingDuplicateMediaAction = false, 300);
        listenAfterCue = false;
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        Log.i(TAG, "Pista de biblioteca detenida por el botón multimedia de las gafas.");
    }

    private File copyAssetIfNeeded(String assetName) throws IOException {
        File target = new File(getFilesDir(), assetName);
        boolean refreshWhatsAppAsset = "mensaje_whatsapp.mp3".equals(assetName);
        if (!refreshWhatsAppAsset && target.exists() && target.length() > 0) return target;

        try (InputStream input = getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
        if (!target.exists() || target.length() == 0) {
            throw new IOException("El asset quedó vacío: " + assetName);
        }
        return target;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // La sesión de reproducción puede seguir activa; no se detiene automáticamente.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        super.onDestroy();
    }

    @Override
    public MediaSession onGetSession(ControllerInfo controllerInfo) {
        return mediaSession;
    }
}
