package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LiveTranscriptGateTest {
    @Test
    public void mergesGrowingAndDeltaTranscripts() {
        assertEquals("وزیر فیس بک", LiveTranscriptGate.merge(
                "وزیر", "وزیر فیس بک"));
        assertEquals("وزیر فیس بک کھولو", LiveTranscriptGate.merge(
                "وزیر فیس بک", "کھولو"));
        assertEquals("وزیر فیس بک کھولو", LiveTranscriptGate.merge(
                "وزیر فیس بک کھولو", "کھولو"));
    }

    @Test
    public void enforcesWakeWordOnlyInHandsFreeMode() {
        assertTrue(LiveTranscriptGate.isUsableCommand(
                "وزیر فیس بک کھولو", false));
        assertFalse(LiveTranscriptGate.isUsableCommand(
                "فیس بک کھولو", false));
        assertTrue(LiveTranscriptGate.isUsableCommand(
                "فیس بک کھولو", true));
    }

    @Test
    public void stripsWazirWakeWordAndRejectsNoise() {
        assertEquals("واٹس ایپ کھولو",
                LiveTranscriptGate.commandAfterWakeWord("وزیر، واٹس ایپ کھولو"));
        assertFalse(LiveTranscriptGate.isUsableCommand("وزیر جی", false));
        assertFalse(LiveTranscriptGate.isUsableCommand("اور", true));
    }

    @Test
    public void acceptsRomanWazirVariant() {
        assertTrue(LiveTranscriptGate.hasWakeWord("Wazir Facebook open karo"));
    }
}
