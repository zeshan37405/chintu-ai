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
        String left = normalizeAppKey(first);
        String right = normalizeAppKey(second);
        if (left.equals(right) && !left.isEmpty()) return 100;
        if (left.replace(" ", "").equals(right.replace(" ", "")) && !left.isEmpty()) return 99;
        return ContactMatcher.similarity(left, right);
    }

    public static String normalizeAppKey(String text) {
        String normalized = CommandEngine.normalize(
                AccentCommandNormalizer.canonicalize(text));
        normalized = replaceAny(normalized, "whatsapp",
                "واٹس ایپ", "واٹسپ", "واٹساپ", "واٹس اپ", "وٹس ایپ",
                "whatsapp", "whats app", "watsapp", "whatsap", "whatsup");
        normalized = replaceAny(normalized, "instagram",
                "انسٹاگرام", "انسٹا گرام", "instagram", "insta gram");
        normalized = replaceAny(normalized, "facebook",
                "فیس بک", "فیسبک", "facebook", "face book");
        normalized = replaceAny(normalized, "messenger",
                "میسنجر", "مسینجر", "messenger");
        normalized = replaceAny(normalized, "youtube",
                "یوٹیوب", "یو ٹیوب", "youtube", "you tube");
        normalized = replaceAny(normalized, "tiktok",
                "ٹک ٹاک", "ٹک ٹوک", "tiktok", "tik tok");
        normalized = replaceAny(normalized, "telegram",
                "ٹیلیگرام", "ٹیلی گرام", "telegram", "tele gram");
        normalized = replaceAny(normalized, "snapchat",
                "سنیپ چیٹ", "snapchat", "snap chat");
        normalized = replaceAny(normalized, "pinterest",
                "پنٹرسٹ", "پینٹرسٹ", "pinterest");
        normalized = replaceAny(normalized, "canva", "کینوا", "canva");
        normalized = replaceAny(normalized, "capcut", "کیپ کٹ", "capcut", "cap cut");
        normalized = replaceAny(normalized, "spotify", "اسپاٹیفائی", "spotify");
        normalized = replaceAny(normalized, "maps", "گوگل میپس", "میپس", "maps");
        normalized = replaceAny(normalized, "gmail", "جی میل", "gmail", "g mail");
        normalized = replaceAny(normalized, "chrome", "کروم", "chrome");
        normalized = replaceAny(normalized, "calculator", "کیلکولیٹر", "calculator");
        normalized = replaceAny(normalized, "gallery", "گیلری", "gallery");
        normalized = replaceAny(normalized, "photos", "فوٹوز", "photos");
        normalized = replaceAny(normalized, "play store", "پلے اسٹور", "play store");
        return normalized
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static List<AppSpec> all() {
        return APPS;
    }

    private static String replaceAny(String source, String canonical, String... values) {
        String result = source;
        for (String value : values) {
            result = result.replace(CommandEngine.normalize(value), canonical);
        }
        return result;
    }

    private static List<AppSpec> buildApps() {
        List<AppSpec> apps = new ArrayList<>();
        apps.add(spec("YouTube", "https://www.youtube.com",
                packages("com.google.android.youtube"),
                aliases("یوٹیوب", "یو ٹیوب", "यूट्यूब", "youtube", "you tube")));
        apps.add(spec("WhatsApp Business", "https://wa.me/",
                packages("com.whatsapp.w4b"),
                aliases("واٹس ایپ بزنس", "व्हाट्सएप बिजनेस", "whatsapp business")));
        apps.add(spec("WhatsApp", "https://wa.me/",
                packages("com.whatsapp"),
                aliases("واٹس ایپ", "واٹسپ", "واٹساپ", "واٹس اپ", "وٹس ایپ",
                        "व्हाट्सएप", "वॉट्सऐप", "वाट्सएप",
                        "whatsapp", "whats app", "watsapp", "whatsap", "whatsup")));
        apps.add(spec("Instagram", "https://www.instagram.com",
                packages("com.instagram.android"),
                aliases("انسٹاگرام", "انسٹا گرام", "इंस्टाग्राम", "instagram", "insta gram")));
        apps.add(spec("Facebook Lite", "https://m.facebook.com",
                packages("com.facebook.lite"),
                aliases("فیس بک لائٹ", "फेसबुक लाइट", "facebook lite")));
        apps.add(spec("Facebook", "https://www.facebook.com",
                packages("com.facebook.katana"),
                aliases("فیس بک", "فیسبک", "फेसबुक", "facebook", "face book")));
        apps.add(spec("Messenger", "https://www.messenger.com",
                packages("com.facebook.orca"),
                aliases("میسنجر", "مسینجر", "मैसेंजर", "messenger")));
        apps.add(spec("TikTok", "https://www.tiktok.com",
                packages("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
                aliases("ٹک ٹاک", "ٹک ٹوک", "टिकटॉक", "tiktok", "tik tok")));
        apps.add(spec("X", "https://x.com",
                packages("com.twitter.android"),
                aliases("ایکس", "twitter", "ٹوئٹر", "ट्विटर", "x")));
        apps.add(spec("Threads", "https://www.threads.net",
                packages("com.instagram.barcelona"), aliases("تھریڈز", "threads")));
        apps.add(spec("Telegram", "https://telegram.org",
                packages("org.telegram.messenger"),
                aliases("ٹیلیگرام", "ٹیلی گرام", "टेलीग्राम", "telegram", "tele gram")));
        apps.add(spec("Snapchat", "https://www.snapchat.com",
                packages("com.snapchat.android"),
                aliases("سنیپ چیٹ", "स्नैपचैट", "snapchat", "snap chat")));
        apps.add(spec("Pinterest", "https://www.pinterest.com",
                packages("com.pinterest"),
                aliases("پنٹرسٹ", "پینٹرسٹ", "पिंटरेस्ट", "pinterest")));
        apps.add(spec("Canva", "https://www.canva.com",
                packages("com.canva.editor"), aliases("کینوا", "कैनवा", "canva")));
        apps.add(spec("CapCut", "https://www.capcut.com",
                packages("com.lemon.lvoverseas"),
                aliases("کیپ کٹ", "कैपकट", "capcut", "cap cut")));
        apps.add(spec("Spotify", "https://open.spotify.com",
                packages("com.spotify.music"), aliases("اسپاٹیفائی", "स्पॉटिफाई", "spotify")));
        apps.add(spec("Google Maps", "https://maps.google.com",
                packages("com.google.android.apps.maps"),
                aliases("گوگل میپس", "میپس", "गूगल मैप्स", "मैप्स", "maps")));
        apps.add(spec("Gmail", "https://mail.google.com",
                packages("com.google.android.gm"),
                aliases("جی میل", "जीमेल", "gmail", "g mail", "ای میل")));
        apps.add(spec("Chrome", "https://www.google.com",
                packages("com.android.chrome"), aliases("کروم", "क्रोम", "chrome", "براؤزر", "browser")));
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
