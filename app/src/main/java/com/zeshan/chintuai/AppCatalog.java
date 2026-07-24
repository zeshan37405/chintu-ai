package com.zeshan.chintuai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Known app aliases and package candidates used before fuzzy launcher-label matching. */
public final class AppCatalog {
    private static final List<AppSpec> APPS = buildApps();

    private AppCatalog() {
    }

    public static final class AppSpec {
        public final String displayName;
        public final List<String> packageNames;
        public final List<String> aliases;
        public final String fallbackUrl;

        AppSpec(String displayName, String fallbackUrl, String[] packages, String[] aliases) {
            this.displayName = displayName;
            this.fallbackUrl = fallbackUrl;
            this.packageNames = Collections.unmodifiableList(Arrays.asList(packages));
            this.aliases = Collections.unmodifiableList(Arrays.asList(aliases));
        }
    }

    public static final class AppMatch {
        public final AppSpec app;
        public final int score;

        AppMatch(AppSpec app, int score) {
            this.app = app;
            this.score = score;
        }
    }

    public static AppMatch findBest(String requestedName) {
        String target = normalizeAppKey(requestedName);
        if (target.isEmpty()) return null;
        AppSpec best = null;
        int bestScore = 0;
        for (AppSpec app : APPS) {
            int score = scoreName(target, normalizeAppKey(app.displayName));
            for (String alias : app.aliases) {
                score = Math.max(score, scoreName(target, normalizeAppKey(alias)));
            }
            if (score > bestScore) {
                best = app;
                bestScore = score;
            }
        }
        return best == null ? null : new AppMatch(best, bestScore);
    }

    public static int scoreName(String first, String second) {
        return ContactMatcher.similarity(normalizeAppKey(first), normalizeAppKey(second));
    }

    public static String normalizeAppKey(String text) {
        return CommandEngine.normalize(text)
                .replace("واٹس ایپ", "whatsapp")
                .replace("انسٹاگرام", "instagram")
                .replace("فیس بک", "facebook")
                .replace("میسنجر", "messenger")
                .replace("یوٹیوب", "youtube")
                .replace("ٹک ٹاک", "tiktok")
                .replace("ٹیلیگرام", "telegram")
                .replace("سنیپ چیٹ", "snapchat")
                .replace("پنٹرسٹ", "pinterest")
                .replace("کینوا", "canva")
                .replace("کیپ کٹ", "capcut")
                .replace("میپس", "maps")
                .replace("جی میل", "gmail")
                .replace("کروم", "chrome")
                .replace("کیلکولیٹر", "calculator")
                .replace("گیلری", "gallery")
                .replace("فوٹوز", "photos")
                .replace("پلے اسٹور", "play store")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static List<AppSpec> all() {
        return APPS;
    }

    private static List<AppSpec> buildApps() {
        List<AppSpec> apps = new ArrayList<>();
        apps.add(spec("YouTube", "https://www.youtube.com",
                packages("com.google.android.youtube"), aliases("یوٹیوب", "youtube")));
        apps.add(spec("WhatsApp Business", "https://wa.me/",
                packages("com.whatsapp.w4b"), aliases("واٹس ایپ بزنس", "whatsapp business")));
        apps.add(spec("WhatsApp", "https://wa.me/",
                packages("com.whatsapp"), aliases("واٹس ایپ", "whatsapp")));
        apps.add(spec("Instagram", "https://www.instagram.com",
                packages("com.instagram.android"), aliases("انسٹاگرام", "instagram")));
        apps.add(spec("Facebook Lite", "https://m.facebook.com",
                packages("com.facebook.lite"), aliases("فیس بک لائٹ", "facebook lite")));
        apps.add(spec("Facebook", "https://www.facebook.com",
                packages("com.facebook.katana"), aliases("فیس بک", "facebook")));
        apps.add(spec("Messenger", "https://www.messenger.com",
                packages("com.facebook.orca"), aliases("میسنجر", "messenger")));
        apps.add(spec("TikTok", "https://www.tiktok.com",
                packages("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
                aliases("ٹک ٹاک", "tiktok")));
        apps.add(spec("X", "https://x.com",
                packages("com.twitter.android"), aliases("ایکس", "twitter", "ٹوئٹر", "x")));
        apps.add(spec("Threads", "https://www.threads.net",
                packages("com.instagram.barcelona"), aliases("تھریڈز", "threads")));
        apps.add(spec("Telegram", "https://telegram.org",
                packages("org.telegram.messenger"), aliases("ٹیلیگرام", "telegram")));
        apps.add(spec("Snapchat", "https://www.snapchat.com",
                packages("com.snapchat.android"), aliases("سنیپ چیٹ", "snapchat")));
        apps.add(spec("Pinterest", "https://www.pinterest.com",
                packages("com.pinterest"), aliases("پنٹرسٹ", "pinterest")));
        apps.add(spec("Canva", "https://www.canva.com",
                packages("com.canva.editor"), aliases("کینوا", "canva")));
        apps.add(spec("CapCut", "https://www.capcut.com",
                packages("com.lemon.lvoverseas"), aliases("کیپ کٹ", "capcut")));
        apps.add(spec("Spotify", "https://open.spotify.com",
                packages("com.spotify.music"), aliases("اسپاٹیفائی", "spotify")));
        apps.add(spec("Google Maps", "https://maps.google.com",
                packages("com.google.android.apps.maps"), aliases("گوگل میپس", "میپس", "maps")));
        apps.add(spec("Gmail", "https://mail.google.com",
                packages("com.google.android.gm"), aliases("جی میل", "gmail", "ای میل")));
        apps.add(spec("Chrome", "https://www.google.com",
                packages("com.android.chrome"), aliases("کروم", "chrome", "براؤزر", "browser")));
        apps.add(spec("Mi Browser", "https://www.google.com",
                packages("com.mi.globalbrowser"), aliases("ایم آئی براؤزر", "mi browser")));
        apps.add(spec("Gallery", null,
                packages("com.miui.gallery"), aliases("گیلری", "gallery", "تصاویر")));
        apps.add(spec("Google Photos", "https://photos.google.com",
                packages("com.google.android.apps.photos"), aliases("فوٹوز", "photos", "تصاویر")));
        apps.add(spec("Calculator", null,
                packages("com.miui.calculator", "com.google.android.calculator"),
                aliases("کیلکولیٹر", "calculator")));
        apps.add(spec("Files", null,
                packages("com.google.android.apps.nbu.files", "com.android.fileexplorer"),
                aliases("فائلز", "فائل مینیجر", "files", "file manager")));
        apps.add(spec("Play Store", "https://play.google.com/store/apps",
                packages("com.android.vending"), aliases("پلے اسٹور", "play store")));
        return Collections.unmodifiableList(apps);
    }

    private static AppSpec spec(String name, String fallback, String[] packages, String[] aliases) {
        return new AppSpec(name, fallback, packages, aliases);
    }

    private static String[] packages(String... values) {
        return values;
    }

    private static String[] aliases(String... values) {
        return values;
    }
}
