package com.zeshan.chintuai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Builds and parses the raw Gemini Live WebSocket messages used by Wazir. */
public final class GeminiLiveProtocol {
    /** Current Live model, followed by the older free-tier-compatible native-audio model. */
    public static final String[] MODELS = {
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025"
    };
    public static final int INPUT_SAMPLE_RATE = 16_000;
    public static final int OUTPUT_SAMPLE_RATE = 24_000;

    private GeminiLiveProtocol() {
    }

    public static String modelAt(int index) {
        int safe = Math.max(0, Math.min(index, MODELS.length - 1));
        return MODELS[safe];
    }

    public static String webSocketUrl(String apiKey) {
        return "wss://generativelanguage.googleapis.com/ws/"
                + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
                + "?key=" + apiKey;
    }

    /**
     * Keep the first handshake deliberately minimal. The 5.0 Redmi recording showed that the
     * socket opened but setupComplete never arrived. Live is now used only for continuous audio
     * transcription; the complete transcript is planned and executed by the existing Gemini REST
     * brain after turnComplete. This removes the large function schema and optional preview fields
     * from the critical connection handshake.
     */
    public static String setupMessage(boolean directMode, String model) throws JSONException {
        JSONObject setup = new JSONObject();
        setup.put("model", "models/" + model);
        setup.put("responseModalities", new JSONArray().put("AUDIO"));
        setup.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text",
                        systemInstruction(directMode)))));
        setup.put("inputAudioTranscription", new JSONObject());
        setup.put("realtimeInputConfig", new JSONObject()
                .put("automaticActivityDetection", new JSONObject()
                        .put("disabled", false)
                        .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        .put("prefixPaddingMs", 250)
                        .put("silenceDurationMs", 1_200))
                .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                .put("turnCoverage", "TURN_INCLUDES_ONLY_ACTIVITY"));
        return new JSONObject().put("setup", setup).toString();
    }

    public static String audioMessage(byte[] pcm, int length) throws JSONException {
        String encoded = android.util.Base64.encodeToString(
                pcm, 0, length, android.util.Base64.NO_WRAP);
        return new JSONObject().put("realtimeInput", new JSONObject()
                .put("audio", new JSONObject()
                        .put("data", encoded)
                        .put("mimeType", "audio/pcm;rate=" + INPUT_SAMPLE_RATE)))
                .toString();
    }

    public static String audioStreamEndMessage() throws JSONException {
        return new JSONObject().put("realtimeInput",
                new JSONObject().put("audioStreamEnd", true)).toString();
    }

    public static String errorSummary(JSONObject root) {
        if (root == null) return "";
        JSONObject error = root.optJSONObject("error");
        if (error == null) return "";
        int code = error.optInt("code", -1);
        String status = error.optString("status", "").trim();
        String message = error.optString("message", "Gemini Live setup failed").trim();
        StringBuilder value = new StringBuilder("Gemini");
        if (code >= 0) value.append(" ").append(code);
        if (!status.isEmpty()) value.append(" ").append(status);
        if (!message.isEmpty()) value.append(": ").append(message);
        String result = value.toString();
        return result.length() > 420 ? result.substring(0, 420) : result;
    }

    private static String systemInstruction(boolean directMode) {
        String mode = directMode
                ? "This is a direct one-command microphone session. The next complete request may omit the wake word."
                : "This is hands-free mode. Ignore speech that does not begin with the wake word وزیر, وزیر جی, Wazir, Wazeer or Vazir.";
        return "You are Wazir, Zeeshan's Urdu-first Android voice assistant. "
                + mode + " Listen to the entire sentence and tolerate natural pauses. "
                + "Do not act and do not call tools in this Live session. The Android app will use the input transcription "
                + "after the user's turn ends. Give at most a very short Urdu acknowledgement so turnComplete is emitted. "
                + "Never repeat the full command and never ask for PIN, password, OTP or payment information.";
    }
}
