package com.zeshan.chintuai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Builds and parses the raw Gemini Live WebSocket messages used by Wazir. */
public final class GeminiLiveProtocol {
    public static final String MODEL = "gemini-3.1-flash-live-preview";
    public static final String FUNCTION_NAME = "execute_phone_actions";
    public static final int INPUT_SAMPLE_RATE = 16_000;
    public static final int OUTPUT_SAMPLE_RATE = 24_000;

    private GeminiLiveProtocol() {
    }

    public static String webSocketUrl(String apiKey) {
        return "wss://generativelanguage.googleapis.com/ws/"
                + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
                + "?key=" + apiKey;
    }

    public static String setupMessage(boolean directMode) throws JSONException {
        JSONObject setup = new JSONObject();
        setup.put("model", "models/" + MODEL);
        setup.put("generationConfig", new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", new JSONObject()
                        .put("voiceConfig", new JSONObject()
                                .put("prebuiltVoiceConfig", new JSONObject()
                                        .put("voiceName", "Gacrux")))));
        setup.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject()
                        .put("text", systemInstruction(directMode)))));
        setup.put("tools", new JSONArray().put(new JSONObject()
                .put("functionDeclarations", new JSONArray().put(functionDeclaration()))));
        setup.put("realtimeInputConfig", new JSONObject()
                .put("automaticActivityDetection", new JSONObject()
                        .put("disabled", false)
                        .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        .put("prefixPaddingMs", 300)
                        .put("silenceDurationMs", 800))
                .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                .put("turnCoverage", "TURN_INCLUDES_ONLY_ACTIVITY"));
        setup.put("inputAudioTranscription", new JSONObject());
        setup.put("outputAudioTranscription", new JSONObject());
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

    public static String toolResponse(String id, String name,
                                      BackgroundCommandExecutor.Result result,
                                      String diagnostic) throws JSONException {
        JSONObject response = new JSONObject()
                .put("ok", result != null && result.handled)
                .put("message", result == null ? "کمانڈ مکمل نہیں ہوئی" : result.message)
                .put("stopHandsFree", result != null && result.stopHandsFree)
                .put("diagnostic", diagnostic == null ? "" : diagnostic);
        JSONObject functionResponse = new JSONObject()
                .put("id", id == null ? "" : id)
                .put("name", name == null || name.isEmpty() ? FUNCTION_NAME : name)
                .put("response", response);
        return new JSONObject().put("toolResponse", new JSONObject()
                .put("functionResponses", new JSONArray().put(functionResponse))).toString();
    }

    public static GeminiActionPlan parsePlan(JSONObject args) {
        if (args == null) return GeminiActionPlan.speakOnly("Gemini action plan خالی تھا");
        return GeminiActionPlan.fromFunctionArgs(args);
    }

    public static String heardText(JSONObject args) {
        return args == null ? "" : args.optString("heard_text", "").trim();
    }

    private static JSONObject functionDeclaration() throws JSONException {
        JSONArray actionTypes = new JSONArray()
                .put("OPEN_APP")
                .put("SCROLL")
                .put("SWIPE")
                .put("TYPE_TEXT")
                .put("CLICK_TEXT")
                .put("BACK")
                .put("HOME")
                .put("RECENTS")
                .put("CALL_CONTACT")
                .put("MESSAGE_CONTACT")
                .put("DRAFT_SOCIAL_POST")
                .put("GOOGLE_SEARCH")
                .put("STAGE_SUBMIT")
                .put("CONFIRM")
                .put("CANCEL")
                .put("LOCAL_COMMAND")
                .put("SPEAK")
                .put("WAIT");

        JSONObject actionProperties = new JSONObject()
                .put("type", new JSONObject()
                        .put("type", "string")
                        .put("enum", actionTypes)
                        .put("description", "Exact safe Android action type"))
                .put("target", new JSONObject()
                        .put("type", "string")
                        .put("description", "App, contact or visible button name"))
                .put("text", new JSONObject()
                        .put("type", "string")
                        .put("description", "Text to type, draft or speak"))
                .put("direction", new JSONObject()
                        .put("type", "string")
                        .put("description", "up, down, left or right"))
                .put("query", new JSONObject()
                        .put("type", "string")
                        .put("description", "Search query or exact local command"))
                .put("delay_ms", new JSONObject()
                        .put("type", "integer")
                        .put("description", "Optional delay from 0 to 5000 milliseconds"));

        JSONObject action = new JSONObject()
                .put("type", "object")
                .put("properties", actionProperties)
                .put("required", new JSONArray().put("type"));

        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("heard_text", new JSONObject()
                                .put("type", "string")
                                .put("description", "Exact user speech transcript, including the wake word"))
                        .put("reply", new JSONObject()
                                .put("type", "string")
                                .put("description", "Very short Urdu acknowledgement"))
                        .put("actions", new JSONObject()
                                .put("type", "array")
                                .put("items", action)
                                .put("description", "Ordered Android actions, maximum six")))
                .put("required", new JSONArray()
                        .put("heard_text")
                        .put("reply")
                        .put("actions"));

        return new JSONObject()
                .put("name", FUNCTION_NAME)
                .put("description", "Execute a safe ordered Wazir Android phone action plan")
                .put("parameters", parameters);
    }

    private static String systemInstruction(boolean directMode) {
        String mode = directMode
                ? "This is a direct one-command microphone session. The next clear request may omit the wake word."
                : "This is hands-free mode. You MUST remain completely silent and MUST NOT call any function unless the user's speech begins with the wake word وزیر, وزیر جی, Wazir, Wazeer or Vazir.";
        return "You are Wazir, Zeeshan's Urdu-first Android voice operating assistant. "
                + mode + " Listen to the entire sentence and tolerate natural pauses. Do not act on fragments. "
                + "When a complete actionable request is heard, call execute_phone_actions exactly once. "
                + "Copy the complete speech into heard_text. Return no more than six ordered actions. "
                + "Use OPEN_APP before screen actions when an app is named. Use SCROLL after the app is open. "
                + "Use DRAFT_SOCIAL_POST to prepare text but never publish it automatically. "
                + "Post, Send and Publish must use STAGE_SUBMIT and wait for a separate explicit confirmation. "
                + "Banking, payments, purchases, money transfer, PIN, password, OTP and account-security changes "
                + "are forbidden; use SPEAK to refuse. Never claim an action succeeded before the tool result. "
                + "Keep spoken replies short, calm, deep and in Pakistani Urdu. Do not repeat the user's full command.";
    }
}
