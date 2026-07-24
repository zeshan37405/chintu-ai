package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContactMatcherTest {
    @Test
    public void normalizesSpeechVariantsOfHomeAsWholeTokens() {
        assertEquals("home", ContactMatcher.normalizeContactKey("ہوم"));
        assertEquals("home", ContactMatcher.normalizeContactKey("ھوم"));
        assertEquals("home", ContactMatcher.normalizeContactKey("ہم"));
    }

    @Test
    public void realNameMatchOutranksGenericHomePhoneLabel() {
        int namedHome = ContactMatcher.score("ہوم", "Home", "Mobile");
        int unrelatedHomeLabel = ContactMatcher.score("ہوم", "Ali", "Home");
        assertEquals(100, namedHome);
        assertTrue(unrelatedHomeLabel < 58);
    }

    @Test
    public void fuzzyNameMatchToleratesSmallRecognitionError() {
        assertTrue(ContactMatcher.score("زیشان", "ذیشان", "Mobile") >= 60);
    }
}
