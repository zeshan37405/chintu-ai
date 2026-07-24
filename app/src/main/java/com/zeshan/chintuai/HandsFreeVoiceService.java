package com.zeshan.chintuai;

import android.Manifest;
import android.annotation.TargetApi;
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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stable hands-free recognizer for Android 13+.
 *
 * Android's ordinary SpeechRecognizer closes its microphone after a silence timeout. On Android 13
 * and newer Chintu instead owns one continuous AudioRecord stream and supplies that audio to the
 * recognition service through EXTRA_AUDIO_SOURCE in segmented-session mode. The recognition
 * session therefore remains open until Chintu closes the pipe, rather than blinking on/off every
 * one or two seconds. A conservative ordinary-session fallback remains for recognition services
 * that do not implement injected audio.
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

    private static final int SAMPLE_RATE = 16_000;
    private static final int CHANNEL_COUNT = 1;
    private static final long START_WATCHDOG_MS = 7_000L;
    private static final long FALLBACK_SESSION_WATCHDOG_MS = 55_000L;
    private static final long INJECTED_HEALTH_RESTART_MS = 20L * 60L * 1000L;
    private static final long COMMAND_WINDOW_MS = 12_000L;
    private static final long IGNORE_AFTER_TTS_MS = 1_100L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 9L * 60L * 1000L;

    private static final List<String> WAKE_PREFIXES = Arrays.asList(
            "چنٹو", "چنتو", "چینٹو", "چین تو", "چن ٹو",
            "جنٹو", "جن تو", "چندو", "chintu");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pumpingAudio = new AtomicBoolean(false);

    private SpeechRecognizer recognizer;
    private Intent recognitionIntent;
    private AudioRecord audioRecord;
    private ParcelFileDescriptor recognizerAudioRead;
    private ParcelFileDescriptor recognizerAudioWrite;

    private boolean stopped;
    private boolean starting;
    private boolean executingCommand;
    private boolean injectedSession;
    private boolean injectedAudioDisabled;
    private int injectedFailures;
    private int fallbackFailures;

    private String lastPartial = "";
    private String lastStatus = "";
    private String lastDetail = "";
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
                    "ChintuAI:StableHandsFree");
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
            broadcastStatus("مائیکروفون اجازت نہیں", "ایپ کھول کر مائیکروفون Allow کریں", true);
            stopSelf();
            return START_NOT_STICKY;
        }

        stopped = false;
        executingCommand = false;
        commandWindowUntil = 0L;
        ignoreAudioUntil = 0L;
        injectedFailures = 0;
        fallbackFailures = 0;
        injectedAudioDisabled = false;
        setEnabled(true);
        startAsForeground();
        acquireWakeLock();
        broadcastStatus("مالک لاک فعال ہے", "کہیں: چنٹو، پھر کمانڈ", true);
        scheduleRestart(0L, false);
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
                CHANNEL_ID, "Chintu stable hands-free microphone",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("چنٹو کی مسلسل wake-word وائس کمانڈ سروس");
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void startListeningSession() {
        clearRestart();
        if (stopped || executingCommand) return;

        stopRecognitionSession(true);
        if (!createRecognizer()) {
            broadcastStatus("وائس شناخت دستیاب نہیں", "Google وائس سروس اپڈیٹ کریں", true);
            scheduleRestart(2_000L, false);
            return;
        }

        lastPartial = "";
        starting = true;
        recognizer.setRecognitionListener(this);

        boolean injectedAttempted = Build.VERSION.SDK_INT >= 33 && !injectedAudioDisabled;
        boolean started = injectedAttempted && startInjectedSegmentedSession();

        if (!started) {
            // If an injected session partially started, recreate the recognizer before the fallback.
            if (injectedAttempted) {
                stopRecognitionSession(true);
                if (!createRecognizer()) {
                    scheduleRestart(nextFallbackDelay(), false);
                    return;
                }
                recognizer.setRecognitionListener(this);
                starting = true;
            }
            injectedSession = false;
            recognitionIntent = createRecognitionIntent(false);
            try {
                recognizer.startListening(recognitionIntent);
                started = true;
                scheduleFallbackSessionWatchdog();
            } catch (RuntimeException error) {
                started = false;
            }
        }

        if (!started) {
            starting = false;
            destroyRecognizer();
            scheduleRestart(nextFallbackDelay(), false);
            return;
        }

        scheduleStartWatchdog();
        broadcastStatus(commandWindowOpen() ? "جی، بولیں" : "مالک لاک فعال ہے",
                commandWindowOpen() ? "اب مکمل کمانڈ بولیں" : "کہیں: چنٹو، پھر کمانڈ",
                false);
    }

    private boolean createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return false;
        try {
            // The installed Google/Xiaomi recognizer is preferred because Urdu support is more
            // reliable than merely checking whether any on-device recognizer exists.
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            return recognizer != null;
        } catch (RuntimeException error) {
            recognizer = null;
            return false;
        }
    }

    @TargetApi(33)
    private boolean startInjectedSegmentedSession() {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) return false;

        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            recognizerAudioRead = pipe[0];
            recognizerAudioWrite = pipe[1];

            audioRecord = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuffer * 4, 32_768))
                    .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                closeInjectedAudio();
                return false;
            }

            recognitionIntent = createRecognitionIntent(true);
            recognitionIntent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, recognizerAudioRead);
            recognitionIntent.putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, CHANNEL_COUNT);
            recognitionIntent.putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            recognitionIntent.putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
            recognitionIntent.putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_AUDIO_SOURCE);

            recognizer.startListening(recognitionIntent);
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                closeInjectedAudio();
                return false;
            }

            injectedSession = true;
            pumpingAudio.set(true);
            ParcelFileDescriptor writeEnd = recognizerAudioWrite;
            recognizerAudioWrite = null;
            audioExecutor.execute(() -> pumpAudio(writeEnd));
            scheduleInjectedHealthRestart();
            return true;
        } catch (IOException | RuntimeException error) {
            closeInjectedAudio();
            return false;
        }
    }

    private void pumpAudio(ParcelFileDescriptor writeEnd) {
        byte[] buffer = new byte[8_192];
        try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
            while (pumpingAudio.get() && !stopped) {
                AudioRecord recorder = audioRecord;
                if (recorder == null) break;
                int read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    output.write(buffer, 0, read);
                } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                    break;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Closing the pipe during command execution is the normal session shutdown path.
        } finally {
            pumpingAudio.set(false);
        }
    }

    private Intent createRecognitionIntent(boolean injected) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);

        if (Build.VERSION.SDK_INT >= 33) {
            ArrayList<String> bias = new ArrayList<>(Arrays.asList(
                    "چنٹو", "چنٹو ذیشان", "ہوم کو کال کرو", "واٹس ایپ کھولو",
                    "نیچے سکرول کرو", "اوپر سکرول کرو", "واپس جاؤ",
                    "اسکرین شاٹ لو", "تصدیق کرو"));
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, bias);
            if (!injected) {
                intent.putExtra(
                        RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS);
            }
        }

        if (!injected) {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 45_000L);
            intent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_200L);
            intent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    3_500L);
        }
        return intent;
    }

    private void scheduleRestart(long delayMs, boolean forceStatus) {
        clearRestart();
        if (stopped || executingCommand) return;
        if (forceStatus) {
            broadcastStatus("مالک لاک فعال ہے", "کہیں: چنٹو، پھر کمانڈ", false);
        }
        restartRunnable = this::startListeningSession;
        handler.postDelayed(restartRunnable, Math.max(0L, delayMs));
    }

    private void scheduleStartWatchdog() {
        removeStartWatchdog();
        startWatchdog = () -> {
            if (stopped || executingCommand || !starting) return;
            if (injectedSession) {
                injectedFailures++;
                if (injectedFailures >= 2) injectedAudioDisabled = true;
            }
            stopRecognitionSession(true);
            scheduleRestart(injectedAudioDisabled ? 900L : 250L, false);
        };
        handler.postDelayed(startWatchdog, START_WATCHDOG_MS);
    }

    private void scheduleFallbackSessionWatchdog() {
        removeSessionWatchdog();
        sessionWatchdog = () -> {
            if (stopped || executingCommand || injectedSession) return;
            String partial = lastPartial;
            stopRecognitionSession(true);
            if (!partial.isEmpty()) processCandidate(partial, -1f, false);
            else scheduleRestart(350L, false);
        };
        handler.postDelayed(sessionWatchdog, FALLBACK_SESSION_WATCHDOG_MS);
    }

    private void scheduleInjectedHealthRestart() {
        removeSessionWatchdog();
        sessionWatchdog = () -> {
            if (stopped || executingCommand || !injectedSession) return;
            stopRecognitionSession(true);
            scheduleRestart(250L, false);
        };
        handler.postDelayed(sessionWatchdog, INJECTED_HEALTH_RESTART_MS);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        starting = false;
        injectedFailures = 0;
        fallbackFailures = 0;
        removeStartWatchdog();
        broadcastStatus(commandWindowOpen() ? "جی، بولیں" : "مالک لاک فعال ہے",
                commandWindowOpen() ? "اب مکمل کمانڈ بولیں" : "کہیں: چنٹو، پھر کمانڈ",
                false);
    }

    @Override
    public void onBeginningOfSpeech() {
        starting = false;
        removeStartWatchdog();
        if (commandWindowOpen()) {
            broadcastStatus("سن رہا ہوں", "کمانڈ مکمل بولیں...", false);
        }
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Sound level alone never changes state or executes a command.
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // Audio is streamed through the injected pipe on Android 13+.
    }

    @Override
    public void onEndOfSpeech() {
        removeStartWatchdog();
    }

    @Override
    public void onError(int error) {
        if (SystemClock.uptimeMillis() < suppressErrorsUntil || stopped || executingCommand) return;
        starting = false;
        removeStartWatchdog();
        removeSessionWatchdog();

        String partial = lastPartial.trim();
        lastPartial = "";
        boolean wasInjected = injectedSession;
        stopRecognitionSession(true);

        if (!partial.isEmpty() && (hasFlexibleWakeWord(partial) || commandWindowOpen())) {
            processCandidate(partial, -1f, false);
            return;
        }
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            broadcastStatus("مائیکروفون اجازت نہیں", "ایپ میں اجازت دیں", true);
            stopHandsFree("");
            return;
        }

        if (wasInjected) {
            injectedFailures++;
            if (injectedFailures >= 2
                    || error == SpeechRecognizer.ERROR_AUDIO
                    || error == SpeechRecognizer.ERROR_CLIENT) {
                injectedAudioDisabled = true;
            }
        } else {
            fallbackFailures++;
        }
        scheduleRestart(wasInjected && !injectedAudioDisabled
                ? 250L : nextFallbackDelay(), false);
    }

    @Override
    public void onResults(Bundle results) {
        starting = false;
        removeStartWatchdog();
        removeSessionWatchdog();
        RecognizedCandidate candidate = chooseBestResult(results);
        if (candidate.text.isEmpty()) candidate = new RecognizedCandidate(lastPartial, -1f);
        lastPartial = "";
        stopRecognitionSession(true);
        if (candidate.text.trim().isEmpty()) {
            scheduleRestart(300L, false);
        } else {
            processCandidate(candidate.text, candidate.confidence, false);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        RecognizedCandidate candidate = chooseBestResult(partialResults);
        if (candidate.text.isEmpty()) return;
        lastPartial = candidate.text;
        if (hasFlexibleWakeWord(candidate.text) || commandWindowOpen()) {
            broadcastStatus("سن رہا ہوں", candidate.text, false);
        }
    }

    @Override
    public void onSegmentResults(Bundle segmentResults) {
        if (Build.VERSION.SDK_INT < 33 || stopped || executingCommand) return;
        RecognizedCandidate candidate = chooseBestResult(segmentResults);
        if (candidate.text.trim().isEmpty()) return;
        lastPartial = "";
        processCandidate(candidate.text, candidate.confidence, true);
    }

    @Override
    public void onEndOfSegmentedSession() {
        if (Build.VERSION.SDK_INT < 33 || stopped || executingCommand) return;
        stopRecognitionSession(true);
        scheduleRestart(220L, false);
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // No vendor-specific event is required.
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
            if (hasFlexibleWakeWord(value)) score += 80;
            if (confidence >= 0f) score += Math.round(confidence * 30f);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        float confidence = confidences != null && bestIndex < confidences.length
                ? confidences[bestIndex] : -1f;
        return new RecognizedCandidate(matches.get(bestIndex), confidence);
    }

    private void processCandidate(String candidate, float confidence, boolean keepSegmentedSession) {
        if (stopped || executingCommand) return;
        long now = SystemClock.uptimeMillis();
        if (now < ignoreAudioUntil) return;

        String cleaned = candidate == null ? "" : candidate.trim();
        boolean hasWakeWord = hasFlexibleWakeWord(cleaned);
        boolean commandWindow = commandWindowOpen();

        if (!hasWakeWord && !commandWindow) {
            // Room sounds and other speakers are ignored without closing the continuous mic.
            if (!keepSegmentedSession) scheduleRestart(300L, false);
            return;
        }
        if (!hasWakeWord && confidence >= 0f && confidence < 0.12f) {
            commandWindowUntil = 0L;
            broadcastStatus("آواز واضح نہیں", "دوبارہ کہیں: چنٹو، پھر کمانڈ", false);
            if (!keepSegmentedSession) scheduleRestart(350L, false);
            return;
        }

        String command = hasWakeWord ? stripFlexibleWakeWord(cleaned) : cleaned;
        if (hasWakeWord && command.isEmpty()) {
            commandWindowUntil = SystemClock.uptimeMillis() + COMMAND_WINDOW_MS;
            broadcastStatus("جی، ذیشان", "بارہ سیکنڈ کے اندر کمانڈ بولیں", true);
            if (!keepSegmentedSession) scheduleRestart(180L, false);
            return;
        }

        commandWindowUntil = 0L;
        if (looksLikeNoise(command)) {
            broadcastStatus("کمانڈ واضح نہیں", "کہیں: چنٹو، پھر مکمل کمانڈ", false);
            if (!keepSegmentedSession) scheduleRestart(350L, false);
            return;
        }

        executingCommand = true;
        stopRecognitionSession(true);
        broadcastStatus("کمانڈ ملی", command, true);
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

    private boolean hasFlexibleWakeWord(String raw) {
        String normalized = CommandEngine.normalize(raw);
        for (String prefix : WAKE_PREFIXES) {
            String normalizedPrefix = CommandEngine.normalize(prefix);
            if (normalized.equals(normalizedPrefix)
                    || normalized.startsWith(normalizedPrefix + " ")) {
                return true;
            }
        }
        return false;
    }

    private String stripFlexibleWakeWord(String raw) {
        String normalized = CommandEngine.normalize(raw);
        for (String prefix : WAKE_PREFIXES) {
            String normalizedPrefix = CommandEngine.normalize(prefix);
            if (normalized.equals(normalizedPrefix)) return "";
            if (normalized.startsWith(normalizedPrefix + " ")) {
                String remaining = normalized.substring(normalizedPrefix.length()).trim();
                return remaining
                        .replaceFirst("^(جی|سنو|بھائی|ذیشان)\\s+", "")
                        .trim();
            }
        }
        return normalized;
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

    private boolean commandWindowOpen() {
        return SystemClock.uptimeMillis() <= commandWindowUntil;
    }

    private void speakThenRestart(String message) {
        if (stopped) return;
        if (!ttsReady || tts == null || message == null || message.trim().isEmpty()) {
            finishSpeakingAndRestart();
            return;
        }
        try {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "hands-free-response");
        } catch (RuntimeException error) {
            finishSpeakingAndRestart();
        }
    }

    private void finishSpeakingAndRestart() {
        ignoreAudioUntil = SystemClock.uptimeMillis() + IGNORE_AFTER_TTS_MS;
        executingCommand = false;
        scheduleRestart(IGNORE_AFTER_TTS_MS, true);
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
                // Recognition is closed while Chintu speaks, preventing feedback commands.
            }

            @Override
            public void onDone(String utteranceId) {
                handler.post(HandsFreeVoiceService.this::finishSpeakingAndRestart);
            }

            @Override
            public void onError(String utteranceId) {
                handler.post(HandsFreeVoiceService.this::finishSpeakingAndRestart);
            }
        });
        ttsReady = true;
    }

    private long nextFallbackDelay() {
        int exponent = Math.min(fallbackFailures, 4);
        return Math.min(2_500L, 300L * (1L << exponent));
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

    private void stopRecognitionSession(boolean destroy) {
        clearSessionTimers();
        suppressErrorsUntil = SystemClock.uptimeMillis() + 900L;
        pumpingAudio.set(false);

        AudioRecord recorder = audioRecord;
        audioRecord = null;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped.
            }
            recorder.release();
        }
        closeQuietly(recognizerAudioWrite);
        recognizerAudioWrite = null;

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
        closeQuietly(recognizerAudioRead);
        recognizerAudioRead = null;
        injectedSession = false;
        starting = false;
    }

    private void closeInjectedAudio() {
        pumpingAudio.set(false);
        AudioRecord recorder = audioRecord;
        audioRecord = null;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
                // Not recording.
            }
            recorder.release();
        }
        closeQuietly(recognizerAudioWrite);
        recognizerAudioWrite = null;
        closeQuietly(recognizerAudioRead);
        recognizerAudioRead = null;
        injectedSession = false;
    }

    private void destroyRecognizer() {
        stopRecognitionSession(true);
    }

    private void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (IOException ignored) {
            // Already closed by the audio writer.
        }
    }

    private void stopHandsFree(String status) {
        stopped = true;
        executingCommand = false;
        setEnabled(false);
        clearTimers();
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

    private void clearSessionTimers() {
        removeStartWatchdog();
        removeSessionWatchdog();
    }

    private void clearTimers() {
        clearRestart();
        clearSessionTimers();
    }

    @Override
    public void onDestroy() {
        stopped = true;
        executingCommand = false;
        setEnabled(false);
        clearTimers();
        destroyRecognizer();
        releaseWakeLock();
        commandExecutor.shutdownNow();
        audioExecutor.shutdownNow();
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
