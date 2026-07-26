package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccentCommandNormalizerTest {
    @Test
    public void convertsHindiWhatsAppCommandToCanonicalUrdu() {
        assertEquals("واٹس ایپ کھولو",
                AccentCommandNormalizer.canonicalize("व्हाट्सएप खोलो"));
    }

    @Test
    public void convertsRomanAndUrduWhatsAppVariants() {
        assertEquals("واٹس ایپ کھولو",
                AccentCommandNormalizer.canonicalize("watsapp open karo"));
        assertEquals("whatsapp",
                AppCatalog.normalizeAppKey("واٹسپ"));
    }

    @Test
    public void convertsHindiScrollAndWakeWord() {
        assertEquals("چنٹو فیس بک کھولو اور نیچے سکرول کرو",
                AccentCommandNormalizer.canonicalize(
                        "चिंटू फेसबुक खोलो और नीचे स्क्रॉल करो"));
    }

    @Test
    public void appCatalogFindsWhatsAppAndFacebookAcrossScripts() {
        AppCatalog.AppMatch whatsapp = AppCatalog.findBest("व्हाट्सएप");
        AppCatalog.AppMatch facebook = AppCatalog.findBest("فیسبک");
        assertNotNull(whatsapp);
        assertNotNull(facebook);
        assertEquals("WhatsApp", whatsapp.app.displayName);
        assertEquals("Facebook", facebook.app.displayName);
        assertTrue(whatsapp.score >= 95);
        assertTrue(facebook.score >= 95);
    }

    @Test
    public void fullSentenceFacebookCommandDoesNotSelectLite() {
        AppCatalog.AppMatch facebook = AppCatalog.findBest(
                "یہ ہے جی جیمنی لائیو وزیر فیس بک کھولو جلدی کرو");

        assertNotNull(facebook);
        assertEquals("Facebook", facebook.app.displayName);
    }
}
