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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redmi-safe hands-free service.
 *
 * Some Xiaomi recognition services accept an injected AudioRecord stream but never emit words,
 * leaving the UI apparently active forever. This implementation deliberately uses the platform
 * recognizer's own microphone, rotates Urdu/Indian language models, and renews the recognition
 * session every eighteen seconds while keeping the visible owner-lock state stable.
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

    private static final long START_WATCHDOG_MS = 6_000L;
    private static final long SESSION_WATCHDOG_MS = 18_000L;
    private static final long RESULT_WATCHDOG_MS = 7_000L;
    private static final long COMMAND_WINDOW_MS = 15_000L;
    private static final long IGNORE_AFTER_TTS_MS = 750L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 9L * 60L * 1000L;

    private static final String[] RECOGNITION_LANGUAGES = {
            "ur-IN", "ur-PK", "hi-IN", "en-IN"
    };

    private static final List<String> WAKE_PREFIXES = Arrays.asList(
            "چنٹو", "چنتو", "چینٹو", "چین تو", "چن ٹو",
            "جنٹو", "جن تو", "چندو", "chintu", "chintoo");

    private enum SpeechPurpose {
        NONE,
        WAKE_ACKNOWLEDGEMENT,
        COMMAND_RESPONSE
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();

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

    private int languageIndex;
    private int consecutiveFailures;
    private int utteranceSequence;

    private String lastPartial = "";
    private String lastStatus = "";
    private String lastDetail = "";
    private String activeUtteranceId = "";
    private String pendingSpeechText = "";

    private long suppressErrorsUntil;
    private long commandWindowUntil;
    private long ignoreAudioUntil;

    private SpeechPurpose speechPurpose = SpeechPurpose.NONE;
    private SpeechPurpose pendingSpeechPurpose = SpeechPurpose.NONE;

    private Runnable restartRunnable;
    private Runnable startWatchdog;
    private Runnable sessionWatchdog;
    private Runnable resultWatchdog;
    private Runnable wakeLockRenewal;
    private Runnable ttsWatchdog;
    private Runnable ttsInitWatchdog;

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
                    "ChintuAI:RedmiSafeHandsFree");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            explicitStop = true;
            stopHandsFree("ہینڈز فری موڈ بند کر دیا ہے");
            return START_NOT_STICKY;
        }

        if (intent == null && !isEnabled(this)) {
            explicitStop = true;
            stopSelf();
            return START_NOT_STICKY;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            explicitStop = true;
            setEnabled(false);
            broadcastStatus("مائیکروفون اجازت نہیں", "ایپ کھول کر مائیکروفون Allow کریں", true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (active && !stopped) {
            broadcastStatus(commandWindowOpen() ? "جی، بولیں" : "مالک لاک فعال ہے",
                    commandWindowOpen()
                            ? "پندرہ سیکنڈ کے اندر مکمل کمانڈ بولیں"
                            : "کہیں: چنٹو، پھر کمانڈ",
                    true);
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
        commandWindowUntil = 0L;
        ignoreAudioUntil = 0L;
        lastPartial = "";

        setEnabled(true);
        startAsForeground();
        acquireWakeLock();
        broadcastStatus("مالک لاک فعال ہے", "کہیں: چنٹو، پھر کمانڈ", true);
        scheduleRestart(0L, false);
        return START_STICKY;
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

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Chintu AI — مسلسل سن رہا ہے")
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
                CHANNEL_ID, "Chintu hands-free microphone", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("چنٹو کی مسلسل wake-word وائس کمانڈ سروس");
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void startListeningSession() {
        clearRestart();
        if (stopped || explicitStop || executingCommand) return;

        stopRecognitionSession(true);
        if (!createRecognizer()) {
            consecutiveFailures++;
            broadcastStatus("وائس سروس دوبارہ جوڑ رہا ہوں",
                    "مائیک بند نہیں ہوا؛ دوبارہ کوشش جاری ہے", false);
            scheduleRestart(nextRetryDelay(), false);
            return;
        }

        lastPartial = "";
        starting = true;
        listening = false;
        recognizer.setRecognitionListener(this);

        try {
            recognizer.startListening(createRecognitionIntent());
            scheduleStartWatchdog();
            scheduleSessionWatchdog();
            broadcastStatus(commandWindowOpen() ? "جی، بولیں" : "مالک لاک فعال ہے",
                    commandWindowOpen()
                            ? "پندرہ سیکنڈ کے اندر مکمل کمانڈ بولیں"
                            : "کہیں: چنٹو، پھر کمانڈ",
                    false);
        } catch (RuntimeException error) {
            starting = false;
            consecutiveFailures++;
            destroyRecognizer();
            rotateLanguageAfterRepeatedFailure();
            scheduleRestart(nextRetryDelay(), false);
        }
    }

    private boolean createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return false;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            return recognizer != null;
        } catch (RuntimeException error) {
            recognizer = null;
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
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 15);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30_000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5_000L);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                7_000L);

        if (Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true);
            ArrayList<String> bias = new ArrayList<>(Arrays.asList(
                    "چنٹو", "چنٹو ذیشان", "واٹس ایپ", "واٹسپ", "واٹساپ",
                    "فیس بک", "انسٹاگرام", "یوٹیوب", "ٹیلیگرام", "ٹک ٹاک",
                    "واٹس ایپ کھولو", "فیس بک کھولو", "نیچے سکرول کرو",
                    "اوپر سکرول کرو", "پوسٹ لکھو", "ٹائپ کرو", "کلک کرو",
                    "ہوم کو کال کرو", "واپس جاؤ", "اسکرین شاٹ لو", "تصدیق کرو",
                    "व्हाट्सएप", "फेसबुक", "इंस्टाग्राम", "नीचे स्क्रॉल करो"));
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, bias);
        }
        return intent;
    }

    private void scheduleRestart(long delayMs, boolean resetStatus) {
        clearRestart();
        if (stopped || explicitStop || executingCommand) return;
        if (resetStatus) {
            broadcastStatus("مالک لاک فعال ہے", "کہیں: چنٹو، پھر کمانڈ", false);
        }
        restartRunnable = this::startListeningSession;
        handler.postDelayed(restartRunnable, Math.max(0L, delayMs));
    }

    private void scheduleStartWatchdog() {
        removeStartWatchdog();
        startWatchdog = () -> {
            if (stopped || explicitStop || executingCommand || !starting) return;
            consecutiveFailures++;
            destroyRecognizer();
            rotateLanguageAfterRepeatedFailure();
            scheduleRestart(nextRetryDelay(), false);
        };
        handler.postDelayed(startWatchdog, START_WATCHDOG_MS);
    }

    /** Prevents the recognizer from sitting visually active forever without returning words. */
    private void scheduleSessionWatchdog() {
        removeSessionWatchdog();
        sessionWatchdog = () -> {
            if (stopped || explicitStop || executingCommand) return;
            String partial = lastPartial.trim();
            stopRecognitionSession(true);
            if (!partial.isEmpty() && (hasFlexibleWakeWord(partial) || commandWindowOpen())) {
                processCandidate(partial, -1f);
                return;
            }
            languageIndex = (languageIndex + 1) % RECOGNITION_LANGUAGES.length;
            broadcastStatus(commandWindowOpen() ? "جی، بولیں" : "مالک لاک فعال ہے",
                    "وائس ماڈل تازہ کر رہا ہوں؛ دوبارہ بولیں", false);
            scheduleRestart(220L, false);
        };
        handler.postDelayed(sessionWatchdog, SESSION_WATCHDOG_MS);
    }

    private void scheduleResultWatchdog() {
        removeResultWatchdog();
        resultWatchdog = () -> {
            if (stopped || explicitStop || executingCommand) return;
            String partial = lastPartial.trim();
            stopRecognitionSession(true);
            if (!partial.isEmpty()) processCandidate(partial, -1f);
            else scheduleRestart(180L, false);
        };
        handler.postDelayed(resultWatchdog, RESULT_WATCHDOG_MS);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        starting = false;
        listening = true;
        consecutiveFailures = 0;
        removeStartWatchdog();
        broadcastStatus(commandWindowOpen() ? "جی، بولیں" : "مالک لاک فعال ہے",
                commandWindowOpen()
                        ? "پندرہ سیکنڈ کے اندر مکمل کمانڈ بولیں"
                        : "کہیں: چنٹو، پھر کمانڈ",
                false);
    }

    @Override
    public void onBeginningOfSpeech() {
        starting = false;
        listening = true;
        removeStartWatchdog();
        if (commandWindowOpen()) {
            broadcastStatus("سن رہا ہوں", "کمانڈ مکمل بولیں...", false);
        }
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Raw room noise never changes state or executes a command.
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
        if (!lastPartial.isEmpty() || commandWindowOpen()) {
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

        String partial = lastPartial.trim();
        lastPartial = "";
        stopRecognitionSession(true);

        if (!partial.isEmpty() && (hasFlexibleWakeWord(partial) || commandWindowOpen())) {
            processCandidate(partial, -1f);
            return;
        }

        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            explicitStop = true;
            setEnabled(false);
            broadcastStatus("مائیکروفون اجازت نہیں", "ایپ میں اجازت دیں", true);
            stopSelf();
            return;
        }

        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            languageIndex = (languageIndex + 1) % RECOGNITION_LANGUAGES.length;
            broadcastStatus("وائس زبان بدل رہا ہوں",
                    "Indian Urdu سے متبادل Urdu/Hindi ماڈل آزما رہا ہوں", false);
            scheduleRestart(350L, false);
            return;
        }

        consecutiveFailures++;
        rotateLanguageAfterRepeatedFailure();
        long delay = error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                ? 2_500L : nextRetryDelay();
        scheduleRestart(delay, false);
    }

    @Override
    public void onResults(Bundle results) {
        starting = false;
        listening = false;
        clearRecognitionWatchdogs();
        RecognizedCandidate candidate = chooseBestResult(results);
        if (candidate.text.isEmpty()) candidate = new RecognizedCandidate(lastPartial, -1f);
        lastPartial = "";
        stopRecognitionSession(true);

        if (candidate.text.isEmpty()) {
            scheduleRestart(180L, false);
        } else {
            processCandidate(candidate.text, candidate.confidence);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        RecognizedCandidate candidate = chooseBestResult(partialResults);
        if (candidate.text.isEmpty()) return;
        lastPartial = candidate.text;
        if (hasFlexibleWakeWord(candidate.text) || commandWindowOpen()) {
            broadcastStatus("سن رہا ہوں",
                    AccentCommandNormalizer.canonicalize(candidate.text), false);
        }
    }

    @Override
    public void onSegmentResults(Bundle segmentResults) {
        // Ordinary Redmi-safe sessions use onResults/onPartialResults.
    }

    @Override
    public void onEndOfSegmentedSession() {
        // Ordinary Redmi-safe sessions use onResults/onError.
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // No vendor-specific event required.
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
            if (hasFlexibleWakeWord(canonical)) score += 100;
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

        String canonical = AccentCommandNormalizer.canonicalize(candidate);
        boolean hasWakeWord = hasFlexibleWakeWord(canonical);
        boolean commandWindow = commandWindowOpen();

        if (!hasWakeWord && !commandWindow) {
            scheduleRestart(160L, false);
            return;
        }

        if (!hasWakeWord && confidence >= 0f && confidence < 0.08f) {
            commandWindowUntil = 0L;
            broadcastStatus("آواز واضح نہیں", "دوبارہ کہیں: چنٹو، پھر کمانڈ", false);
            scheduleRestart(250L, false);
            return;
        }

        String command = hasWakeWord ? stripFlexibleWakeWord(canonical) : canonical.trim();
        if (hasWakeWord && command.isEmpty()) {
            commandWindowUntil = 0L;
            executingCommand = true;
            stopRecognitionSession(true);
            broadcastStatus("جی، ذیشان", "جو حکم، بولیں", true);
            speakWithGuard("جی ذیشان، بولیں", SpeechPurpose.WAKE_ACKNOWLEDGEMENT);
            return;
        }

        commandWindowUntil = 0L;
        if (looksLikeNoise(command)) {
            broadcastStatus("کمانڈ واضح نہیں", "کہیں: چنٹو، پھر مکمل کمانڈ", false);
            scheduleRestart(250L, false);
            return;
        }

        executingCommand = true;
        stopRecognitionSession(true);
        broadcastStatus("کمانڈ ملی", command, true);
        String finalCommand = command;
        commandExecutor.execute(() -> {
            BackgroundCommandExecutor.Result result =
                    BackgroundCommandExecutor.execute(getApplicationContext(), finalCommand);
            handler.post(() -> {
                broadcastCommand(finalCommand, result.message);
                if (result.stopHandsFree) {
                    if (isExactStopCommand(finalCommand)) {
                        explicitStop = true;
                        stopHandsFree(result.message);
                    } else {
                        speakWithGuard(
                                "ہینڈز فری بند نہیں کیا۔ بند کرنے کے لیے واضح کہیں: چنٹو ہینڈز فری بند کرو",
                                SpeechPurpose.COMMAND_RESPONSE);
                    }
                } else {
                    speakWithGuard(result.message, SpeechPurpose.COMMAND_RESPONSE);
                }
            });
        });
    }

    private boolean hasFlexibleWakeWord(String raw) {
        String normalized = CommandEngine.normalize(
                AccentCommandNormalizer.canonicalize(raw));
        for (String prefix : WAKE_PREFIXES) {
            String key = CommandEngine.normalize(prefix);
            if (normalized.equals(key) || normalized.startsWith(key + " ")) return true;
        }
        return false;
    }

    private String stripFlexibleWakeWord(String raw) {
        String canonical = AccentCommandNormalizer.canonicalize(raw).trim();
        return canonical
                .replaceFirst("(?iu)^(چنٹو|چنتو|چینٹو|چین تو|چن ٹو|جنٹو|جن تو|چندو|chintu|chintoo)(\\s+جی|\\s+سنو|\\s+بھائی|\\s+ذیشان)?\\s*", "")
                .trim();
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
        return normalized.split(" ").length == 1 && normalized.length() < 3;
    }

    private boolean isExactStopCommand(String command) {
        String normalized = CommandEngine.normalize(
                AccentCommandNormalizer.canonicalize(command));
        return normalized.equals("ہینڈز فری بند کرو")
                || normalized.equals("ہینڈ فری بند کرو")
                || normalized.equals("مسلسل سننا بند کرو")
                || normalized.equals("چنٹو بند ہو جاؤ")
                || normalized.equals("بند ہو جاؤ")
                || normalized.equals("hands free off");
    }

    private boolean commandWindowOpen() {
        return SystemClock.uptimeMillis() <= commandWindowUntil;
    }

    private void speakWithGuard(String message, SpeechPurpose purpose) {
        removeTtsWatchdog();
        removeTtsInitWatchdog();
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
                    SpeechPurpose pendingPurpose = pendingSpeechPurpose;
                    pendingSpeechText = "";
                    pendingSpeechPurpose = SpeechPurpose.NONE;
                    completeSpeech(pendingPurpose);
                }
            };
            handler.postDelayed(ttsInitWatchdog, 1_800L);
            return;
        }

        pendingSpeechText = "";
        pendingSpeechPurpose = SpeechPurpose.NONE;
        activeUtteranceId = "chintu-" + (++utteranceSequence);
        try {
            tts.stop();
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
            if (result == TextToSpeech.ERROR) {
                completeSpeech(purpose);
                return;
            }
            long timeout = Math.max(3_000L, Math.min(10_000L, 1_700L + text.length() * 95L));
            SpeechPurpose expectedPurpose = purpose;
            String expectedId = activeUtteranceId;
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

        if (completedPurpose == SpeechPurpose.WAKE_ACKNOWLEDGEMENT) {
            commandWindowUntil = now + COMMAND_WINDOW_MS;
            broadcastStatus("جی، بولیں", "پندرہ سیکنڈ کے اندر مکمل کمانڈ بولیں", true);
            scheduleRestart(IGNORE_AFTER_TTS_MS, false);
        } else {
            commandWindowUntil = 0L;
            scheduleRestart(IGNORE_AFTER_TTS_MS, true);
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            ttsReady = false;
            finishPendingSpeechWithoutVoice();
            return;
        }

        ChintuVoiceProfile.Selection selection = ChintuVoiceProfile.configure(tts);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // Recognition stays closed while Chintu speaks.
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
        ttsReady = selection.configured || tts.getVoice() != null;

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
        SpeechPurpose completed = speechPurpose;
        completeSpeech(completed);
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
                .putExtra(EXTRA_DETAIL, safeDetail);
        sendBroadcast(intent);

        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && isEnabled(this)) {
            manager.notify(NOTIFICATION_ID, buildNotification(safeStatus));
        }
    }

    private void broadcastCommand(String command, String result) {
        Intent intent = new Intent(ACTION_COMMAND)
                .setPackage(getPackageName())
                .putExtra(EXTRA_COMMAND, command)
                .putExtra(EXTRA_RESULT, result);
        sendBroadcast(intent);
    }

    private void rotateLanguageAfterRepeatedFailure() {
        if (consecutiveFailures < 2) return;
        consecutiveFailures = 0;
        languageIndex = (languageIndex + 1) % RECOGNITION_LANGUAGES.length;
    }

    private long nextRetryDelay() {
        int exponent = Math.min(consecutiveFailures, 3);
        return Math.min(2_200L, 220L * (1L << exponent));
    }

    private void stopRecognitionSession(boolean destroy) {
        clearRecognitionWatchdogs();
        suppressErrorsUntil = SystemClock.uptimeMillis() + 850L;
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

    private void destroyRecognizer() {
        stopRecognitionSession(true);
    }

    private void stopHandsFree(String status) {
        stopped = true;
        active = false;
        executingCommand = false;
        speechPurpose = SpeechPurpose.NONE;
        commandWindowUntil = 0L;
        clearTimers();
        removeTtsWatchdog();
        removeTtsInitWatchdog();
        destroyRecognizer();
        releaseWakeLock();
        if (tts != null) tts.stop();
        setEnabled(false);
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
    }

    @Override
    public void onDestroy() {
        stopped = true;
        active = false;
        executingCommand = false;
        clearTimers();
        removeTtsWatchdog();
        removeTtsInitWatchdog();
        destroyRecognizer();
        releaseWakeLock();
        commandExecutor.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        // Keep the preference true when Android/HyperOS kills a sticky service unexpectedly.
        if (explicitStop) setEnabled(false);
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
