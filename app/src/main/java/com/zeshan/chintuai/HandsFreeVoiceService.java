package com.zeshan.chintuai;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Persistent, user-visible microphone mode that automatically re-arms after every command. */
public final class HandsFreeVoiceService extends Service
        implements RecognitionListener, TextToSpeech.OnInitListener {

    public static final String ACTION_START = "com.zeshan.chintuai.action.HANDS_FREE_START";
    public static final String ACTION_STOP = "com.zeshan.chintuai.action.HANDS_FREE_STOP";
    public static final String ACTION_STATUS = "com.zeshan.chintuai.action.HANDS_FREE_STATUS";
    public static final String ACTION_COMMAND = "com.zeshan.chintuai.action.HANDS_FREE_COMMAND";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_RESULT = "result";

    private static final String PREFS = "chintu_preferences";
    private static final String PREF_ENABLED = "hands_free_enabled";
    private static final String CHANNEL_ID = "chintu_hands_free";
    private static final int NOTIFICATION_ID = 7101;
    private static final long START_WATCHDOG_MS = 4500L;
    private static final long SESSION_WATCHDOG_MS = 60000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();

    private SpeechRecognizer recognizer;
    private Intent recognitionIntent;
    private boolean listening;
    private boolean starting;
    private boolean stopped;
    private String lastPartial = "";
    private long suppressErrorsUntil;
    private Runnable restartRunnable;
    private Runnable startWatchdog;
    private Runnable sessionWatchdog;
    private PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;
    private boolean ttsReady;

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        tts = new TextToSpeech(this, this);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "ChintuAI:HandsFreeVoice");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopHandsFree("ہینڈز فری موڈ بند کر دیا ہے");
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            broadcastStatus("مائیکروفون اجازت نہیں", "ایپ کھول کر مائیکروفون Allow کریں");
            stopSelf();
            return START_NOT_STICKY;
        }
        stopped = false;
        setEnabled(true);
        startAsForeground();
        acquireWakeLock();
        prepareRecognizer();
        scheduleRestart(0L);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground() {
        Notification notification = buildNotification("چنٹو سن رہا ہے");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, ChintuActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, HandsFreeVoiceService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Chintu AI — ہینڈز فری")
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "بند کریں", stopPending).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Chintu hands-free microphone", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("چنٹو کی مسلسل وائس کمانڈ سروس");
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void prepareRecognizer() {
        destroyRecognizer();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            broadcastStatus("وائس شناخت دستیاب نہیں", "Google وائس سروس اپڈیٹ کریں");
            stopHandsFree("");
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            recognitionIntent = createRecognitionIntent();
        } catch (RuntimeException error) {
            recognizer = null;
            broadcastStatus("وائس شناخت شروع نہیں ہوئی", "دوبارہ ہینڈز فری چلائیں");
            stopHandsFree("");
        }
    }

    private Intent createRecognitionIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                8000L);
        return intent;
    }

    private void startListening() {
        clearRestart();
        if (stopped) return;
        if (recognizer == null || recognitionIntent == null) prepareRecognizer();
        if (recognizer == null) return;
        try {
            lastPartial = "";
            starting = true;
            listening = false;
            recognizer.setRecognitionListener(this);
            recognizer.startListening(recognitionIntent);
            broadcastStatus("چنٹو سن رہا ہے", ChintuActivity.isAppVisible()
                    ? "کمانڈ بولیں" : "پہلے چنٹو کہیں، پھر کمانڈ بولیں");
            scheduleStartWatchdog();
            scheduleSessionWatchdog();
        } catch (RuntimeException error) {
            destroyRecognizer();
            prepareRecognizer();
            scheduleRestart(900L);
        }
    }

    private void scheduleRestart(long delayMs) {
        clearRestart();
        if (stopped) return;
        restartRunnable = this::startListening;
        handler.postDelayed(restartRunnable, Math.max(0L, delayMs));
    }

    private void scheduleStartWatchdog() {
        removeStartWatchdog();
        startWatchdog = () -> {
            if (stopped || !starting) return;
            cancelRecognizer();
            destroyRecognizer();
            prepareRecognizer();
            scheduleRestart(250L);
        };
        handler.postDelayed(startWatchdog, START_WATCHDOG_MS);
    }

    private void scheduleSessionWatchdog() {
        removeSessionWatchdog();
        sessionWatchdog = () -> {
            if (stopped) return;
            String partial = lastPartial;
            cancelRecognizer();
            if (!partial.isEmpty()) processCandidate(partial);
            else scheduleRestart(150L);
        };
        handler.postDelayed(sessionWatchdog, SESSION_WATCHDOG_MS);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        starting = false;
        listening = true;
        removeStartWatchdog();
        broadcastStatus("چنٹو سن رہا ہے", ChintuActivity.isAppVisible()
                ? "کمانڈ بولیں" : "چنٹو کہہ کر کمانڈ بولیں");
    }

    @Override
    public void onBeginningOfSpeech() {
        starting = false;
        listening = true;
        removeStartWatchdog();
        broadcastStatus("آواز مل گئی", "بولتے رہیں...");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Intentionally no UI updates for every audio frame.
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // Not required.
    }

    @Override
    public void onEndOfSpeech() {
        starting = false;
        listening = false;
        removeStartWatchdog();
        broadcastStatus("کمانڈ سمجھ رہا ہوں", lastPartial);
    }

    @Override
    public void onError(int error) {
        if (SystemClock.uptimeMillis() < suppressErrorsUntil || stopped) return;
        starting = false;
        listening = false;
        removeStartWatchdog();
        removeSessionWatchdog();
        String partial = lastPartial.trim();
        lastPartial = "";
        if (!partial.isEmpty()) {
            processCandidate(partial);
            return;
        }
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            broadcastStatus("مائیکروفون اجازت نہیں", "ایپ میں اجازت دیں");
            stopHandsFree("");
            return;
        }
        if (error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            destroyRecognizer();
            prepareRecognizer();
        }
        scheduleRestart(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1100L : 180L);
    }

    @Override
    public void onResults(Bundle results) {
        starting = false;
        listening = false;
        removeStartWatchdog();
        removeSessionWatchdog();
        ArrayList<String> matches = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String best = CommandEngine.chooseBest(matches);
        if (best.isEmpty()) best = lastPartial;
        lastPartial = "";
        if (best == null || best.trim().isEmpty()) scheduleRestart(150L);
        else processCandidate(best);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults == null ? null
                : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String best = CommandEngine.chooseBest(matches);
        if (best.isEmpty()) return;
        lastPartial = best;
        broadcastStatus("سن رہا ہوں", best);
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // No vendor-specific events required.
    }

    private void processCandidate(String candidate) {
        removeStartWatchdog();
        removeSessionWatchdog();
        cancelRecognizer();
        String cleaned = candidate == null ? "" : candidate.trim();
        boolean visible = ChintuActivity.isAppVisible();
        if (!visible && !CommandEngine.hasWakeWord(cleaned)) {
            broadcastStatus("چنٹو سن رہا ہے", "چنٹو کہہ کر کمانڈ بولیں");
            scheduleRestart(180L);
            return;
        }
        String command = CommandEngine.stripWakeWord(cleaned);
        if (command.isEmpty()) {
            broadcastStatus("جی، بولیں", "کمانڈ سن رہا ہوں");
            scheduleRestart(100L);
            return;
        }
        broadcastStatus("کمانڈ ملی", command);
        commandExecutor.execute(() -> {
            BackgroundCommandExecutor.Result result =
                    BackgroundCommandExecutor.execute(getApplicationContext(), command);
            handler.post(() -> {
                broadcastCommand(command, result.message);
                if (result.stopHandsFree) {
                    stopHandsFree(result.message);
                } else {
                    speakThenRestart(result.message);
                }
            });
        });
    }

    private void speakThenRestart(String message) {
        if (stopped) return;
        if (!ttsReady || tts == null || message == null || message.trim().isEmpty()) {
            scheduleRestart(450L);
            return;
        }
        try {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "hands-free-response");
        } catch (RuntimeException error) {
            scheduleRestart(500L);
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) return;
        int language = tts.setLanguage(new Locale("ur", "PK"));
        if (language == TextToSpeech.LANG_MISSING_DATA
                || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
        }
        tts.setSpeechRate(0.92f);
        tts.setPitch(0.9f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // Recognition remains paused while Chintu speaks.
            }

            @Override
            public void onDone(String utteranceId) {
                handler.postDelayed(() -> scheduleRestart(0L), 350L);
            }

            @Override
            public void onError(String utteranceId) {
                handler.postDelayed(() -> scheduleRestart(0L), 350L);
            }
        });
        ttsReady = true;
    }

    private void broadcastStatus(String status, String detail) {
        Intent intent = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_DETAIL, detail);
        sendBroadcast(intent);
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && isEnabled(this)) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }

    private void broadcastCommand(String command, String result) {
        Intent intent = new Intent(ACTION_COMMAND)
                .setPackage(getPackageName())
                .putExtra(EXTRA_COMMAND, command)
                .putExtra(EXTRA_RESULT, result);
        sendBroadcast(intent);
    }

    private void stopHandsFree(String status) {
        stopped = true;
        setEnabled(false);
        clearTimers();
        cancelRecognizer();
        destroyRecognizer();
        releaseWakeLock();
        if (tts != null) tts.stop();
        if (status != null && !status.isEmpty()) broadcastCommand("", status);
        stopForeground(true);
        stopSelf();
    }

    private void setEnabled(boolean enabled) {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        preferences.edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void cancelRecognizer() {
        if (recognizer == null) return;
        suppressErrorsUntil = SystemClock.uptimeMillis() + 700L;
        try {
            recognizer.cancel();
        } catch (RuntimeException ignored) {
            // Xiaomi compatibility.
        }
        starting = false;
        listening = false;
    }

    private void destroyRecognizer() {
        if (recognizer == null) return;
        suppressErrorsUntil = SystemClock.uptimeMillis() + 800L;
        try {
            recognizer.cancel();
        } catch (RuntimeException ignored) {
            // Xiaomi compatibility.
        }
        try {
            recognizer.destroy();
        } catch (RuntimeException ignored) {
            // Xiaomi compatibility.
        }
        recognizer = null;
    }

    private void clearRestart() {
        if (restartRunnable != null) handler.removeCallbacks(restartRunnable);
        restartRunnable = null;
    }

    private void removeStartWatchdog() {
        if (startWatchdog != null) handler.removeCallbacks(startWatchdog);
        startWatchdog = null;
    }

    private void removeSessionWatchdog() {
        if (sessionWatchdog != null) handler.removeCallbacks(sessionWatchdog);
        sessionWatchdog = null;
    }

    private void clearTimers() {
        clearRestart();
        removeStartWatchdog();
        removeSessionWatchdog();
    }

    @Override
    public void onDestroy() {
        stopped = true;
        setEnabled(false);
        clearTimers();
        destroyRecognizer();
        releaseWakeLock();
        commandExecutor.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
