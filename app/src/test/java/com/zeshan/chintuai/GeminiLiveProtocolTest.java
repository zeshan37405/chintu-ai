package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import okio.ByteString;

public final class GeminiLiveProtocolTest {
    @Test
    public void responseModalitiesLivesInsideGenerationConfig() throws Exception {
        JSONObject root = new JSONObject(GeminiLiveProtocol.setupMessage(
                true, "gemini-3.1-flash-live-preview"));
        JSONObject setup = root.getJSONObject("setup");

        assertFalse(setup.has("responseModalities"));
        JSONObject generation = setup.getJSONObject("generationConfig");
        JSONArray modalities = generation.getJSONArray("responseModalities");
        assertEquals(1, modalities.length());
        assertEquals("AUDIO", modalities.getString(0));
    }

    @Test
    public void setupStillIncludesTranscriptionAndVad() throws Exception {
        JSONObject root = new JSONObject(GeminiLiveProtocol.setupMessage(
                false, "gemini-2.5-flash-native-audio-preview-12-2025"));
        JSONObject setup = root.getJSONObject("setup");

        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025",
                setup.getString("model"));
        assertTrue(setup.has("inputAudioTranscription"));
        assertTrue(setup.has("realtimeInputConfig"));
        JSONObject vad = setup.getJSONObject("realtimeInputConfig")
                .getJSONObject("automaticActivityDetection");
        assertEquals("END_SENSITIVITY_HIGH",
                vad.getString("endOfSpeechSensitivity"));
        assertEquals(900, vad.getInt("silenceDurationMs"));
        assertTrue(setup.getJSONObject("systemInstruction").toString()
                .contains("Perso-Arabic"));
    }

    @Test
    public void binarySetupCompleteFrameDecodesAsUtf8Json() throws Exception {
        ByteString frame = ByteString.encodeUtf8("{\"setupComplete\":{}}");

        JSONObject root = new JSONObject(GeminiLiveProtocol.serverMessageText(frame));

        assertTrue(root.has("setupComplete"));
    }
}
