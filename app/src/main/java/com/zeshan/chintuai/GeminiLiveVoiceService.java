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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Wazir Gen Z 5.0.1 transcript-first Gemini Live voice service.
 *
 * A WebSocket TCP upgrade is not called "connected" anymore. The microphone starts only after an
 * actual setupComplete server message. Server JSON errors and WebSocket close codes are surfaced to
 * the screen. Live performs continuous transcription; the existing Gemini REST brain converts the
 * finished transcript into safe Android actions. This keeps the fragile Live handshake minimal.
 */
public final class GeminiLiveVoiceService extends Service {
    public static final String ACTION_START_HANDS_FREE =
            "com.zeshan.chintuai.action.LIVE_START_HANDS_FREE";
    public static final String ACTION_START_DIRECT =
            "com.zeshan.chintuai.action.LIVE_START_DIRECT";
    public static final String ACTION_STOP =
            "com.zeshan.chintuai.action.LIVE_STOP";
    public static final String ACTION_STATUS =
            "com.zeshan.chintuai.action.LIVE_STATUS";
    public static final String ACTION_TRANSCRIPT =
            "com.zeshan.chintuai.action.LIVE_TRANSCRIPT";
    public static final String ACTION_COMMAND =
            "com.zeshan.chintuai.action.LIVE_COMMAND";

    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_ENGINE = "engine";
    public static final String EXTRA_TRANSCRIPT = "transcript";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_LATENCY_MS = "latency_ms";

    private static final String PREFS = "wazir_live_voice_preferences";
    private static final String PREF_HANDS_FREE = "live_hands_free_enabled";
    private static final String CHANNEL_ID = "wazir_gemini_live_voice";
    private static final int NOTIFICATION_ID = 7501;
    private static final long SETUP_TIMEOUT_MS = 20_000L;
    private static final long DIRECT_INITIAL_TIMEOUT_MS = 45_000L;
    private static final long DIRECT_ACTIVE_TIMEOUT_MS = 20_000L;
    private static final long TURN_SETTLE_MS = 650L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1_000L;
    private static final long WAKE_LOCK_RENEW_MS = 9L * 60L * 1_000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private OkHttpClient client;
    private WebSocket socket;
    private AudioRecord recorder;
    private Thread audioThread;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl gainControl;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean active;
    private volatile boolean directMode;
    private volatile boolean manualStop;
    private volatile boolean setupComplete;
    private volatile boolean recording;
    private volatile boolean commandRunning;
    private volatile boolean audioStreamEnded;

    private int generation;
    private int modelIndex;
    private int reconnectAttempt;
    private String activeModel = GeminiLiveProtocol.modelAt(0);
    private String transcript = "";
    private long speechStartedAt;

    private Runnable setupWatchdog;
    private Runnable reconnectRunnable;
    private Runnable directTimeoutRunnable;
    private Runnable turnRunnable;
    private Runnable wakeLockRenewal;

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_HANDS_FREE, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "WazirGenZ:LiveTranscript");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START_HANDS_FREE : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            manualStop = true;
            stopLive("وزیر Live Voice بند کر دیا ہے");
            return START_NOT_STICKY;
        }

        boolean direct = ACTION_START_DIRECT.equals(action);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setHandsFreeEnabled(false);
            startAsForeground(direct);
            broadcastStatus("مائیکروفون اجازت نہیں",
                    "Settings میں Microphone اجازت دیں", true);
            main.postDelayed(this::stopSelf, 2_500L);
            return START_NOT_STICKY;
        }
        if (!WazirSecretStore.hasGeminiApiKey(this)) {
            setHandsFreeEnabled(false);
            startAsForeground(direct);
            broadcastStatus("Gemini key درکار ہے",
                    "ایپ میں Gemini API key محفوظ کریں", true);
            main.postDelayed(this::stopSelf, 2_500L);
            return START_NOT_STICKY;
        }

        manualStop = false;
        active = true;
        directMode = direct;
        modelIndex = 0;
        reconnectAttempt = 0;
        setHandsFreeEnabled(!direct);
        startAsForeground(direct);
        acquireWakeLock();
        connect();
        return direct ? START_NOT_STICKY : START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void connect() {
        if (!active || manualStop) return;
        int ticket = ++generation;
        clearSessionTimers();
        stopAudioCapture();
        closeSocket();
        setupComplete = false;
        commandRunning = false;
        audioStreamEnded = false;
        transcript = "";
        speechStartedAt = 0L;

        if (!hasNetwork()) {
            broadcastStatus("انٹرنیٹ دستیاب نہیں",
                    "Wi-Fi یا mobile data آن کریں", true);
            scheduleReconnect(3_000L);
            return;
        }

        String key = WazirSecretStore.getGeminiApiKey(this);
        if (key.isEmpty()) {
            stopLive("Gemini key دستیاب نہیں");
            return;
        }
        activeModel = GeminiLiveProtocol.modelAt(modelIndex);
        broadcastStatus("Gemini Live handshake",
                activeModel + " سے setupComplete کا انتظار ہے", true);

        Request request = new Request.Builder()
                .url(GeminiLiveProtocol.webSocketUrl(key))
                .build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (ticket != generation || manualStop) {
                    webSocket.close(1000, "stale");
                    return;
                }
                try {
                    boolean sent = webSocket.send(
                            GeminiLiveProtocol.setupMessage(directMode, activeModel));
                    main.post(() -> {
                        if (!sent) {
                            handleConnectionFailure(ticket,
                                    "WebSocket کھلا مگر setup message send نہیں ہوئی");
                        } else {
                            broadcastStatus("WebSocket کھلا ہے",
                                    "ابھی voice تیار نہیں؛ Gemini setupComplete کا انتظار ہے",
                                    false);
                        }
                    });
                } catch (JSONException error) {
                    main.post(() -> handleConnectionFailure(ticket,
                            "Live setup JSON تیار نہیں ہوئی"));
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                main.post(() -> handleMessage(ticket, text));
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                String detail = "WebSocket closed " + code;
                if (reason != null && !reason.trim().isEmpty()) detail += ": " + reason.trim();
                String finalDetail = detail;
                main.post(() -> handleConnectionFailure(ticket, finalDetail));
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                StringBuilder detail = new StringBuilder();
                if (response != null) {
                    detail.append("HTTP ").append(response.code())
                            .append(" ").append(response.message());
                }
                if (error != null && error.getMessage() != null) {
                    if (detail.length() > 0) detail.append(" — ");
                    detail.append(error.getMessage());
                }
                if (detail.length() == 0) detail.append("Gemini Live connection failed");
                String finalDetail = detail.toString();
                main.post(() -> handleConnectionFailure(ticket, finalDetail));
            }
        });

        setupWatchdog = () -> {
            if (ticket != generation || setupComplete || manualStop) return;
            handleConnectionFailure(ticket,
                    "20 سیکنڈ میں setupComplete نہیں آیا");
        };
        main.postDelayed(setupWatchdog, SETUP_TIMEOUT_MS);
    }

    private void handleMessage(int ticket, String text) {
        if (ticket != generation || manualStop || text == null || text.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(text);
            String apiError = GeminiLiveProtocol.errorSummary(root);
            if (!apiError.isEmpty()) {
                handleConnectionFailure(ticket, apiError);
                return;
            }
            if (root.has("setupComplete")) {
                setupComplete = true;
                reconnectAttempt = 0;
                removeSetupWatchdog();
                startAudioCapture();
                broadcastStatus(directMode ? "فوری Live Voice سن رہا ہے" : "وزیر Live تیار ہے",
                        directMode
                                ? "پوری کمانڈ بولیں؛ 45 سیکنڈ کی window ہے"
                                : "کہیں: وزیر، پھر پوری کمانڈ",
                        true);
                if (directMode) armDirectTimeout(DIRECT_INITIAL_TIMEOUT_MS);
                return;
            }
            JSONObject serverContent = root.optJSONObject("serverContent");
            if (serverContent != null) processServerContent(serverContent);
            if (root.has("goAway")) {
                broadcastStatus("Gemini session تازہ ہو رہی ہے",
                        "Server نے reconnect کہا ہے", false);
                scheduleReconnect(500L);
            }
        } catch (JSONException error) {
            broadcastStatus("Gemini جواب پڑھا نہیں گیا",
                    error.getClass().getSimpleName(), false);
        }
    }

    private void processServerContent(JSONObject content) {
        JSONObject input = content.optJSONObject("inputTranscription");
        if (input != null) {
            String update = input.optString("text", "").trim();
            if (!update.isEmpty()) {
                if (speechStartedAt == 0L) speechStartedAt = SystemClock.uptimeMillis();
                transcript = LiveTranscriptGate.merge(transcript, update);
                broadcastTranscript(transcript);
                broadcastStatus("سن رہا ہوں", transcript, false);
                if (directMode) armDirectTimeout(DIRECT_ACTIVE_TIMEOUT_MS);
            }
        }
        if (content.optBoolean("turnComplete", false)) {
            scheduleTurnExecution();
        }
    }

    private void scheduleTurnExecution() {
        removeTurnRunnable();
        String snapshot = transcript.trim();
        turnRunnable = () -> executeTranscript(snapshot);
        main.postDelayed(turnRunnable, TURN_SETTLE_MS);
    }

    private void executeTranscript(String snapshot) {
        if (manualStop || commandRunning || snapshot == null) return;
        String heard = snapshot.trim();
        if (!LiveTranscriptGate.isUsableCommand(heard, directMode)) {
            transcript = "";
            speechStartedAt = 0L;
            broadcastStatus(directMode ? "فوری Live Voice سن رہا ہے" : "وزیر Live تیار ہے",
                    directMode ? "پوری کمانڈ واضح بولیں" : "پہلے کہیں: وزیر",
                    false);
            if (directMode) armDirectTimeout(DIRECT_ACTIVE_TIMEOUT_MS);
            return;
        }

        String command = directMode ? heard : LiveTranscriptGate.commandAfterWakeWord(heard);
        commandRunning = true;
        pauseAudioInput();
        removeDirectTimeout();
        long started = speechStartedAt > 0L ? speechStartedAt : SystemClock.uptimeMillis();
        broadcastStatus("Gemini سمجھ رہا ہے", command, true);

        ResultReceiver reply = new ResultReceiver(main) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                boolean handled = data != null && data.getBoolean(
                        CommandExecutionReceiver.RESULT_HANDLED, false);
                String message = data == null ? "" : data.getString(
                        CommandExecutionReceiver.RESULT_MESSAGE, "");
                boolean stop = data != null && data.getBoolean(
                        CommandExecutionReceiver.RESULT_STOP_HANDS_FREE, false);
                String mode = data == null ? "Gemini REST" : data.getString(
                        CommandExecutionReceiver.RESULT_AI_MODE, "Gemini REST");
                long latency = Math.max(0L, SystemClock.uptimeMillis() - started);
                broadcastCommand(command, message, mode, latency);
                commandRunning = false;
                if (stop) {
                    manualStop = true;
                    stopLive(message);
                } else if (directMode) {
                    main.postDelayed(() -> stopLive(
                            handled ? "فوری command مکمل ہوئی" : "فوری command مکمل نہیں ہوئی"),
                            1_500L);
                } else {
                    resetForNextTurn();
                }
            }
        };

        Intent execution = new Intent(this, CommandExecutionReceiver.class)
                .setAction(CommandExecutionReceiver.ACTION_EXECUTE)
                .putExtra(CommandExecutionReceiver.EXTRA_COMMAND, command)
                .putExtra(CommandExecutionReceiver.EXTRA_REPLY, reply);
        try {
            sendBroadcast(execution);
        } catch (RuntimeException error) {
            commandRunning = false;
            broadcastCommand(command, "Android command bridge دستیاب نہیں",
                    "Bridge error", 0L);
            if (directMode) main.postDelayed(() -> stopLive("Bridge error"), 1_500L);
            else resetForNextTurn();
        }
    }

    private void resetForNextTurn() {
        transcript = "";
        speechStartedAt = 0L;
        audioStreamEnded = false;
        broadcastStatus("وزیر Live تیار ہے",
                "کہیں: وزیر، پھر پوری کمانڈ", false);
    }

    private void startAudioCapture() {
        if (recording || manualStop || !setupComplete) return;
        int min = AudioRecord.getMinBufferSize(
                GeminiLiveProtocol.INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = GeminiLiveProtocol.INPUT_SAMPLE_RATE * 2;
        int bufferSize = Math.max(min * 3, GeminiLiveProtocol.INPUT_SAMPLE_RATE * 2);

        AudioRecord value = createRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferSize);
        if (value == null || value.getState() != AudioRecord.STATE_INITIALIZED) {
            if (value != null) value.release();
            value = createRecorder(MediaRecorder.AudioSource.MIC, bufferSize);
        }
        if (value == null || value.getState() != AudioRecord.STATE_INITIALIZED) {
            if (value != null) value.release();
            handleConnectionFailure(generation,
                    "AudioRecord شروع نہیں ہوا؛ دوسری microphone app بند کریں");
            return;
        }

        recorder = value;
        enableAudioEffects(value.getAudioSessionId());
        recording = true;
        audioThread = new Thread(this::audioLoop, "Wazir-Live-PCM");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    private AudioRecord createRecorder(int source, int bufferSize) {
        try {
            return new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(GeminiLiveProtocol.INPUT_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void audioLoop() {
        AudioRecord value = recorder;
        if (value == null) return;
        byte[] buffer = new byte[3_200];
        try {
            value.startRecording();
            while (recording && !manualStop) {
                int read = value.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) continue;
                if (!setupComplete || commandRunning) continue;
                audioStreamEnded = false;
                WebSocket current = socket;
                if (current == null) continue;
                try {
                    if (!current.send(GeminiLiveProtocol.audioMessage(buffer, read))) {
                        main.post(() -> handleConnectionFailure(generation,
                                "Live audio send queue بند ہوئی"));
                    }
                } catch (JSONException ignored) {
                }
            }
        } catch (RuntimeException error) {
            main.post(() -> handleConnectionFailure(generation,
                    "Microphone stream: " + error.getClass().getSimpleName()));
        }
    }

    private void pauseAudioInput() {
        if (audioStreamEnded) return;
        audioStreamEnded = true;
        WebSocket current = socket;
        if (current != null && setupComplete) {
            try {
                current.send(GeminiLiveProtocol.audioStreamEndMessage());
            } catch (JSONException ignored) {
            }
        }
    }

    private void handleConnectionFailure(int ticket, String reason) {
        if (ticket != generation || manualStop || !active) return;
        boolean failedBeforeSetup = !setupComplete;
        setupComplete = false;
        stopAudioCapture();
        closeSocket();
        removeSetupWatchdog();

        String detail = clean(reason);
        if (failedBeforeSetup && modelIndex + 1 < GeminiLiveProtocol.MODELS.length) {
            modelIndex++;
            activeModel = GeminiLiveProtocol.modelAt(modelIndex);
            broadcastStatus("پہلا Live model نہیں جڑا",
                    detail + " — اب " + activeModel + " آزما رہا ہوں", true);
            scheduleReconnect(700L);
            return;
        }

        broadcastStatus("Gemini Live setup ناکام",
                detail, true);
        if (directMode) {
            main.postDelayed(() -> stopLive("Live setup ناکام: " + detail), 4_000L);
            return;
        }
        modelIndex = 0;
        reconnectAttempt++;
        long delay = Math.min(30_000L, 4_000L * Math.max(1, reconnectAttempt));
        scheduleReconnect(delay);
    }

    private void scheduleReconnect(long delayMs) {
        if (manualStop || !active) return;
        if (reconnectRunnable != null) main.removeCallbacks(reconnectRunnable);
        reconnectRunnable = this::connect;
        main.postDelayed(reconnectRunnable, Math.max(400L, delayMs));
    }

    private void armDirectTimeout(long delayMs) {
        removeDirectTimeout();
        if (!directMode || manualStop || commandRunning) return;
        directTimeoutRunnable = () -> {
            if (!directMode || manualStop || commandRunning) return;
            if (!transcript.trim().isEmpty()) {
                executeTranscript(transcript);
                return;
            }
            broadcastStatus("کمانڈ نہیں ملی",
                    "Listening window مکمل ہوئی", true);
            main.postDelayed(() -> stopLive("فوری listening مکمل ہوئی"), 1_800L);
        };
        main.postDelayed(directTimeoutRunnable, delayMs);
    }

    private boolean hasNetwork() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void startAsForeground(boolean direct) {
        Notification notification = buildNotification(direct
                ? "فوری Live transcript"
                : "کہیں: وزیر، پھر پوری کمانڈ");
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
        PendingIntent openPending = PendingIntent.getActivity(this, 30, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, GeminiLiveVoiceService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 31, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("وزیر Gen Z — Live transcript")
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
                CHANNEL_ID, "Wazir Live transcript", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("وزیر کا Gemini Live microphone transcript");
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void broadcastStatus(String status, String detail, boolean force) {
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status == null ? "" : status)
                .putExtra(EXTRA_DETAIL, detail == null ? "" : detail)
                .putExtra(EXTRA_ENGINE, "Gemini Live • " + activeModel)
                .putExtra(EXTRA_MODE, directMode ? "Direct Live" : "Hands-free Live"));
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && active) {
            manager.notify(NOTIFICATION_ID, buildNotification(
                    status == null ? "وزیر Live" : status));
        }
    }

    private void broadcastTranscript(String value) {
        sendBroadcast(new Intent(ACTION_TRANSCRIPT)
                .setPackage(getPackageName())
                .putExtra(EXTRA_TRANSCRIPT, value == null ? "" : value)
                .putExtra(EXTRA_ENGINE, "Gemini Live input transcription")
                .putExtra(EXTRA_MODE, directMode ? "Direct Live" : "Hands-free Live"));
    }

    private void broadcastCommand(String command, String result, String mode, long latency) {
        sendBroadcast(new Intent(ACTION_COMMAND)
                .setPackage(getPackageName())
                .putExtra(EXTRA_COMMAND, command == null ? "" : command)
                .putExtra(EXTRA_RESULT, result == null ? "" : result)
                .putExtra(EXTRA_ENGINE, "Live transcript → Gemini REST")
                .putExtra(EXTRA_MODE, mode == null ? "Gemini" : mode)
                .putExtra(EXTRA_LATENCY_MS, latency));
    }

    private void stopAudioCapture() {
        recording = false;
        AudioRecord value = recorder;
        recorder = null;
        if (value != null) {
            try { value.stop(); } catch (RuntimeException ignored) { }
            try { value.release(); } catch (RuntimeException ignored) { }
        }
        releaseAudioEffects();
        Thread thread = audioThread;
        audioThread = null;
        if (thread != null) thread.interrupt();
    }

    private void enableAudioEffects(int sessionId) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (RuntimeException ignored) { }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
        } catch (RuntimeException ignored) { }
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId);
                if (gainControl != null) gainControl.setEnabled(true);
            }
        } catch (RuntimeException ignored) { }
    }

    private void releaseAudioEffects() {
        if (echoCanceler != null) {
            try { echoCanceler.release(); } catch (RuntimeException ignored) { }
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (RuntimeException ignored) { }
            noiseSuppressor = null;
        }
        if (gainControl != null) {
            try { gainControl.release(); } catch (RuntimeException ignored) { }
            gainControl = null;
        }
    }

    private void closeSocket() {
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try { current.cancel(); } catch (RuntimeException ignored) { }
        }
    }

    private void setHandsFreeEnabled(boolean enabled) {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        preferences.edit().putBoolean(PREF_HANDS_FREE, enabled).apply();
    }

    private void acquireWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            if (wakeLockRenewal != null) main.removeCallbacks(wakeLockRenewal);
            wakeLockRenewal = this::acquireWakeLock;
            main.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_MS);
        } catch (RuntimeException ignored) { }
    }

    private void releaseWakeLock() {
        if (wakeLockRenewal != null) main.removeCallbacks(wakeLockRenewal);
        wakeLockRenewal = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (RuntimeException ignored) { }
        }
    }

    private void stopLive(String message) {
        active = false;
        manualStop = true;
        generation++;
        clearSessionTimers();
        stopAudioCapture();
        closeSocket();
        releaseWakeLock();
        setHandsFreeEnabled(false);
        stopForeground(true);
        if (message != null && !message.trim().isEmpty()) {
            broadcastCommand("", message.trim(), "Live", 0L);
        }
        stopSelf();
    }

    private String clean(String value) {
        String safe = value == null ? "نامعلوم Live error" : value.trim();
        if (safe.isEmpty()) safe = "نامعلوم Live error";
        return safe.length() > 500 ? safe.substring(0, 500) : safe;
    }

    private void removeSetupWatchdog() {
        if (setupWatchdog != null) main.removeCallbacks(setupWatchdog);
        setupWatchdog = null;
    }

    private void removeDirectTimeout() {
        if (directTimeoutRunnable != null) main.removeCallbacks(directTimeoutRunnable);
        directTimeoutRunnable = null;
    }

    private void removeTurnRunnable() {
        if (turnRunnable != null) main.removeCallbacks(turnRunnable);
        turnRunnable = null;
    }

    private void clearSessionTimers() {
        removeSetupWatchdog();
        removeDirectTimeout();
        removeTurnRunnable();
        if (reconnectRunnable != null) main.removeCallbacks(reconnectRunnable);
        reconnectRunnable = null;
    }

    @Override
    public void onDestroy() {
        active = false;
        manualStop = true;
        generation++;
        clearSessionTimers();
        stopAudioCapture();
        closeSocket();
        releaseWakeLock();
        if (client != null) {
            try { client.dispatcher().executorService().shutdownNow(); }
            catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }
}
