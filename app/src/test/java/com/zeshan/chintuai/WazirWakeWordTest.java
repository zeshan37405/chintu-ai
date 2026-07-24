package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WazirWakeWordTest {
    @Test
    public void acceptsUrduWakeWord() {
        assertTrue(WazirWakeWord.startsWithWakeWord("وزیر"));
        assertTrue(WazirWakeWord.startsWithWakeWord("وزیر جی فیس بک کھولو"));
        assertEquals("فیس بک کھولو", WazirWakeWord.strip("وزیر جی، فیس بک کھولو"));
    }

    @Test
    public void acceptsRomanAndHindiWakeWord() {
        assertTrue(WazirWakeWord.startsWithWakeWord("Wazir WhatsApp kholo"));
        assertTrue(WazirWakeWord.startsWithWakeWord("Vazir scroll down"));
        assertTrue(WazirWakeWord.startsWithWakeWord("वज़ीर फेसबुक खोलो"));
    }

    @Test
    public void rejectsOldChintuWakeWord() {
        assertFalse(WazirWakeWord.startsWithWakeWord("چنٹو فیس بک کھولو"));
        assertFalse(WazirWakeWord.startsWithWakeWord("Chintu WhatsApp kholo"));
    }

    @Test
    public void requiresExplicitStopPhrase() {
        assertTrue(WazirWakeWord.isExactStopCommand("وزیر ہینڈز فری بند کرو"));
        assertTrue(WazirWakeWord.isExactStopCommand("Wazir stop listening"));
        assertFalse(WazirWakeWord.isExactStopCommand("وزیر فیس بک بند کرو"));
    }
}
