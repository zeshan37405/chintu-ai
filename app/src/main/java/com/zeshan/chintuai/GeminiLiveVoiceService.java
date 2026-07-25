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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Wazir Gen Z 5.0 real-time voice core.
 *
 * It does not use Android SpeechRecognizer or Google's two-second recognition dialog. Raw 16 kHz
 * PCM is streamed through a persistent Gemini Live WebSocket. Gemini returns live transcription,
 * native audio and structured tool calls. Android actions still execute inside the main process
 * through CommandExecutionReceiver, where the Accessibility safety layer lives.
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
    private static final int NOTIFICATION_ID = 7500;
    private static final long CONNECT_TIMEOUT_MS = 18_000L;
    private static final long DIRECT_LISTEN_TIMEOUT_MS = 35_000L;
    private static final long FALLBACK_DELAY_MS = 1_050L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1_000L;
    private static final long WAKE_LOCK_RENEW_MS = 9L * 60L * 1_000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl gainControl;
    private PcmAudioPlayer audioPlayer;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean active;
    private volatile boolean directMode;
    private volatile boolean setupComplete;
    private volatile boolean recording;
    private volatile boolean modelSpeaking;
    private volatile boolean audioStreamEnded;
    private volatile boolean toolRunning;
    private volatile boolean commandHandled;
    private volatile boolean directFinished;
    private volatile boolean manualStop;

    private int generation;
    private int reconnectAttempt;
    private int directRetryCount;
    private String currentTranscript = "";
    private String outputTranscript = "";
    private long turnStartedAt;

    private Runnable connectWatchdog;
    private Runnable reconnectRunnable;
    private Runnable directTimeoutRunnable;
    private Runnable fallbackRunnable;
    private Runnable wakeLockRenewal;

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_HANDS_FREE, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        audioPlayer = new PcmAudioPlayer(this::onPlaybackState);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "WazirGenZ:GeminiLiveVoice");
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
            broadcastStatus("مائیکروفون اجازت نہیں",
                    "وزیر کو Microphone اجازت دیں", true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!WazirSecretStore.hasGeminiApiKey(this)) {
            setHandsFreeEnabled(false);
            startAsForeground(direct);
            broadcastStatus("Gemini key درکار ہے",
                    "ایپ میں اپنی Gemini API key محفوظ کریں", true);
            main.postDelayed(this::stopSelf, 2_500L);
            return START_NOT_STICKY;
        }

        manualStop = false;
        directMode = direct;
        directRetryCount = 0;
        setHandsFreeEnabled(!direct);
        startAsForeground(direct);
        acquireWakeLock();
        connectNewSession();
        return direct ? START_NOT_STICKY : START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void connectNewSession() {
        if (manualStop) return;
        active = true;
        int ticket = ++generation;
        removeConnectWatchdog();
        removeFallback();
        removeDirectTimeout();
        stopAudioCapture();
        closeSocket();
        setupComplete = false;
        modelSpeaking = false;
        audioStreamEnded = false;
        toolRunning = false;
        commandHandled = false;
        directFinished = false;
        currentTranscript = "";
        outputTranscript = "";
        turnStartedAt = 0L;

        if (!hasNetwork()) {
            broadcastStatus("انٹرنیٹ دستیاب نہیں",
                    "Gemini Live کے لیے Wi-Fi یا mobile data آن کریں", true);
            scheduleReconnect(2_500L);
            return;
        }

        String apiKey = WazirSecretStore.getGeminiApiKey(this);
        if (apiKey.isEmpty()) {
            stopLive("Gemini key دستیاب نہیں");
            return;
        }

        broadcastStatus("Gemini Live جوڑ رہا ہوں",
                directMode
                        ? "براہِ راست کمانڈ کے لیے مائیک تیار ہو رہا ہے"
                        : "مسلسل سننے والا وزیر تیار ہو رہا ہے",
                true);
        Request request = new Request.Builder()
                .url(GeminiLiveProtocol.webSocketUrl(apiKey))
                .build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                if (ticket != generation || manualStop) {
                    socket.close(1000, "stale session");
                    return;
                }
                try {
                    socket.send(GeminiLiveProtocol.setupMessage(directMode));
                    main.post(() -> broadcastStatus("Gemini Live connected",
                            "Session setup مکمل ہو رہی ہے", false));
                } catch (JSONException error) {
                    main.post(() -> handleSocketFailure(ticket,
                            "Live setup message تیار نہیں ہوئی"));
                }
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                main.post(() -> handleSocketMessage(ticket, text));
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                // Gemini Live currently sends JSON text frames. Ignore unexpected binary frames.
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                socket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                main.post(() -> handleSocketFailure(ticket,
                        reason == null || reason.isEmpty() ? "Live session بند ہوئی" : reason));
            }

            @Override
            public void onFailure(WebSocket socket, Throwable error, Response response) {
                String message = error == null || error.getMessage() == null
                        ? "Gemini Live connection failed" : error.getMessage();
                main.post(() -> handleSocketFailure(ticket, message));
            }
        });

        connectWatchdog = () -> {
            if (ticket != generation || setupComplete || manualStop) return;
            handleSocketFailure(ticket, "Gemini Live connection timeout");
        };
        main.postDelayed(connectWatchdog, CONNECT_TIMEOUT_MS);
    }

    private void handleSocketMessage(int ticket, String text) {
        if (ticket != generation || manualStop || text == null || text.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(text);
            if (root.has("setupComplete")) {
                setupComplete = true;
                reconnectAttempt = 0;
                removeConnectWatchdog();
                startAudioCapture();
                broadcastStatus(directMode ? "فوری Live Voice سن رہا ہے" : "وزیر Live تیار ہے",
                        directMode
                                ? "پوری کمانڈ بولیں؛ 35 سیکنڈ کی listening window ہے"
                                : "کہیں: وزیر، پھر پوری کمانڈ؛ درمیان میں قدرتی وقفہ کر سکتے ہیں",
                        true);
                if (directMode) armDirectTimeout();
            }
            JSONObject serverContent = root.optJSONObject("serverContent");
            if (serverContent != null) processServerContent(serverContent);
            JSONObject toolCall = root.optJSONObject("toolCall");
            if (toolCall != null) processToolCall(toolCall);
            if (root.has("goAway")) {
                broadcastStatus("Gemini session تازہ کر رہا ہوں",
                        "Live connection خودکار طور پر دوبارہ جڑ رہی ہے", false);
                scheduleReconnect(450L);
            }
        } catch (JSONException error) {
            broadcastStatus("Gemini Live جواب پڑھا نہیں گیا",
                    error.getClass().getSimpleName(), false);
        }
    }

    private void processServerContent(JSONObject content) {
        JSONObject input = content.optJSONObject("inputTranscription");
        if (input != null) {
            String update = input.optString("text", "").trim();
            if (!update.isEmpty()) {
                if (turnStartedAt == 0L) turnStartedAt = SystemClock.uptimeMillis();
                currentTranscript = LiveTranscriptGate.merge(currentTranscript, update);
                removeDirectTimeout();
                broadcastTranscript(currentTranscript);
                broadcastStatus("سن رہا ہوں", currentTranscript, false);
            }
        }

        JSONObject output = content.optJSONObject("outputTranscription");
        if (output != null) {
            String update = output.optString("text", "").trim();
            if (!update.isEmpty()) {
                outputTranscript = LiveTranscriptGate.merge(outputTranscript, update);
                broadcastStatus("وزیر جواب دے رہا ہے", outputTranscript, false);
            }
        }

        JSONObject modelTurn = content.optJSONObject("modelTurn");
        JSONArray parts = modelTurn == null ? null : modelTurn.optJSONArray("parts");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                JSONObject inline = part == null ? null : part.optJSONObject("inlineData");
                if (inline == null) continue;
                String data = inline.optString("data", "");
                if (data.isEmpty()) continue;
                try {
                    audioPlayer.enqueue(android.util.Base64.decode(
                            data, android.util.Base64.DEFAULT));
                } catch (IllegalArgumentException ignored) {
                    // Ignore one malformed audio chunk and keep the session alive.
                }
            }
        }

        if (content.optBoolean("interrupted", false)) audioPlayer.stopNow();
        if (content.optBoolean("turnComplete", false)) onTurnComplete();
    }

    private void processToolCall(JSONObject toolCall) {
        JSONArray calls = toolCall.optJSONArray("functionCalls");
        if (calls == null) return;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null) continue;
            String name = call.optString("name", "");
            String id = call.optString("id", "");
            if (!GeminiLiveProtocol.FUNCTION_NAME.equals(name)) {
                sendToolResponse(id, name,
                        BackgroundCommandExecutor.Result.fail("یہ Live tool دستیاب نہیں"),
                        "unsupported tool");
                continue;
            }
            JSONObject args = call.optJSONObject("args");
            if (args == null) {
                try {
                    args = new JSONObject(call.optString("args", "{}"));
                } catch (JSONException ignored) {
                    args = new JSONObject();
                }
            }

            String heard = LiveTranscriptGate.merge(
                    currentTranscript, GeminiLiveProtocol.heardText(args));
            currentTranscript = heard;
            if (!LiveTranscriptGate.isUsableCommand(heard, directMode)) {
                sendToolResponse(id, name,
                        BackgroundCommandExecutor.Result.fail(
                                directMode ? "پوری کمانڈ واضح نہیں ملی" : "وزیر wake word نہیں ملا"),
                        "wake gate rejected: " + heard);
                commandHandled = false;
                toolRunning = false;
                broadcastStatus("سن رہا ہوں",
                        directMode ? "پوری کمانڈ دوبارہ بولیں" : "پہلے کہیں: وزیر",
                        false);
                if (directMode) armDirectTimeout();
                return;
            }

            String command = directMode
                    ? heard.trim() : LiveTranscriptGate.commandAfterWakeWord(heard);
            GeminiActionPlan plan = GeminiLiveProtocol.parsePlan(args);
            executePlanThroughMainProcess(id, name, command, args.toString(), plan.actions.size());
            return;
        }
    }

    private void executePlanThroughMainProcess(String toolId, String toolName,
                                               String command, String planJson,
                                               int actionCount) {
        if (toolRunning || commandHandled) return;
        toolRunning = true;
        commandHandled = true;
        removeFallback();
        removeDirectTimeout();
        pauseAudioInput();
        long started = turnStartedAt > 0L ? turnStartedAt : SystemClock.uptimeMillis();
        broadcastStatus("عمل کر رہا ہوں", command, true);

        ResultReceiver reply = new ResultReceiver(main) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                boolean handled = data != null && data.getBoolean(
                        CommandExecutionReceiver.RESULT_HANDLED, false);
                String message = data == null ? "" : data.getString(
                        CommandExecutionReceiver.RESULT_MESSAGE, "");
                boolean stop = data != null && data.getBoolean(
                        CommandExecutionReceiver.RESULT_STOP_HANDS_FREE, false);
                String mode = data == null ? "Gemini Live" : data.getString(
                        CommandExecutionReceiver.RESULT_AI_MODE, "Gemini Live");
                String diagnostic = data == null ? "" : data.getString(
                        CommandExecutionReceiver.RESULT_DIAGNOSTIC, "");
                BackgroundCommandExecutor.Result result =
                        new BackgroundCommandExecutor.Result(handled, message, stop);
                toolRunning = false;
                directFinished = directMode;
                long latency = Math.max(0L, SystemClock.uptimeMillis() - started);
                sendToolResponse(toolId, toolName, result,
                        "actions=" + actionCount + "; " + diagnostic);
                broadcastCommand(command, result.message, mode, latency);
                if (stop) {
                    manualStop = true;
                    stopLive(result.message);
                }
            }
        };

        Intent execution = new Intent(this, CommandExecutionReceiver.class)
                .setAction(CommandExecutionReceiver.ACTION_EXECUTE)
                .putExtra(CommandExecutionReceiver.EXTRA_COMMAND, command)
                .putExtra(CommandExecutionReceiver.EXTRA_PLAN_JSON, planJson)
                .putExtra(CommandExecutionReceiver.EXTRA_REPLY, reply);
        try {
            sendBroadcast(execution);
        } catch (RuntimeException error) {
            toolRunning = false;
            BackgroundCommandExecutor.Result result = BackgroundCommandExecutor.Result.fail(
                    "Android action bridge دستیاب نہیں");
            sendToolResponse(toolId, toolName, result, error.getClass().getSimpleName());
            broadcastCommand(command, result.message, "Bridge error", 0L);
        }
    }

    private void executeTranscriptFallback(String snapshot) {
        if (toolRunning || commandHandled || manualStop) return;
        if (!LiveTranscriptGate.isUsableCommand(snapshot, directMode)) {
            currentTranscript = "";
            turnStartedAt = 0L;
            if (directMode) {
                broadcastStatus("فوری Live Voice سن رہا ہے",
                        "پوری کمانڈ بولیں؛ session ابھی کھلا ہے", false);
                armDirectTimeout();
            } else {
                broadcastStatus("وزیر Live تیار ہے",
                        "کہیں: وزیر، پھر پوری کمانڈ", false);
            }
            return;
        }
        String command = directMode
                ? snapshot.trim() : LiveTranscriptGate.commandAfterWakeWord(snapshot);
        commandHandled = true;
        toolRunning = true;
        pauseAudioInput();
        long started = turnStartedAt > 0L ? turnStartedAt : SystemClock.uptimeMillis();
        broadcastStatus("Gemini fallback سمجھ رہا ہے", command, true);

        ResultReceiver reply = new ResultReceiver(main) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                boolean handled = data != null && data.getBoolean(
                        CommandExecutionReceiver.RESULT_HANDLED, false);
                String message = data == null ? "" : data.getString(
                        CommandExecutionReceiver.RESULT_MESSAGE, "");
                boolean stop = data != null && data.getBoolean(
                        CommandExecutionReceiver.RESULT_STOP_HANDS_FREE, false);
                String mode = data == null ? "Gemini fallback" : data.getString(
                        CommandExecutionReceiver.RESULT_AI_MODE, "Gemini fallback");
                toolRunning = false;
                directFinished = directMode;
                long latency = Math.max(0L, SystemClock.uptimeMillis() - started);
                broadcastCommand(command, message, mode, latency);
                if (stop) {
                    manualStop = true;
                    stopLive(message);
                } else if (directMode) {
                    main.postDelayed(() -> stopLive("فوری Live command مکمل ہوئی"), 1_400L);
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
            toolRunning = false;
            commandHandled = false;
            broadcastStatus("کمانڈ bridge نہیں چلا",
                    error.getClass().getSimpleName(), true);
        }
    }

    private void onTurnComplete() {
        audioPlayer.markTurnComplete();
        removeFallback();
        String snapshot = currentTranscript;
        if (commandHandled) {
            if (directMode && directFinished) {
                main.postDelayed(() -> stopLive("فوری Live command مکمل ہوئی"), 1_200L);
            } else if (!directMode && !toolRunning) {
                main.postDelayed(this::resetForNextTurn, 650L);
            }
            return;
        }
        fallbackRunnable = () -> executeTranscriptFallback(snapshot);
        main.postDelayed(fallbackRunnable, FALLBACK_DELAY_MS);
    }

    private void resetForNextTurn() {
        if (manualStop || !active) return;
        currentTranscript = "";
        outputTranscript = "";
        turnStartedAt = 0L;
        commandHandled = false;
        toolRunning = false;
        directFinished = false;
        audioStreamEnded = false;
        broadcastStatus("وزیر Live تیار ہے",
                "کہیں: وزیر، پھر پوری کمانڈ", false);
    }

    private void sendToolResponse(String id, String name,
                                  BackgroundCommandExecutor.Result result,
                                  String diagnostic) {
        WebSocket socket = webSocket;
        if (socket == null) return;
        try {
            socket.send(GeminiLiveProtocol.toolResponse(id, name, result, diagnostic));
        } catch (JSONException ignored) {
            // The Android action has already been safely handled; keep the session alive.
        }
    }

    private void startAudioCapture() {
        if (recording || manualStop || !setupComplete) return;
        int min = AudioRecord.getMinBufferSize(
                GeminiLiveProtocol.INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min * 3, GeminiLiveProtocol.INPUT_SAMPLE_RATE * 2);
        AudioRecord record = createAudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, bufferSize);
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            if (record != null) record.release();
            record = createAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferSize);
        }
        if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
            if (record != null) record.release();
            broadcastStatus("مائیکروفون شروع نہیں ہوا",
                    "Redmi میں دوسری microphone app بند کریں", true);
            scheduleReconnect(1_800L);
            return;
        }
        audioRecord = record;
        enableAudioEffects(record.getAudioSessionId());
        recording = true;
        audioThread = new Thread(this::audioLoop, "Wazir-GeminiLive-Audio");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    private AudioRecord createAudioRecord(int source, int bufferSize) {
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
        AudioRecord record = audioRecord;
        if (record == null) return;
        byte[] chunk = new byte[3_200]; // 100 ms of 16 kHz mono PCM16.
        try {
            record.startRecording();
            while (recording && !manualStop) {
                int read = record.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) continue;
                if (!setupComplete || modelSpeaking || toolRunning) {
                    pauseAudioInput();
                    continue;
                }
                audioStreamEnded = false;
                WebSocket socket = webSocket;
                if (socket == null) continue;
                try {
                    if (!socket.send(GeminiLiveProtocol.audioMessage(chunk, read))) {
                        main.post(() -> scheduleReconnect(700L));
                    }
                } catch (JSONException ignored) {
                }
            }
        } catch (RuntimeException error) {
            main.post(() -> {
                broadcastStatus("مائیکروفون stream رک گئی",
                        "خودکار recovery جاری ہے", false);
                scheduleReconnect(900L);
            });
        }
    }

    private void pauseAudioInput() {
        if (audioStreamEnded) return;
        audioStreamEnded = true;
        WebSocket socket = webSocket;
        if (socket != null && setupComplete) {
            try {
                socket.send(GeminiLiveProtocol.audioStreamEndMessage());
            } catch (JSONException ignored) {
            }
        }
    }

    private void onPlaybackState(boolean playing) {
        modelSpeaking = playing;
        if (playing) {
            pauseAudioInput();
        } else {
            audioStreamEnded = false;
            if (!directMode && active && !toolRunning) {
                main.post(() -> broadcastStatus("وزیر Live تیار ہے",
                        "کہیں: وزیر، پھر پوری کمانڈ", false));
            }
        }
    }

    private void enableAudioEffects(int sessionId) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId);
                if (gainControl != null) gainControl.setEnabled(true);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void stopAudioCapture() {
        recording = false;
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try {
                record.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                record.release();
            } catch (RuntimeException ignored) {
            }
        }
        releaseAudioEffects();
        Thread thread = audioThread;
        audioThread = null;
        if (thread != null) thread.interrupt();
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

    private void handleSocketFailure(int ticket, String reason) {
        if (ticket != generation || manualStop || !active) return;
        setupComplete = false;
        stopAudioCapture();
        closeSocket();
        broadcastStatus("Gemini Live دوبارہ جوڑ رہا ہوں",
                cleanNetworkMessage(reason), false);
        if (directMode && directRetryCount++ >= 2) {
            broadcastStatus("فوری Live Voice نہیں جڑی",
                    "Internet، API key یا Gemini quota چیک کریں", true);
            main.postDelayed(() -> stopLive("Live connection دستیاب نہیں"), 2_300L);
            return;
        }
        reconnectAttempt++;
        long delay = Math.min(8_000L, 500L * (1L << Math.min(reconnectAttempt, 4)));
        scheduleReconnect(delay);
    }

    private void scheduleReconnect(long delayMs) {
        if (manualStop || !active) return;
        if (reconnectRunnable != null) main.removeCallbacks(reconnectRunnable);
        reconnectRunnable = this::connectNewSession;
        main.postDelayed(reconnectRunnable, Math.max(250L, delayMs));
    }

    private void armDirectTimeout() {
        removeDirectTimeout();
        if (!directMode || manualStop || commandHandled) return;
        directTimeoutRunnable = () -> {
            if (!directMode || commandHandled || toolRunning || manualStop) return;
            if (!currentTranscript.trim().isEmpty()) {
                executeTranscriptFallback(currentTranscript);
                return;
            }
            broadcastStatus("کمانڈ نہیں ملی",
                    "35 سیکنڈ مکمل ہوئے؛ دوبارہ فوری Live Voice دبائیں", true);
            main.postDelayed(() -> stopLive("فوری listening مکمل ہوئی"), 1_500L);
        };
        main.postDelayed(directTimeoutRunnable, DIRECT_LISTEN_TIMEOUT_MS);
    }

    private boolean hasNetwork() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void startAsForeground(boolean direct) {
        Notification notification = buildNotification(direct
                ? "فوری Gemini Live command"
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
        PendingIntent openPending = PendingIntent.getActivity(this, 20, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, GeminiLiveVoiceService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 21, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("وزیر Gen Z — Gemini Live")
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
                CHANNEL_ID, "Wazir Gemini Live voice", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("وزیر کا مسلسل Gemini Live microphone اور phone control");
        channel.setSound(null, null);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void broadcastStatus(String status, String detail, boolean force) {
        Intent intent = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status == null ? "" : status)
                .putExtra(EXTRA_DETAIL, detail == null ? "" : detail)
                .putExtra(EXTRA_ENGINE, "Gemini Live • " + GeminiLiveProtocol.MODEL)
                .putExtra(EXTRA_MODE, directMode ? "Direct Live" : "Hands-free Live");
        sendBroadcast(intent);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && active) manager.notify(NOTIFICATION_ID,
                buildNotification(status == null ? "وزیر Live" : status));
    }

    private void broadcastTranscript(String transcript) {
        sendBroadcast(new Intent(ACTION_TRANSCRIPT)
                .setPackage(getPackageName())
                .putExtra(EXTRA_TRANSCRIPT, transcript == null ? "" : transcript)
                .putExtra(EXTRA_ENGINE, "Gemini Live • input transcription")
                .putExtra(EXTRA_MODE, directMode ? "Direct Live" : "Hands-free Live"));
    }

    private void broadcastCommand(String command, String result, String mode, long latency) {
        sendBroadcast(new Intent(ACTION_COMMAND)
                .setPackage(getPackageName())
                .putExtra(EXTRA_COMMAND, command == null ? "" : command)
                .putExtra(EXTRA_RESULT, result == null ? "" : result)
                .putExtra(EXTRA_ENGINE, "Gemini Live • " + GeminiLiveProtocol.MODEL)
                .putExtra(EXTRA_MODE, mode == null ? "Gemini Live" : mode)
                .putExtra(EXTRA_LATENCY_MS, latency));
    }

    private void setHandsFreeEnabled(boolean enabled) {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        preferences.edit().putBoolean(PREF_HANDS_FREE, enabled).apply();
    }

    private void stopLive(String message) {
        active = false;
        setupComplete = false;
        setHandsFreeEnabled(false);
        generation++;
        removeAllCallbacks();
        stopAudioCapture();
        closeSocket();
        releaseWakeLock();
        if (audioPlayer != null) audioPlayer.stopNow();
        if (message != null && !message.isEmpty()) {
            broadcastCommand("", message, "Gemini Live", 0L);
        }
        stopForeground(true);
        stopSelf();
    }

    private void closeSocket() {
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try {
                socket.close(1000, "Wazir session restart");
            } catch (RuntimeException ignored) {
                try { socket.cancel(); } catch (RuntimeException ignoredAgain) { }
            }
        }
    }

    private String cleanNetworkMessage(String value) {
        if (value == null || value.trim().isEmpty()) return "connection interrupted";
        String safe = value.replaceAll("(?i)key=[^&\\s]+", "key=***").trim();
        return safe.length() <= 180 ? safe : safe.substring(0, 180);
    }

    private void acquireWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            if (wakeLockRenewal != null) main.removeCallbacks(wakeLockRenewal);
            wakeLockRenewal = this::acquireWakeLock;
            main.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_MS);
        } catch (RuntimeException ignored) {
        }
    }

    private void releaseWakeLock() {
        if (wakeLockRenewal != null) main.removeCallbacks(wakeLockRenewal);
        wakeLockRenewal = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (RuntimeException ignored) { }
        }
    }

    private void removeConnectWatchdog() {
        if (connectWatchdog != null) main.removeCallbacks(connectWatchdog);
        connectWatchdog = null;
    }

    private void removeFallback() {
        if (fallbackRunnable != null) main.removeCallbacks(fallbackRunnable);
        fallbackRunnable = null;
    }

    private void removeDirectTimeout() {
        if (directTimeoutRunnable != null) main.removeCallbacks(directTimeoutRunnable);
        directTimeoutRunnable = null;
    }

    private void removeAllCallbacks() {
        removeConnectWatchdog();
        removeFallback();
        removeDirectTimeout();
        if (reconnectRunnable != null) main.removeCallbacks(reconnectRunnable);
        reconnectRunnable = null;
    }

    @Override
    public void onDestroy() {
        manualStop = true;
        active = false;
        removeAllCallbacks();
        stopAudioCapture();
        closeSocket();
        releaseWakeLock();
        if (audioPlayer != null) {
            audioPlayer.release();
            audioPlayer = null;
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            httpClient = null;
        }
        super.onDestroy();
    }
}
