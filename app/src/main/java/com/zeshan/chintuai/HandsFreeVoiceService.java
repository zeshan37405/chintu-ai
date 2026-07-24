package com.zeshan.chintuai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
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
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Wazir Gen Z low-latency hands-free service.
 *
 * The previous build asked the vendor recognizer for a thirty-second minimum utterance. On Redmi
 * that could delay a short command for close to a minute. This service removes that long minimum,
 * commits stable partial text without waiting indefinitely for a final callback, prefers the
 * on-device recognizer when it supports the requested language, and keeps voice work isolated in
 * the dedicated :voice process declared in the manifest.
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
    public static final String EXTRA_ENGINE = "engine";
    public static final String EXTRA_LATENCY_MS = "latency_ms";

    private static final String PREFS = "chintu_preferences";
    private static final String PREF_ENABLED = "hands_free_enabled";
    private static final String CHANNEL_ID = "wazir_gen_z_voice";
    private static final int NOTIFICATION_ID = 7101;

    private static final long START_WATCHDOG_MS = 4_500L;
    private static final long SESSION_WATCHDOG_MS = 12_000L;
    private static final long RESULT_WATCHDOG_MS = 1_500L;
    private static final long COMMAND_WINDOW_MS = 12_000L;
    private static final long COMMAND_EXECUTION_TIMEOUT_MS = 15_000L;
    private static final long IGNORE_AFTER_TTS_MS = 650L;
    private static final long MIN_RESTART_GAP_MS = 550L;
    private static final long STATUS_THROTTLE_MS = 250L;
    private static final long NOTIFICATION_THROTTLE_MS = 1_500L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 9L * 60L * 1000L;

    private static final String[] RECOGNITION_LANGUAGES = {
            "ur-IN", "ur-PK", "hi-IN", "en-IN"
    };

    private enum ListeningMode {
        WAKE_WORD,
        COMMAND
    }

    private enum SpeechPurpose {
        NONE,
        WAKE_ACKNOWLEDGEMENT,
        COMMAND_RESPONSE
    }

    private static volatile boolean processRunning;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private PowerManager.WakeLock wakeLock;

    private boolean active;
    private boolean stopped;
    private boolean explicitStop;
    private boolean starting;
    private boolean listening;
    private boolean executingCommand;
    private boolean ttsReady;
    private boolean ttsInitializing;
    private boolean usingOnDeviceRecognizer;
    private boolean onDeviceRejected;

    private int languageIndex;
    private int consecutiveFailures;
    private int utteranceSequence;
    private int commandSequence;

    private String lastPartial = "";
    private String lastStatus = "";
    private String lastDetail = "";
    private String activeUtteranceId = "";
    private String pendingSpeechText = "";

    private long suppressErrorsUntil;
    private long commandWindowUntil;
    private long ignoreAudioUntil;
    private long lastSessionStartAt;
    private long firstSpeechAt;
    private long wakeDetectedAt;
    private long lastStatusBroadcastAt;
    private long lastNotificationAt;

    private ListeningMode listeningMode = ListeningMode.WAKE_WORD;
    private SpeechPurpose speechPurpose = SpeechPurpose.NONE;
    private SpeechPurpose pendingSpeechPurpose = SpeechPurpose.NONE;

    private Runnable restartRunnable;
    private Runnable startWatchdog;
    private Runnable sessionWatchdog;
    private Runnable resultWatchdog;
    private Runnable partialCommitRunnable;
    private Runnable commandWindowRunnable;
    private Runnable commandWatchdog;
    private Runnable wakeLockRenewal;
    private Runnable ttsWatchdog;
    private Runnable ttsInitWatchdog;

    @SuppressLint("Deprecated")
    public static boolean isEnabled(Context context) {
        if (processRunning) return true;
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ComponentName expected = new ComponentName(context, HandsFreeVoiceService.class);
                for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(50)) {
                    if (expected.equals(info.service) && info.started) return true;
                }
            }
        } catch (RuntimeException ignored) {
            // The persisted user choice below is the fallback on restricted Android builds.
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        processRunning = true;
        createNotificationChannel();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "WazirGenZ:FastVoice");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            explicitStop = true;
            stopHandsFree("وزیر ہینڈز فری بند کر دیا ہے");
            return START_NOT_STICKY;
        }

        if (intent == null && !persistedEnabled()) {
            explicitStop = true;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            explicitStop = true;
            setEnabled(false);
            broadcastStatus("مائیکروفون اجازت نہیں",
                    "وزیر کو مائیکروفون کی اجازت دیں", true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (active && !stopped) {
            broadcastListeningState(true);
            if (!starting && !listening && !executingCommand) scheduleRestart(0L, false);
            return START_STICKY;
        }

        active = true;
        stopped = false;
        explicitStop = false;
        starting = false;
        listening = false;
        executingCommand = false;
        languageIndex = 0;
        consecutiveFailures = 0;
        listeningMode = ListeningMode.WAKE_WORD;
        commandWindowUntil = 0L;
        ignoreAudioUntil = 0L;
        firstSpeechAt = 0L;
        wakeDetectedAt = 0L;
        lastPartial = "";

        setEnabled(true);
        startAsForeground();
        acquireWakeLock();
        initTtsIfNeeded();
        broadcastStatus("وزیر تیار ہے", "کہیں: وزیر، پھر کمانڈ", true);
        scheduleRestart(250L, false);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean persistedEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
    }

    private void startAsForeground() {
        Notification notification = buildNotification("کہیں: وزیر، پھر کمانڈ");
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, WazirActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, HandsFreeVoiceService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("وزیر Gen Z — سن رہا ہے")
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
                CHANNEL_ID, "Wazir Gen Z voice", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("وزیر کی wake-word اور فوری وائس کمانڈ سروس");
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void initTtsIfNeeded() {
        if (tts != null || ttsInitializing) return;
        ttsInitializing = true;
        try {
            tts = new TextToSpeech(this, this);
        } catch (RuntimeException error) {
            tts = null;
            ttsInitializing = false;
            ttsReady = false;
        }
    }

    private void startListeningSession() {
        clearRestart();
        if (stopped || explicitStop || executingCommand) return;

        long now = SystemClock.uptimeMillis();
        long elapsed = now - lastSessionStartAt;
        if (lastSessionStartAt > 0L && elapsed < MIN_RESTART_GAP_MS) {
            scheduleRestart(MIN_RESTART_GAP_MS - elapsed, false);
            return;
        }

        if (!ensureRecognizer()) {
            consecutiveFailures++;
            broadcastStatus("وائس انجن دوبارہ جوڑ رہا ہوں",
                    "مائیک بند نہیں ہوا؛ خودکار کوشش جاری ہے", false);
            scheduleRestart(nextRetryDelay(), false);
            return;
        }

        if (starting || listening) stopRecognitionSession(false);
        lastPartial = "";
        firstSpeechAt = 0L;
        starting = true;
        listening = false;
        lastSessionStartAt = SystemClock.uptimeMillis();

        try {
            recognizer.startListening(createRecognitionIntent());
            scheduleStartWatchdog();
            scheduleSessionWatchdog();
            broadcastListeningState(false);
        } catch (RuntimeException error) {
            starting = false;
            consecutiveFailures++;
            if (consecutiveFailures >= 2) recreateRecognizer();
            rotateLanguageAfterRepeatedFailure();
            scheduleRestart(nextRetryDelay(), false);
        }
    }

    private boolean ensureRecognizer() {
        if (recognizer != null) return true;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return false;

        try {
            if (Build.VERSION.SDK_INT >= 31
                    && !onDeviceRejected
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                usingOnDeviceRecognizer = true;
            } else {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                usingOnDeviceRecognizer = false;
            }
            recognizer.setRecognitionListener(this);
            return true;
        } catch (RuntimeException error) {
            recognizer = null;
            if (usingOnDeviceRecognizer) {
                onDeviceRejected = true;
                usingOnDeviceRecognizer = false;
                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                    recognizer.setRecognitionListener(this);
                    return true;
                } catch (RuntimeException ignored) {
                    recognizer = null;
                }
            }
            return false;
        }
    }

    private Intent createRecognitionIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String language = RECOGNITION_LANGUAGES[Math.min(
                languageIndex, RECOGNITION_LANGUAGES.length - 1)];
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 12);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, usingOnDeviceRecognizer);

        // Deliberately no 30-second minimum utterance. Short commands should finish quickly.
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1_000L);

        if (Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true);
            ArrayList<String> bias = new ArrayList<>(Arrays.asList(
                    "وزیر", "وزیر جی", "Wazir", "Vazir", "वज़ीर", "वजीर",
                    "واٹس ایپ کھولو", "واٹسپ کھولو", "فیس بک کھولو",
                    "نیچے سکرول کرو", "اوپر سکرول کرو", "پوسٹ لکھو",
                    "ٹائپ کرو", "کلک کرو", "ہوم کو کال کرو", "واپس جاؤ",
                    "اسکرین شاٹ لو", "تصدیق کرو", "WhatsApp", "Facebook"));
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, bias);
        }
        return intent;
    }

    private String engineLabel() {
        String language = RECOGNITION_LANGUAGES[Math.min(
                languageIndex, RECOGNITION_LANGUAGES.length - 1)];
        return (usingOnDeviceRecognizer ? "On-device" : "Google") + " • " + language;
    }

    private void broadcastListeningState(boolean force) {
        if (listeningMode == ListeningMode.COMMAND && commandWindowOpen()) {
            broadcastStatus("جی ذیشان، حکم کریں",
                    "کمانڈ بولیں — عبارت فوراً نظر آئے گی", force);
        } else {
            broadcastStatus("وزیر تیار ہے", "کہیں: وزیر، پھر کمانڈ", force);
        }
    }

    private void scheduleRestart(long delayMs, boolean resetToWake) {
        clearRestart();
        if (stopped || explicitStop || executingCommand) return;
        if (resetToWake) {
            listeningMode = ListeningMode.WAKE_WORD;
            commandWindowUntil = 0L;
            removeCommandWindowTimer();
            broadcastListeningState(false);
        }
        restartRunnable = this::startListeningSession;
        handler.postDelayed(restartRunnable, Math.max(0L, delayMs));
    }

    private void scheduleStartWatchdog() {
        removeStartWatchdog();
        startWatchdog = () -> {
            if (stopped || explicitStop || executingCommand || !starting) return;
            consecutiveFailures++;
            stopRecognitionSession(false);
            if (consecutiveFailures >= 2) recreateRecognizer();
            rotateLanguageAfterRepeatedFailure();
            scheduleRestart(nextRetryDelay(), false);
        };
        handler.postDelayed(startWatchdog, START_WATCHDOG_MS);
    }

    private void scheduleSessionWatchdog() {
        removeSessionWatchdog();
        sessionWatchdog = () -> {
            if (stopped || explicitStop || executingCommand) return;
            String partial = lastPartial.trim();
            stopRecognitionSession(false);
            if (!partial.isEmpty()) {
                processCandidate(partial, -1f);
                return;
            }
            if (listeningMode == ListeningMode.WAKE_WORD) {
                languageIndex = (languageIndex + 1) % RECOGNITION_LANGUAGES.length;
            }
            scheduleRestart(300L, false);
        };
        handler.postDelayed(sessionWatchdog, SESSION_WATCHDOG_MS);
    }

    private void scheduleResultWatchdog() {
        removeResultWatchdog();
        resultWatchdog = () -> {
            if (stopped || explicitStop || executingCommand) return;
            String partial = lastPartial.trim();
            stopRecognitionSession(false);
            if (!partial.isEmpty()) processCandidate(partial, -1f);
            else scheduleRestart(300L, false);
        };
        handler.postDelayed(resultWatchdog, RESULT_WATCHDOG_MS);
    }

    private void schedulePartialCommit(long delayMs) {
        removePartialCommit();
        String expected = lastPartial;
        partialCommitRunnable = () -> {
            if (stopped || explicitStop || executingCommand) return;
            if (!expected.equals(lastPartial) || expected.trim().isEmpty()) return;
            stopRecognitionSession(false);
            processCandidate(expected, -1f);
        };
        handler.postDelayed(partialCommitRunnable, delayMs);
    }

    private void openCommandWindow() {
        listeningMode = ListeningMode.COMMAND;
        commandWindowUntil = SystemClock.uptimeMillis() + COMMAND_WINDOW_MS;
        removeCommandWindowTimer();
        commandWindowRunnable = () -> {
            if (stopped || explicitStop || executingCommand) return;
            if (listeningMode == ListeningMode.COMMAND && !commandWindowOpen()) {
                listeningMode = ListeningMode.WAKE_WORD;
                broadcastStatus("وزیر تیار ہے",
                        "وقت ختم ہوا؛ دوبارہ کہیں: وزیر", true);
                stopRecognitionSession(false);
                scheduleRestart(500L, false);
            }
        };
        handler.postDelayed(commandWindowRunnable, COMMAND_WINDOW_MS + 100L);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        starting = false;
        listening = true;
        consecutiveFailures = 0;
        removeStartWatchdog();
        broadcastListeningState(false);
    }

    @Override
    public void onBeginningOfSpeech() {
        starting = false;
        listening = true;
        firstSpeechAt = SystemClock.uptimeMillis();
        removeStartWatchdog();
        broadcastStatus("سن رہا ہوں",
                listeningMode == ListeningMode.COMMAND
                        ? "کمانڈ مکمل بولیں…" : "وزیر سن رہا ہے…",
                false);
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Room noise never executes a command or changes the visible state.
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
        removeSessionWatchdog();
        if (!lastPartial.isEmpty()) {
            broadcastStatus("سمجھ رہا ہوں", lastPartial, false);
        }
        scheduleResultWatchdog();
    }

    @Override
    public void onError(int error) {
        if (SystemClock.uptimeMillis() < suppressErrorsUntil
                || stopped || explicitStop || executingCommand) return;

        starting = false;
        listening = false;
        clearRecognitionWatchdogs();
        removePartialCommit();

        String partial = lastPartial.trim();
        lastPartial = "";
        stopRecognitionSession(false);
        if (!partial.isEmpty()) {
            processCandidate(partial, -1f);
            return;
        }

        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            explicitStop = true;
            setEnabled(false);
            broadcastStatus("مائیکروفون اجازت نہیں", "وزیر کو اجازت دیں", true);
            stopSelf();
            return;
        }

        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            if (usingOnDeviceRecognizer) {
                onDeviceRejected = true;
                recreateRecognizer();
            } else {
                languageIndex = (languageIndex + 1) % RECOGNITION_LANGUAGES.length;
            }
            scheduleRestart(450L, false);
            return;
        }

        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            scheduleRestart(350L, false);
            return;
        }

        consecutiveFailures++;
        if (consecutiveFailures >= 2) recreateRecognizer();
        rotateLanguageAfterRepeatedFailure();
        long delay = error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                ? 1_800L : nextRetryDelay();
        scheduleRestart(delay, false);
    }

    @Override
    public void onResults(Bundle results) {
        starting = false;
        listening = false;
        clearRecognitionWatchdogs();
        removePartialCommit();
        RecognizedCandidate candidate = chooseBestResult(results);
        if (candidate.text.isEmpty()) candidate = new RecognizedCandidate(lastPartial, -1f);
        lastPartial = "";
        stopRecognitionSession(false);

        if (candidate.text.isEmpty()) {
            scheduleRestart(300L, false);
        } else {
            processCandidate(candidate.text, candidate.confidence);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        RecognizedCandidate candidate = chooseBestResult(partialResults);
        if (candidate.text.isEmpty()) return;
        lastPartial = candidate.text.trim();
        broadcastStatus("سن رہا ہوں",
                AccentCommandNormalizer.canonicalize(lastPartial), false);

        if (listeningMode == ListeningMode.WAKE_WORD) {
            if (WazirWakeWord.startsWithWakeWord(lastPartial)) {
                String remainder = WazirWakeWord.strip(lastPartial);
                schedulePartialCommit(remainder.isEmpty() ? 280L : 520L);
            }
        } else if (commandWindowOpen() && !looksLikeNoise(lastPartial)) {
            schedulePartialCommit(600L);
        }
    }

    @Override
    public void onSegmentResults(Bundle segmentResults) {
        // Ordinary recognizer sessions use partial/final callbacks.
    }

    @Override
    public void onEndOfSegmentedSession() {
        // Not used.
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // Vendor-specific events are not required.
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
            String canonical = AccentCommandNormalizer.canonicalize(value);
            float confidence = confidences != null && i < confidences.length
                    ? confidences[i] : -1f;
            int score = CommandEngine.scoreRecognitionCandidate(canonical);
            AppCatalog.AppMatch appMatch = AppCatalog.findBest(canonical);
            if (appMatch != null) score += appMatch.score / 2;
            if (WazirWakeWord.startsWithWakeWord(canonical)) score += 140;
            if (listeningMode == ListeningMode.COMMAND) score += 30;
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
        if (stopped || explicitStop || executingCommand) return;
        if (SystemClock.uptimeMillis() < ignoreAudioUntil) {
            scheduleRestart(IGNORE_AFTER_TTS_MS, false);
            return;
        }

        String canonical = AccentCommandNormalizer.canonicalize(candidate).trim();
        if (canonical.isEmpty()) {
            scheduleRestart(300L, false);
            return;
        }

        if (listeningMode == ListeningMode.WAKE_WORD) {
            if (!WazirWakeWord.startsWithWakeWord(canonical)) {
                scheduleRestart(350L, false);
                return;
            }

            String command = WazirWakeWord.strip(canonical);
            wakeDetectedAt = SystemClock.uptimeMillis();
            if (command.isEmpty()) {
                executingCommand = true;
                stopRecognitionSession(false);
                broadcastStatus("جی ذیشان", "حکم کریں", true);
                speakWithGuard("جی ذیشان، حکم کریں", SpeechPurpose.WAKE_ACKNOWLEDGEMENT);
                return;
            }
            dispatchCommand(command);
            return;
        }

        if (!commandWindowOpen()) {
            listeningMode = ListeningMode.WAKE_WORD;
            scheduleRestart(350L, false);
            return;
        }

        String command = WazirWakeWord.startsWithWakeWord(canonical)
                ? WazirWakeWord.strip(canonical) : canonical;
        if (looksLikeNoise(command) || (confidence >= 0f && confidence < 0.05f)) {
            broadcastStatus("کمانڈ واضح نہیں", "دوبارہ مکمل کمانڈ بولیں", false);
            scheduleRestart(300L, false);
            return;
        }
        dispatchCommand(command);
    }

    private void dispatchCommand(String command) {
        String finalCommand = command == null ? "" : command.trim();
        if (finalCommand.isEmpty()) {
            scheduleRestart(300L, false);
            return;
        }

        executingCommand = true;
        removeCommandWindowTimer();
        commandWindowUntil = 0L;
        stopRecognitionSession(false);
        int requestId = ++commandSequence;
        long startedAt = firstSpeechAt > 0L ? firstSpeechAt
                : (wakeDetectedAt > 0L ? wakeDetectedAt : SystemClock.uptimeMillis());
        long recognitionLatency = Math.max(0L, SystemClock.uptimeMillis() - startedAt);
        broadcastStatus("عمل کر رہا ہوں", finalCommand, true);

        ResultReceiver reply = new ResultReceiver(handler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                if (requestId != commandSequence || stopped || explicitStop) return;
                removeCommandWatchdog();
                String message = data == null ? "" : data.getString(
                        CommandExecutionReceiver.RESULT_MESSAGE, "");
                boolean stop = data != null && data.getBoolean(
                        CommandExecutionReceiver.RESULT_STOP_HANDS_FREE, false);
                finishCommand(finalCommand, message, stop, recognitionLatency);
            }
        };

        Intent execution = new Intent(this, CommandExecutionReceiver.class)
                .setAction(CommandExecutionReceiver.ACTION_EXECUTE)
                .putExtra(CommandExecutionReceiver.EXTRA_COMMAND, finalCommand)
                .putExtra(CommandExecutionReceiver.EXTRA_REPLY, reply);
        try {
            sendBroadcast(execution);
        } catch (RuntimeException error) {
            finishCommand(finalCommand,
                    "کمانڈ سروس دستیاب نہیں، دوبارہ کوشش کریں", false, recognitionLatency);
            return;
        }

        removeCommandWatchdog();
        commandWatchdog = () -> {
            if (requestId != commandSequence || stopped || explicitStop) return;
            commandSequence++;
            finishCommand(finalCommand,
                    "کمانڈ میں دیر ہوئی؛ وزیر دوبارہ تیار ہے", false, recognitionLatency);
        };
        handler.postDelayed(commandWatchdog, COMMAND_EXECUTION_TIMEOUT_MS);
    }

    private void finishCommand(String command, String message, boolean stopRequested,
                               long recognitionLatency) {
        String safeMessage = message == null || message.trim().isEmpty()
                ? "کمانڈ مکمل ہو گئی" : message.trim();
        broadcastCommand(command, safeMessage, recognitionLatency);

        if (stopRequested && WazirWakeWord.isExactStopCommand(command)) {
            explicitStop = true;
            stopHandsFree(safeMessage);
            return;
        }
        speakWithGuard(safeMessage, SpeechPurpose.COMMAND_RESPONSE);
    }

    private boolean looksLikeNoise(String command) {
        String normalized = CommandEngine.normalize(
                AccentCommandNormalizer.canonicalize(command == null ? "" : command));
        if (normalized.length() < 2) return true;
        return normalized.equals("ہاں")
                || normalized.equals("نہیں")
                || normalized.equals("اچھا")
                || normalized.equals("اوکے")
                || normalized.equals("ہم")
                || normalized.equals("ہوں")
                || normalized.equals("جی")
                || normalized.equals("hello");
    }

    private boolean commandWindowOpen() {
        return listeningMode == ListeningMode.COMMAND
                && SystemClock.uptimeMillis() <= commandWindowUntil;
    }

    private void speakWithGuard(String message, SpeechPurpose purpose) {
        removeTtsWatchdog();
        removeTtsInitWatchdog();
        initTtsIfNeeded();
        speechPurpose = purpose;
        String text = message == null ? "" : message.trim();

        if (text.isEmpty()) {
            completeSpeech(purpose);
            return;
        }

        if (!ttsReady || tts == null) {
            pendingSpeechText = text;
            pendingSpeechPurpose = purpose;
            ttsInitWatchdog = () -> {
                if (!pendingSpeechText.isEmpty()) {
                    SpeechPurpose pending = pendingSpeechPurpose;
                    pendingSpeechText = "";
                    pendingSpeechPurpose = SpeechPurpose.NONE;
                    completeSpeech(pending);
                }
            };
            handler.postDelayed(ttsInitWatchdog, 1_500L);
            return;
        }

        pendingSpeechText = "";
        pendingSpeechPurpose = SpeechPurpose.NONE;
        activeUtteranceId = "wazir-" + (++utteranceSequence);
        try {
            tts.stop();
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
            if (result == TextToSpeech.ERROR) {
                completeSpeech(purpose);
                return;
            }
            long timeout = Math.max(2_500L, Math.min(9_000L, 1_300L + text.length() * 80L));
            String expectedId = activeUtteranceId;
            SpeechPurpose expectedPurpose = purpose;
            ttsWatchdog = () -> {
                if (expectedId.equals(activeUtteranceId)) completeSpeech(expectedPurpose);
            };
            handler.postDelayed(ttsWatchdog, timeout);
        } catch (RuntimeException error) {
            completeSpeech(purpose);
        }
    }

    private void completeSpeech(SpeechPurpose completedPurpose) {
        removeTtsWatchdog();
        removeTtsInitWatchdog();
        activeUtteranceId = "";
        speechPurpose = SpeechPurpose.NONE;
        pendingSpeechText = "";
        pendingSpeechPurpose = SpeechPurpose.NONE;

        long now = SystemClock.uptimeMillis();
        ignoreAudioUntil = now + IGNORE_AFTER_TTS_MS;
        executingCommand = false;
        firstSpeechAt = 0L;

        if (completedPurpose == SpeechPurpose.WAKE_ACKNOWLEDGEMENT) {
            openCommandWindow();
            broadcastStatus("جی ذیشان، حکم کریں",
                    "بارہ سیکنڈ کے اندر کمانڈ بولیں", true);
            scheduleRestart(IGNORE_AFTER_TTS_MS, false);
        } else {
            listeningMode = ListeningMode.WAKE_WORD;
            commandWindowUntil = 0L;
            wakeDetectedAt = 0L;
            removeCommandWindowTimer();
            scheduleRestart(IGNORE_AFTER_TTS_MS, true);
        }
    }

    @Override
    public void onInit(int status) {
        ttsInitializing = false;
        if (status != TextToSpeech.SUCCESS || tts == null) {
            ttsReady = false;
            finishPendingSpeechWithoutVoice();
            return;
        }

        try {
            ChintuVoiceProfile.configure(tts);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    // Recognition stays closed while Wazir speaks.
                }

                @Override
                public void onDone(String utteranceId) {
                    handler.post(() -> finishUtteranceIfCurrent(utteranceId));
                }

                @Override
                public void onError(String utteranceId) {
                    handler.post(() -> finishUtteranceIfCurrent(utteranceId));
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    handler.post(() -> finishUtteranceIfCurrent(utteranceId));
                }
            });
            ttsReady = true;
        } catch (RuntimeException error) {
            try {
                tts.setLanguage(new Locale("ur", "IN"));
                tts.setPitch(0.72f);
                tts.setSpeechRate(0.98f);
                ttsReady = true;
            } catch (RuntimeException ignored) {
                ttsReady = false;
            }
        }

        if (!pendingSpeechText.isEmpty()) {
            String pendingText = pendingSpeechText;
            SpeechPurpose pendingPurpose = pendingSpeechPurpose;
            pendingSpeechText = "";
            pendingSpeechPurpose = SpeechPurpose.NONE;
            speakWithGuard(pendingText, pendingPurpose);
        }
    }

    private void finishPendingSpeechWithoutVoice() {
        if (pendingSpeechText.isEmpty()) return;
        SpeechPurpose pendingPurpose = pendingSpeechPurpose;
        pendingSpeechText = "";
        pendingSpeechPurpose = SpeechPurpose.NONE;
        completeSpeech(pendingPurpose);
    }

    private void finishUtteranceIfCurrent(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(activeUtteranceId)) return;
        completeSpeech(speechPurpose);
    }

    private void broadcastStatus(String status, String detail, boolean force) {
        String safeStatus = status == null ? "" : status;
        String safeDetail = detail == null ? "" : detail;
        long now = SystemClock.uptimeMillis();
        if (!force) {
            if (safeStatus.equals(lastStatus) && safeDetail.equals(lastDetail)) return;
            if (now - lastStatusBroadcastAt < STATUS_THROTTLE_MS) return;
        }
        lastStatus = safeStatus;
        lastDetail = safeDetail;
        lastStatusBroadcastAt = now;

        Intent intent = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, safeStatus)
                .putExtra(EXTRA_DETAIL, safeDetail)
                .putExtra(EXTRA_ENGINE, engineLabel());
        sendBroadcast(intent);

        if (force || now - lastNotificationAt >= NOTIFICATION_THROTTLE_MS) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && persistedEnabled()) {
                manager.notify(NOTIFICATION_ID, buildNotification(safeStatus));
                lastNotificationAt = now;
            }
        }
    }

    private void broadcastCommand(String command, String result, long latencyMs) {
        Intent intent = new Intent(ACTION_COMMAND)
                .setPackage(getPackageName())
                .putExtra(EXTRA_COMMAND, command == null ? "" : command)
                .putExtra(EXTRA_RESULT, result == null ? "" : result)
                .putExtra(EXTRA_ENGINE, engineLabel())
                .putExtra(EXTRA_LATENCY_MS, latencyMs);
        sendBroadcast(intent);
    }

    private void rotateLanguageAfterRepeatedFailure() {
        if (consecutiveFailures < 2) return;
        consecutiveFailures = 0;
        languageIndex = (languageIndex + 1) % RECOGNITION_LANGUAGES.length;
    }

    private long nextRetryDelay() {
        int exponent = Math.min(consecutiveFailures, 3);
        return Math.min(2_000L, 250L * (1L << exponent));
    }

    private void stopRecognitionSession(boolean destroy) {
        clearRecognitionWatchdogs();
        removePartialCommit();
        suppressErrorsUntil = SystemClock.uptimeMillis() + 700L;
        SpeechRecognizer current = recognizer;
        if (current != null) {
            try {
                current.cancel();
            } catch (RuntimeException ignored) {
                // Vendor recognizer may already be closed.
            }
            if (destroy) {
                try {
                    current.destroy();
                } catch (RuntimeException ignored) {
                    // Vendor compatibility.
                }
                recognizer = null;
            }
        }
        starting = false;
        listening = false;
    }

    private void recreateRecognizer() {
        stopRecognitionSession(true);
    }

    private void stopHandsFree(String status) {
        stopped = true;
        active = false;
        executingCommand = false;
        speechPurpose = SpeechPurpose.NONE;
        listeningMode = ListeningMode.WAKE_WORD;
        commandWindowUntil = 0L;
        clearTimers();
        stopRecognitionSession(true);
        releaseWakeLock();
        if (tts != null) {
            try {
                tts.stop();
            } catch (RuntimeException ignored) {
                // Already stopped.
            }
        }
        setEnabled(false);
        if (status != null && !status.isEmpty()) broadcastCommand("", status, 0L);
        stopForeground(true);
        stopSelf();
    }

    private void setEnabled(boolean enabled) {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        preferences.edit().putBoolean(PREF_ENABLED, enabled).commit();
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
            // Foreground service continues even if HyperOS blocks the wake lock.
        }
    }

    private void releaseWakeLock() {
        if (wakeLockRenewal != null) handler.removeCallbacks(wakeLockRenewal);
        wakeLockRenewal = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
                // Already released by timeout.
            }
        }
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

    private void removeResultWatchdog() {
        if (resultWatchdog != null) handler.removeCallbacks(resultWatchdog);
        resultWatchdog = null;
    }

    private void removePartialCommit() {
        if (partialCommitRunnable != null) handler.removeCallbacks(partialCommitRunnable);
        partialCommitRunnable = null;
    }

    private void removeCommandWindowTimer() {
        if (commandWindowRunnable != null) handler.removeCallbacks(commandWindowRunnable);
        commandWindowRunnable = null;
    }

    private void removeCommandWatchdog() {
        if (commandWatchdog != null) handler.removeCallbacks(commandWatchdog);
        commandWatchdog = null;
    }

    private void removeTtsWatchdog() {
        if (ttsWatchdog != null) handler.removeCallbacks(ttsWatchdog);
        ttsWatchdog = null;
    }

    private void removeTtsInitWatchdog() {
        if (ttsInitWatchdog != null) handler.removeCallbacks(ttsInitWatchdog);
        ttsInitWatchdog = null;
    }

    private void clearRecognitionWatchdogs() {
        removeStartWatchdog();
        removeSessionWatchdog();
        removeResultWatchdog();
    }

    private void clearTimers() {
        clearRestart();
        clearRecognitionWatchdogs();
        removePartialCommit();
        removeCommandWindowTimer();
        removeCommandWatchdog();
        removeTtsWatchdog();
        removeTtsInitWatchdog();
    }

    @Override
    public void onDestroy() {
        processRunning = false;
        stopped = true;
        active = false;
        clearTimers();
        stopRecognitionSession(true);
        releaseWakeLock();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // Vendor TTS may already be disconnected.
            }
            tts = null;
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
