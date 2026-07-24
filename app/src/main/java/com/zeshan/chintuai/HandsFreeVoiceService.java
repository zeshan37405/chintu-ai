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

/**
 * Persistent, user-visible microphone mode. Every command is wake-word gated so television,
 * nearby people and random noises do not continuously move the recognizer through command states.
 */
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
    private static final long COMMAND_WINDOW_MS = 10_000L;
    private static final long IGNORE_AFTER_TTS_MS = 900L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 9L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();

    private SpeechRecognizer recognizer;
    private Intent recognitionIntent;
    private boolean listening;
    private boolean starting;
    private boolean stopped;
    private String lastPartial = "";
    private long suppressErrorsUntil;
    private long commandWindowUntil;
    private long ignoreAudioUntil;
    private Runnable restartRunnable;
    private Runnable startWatchdog;
    private Runnable sessionWatchdog;
    private Runnable wakeLockRenewal;
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
        commandWindowUntil = 0L;
        ignoreAudioUntil = 0L;
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
        Notification notification = buildNotification("کہیں: چنٹو، پھر کمانڈ");
        if (Build.VERSION.SDK_INT >= 30) {
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

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        return builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Chintu AI — مالک لاک")
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
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Chintu owner-locked microphone", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("چنٹو کی wake-word gated وائس کمانڈ سروس");
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
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                7000L);
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
            broadcastStatus(commandWindowOpen() ? "جی، بولیں" : "مالک لاک فعال ہے",
                    commandWindowOpen() ? "اب کمانڈ بولیں" : "پہلے چنٹو کہیں، پھر کمانڈ بولیں");
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
            if (!partial.isEmpty()) processCandidate(partial, -1f);
            else scheduleRestart(180L);
        };
        handler.postDelayed(sessionWatchdog, SESSION_WATCHDOG_MS);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        starting = false;
        listening = true;
        removeStartWatchdog();
        broadcastStatus(commandWindowOpen() ? "جی، بولیں" : "مالک لاک فعال ہے",
                commandWindowOpen() ? "اب کمانڈ بولیں" : "کہیں: چنٹو، پھر کمانڈ");
    }

    @Override
    public void onBeginningOfSpeech() {
        starting = false;
        listening = true;
        removeStartWatchdog();
        if (commandWindowOpen()) broadcastStatus("سن رہا ہوں", "کمانڈ مکمل بولیں...");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Random sound levels must not move the UI through command states.
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // The platform recognizer does not provide reliable speaker-biometric buffers.
    }

    @Override
    public void onEndOfSpeech() {
        starting = false;
        listening = false;
        removeStartWatchdog();
        if (commandWindowOpen() || CommandEngine.hasWakeWord(lastPartial)) {
            broadcastStatus("کمانڈ سمجھ رہا ہوں", lastPartial);
        }
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
        if (!partial.isEmpty()
                && (CommandEngine.hasWakeWord(partial) || commandWindowOpen())) {
            processCandidate(partial, -1f);
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
        scheduleRestart(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1100L : 220L);
    }

    @Override
    public void onResults(Bundle results) {
        starting = false;
        listening = false;
        removeStartWatchdog();
        removeSessionWatchdog();
        RecognizedCandidate candidate = chooseBestResult(results);
        if (candidate.text.isEmpty()) candidate = new RecognizedCandidate(lastPartial, -1f);
        lastPartial = "";
        if (candidate.text.trim().isEmpty()) scheduleRestart(180L);
        else processCandidate(candidate.text, candidate.confidence);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> matches = partialResults == null ? null
                : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String best = CommandEngine.chooseBest(matches);
        if (best.isEmpty()) return;
        lastPartial = best;
        if (CommandEngine.hasWakeWord(best) || commandWindowOpen()) {
            broadcastStatus("سن رہا ہوں", best);
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // No vendor-specific events required.
    }

    private RecognizedCandidate chooseBestResult(Bundle results) {
        if (results == null) return new RecognizedCandidate("", -1f);
        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        float[] confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        if (matches == null || matches.isEmpty()) return new RecognizedCandidate("", -1f);
        int bestIndex = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < matches.size(); i++) {
            String value = matches.get(i) == null ? "" : matches.get(i);
            float confidence = confidences != null && i < confidences.length
                    ? confidences[i] : -1f;
            int score = CommandEngine.scoreRecognitionCandidate(value);
            if (CommandEngine.hasWakeWord(value)) score += 60;
            if (confidence >= 0f) score += Math.round(confidence * 35f);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        float confidence = confidences != null && bestIndex < confidences.length
                ? confidences[bestIndex] : -1f;
        return new RecognizedCandidate(matches.get(bestIndex), confidence);
    }

    private void processCandidate(String candidate, float confidence) {
        removeStartWatchdog();
        removeSessionWatchdog();
        cancelRecognizer();
        long now = SystemClock.uptimeMillis();
        if (now < ignoreAudioUntil) {
            scheduleRestart(ignoreAudioUntil - now);
            return;
        }

        String cleaned = candidate == null ? "" : candidate.trim();
        boolean hasWakeWord = CommandEngine.hasWakeWord(cleaned);
        boolean commandWindow = commandWindowOpen();

        if (!hasWakeWord && !commandWindow) {
            broadcastStatus("مالک لاک فعال ہے", "صرف چنٹو کہنے پر کمانڈ چلے گی");
            scheduleRestart(220L);
            return;
        }
        if (!hasWakeWord && confidence >= 0f && confidence < 0.18f) {
            broadcastStatus("آواز واضح نہیں", "دوبارہ کہیں: چنٹو، پھر کمانڈ");
            commandWindowUntil = 0L;
            scheduleRestart(250L);
            return;
        }

        String command = hasWakeWord ? CommandEngine.stripWakeWord(cleaned) : cleaned;
        if (hasWakeWord && command.isEmpty()) {
            commandWindowUntil = SystemClock.uptimeMillis() + COMMAND_WINDOW_MS;
            broadcastStatus("جی، ذیشان", "دس سیکنڈ کے اندر کمانڈ بولیں");
            scheduleRestart(140L);
            return;
        }
        commandWindowUntil = 0L;
        if (looksLikeNoise(command)) {
            broadcastStatus("کمانڈ واضح نہیں", "کہیں: چنٹو، پھر مکمل کمانڈ");
            scheduleRestart(250L);
            return;
        }

        broadcastStatus("کمانڈ ملی", command);
        commandExecutor.execute(() -> {
            BackgroundCommandExecutor.Result result =
                    JarvisAutomationExecutor.tryExecute(getApplicationContext(), command);
            if (result == null) {
                result = BackgroundCommandExecutor.execute(getApplicationContext(), command);
            }
            BackgroundCommandExecutor.Result finalResult = result;
            handler.post(() -> {
                broadcastCommand(command, finalResult.message);
                if (finalResult.stopHandsFree) {
                    stopHandsFree(finalResult.message);
                } else {
                    speakThenRestart(finalResult.message);
                }
            });
        });
    }

    private boolean looksLikeNoise(String command) {
        String normalized = CommandEngine.normalize(command);
        if (normalized.length() < 2) return true;
        if (normalized.equals("ہاں") || normalized.equals("نہیں")
                || normalized.equals("اچھا") || normalized.equals("اوکے")
                || normalized.equals("ہم") || normalized.equals("ہوں")
                || normalized.equals("جی") || normalized.equals("hello")) {
            return true;
        }
        String[] words = normalized.split(" ");
        return words.length == 1 && normalized.length() < 3;
    }

    private boolean commandWindowOpen() {
        return SystemClock.uptimeMillis() <= commandWindowUntil;
    }

    private void speakThenRestart(String message) {
        if (stopped) return;
        if (!ttsReady || tts == null || message == null || message.trim().isEmpty()) {
            ignoreAudioUntil = SystemClock.uptimeMillis() + IGNORE_AFTER_TTS_MS;
            scheduleRestart(IGNORE_AFTER_TTS_MS);
            return;
        }
        try {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "hands-free-response");
        } catch (RuntimeException error) {
            ignoreAudioUntil = SystemClock.uptimeMillis() + IGNORE_AFTER_TTS_MS;
            scheduleRestart(IGNORE_AFTER_TTS_MS);
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
                ignoreAudioUntil = SystemClock.uptimeMillis() + IGNORE_AFTER_TTS_MS;
                handler.postDelayed(() -> scheduleRestart(0L), IGNORE_AFTER_TTS_MS);
            }

            @Override
            public void onError(String utteranceId) {
                ignoreAudioUntil = SystemClock.uptimeMillis() + IGNORE_AFTER_TTS_MS;
                handler.postDelayed(() -> scheduleRestart(0L), IGNORE_AFTER_TTS_MS);
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
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            if (wakeLockRenewal != null) handler.removeCallbacks(wakeLockRenewal);
            wakeLockRenewal = this::acquireWakeLock;
            handler.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_MS);
        } catch (RuntimeException ignored) {
            // Foreground service continues even if a vendor blocks the wake lock.
        }
    }

    private void releaseWakeLock() {
        if (wakeLockRenewal != null) handler.removeCallbacks(wakeLockRenewal);
        wakeLockRenewal = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
                // Already released by the platform timeout.
            }
        }
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

    private static final class RecognizedCandidate {
        final String text;
        final float confidence;

        RecognizedCandidate(String text, float confidence) {
            this.text = text == null ? "" : text.trim();
            this.confidence = confidence;
        }
    }
}
