package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class UrduTranscriptNormalizerTest {
    @Test
    public void convertsVideoDevanagariTranscriptToUrduScript() {
        String value = UrduTranscriptNormalizer.toUrduScript(
                "यह है जी Gemini Live। वज़ीर Facebook खोलो। Jaldi karo.");

        assertEquals(
                "یہ ہے جی جیمنی لائیو۔ وزیر فیس بک کھولو۔ جلدی کرو۔",
                value);
        assertUrduDisplayOnly(value);
    }

    @Test
    public void convertsVideoRomanUrduTranscriptToUrduScript() {
        String value = UrduTranscriptNormalizer.toUrduScript(
                "Wazir Facebook kholo aur scroll karo usko.");

        assertEquals(
                "وزیر فیس بک کھولو اور سکرول کرو اس کو۔",
                value);
        assertUrduDisplayOnly(value);
    }

    @Test
    public void removesUnsupportedIndicScriptFromDisplay() {
        String value = UrduTranscriptNormalizer.toUrduScript(
                "وزیر ফেসবুক کھولو");

        assertFalse(value.matches(".*[\\u0980-\\u09FF].*"));
    }

    private static void assertUrduDisplayOnly(String value) {
        assertFalse(value.matches(".*[A-Za-z].*"));
        assertFalse(value.matches(".*[\\u0900-\\u097F].*"));
    }
}
