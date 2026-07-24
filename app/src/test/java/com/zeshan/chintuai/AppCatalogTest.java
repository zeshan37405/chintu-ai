package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppCatalogTest {
    @Test
    public void distinguishesWhatsAppBusinessFromWhatsApp() {
        AppCatalog.AppMatch match = AppCatalog.findBest("واٹس ایپ بزنس");
        assertNotNull(match);
        assertEquals("WhatsApp Business", match.app.displayName);
        assertTrue(match.score >= 90);
    }

    @Test
    public void recognizesSocialCreationApps() {
        assertEquals("Pinterest", AppCatalog.findBest("پنٹرسٹ").app.displayName);
        assertEquals("Canva", AppCatalog.findBest("کینوا").app.displayName);
        assertEquals("CapCut", AppCatalog.findBest("کیپ کٹ").app.displayName);
    }
}
