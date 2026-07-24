package com.zeshan.chintuai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VoiceTurnPolicyTest {
    @Test
    public void rejectsWazirsOwnAcknowledgement() {
        assertTrue(VoiceTurnPolicy.isFixedSelfEcho("جی ذیشان"));
        assertTrue(VoiceTurnPolicy.isFixedSelfEcho("جی ذیشان حکم کریں"));
        assertTrue(VoiceTurnPolicy.matchesRecentSpokenText(
                "جی ذیشان", "جی ذیشان، حکم کریں"));
    }

    @Test
    public void doesNotCommitConversationalFragments() {
        assertFalse(VoiceTurnPolicy.isLikelyCompleteCommand("اور"));
        assertFalse(VoiceTurnPolicy.isLikelyCompleteCommand("اور دیکھو"));
        assertFalse(VoiceTurnPolicy.isLikelyCompleteCommand("جی ذیشان"));
    }

    @Test
    public void commitsKnownPhoneActions() {
        assertTrue(VoiceTurnPolicy.isLikelyCompleteCommand("واٹس ایپ کھولو"));
        assertTrue(VoiceTurnPolicy.isLikelyCompleteCommand("فیس بک کھولو"));
        assertTrue(VoiceTurnPolicy.isLikelyCompleteCommand("نیچے سکرول کرو"));
        assertTrue(VoiceTurnPolicy.isLikelyCompleteCommand("ہوم کو کال کرو"));
    }

    @Test
    public void acceptsWakeWordWithCompleteRemainder() {
        assertTrue(VoiceTurnPolicy.isLikelyCompleteCommand("وزیر واٹس ایپ کھولو"));
    }
}
