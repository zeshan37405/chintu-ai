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
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Wazir Gen Z 4.0.1 voice-turn service.
 *
 * The wake acknowledgement is intentionally visual/haptic instead of spoken. The 4.0 video showed
 * the recognizer hearing Wazir's own "جی ذیشان، حکم کریں" response and treating it as the user's
 * command. Partial phrases such as "اور دیکھو" were also committed before the sentence finished.
 * This state machine keeps the command window closed until the recognizer is actually ready,
 * rejects recent TTS echo, and commits partial text only when it resembles a complete phone action.
 */
public final class WazirVoiceService extends Service
        implements RecognitionListener, TextToSpeech.OnInitListener {

    public static final String ACTION_START = "com.zeshan.chintuai.action.WAZIR_START";
    public static final String ACTION_STOP = "com.zeshan.chintuai.action.WAZIR_STOP";
    public static final String ACTION_LISTEN_NOW = "com.zeshan.chintuai.action.WAZIR_LISTEN_NOW";
    public static final String ACTION_STATUS = "com.zeshan.chintuai.action.WAZIR_STATUS";
    public static final String ACTION_COMMAND = "com.zeshan.chintuai.action.WAZIR_COMMAND";

    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_ENGINE = "engine";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_LATENCY_MS = "latency_ms";

    private static final String PREFS = "wazir_gen_z_preferences";
    private static final String PREF_ENABLED = "hands_free_enabled";
    private static final String CHANNEL_ID = "wazir_voice_turns";
    private static final int NOTIFICATION_ID = 7410;

    private static final long MIN_RESTART_GAP_MS = 500L;
    private static final long START_WATCHDOG_MS = 5_000L;
    private static final long WAKE_SESSION_MS = 9_000L;
    private static final long COMMAND_SESSION_MS = 25_000L;
    private static final long RESULT_WAIT_MS = 900L;
    private static final long WAKE_SETTLE_MS = 1_100L;
    private static final long COMMAND_SETTLE_MS = 1_300L;
    private static final long COMMAND_READY_WINDOW_MS = 20_000L;
    private static final long COMMAND_ACTIVITY_WINDOW_MS = 8_000L;
    private static final long COMMAND_EXECUTION_TIMEOUT_MS = 15_000L;
    private static final long AFTER_TTS_ECHO_BLOCK_MS = 1_800L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1_000L;
    private static final long WAKE_LOCK_RENEW_MS = 9L * 60L * 1_000L;

    private static final String[] LANGUAGES = {"ur-PK", "ur-IN", "hi-IN", "en-IN"};

    private enum Mode { WAKE, COMMAND }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private PowerManager.WakeLock wakeLock;

    private boolean active;
    private boolean stopped;
    private boolean explicitStop;
    private boolean starting;
    private boolean listening;
    private boolean speechActive;
    private boolean executingCommand;
    private boolean ttsReady;

    private Mode mode = Mode.WAKE;
    private int languageIndex;
    private int failures;
    private int commandSequence;
    private int settleSequence;
    private int utteranceSequence;

    private String lastPartial = "";
    private String lastSpokenText = "";
    private String activeUtteranceId = "";
    private String lastStatus = "";
    private String lastDetail = "";

    private long lastSessionStartedAt;
    private long firstSpeechAt;
    private long wakeDetectedAt;
    private long lastPartialAt;
    private long suppressErrorsUntil;
    private long echoBlockUntil;

    private Runnable restartRunnable;
    private Runnable startWatchdog;
    private Runnable sessionWatchdog;
    private Runnable resultWatchdog;
    private Runnable settleRunnable;
    private Runnable commandWindowRunnable;
    private Runnable commandWatchdog;
    private Runnable ttsWatchdog;
    private Runnable wakeLockRenewal;

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        try {
            tts = new TextToSpeech(this, this);
        } catch (RuntimeException ignored) {
            tts = null;
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "WazirGenZ:VoiceTurnGate");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            explicitStop = true;
            stopWazir("وزیر ہینڈز فری بند کر دیا ہے");
            return START_NOT_STICKY;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setEnabled(false);
            broadcastStatus("مائیکروفون اجازت نہیں", "وزیر کو مائیکروفون کی اجازت دیں", true);
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean listenNow = ACTION_LISTEN_NOW.equals(action);
        if (!active || stopped) initializeService();

        if (listenNow) {
            enterCommandMode("فوری کمانڈ سن رہا ہوں");
        } else if (!starting && !listening && !executingCommand) {
            scheduleRestart(0L);
        }
        return START_STICKY;
    }

    private void initializeService() {
        active = true;
        stopped = false;
        explicitStop = false;
        starting = false;
        listening = false;
        speechActive = false;
        executingCommand = false;
        mode = Mode.WAKE;
        languageIndex = 0;
        failures = 0;
        lastPartial = "";
        firstSpeechAt = 0L;
        wakeDetectedAt = 0L;
        setEnabled(true);
        startAsForeground();
        acquireWakeLock();
        broadcastStatus("وزیر تیار ہے", "کہیں: وزیر، پھر پوری کمانڈ", true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground() {
        Notification notification = buildNotification("کہیں: وزیر، پھر پوری کمانڈ");
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, WazirGenZActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, WazirVoiceService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 11, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("وزیر Gen Z — وائس تیار")
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
                CHANNEL_ID, "Wazir voice turns", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("وزیر wake word اور فوری کمانڈ سروس");
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void startListeningSession() {
        clearRestart();
        if (stopped || explicitStop || executingCommand) return;

        long elapsed = SystemClock.uptimeMillis() - lastSessionStartedAt;
        if (lastSessionStartedAt > 0L && elapsed < MIN_RESTART_GAP_MS) {
            scheduleRestart(MIN_RESTART_GAP_MS - elapsed);
            return;
        }

        if (!ensureRecognizer()) {
            failures++;
            broadcastStatus("وائس انجن دوبارہ جوڑ رہا ہوں", "خودکار کوشش جاری ہے", false);
            scheduleRestart(retryDelay());
            return;
        }

        if (starting || listening) cancelRecognition(false);
        lastPartial = "";
        lastPartialAt = 0L;
        firstSpeechAt = 0L;
        speechActive = false;
        starting = true;
        listening = false;
        lastSessionStartedAt = SystemClock.uptimeMillis();

        try {
            recognizer.startListening(createRecognitionIntent());
            scheduleStartWatchdog();
            scheduleSessionWatchdog();
            broadcastListeningState(false);
        } catch (RuntimeException error) {
            starting = false;
            failures++;
            if (failures >= 2) recreateRecognizer();
            scheduleRestart(retryDelay());
        }
    }

    private boolean ensureRecognizer() {
        if (recognizer != null) return true;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return false;
        try {
            // Redmi's normal Google recognizer is more consistent for Urdu partial callbacks than
            // the device-only service. Offline fallback can be added as a separate engine later.
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            return true;
        } catch (RuntimeException error) {
            recognizer = null;
            return false;
        }
    }

    private Intent createRecognitionIntent() {
        String language = LANGUAGES[Math.min(languageIndex, LANGUAGES.length - 1)];
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1_200L);
        if (Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true);
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                    new ArrayList<>(Arrays.asList(
                            "وزیر", "وزیر جی", "Wazir", "Wazeer", "Vazir", "वज़ीर",
                            "واٹس ایپ کھولو", "واٹسپ کھولو", "فیس بک کھولو",
                            "نیچے سکرول کرو", "اوپر سکرول کرو", "پوسٹ لکھو",
                            "ٹائپ کرو", "کلک کرو", "ہوم کو کال کرو", "واپس جاؤ")));
        }
        return intent;
    }

    private String engineLabel() {
        return "Google stream • " + LANGUAGES[Math.min(languageIndex, LANGUAGES.length - 1)];
    }

    private void broadcastListeningState(boolean force) {
        if (mode == Mode.COMMAND) {
            broadcastStatus("جی ذیشان، حکم کریں",
                    "اب پوری کمانڈ بولیں — وزیر اپنی آواز نہیں چلائے گا", force);
        } else {
            broadcastStatus("وزیر تیار ہے", "کہیں: وزیر، پھر پوری کمانڈ", force);
        }
    }

    private void enterCommandMode(String detail) {
        mode = Mode.COMMAND;
        wakeDetectedAt = SystemClock.uptimeMillis();
        removeCommandWindow();
        cancelRecognition(false);
        vibrateWake();
        broadcastStatus("جی ذیشان، حکم کریں",
                detail + " — ٹائمر مائیک تیار ہونے کے بعد شروع ہوگا", true);
        scheduleRestart(220L);
    }

    private void vibrateWake() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        70L, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(70L);
            }
        } catch (RuntimeException ignored) {
            // Haptic acknowledgement is optional.
        }
    }

    private void armCommandWindow(long delayMs) {
        removeCommandWindow();
        if (mode != Mode.COMMAND || stopped || executingCommand) return;
        commandWindowRunnable = () -> {
            if (mode != Mode.COMMAND || stopped || executingCommand) return;
            if (speechActive || !lastPartial.trim().isEmpty()) {
                armCommandWindow(COMMAND_ACTIVITY_WINDOW_MS);
                return;
            }
            mode = Mode.WAKE;
            broadcastStatus("وزیر تیار ہے",
                    "کمانڈ نہیں ملی؛ دوبارہ کہیں: وزیر", true);
            cancelRecognition(false);
            scheduleRestart(350L);
        };
        handler.postDelayed(commandWindowRunnable, delayMs);
    }

    private void scheduleRestart(long delayMs) {
        clearRestart();
        if (stopped || explicitStop || executingCommand) return;
        restartRunnable = this::startListeningSession;
        handler.postDelayed(restartRunnable, Math.max(0L, delayMs));
    }

    private void scheduleStartWatchdog() {
        removeStartWatchdog();
        startWatchdog = () -> {
            if (!starting || stopped || executingCommand) return;
            failures++;
            cancelRecognition(false);
            if (failures >= 2) recreateRecognizer();
            rotateLanguageIfNeeded();
            scheduleRestart(retryDelay());
        };
        handler.postDelayed(startWatchdog, START_WATCHDOG_MS);
    }

    private void scheduleSessionWatchdog() {
        removeSessionWatchdog();
        long delay = mode == Mode.COMMAND ? COMMAND_SESSION_MS : WAKE_SESSION_MS;
        sessionWatchdog = () -> {
            if (stopped || executingCommand) return;
            String partial = lastPartial.trim();
            if (!partial.isEmpty()) {
                processCandidate(partial, true);
                return;
            }
            cancelRecognition(false);
            scheduleRestart(300L);
        };
        handler.postDelayed(sessionWatchdog, delay);
    }

    private void scheduleResultWatchdog() {
        removeResultWatchdog();
        resultWatchdog = () -> {
            if (stopped || executingCommand) return;
            String partial = lastPartial.trim();
            cancelRecognition(false);
            if (!partial.isEmpty()) processCandidate(partial, true);
            else scheduleRestart(250L);
        };
        handler.postDelayed(resultWatchdog, RESULT_WAIT_MS);
    }

    private void scheduleStableCommit(long delayMs, boolean finalLike) {
        removeSettle();
        int ticket = ++settleSequence;
        String expected = lastPartial;
        settleRunnable = () -> {
            if (ticket != settleSequence || stopped || executingCommand) return;
            if (!expected.equals(lastPartial) || expected.trim().isEmpty()) return;
            if (SystemClock.uptimeMillis() - lastPartialAt < delayMs - 80L) return;
            processCandidate(expected, finalLike);
        };
        handler.postDelayed(settleRunnable, delayMs);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        starting = false;
        listening = true;
        speechActive = false;
        failures = 0;
        removeStartWatchdog();
        if (mode == Mode.COMMAND) armCommandWindow(COMMAND_READY_WINDOW_MS);
        broadcastListeningState(false);
    }

    @Override
    public void onBeginningOfSpeech() {
        starting = false;
        listening = true;
        speechActive = true;
        firstSpeechAt = SystemClock.uptimeMillis();
        removeStartWatchdog();
        if (mode == Mode.COMMAND) removeCommandWindow();
        broadcastStatus("سن رہا ہوں",
                mode == Mode.COMMAND ? "پوری کمانڈ بولتے رہیں…" : "وزیر سن رہا ہے…",
                false);
    }

    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }

    @Override
    public void onEndOfSpeech() {
        starting = false;
        listening = false;
        speechActive = false;
        removeStartWatchdog();
        removeSessionWatchdog();
        removeSettle();
        if (!lastPartial.isEmpty()) broadcastStatus("سمجھ رہا ہوں", lastPartial, false);
        if (mode == Mode.COMMAND) armCommandWindow(4_000L);
        scheduleResultWatchdog();
    }

    @Override
    public void onError(int error) {
        if (SystemClock.uptimeMillis() < suppressErrorsUntil
                || stopped || explicitStop || executingCommand) return;
        starting = false;
        listening = false;
        speechActive = false;
        clearRecognitionTimers();

        String partial = lastPartial.trim();
        lastPartial = "";
        cancelRecognition(false);
        if (!partial.isEmpty() && !isSelfEcho(partial)) {
            processCandidate(partial, true);
            return;
        }

        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            setEnabled(false);
            broadcastStatus("مائیکروفون اجازت نہیں", "وزیر کو اجازت دیں", true);
            stopSelf();
            return;
        }
        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            languageIndex = (languageIndex + 1) % LANGUAGES.length;
            recreateRecognizer();
            scheduleRestart(400L);
            return;
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
            failures++;
            if (failures >= 2) recreateRecognizer();
            scheduleRestart(error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ? 1_800L : 700L);
            return;
        }
        scheduleRestart(300L);
    }

    @Override
    public void onResults(Bundle results) {
        starting = false;
        listening = false;
        speechActive = false;
        clearRecognitionTimers();
        String candidate = chooseBestResult(results);
        if (candidate.isEmpty()) candidate = lastPartial;
        lastPartial = "";
        cancelRecognition(false);
        if (candidate.trim().isEmpty()) scheduleRestart(250L);
        else processCandidate(candidate, true);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String candidate = chooseBestResult(partialResults);
        if (candidate.isEmpty()) return;
        lastPartial = candidate.trim();
        lastPartialAt = SystemClock.uptimeMillis();
        broadcastStatus("سن رہا ہوں",
                AccentCommandNormalizer.canonicalize(lastPartial), false);

        if (mode == Mode.WAKE) {
            if (WazirWakeWord.startsWithWakeWord(lastPartial)) {
                String remainder = WazirWakeWord.strip(lastPartial);
                scheduleStableCommit(remainder.isEmpty() ? WAKE_SETTLE_MS : COMMAND_SETTLE_MS,
                        !remainder.isEmpty() && VoiceTurnPolicy.isLikelyCompleteCommand(remainder));
            }
            return;
        }

        if (isSelfEcho(lastPartial)) {
            removeSettle();
            return;
        }
        armCommandWindow(COMMAND_ACTIVITY_WINDOW_MS);
        if (VoiceTurnPolicy.isLikelyCompleteCommand(lastPartial)) {
            scheduleStableCommit(COMMAND_SETTLE_MS, false);
        } else {
            removeSettle();
        }
    }

    @Override public void onSegmentResults(Bundle segmentResults) { }
    @Override public void onEndOfSegmentedSession() { }
    @Override public void onEvent(int eventType, Bundle params) { }

    private String chooseBestResult(Bundle results) {
        if (results == null) return "";
        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) return "";
        float[] confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < matches.size(); i++) {
            String value = matches.get(i) == null ? "" : matches.get(i).trim();
            String canonical = AccentCommandNormalizer.canonicalize(value);
            int score = CommandEngine.scoreRecognitionCandidate(canonical);
            AppCatalog.AppMatch app = AppCatalog.findBest(canonical);
            if (app != null) score += app.score / 2;
            if (WazirWakeWord.startsWithWakeWord(canonical)) score += 160;
            if (mode == Mode.COMMAND && VoiceTurnPolicy.isLikelyCompleteCommand(canonical)) score += 90;
            if (isSelfEcho(canonical)) score -= 300;
            if (confidences != null && i < confidences.length && confidences[i] >= 0f) {
                score += Math.round(confidences[i] * 35f);
            }
            if (score > bestScore) {
                bestScore = score;
                best = value;
            }
        }
        return best;
    }

    private void processCandidate(String raw, boolean finalLike) {
        if (stopped || explicitStop || executingCommand) return;
        String canonical = AccentCommandNormalizer.canonicalize(raw == null ? "" : raw).trim();
        if (canonical.isEmpty()) {
            scheduleRestart(250L);
            return;
        }

        if (mode == Mode.WAKE) {
            if (!WazirWakeWord.startsWithWakeWord(canonical)) {
                scheduleRestart(250L);
                return;
            }
            String remainder = WazirWakeWord.strip(canonical);
            if (remainder.isEmpty()) {
                enterCommandMode("اب کمانڈ بولیں");
                return;
            }
            if (!finalLike && !VoiceTurnPolicy.isLikelyCompleteCommand(remainder)) return;
            dispatchCommand(remainder);
            return;
        }

        String command = WazirWakeWord.startsWithWakeWord(canonical)
                ? WazirWakeWord.strip(canonical) : canonical;
        if (isSelfEcho(command)) {
            lastPartial = "";
            broadcastStatus("جی ذیشان، حکم کریں",
                    "اپنی آواز نظر انداز کی؛ آپ کمانڈ بولیں", false);
            armCommandWindow(COMMAND_READY_WINDOW_MS);
            cancelRecognition(false);
            scheduleRestart(300L);
            return;
        }
        if (VoiceTurnPolicy.isNoise(command)) {
            lastPartial = "";
            broadcastStatus("سن رہا ہوں", "کمانڈ مکمل بولیں…", false);
            armCommandWindow(COMMAND_ACTIVITY_WINDOW_MS);
            cancelRecognition(false);
            scheduleRestart(250L);
            return;
        }
        if (!finalLike && !VoiceTurnPolicy.isLikelyCompleteCommand(command)) return;
        dispatchCommand(command);
    }

    private boolean isSelfEcho(String text) {
        if (VoiceTurnPolicy.isFixedSelfEcho(text)) return true;
        return SystemClock.uptimeMillis() <= echoBlockUntil
                && VoiceTurnPolicy.matchesRecentSpokenText(text, lastSpokenText);
    }

    private void dispatchCommand(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.trim();
        if (command.isEmpty()) return;
        executingCommand = true;
        mode = Mode.WAKE;
        removeCommandWindow();
        cancelRecognition(false);
        int requestId = ++commandSequence;
        long baseline = firstSpeechAt > 0L ? firstSpeechAt
                : (wakeDetectedAt > 0L ? wakeDetectedAt : SystemClock.uptimeMillis());
        long latency = Math.max(0L, SystemClock.uptimeMillis() - baseline);
        broadcastStatus("عمل کر رہا ہوں", command, true);

        ResultReceiver reply = new ResultReceiver(handler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                if (requestId != commandSequence || stopped) return;
                removeCommandWatchdog();
                String message = data == null ? "" : data.getString(
                        CommandExecutionReceiver.RESULT_MESSAGE, "");
                boolean stop = data != null && data.getBoolean(
                        CommandExecutionReceiver.RESULT_STOP_HANDS_FREE, false);
                finishCommand(command, message, stop, latency);
            }
        };

        Intent execution = new Intent(this, CommandExecutionReceiver.class)
                .setAction(CommandExecutionReceiver.ACTION_EXECUTE)
                .putExtra(CommandExecutionReceiver.EXTRA_COMMAND, command)
                .putExtra(CommandExecutionReceiver.EXTRA_REPLY, reply);
        try {
            sendBroadcast(execution);
        } catch (RuntimeException error) {
            finishCommand(command, "کمانڈ سروس دستیاب نہیں", false, latency);
            return;
        }

        commandWatchdog = () -> {
            if (requestId != commandSequence || stopped) return;
            commandSequence++;
            finishCommand(command, "کمانڈ میں دیر ہوئی؛ وزیر دوبارہ تیار ہے", false, latency);
        };
        handler.postDelayed(commandWatchdog, COMMAND_EXECUTION_TIMEOUT_MS);
    }

    private void finishCommand(String command, String message, boolean stopRequested, long latency) {
        String safe = message == null || message.trim().isEmpty()
                ? "کمانڈ مکمل ہو گئی" : message.trim();
        broadcastCommand(command, safe, latency);
        if (stopRequested && WazirWakeWord.isExactStopCommand(command)) {
            explicitStop = true;
            stopWazir(safe);
            return;
        }
        speakResult(safe);
    }

    private void speakResult(String text) {
        lastSpokenText = text == null ? "" : text.trim();
        echoBlockUntil = SystemClock.uptimeMillis() + AFTER_TTS_ECHO_BLOCK_MS;
        if (!ttsReady || tts == null || lastSpokenText.isEmpty()) {
            completeTts();
            return;
        }
        activeUtteranceId = "wazir-result-" + (++utteranceSequence);
        try {
            tts.stop();
            int result = tts.speak(lastSpokenText, TextToSpeech.QUEUE_FLUSH,
                    null, activeUtteranceId);
            if (result == TextToSpeech.ERROR) {
                completeTts();
                return;
            }
            String expected = activeUtteranceId;
            ttsWatchdog = () -> {
                if (expected.equals(activeUtteranceId)) completeTts();
            };
            handler.postDelayed(ttsWatchdog,
                    Math.max(3_000L, Math.min(10_000L, 1_500L + lastSpokenText.length() * 85L)));
        } catch (RuntimeException error) {
            completeTts();
        }
    }

    private void completeTts() {
        removeTtsWatchdog();
        activeUtteranceId = "";
        echoBlockUntil = SystemClock.uptimeMillis() + AFTER_TTS_ECHO_BLOCK_MS;
        executingCommand = false;
        firstSpeechAt = 0L;
        wakeDetectedAt = 0L;
        lastPartial = "";
        mode = Mode.WAKE;
        broadcastStatus("وزیر تیار ہے", "کہیں: وزیر، پھر پوری کمانڈ", true);
        scheduleRestart(AFTER_TTS_ECHO_BLOCK_MS);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            ttsReady = false;
            return;
        }
        try {
            ChintuVoiceProfile.configure(tts);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) {
                    handler.post(() -> finishUtterance(utteranceId));
                }
                @Override public void onError(String utteranceId) {
                    handler.post(() -> finishUtterance(utteranceId));
                }
                @Override public void onError(String utteranceId, int errorCode) {
                    handler.post(() -> finishUtterance(utteranceId));
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
    }

    private void finishUtterance(String utteranceId) {
        if (utteranceId == null || !utteranceId.equals(activeUtteranceId)) return;
        completeTts();
    }

    private void broadcastStatus(String status, String detail, boolean force) {
        String safeStatus = status == null ? "" : status;
        String safeDetail = detail == null ? "" : detail;
        if (!force && safeStatus.equals(lastStatus) && safeDetail.equals(lastDetail)) return;
        lastStatus = safeStatus;
        lastDetail = safeDetail;
        Intent intent = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, safeStatus)
                .putExtra(EXTRA_DETAIL, safeDetail)
                .putExtra(EXTRA_ENGINE, engineLabel());
        sendBroadcast(intent);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && isEnabled(this)) {
            manager.notify(NOTIFICATION_ID, buildNotification(safeStatus));
        }
    }

    private void broadcastCommand(String command, String result, long latency) {
        Intent intent = new Intent(ACTION_COMMAND)
                .setPackage(getPackageName())
                .putExtra(EXTRA_COMMAND, command == null ? "" : command)
                .putExtra(EXTRA_RESULT, result == null ? "" : result)
                .putExtra(EXTRA_ENGINE, engineLabel())
                .putExtra(EXTRA_LATENCY_MS, latency);
        sendBroadcast(intent);
    }

    private void cancelRecognition(boolean destroy) {
        clearRecognitionTimers();
        removeSettle();
        suppressErrorsUntil = SystemClock.uptimeMillis() + 650L;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (RuntimeException ignored) { }
            if (destroy) {
                try { recognizer.destroy(); } catch (RuntimeException ignored) { }
                recognizer = null;
            }
        }
        starting = false;
        listening = false;
        speechActive = false;
    }

    private void recreateRecognizer() {
        cancelRecognition(true);
    }

    private void rotateLanguageIfNeeded() {
        if (failures < 2) return;
        failures = 0;
        languageIndex = (languageIndex + 1) % LANGUAGES.length;
    }

    private long retryDelay() {
        int exponent = Math.min(failures, 3);
        return Math.min(2_000L, 250L * (1L << exponent));
    }

    private void stopWazir(String message) {
        stopped = true;
        active = false;
        executingCommand = false;
        clearAllTimers();
        cancelRecognition(true);
        releaseWakeLock();
        if (tts != null) {
            try { tts.stop(); } catch (RuntimeException ignored) { }
        }
        setEnabled(false);
        if (message != null && !message.isEmpty()) broadcastCommand("", message, 0L);
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
        } catch (RuntimeException ignored) { }
    }

    private void releaseWakeLock() {
        if (wakeLockRenewal != null) handler.removeCallbacks(wakeLockRenewal);
        wakeLockRenewal = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (RuntimeException ignored) { }
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
    private void removeSettle() {
        settleSequence++;
        if (settleRunnable != null) handler.removeCallbacks(settleRunnable);
        settleRunnable = null;
    }
    private void removeCommandWindow() {
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
    private void clearRecognitionTimers() {
        removeStartWatchdog();
        removeSessionWatchdog();
        removeResultWatchdog();
    }
    private void clearAllTimers() {
        clearRestart();
        clearRecognitionTimers();
        removeSettle();
        removeCommandWindow();
        removeCommandWatchdog();
        removeTtsWatchdog();
    }

    @Override
    public void onDestroy() {
        stopped = true;
        active = false;
        clearAllTimers();
        cancelRecognition(true);
        releaseWakeLock();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (RuntimeException ignored) { }
            tts = null;
        }
        super.onDestroy();
    }
}
