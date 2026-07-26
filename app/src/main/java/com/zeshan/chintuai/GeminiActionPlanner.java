package com.zeshan.chintuai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Calls Gemini 2.5 Flash and extracts a forced structured phone-action function call. */
public final class GeminiActionPlanner {
    public static final String MODEL = "gemini-2.5-flash";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/"
                    + MODEL + ":generateContent";

    private static final String SYSTEM_INSTRUCTION =
            "You are Wazir, Zeeshan's Urdu-first Android operating assistant. "
                    + "Convert the user's Urdu, Roman Urdu, Hindi or English request into a small, "
                    + "safe sequence of phone actions by calling plan_phone_actions. "
                    + "Never invent an installed app, contact, visible button or successful result. "
                    + "Use OPEN_APP before screen actions when an app is named. "
                    + "Use DRAFT_SOCIAL_POST to prepare text, but never publish automatically. "
                    + "Use STAGE_SUBMIT for post/send/publish so the Android safety layer asks for "
                    + "a separate confirmation. Use CONFIRM only when the user explicitly says "
                    + "confirm/تصدیق. Banking, payments, purchases, PINs, passwords, OTPs, account "
                    + "security changes and money transfers are forbidden: return SPEAK explaining "
                    + "that Wazir will not do them. Keep reply concise and in Urdu. "
                    + "For a simple supported local command, prefer the specific action type rather "
                    + "than LOCAL_COMMAND. Never return more than six actions.";

    private GeminiActionPlanner() {
    }

    public static GeminiActionPlan plan(Context context, String transcript) throws IOException {
        String apiKey = WazirSecretStore.getGeminiApiKey(context);
        if (apiKey.isEmpty()) {
            throw new IOException("Gemini API key محفوظ نہیں ہے");
        }
        String prompt = transcript == null ? "" : transcript.trim();
        if (prompt.isEmpty()) throw new IOException("خالی کمانڈ Gemini کو نہیں بھیجی گئی");

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("x-goog-api-key", apiKey);

            byte[] body = buildRequest(prompt).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            String response = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IOException(parseApiError(status, response));
            }
            return parseResponse(response);
        } catch (JSONException error) {
            throw new IOException("Gemini کا structured جواب پڑھا نہیں گیا", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static JSONObject buildRequest(String prompt) throws JSONException {
        JSONObject request = new JSONObject();
        request.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(
                        new JSONObject().put("text", SYSTEM_INSTRUCTION))));

        JSONObject context = new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text",
                        "Recognized voice transcript: " + prompt)));
        request.put("contents", new JSONArray().put(context));

        JSONObject actionProperties = new JSONObject()
                .put("type", new JSONObject()
                        .put("type", "string")
                        .put("description", "Exact Android action type")
                        .put("enum", new JSONArray()
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
                                .put("WAIT")))
                .put("target", new JSONObject()
                        .put("type", "string")
                        .put("description", "App, contact or visible button name; empty if unused"))
                .put("text", new JSONObject()
                        .put("type", "string")
                        .put("description", "Text to type, draft or speak; empty if unused"))
                .put("direction", new JSONObject()
                        .put("type", "string")
                        .put("description", "up, down, left or right; empty if unused"))
                .put("query", new JSONObject()
                        .put("type", "string")
                        .put("description", "Search query or exact local command; empty if unused"))
                .put("delay_ms", new JSONObject()
                        .put("type", "integer")
                        .put("description", "Optional wait before this action, 0 to 5000"));

        JSONObject actionSchema = new JSONObject()
                .put("type", "object")
                .put("properties", actionProperties)
                .put("required", new JSONArray().put("type"));

        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("reply", new JSONObject()
                                .put("type", "string")
                                .put("description", "Short Urdu acknowledgement or explanation"))
                        .put("actions", new JSONObject()
                                .put("type", "array")
                                .put("description", "Ordered safe Android actions")
                                .put("items", actionSchema)))
                .put("required", new JSONArray().put("reply").put("actions"));

        JSONObject declaration = new JSONObject()
                .put("name", "plan_phone_actions")
                .put("description", "Plan safe structured actions for the Wazir Android app")
                .put("parameters", parameters);
        request.put("tools", new JSONArray().put(new JSONObject()
                .put("functionDeclarations", new JSONArray().put(declaration))));
        request.put("toolConfig", new JSONObject()
                .put("functionCallingConfig", new JSONObject()
                        .put("mode", "ANY")
                        .put("allowedFunctionNames",
                                new JSONArray().put("plan_phone_actions"))));
        request.put("generationConfig", new JSONObject()
                .put("temperature", 0.2)
                .put("maxOutputTokens", 800)
                .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0))
                .put("candidateCount", 1));
        return request;
    }

    private static GeminiActionPlan parseResponse(String response) throws JSONException, IOException {
        JSONObject root = new JSONObject(response);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IOException(blockedReason(root));
        }
        for (int candidateIndex = 0; candidateIndex < candidates.length(); candidateIndex++) {
            JSONObject candidate = candidates.optJSONObject(candidateIndex);
            JSONObject content = candidate == null ? null : candidate.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts == null) continue;
            String fallbackText = "";
            for (int partIndex = 0; partIndex < parts.length(); partIndex++) {
                JSONObject part = parts.optJSONObject(partIndex);
                if (part == null) continue;
                JSONObject call = part.optJSONObject("functionCall");
                if (call != null && "plan_phone_actions".equals(call.optString("name"))) {
                    JSONObject args = call.optJSONObject("args");
                    GeminiActionPlan plan = GeminiActionPlan.fromFunctionArgs(args);
                    if (!plan.actions.isEmpty() || !plan.reply.isEmpty()) return plan;
                }
                if (fallbackText.isEmpty()) fallbackText = part.optString("text", "").trim();
            }
            if (!fallbackText.isEmpty()) return GeminiActionPlan.speakOnly(fallbackText);
        }
        throw new IOException("Gemini نے کوئی قابلِ عمل structured action نہیں دیا");
    }

    private static String blockedReason(JSONObject root) {
        JSONObject feedback = root.optJSONObject("promptFeedback");
        String reason = feedback == null ? "" : feedback.optString("blockReason", "");
        return reason.isEmpty()
                ? "Gemini سے کوئی جواب نہیں ملا"
                : "Gemini نے درخواست روک دی: " + reason;
    }

    private static String parseApiError(int status, String response) {
        try {
            JSONObject root = new JSONObject(response == null ? "{}" : response);
            JSONObject error = root.optJSONObject("error");
            String message = error == null ? "" : error.optString("message", "");
            if (status == 400 && message.toLowerCase(Locale.ROOT).contains("api key")) {
                return "Gemini API key درست یا فعال نہیں ہے";
            }
            if (status == 401 || status == 403) {
                return "Gemini API key کی اجازت یا restriction چیک کریں";
            }
            if (status == 429) return "Gemini free quota فی الحال مکمل ہو گئی ہے";
            if (!message.isEmpty()) return "Gemini error: " + message;
        } catch (JSONException ignored) {
            // Fall through to the status message.
        }
        return "Gemini HTTP error " + status;
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) value.append(line);
        }
        return value.toString();
    }
}
