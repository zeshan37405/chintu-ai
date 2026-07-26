package com.zeshan.chintuai;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Converts common Urdu, Hindi and Roman-Urdu recognition variants into stable command phrases. */
public final class AccentCommandNormalizer {
    private static final Map<String, String> REPLACEMENTS = buildReplacements();

    private AccentCommandNormalizer() {
    }

    public static String canonicalize(String raw) {
        if (raw == null) return "";
        String result = raw.trim();
        if (result.isEmpty()) return "";

        for (Map.Entry<String, String> entry : REPLACEMENTS.entrySet()) {
            result = replaceIgnoreCase(result, entry.getKey(), entry.getValue());
        }
        return result
                .replace('،', ' ')
                .replace('۔', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String canonicalAppName(String raw) {
        String value = CommandEngine.normalize(canonicalize(raw));
        String[][] aliases = {
                {"whatsapp", "واٹس ایپ", "واٹسپ", "واٹساپ", "واٹس اپ", "وٹس ایپ", "وٹس اپ", "واٹسایپ", "व्हाट्सएप", "वॉट्सऐप", "वाट्सएप", "whats app", "watsapp", "whatsap", "whatsup"},
                {"facebook", "فیس بک", "فیسبک", "फेसबुक", "face book"},
                {"instagram", "انسٹاگرام", "انسٹا گرام", "इंस्टाग्राम", "insta gram"},
                {"youtube", "یوٹیوب", "یو ٹیوب", "यूट्यूब", "you tube"},
                {"telegram", "ٹیلیگرام", "ٹیلی گرام", "टेलीग्राम", "tele gram"},
                {"tiktok", "ٹک ٹاک", "ٹک ٹوک", "टिकटॉक", "tik tok"},
                {"messenger", "میسنجر", "مسینجر", "मैसेंजर"},
                {"snapchat", "سنیپ چیٹ", "स्नैपचैट", "snap chat"},
                {"pinterest", "پنٹرسٹ", "پینٹرسٹ", "पिंटरेस्ट"},
                {"canva", "کینوا", "कैनवा"},
                {"capcut", "کیپ کٹ", "कैपकट", "cap cut"},
                {"spotify", "اسپاٹیفائی", "स्पॉटिफाई"},
                {"gmail", "جی میل", "जीमेल", "g mail"},
                {"chrome", "کروم", "क्रोम"},
                {"maps", "گوگل میپس", "میپس", "गूगल मैप्स", "मैप्स"}
        };
        for (String[] group : aliases) {
            for (int i = 1; i < group.length; i++) {
                if (value.contains(CommandEngine.normalize(group[i]))) return group[0];
            }
        }
        return value;
    }

    private static String replaceIgnoreCase(String source, String target, String replacement) {
        if (target.isEmpty()) return source;
        String lowerSource = source.toLowerCase(Locale.ROOT);
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        int from = 0;
        int index = lowerSource.indexOf(lowerTarget, from);
        if (index < 0) return source;

        StringBuilder output = new StringBuilder(source.length() + replacement.length());
        while (index >= 0) {
            output.append(source, from, index).append(replacement);
            from = index + target.length();
            index = lowerSource.indexOf(lowerTarget, from);
        }
        output.append(source.substring(from));
        return output.toString();
    }

    private static Map<String, String> buildReplacements() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();

        // Longer app names first.
        add(map, "واٹس ایپ بزنس", "واٹس ایپ بزنس");
        add(map, "व्हाट्सएप बिजनेस", "واٹس ایپ بزنس");
        add(map, "whatsapp business", "واٹس ایپ بزنس");

        addMany(map, "واٹس ایپ",
                "व्हाट्सएप", "व्हाट्स ऐप", "वॉट्सऐप", "वॉट्सएप", "वाट्सएप",
                "واٹسپ", "واٹساپ", "واٹس اپ", "وٹس ایپ", "وٹس اپ", "واٹسایپ",
                "what's app", "whats app", "watsapp", "whatsap", "whatsup", "what sap");
        addMany(map, "فیس بک", "फेसबुक", "فیسبک", "face book");
        addMany(map, "انسٹاگرام", "इंस्टाग्राम", "انسٹا گرام", "insta gram");
        addMany(map, "یوٹیوب", "यूट्यूब", "یو ٹیوب", "you tube");
        addMany(map, "ٹیلیگرام", "टेलीग्राम", "ٹیلی گرام", "tele gram");
        addMany(map, "ٹک ٹاک", "टिकटॉक", "ٹک ٹوک", "tik tok");
        addMany(map, "میسنجر", "मैसेंजर", "مسینجر");
        addMany(map, "سنیپ چیٹ", "स्नैपचैट", "snap chat");
        addMany(map, "پنٹرسٹ", "पिंटरेस्ट", "پینٹرسٹ");
        addMany(map, "کینوا", "कैनवा");
        addMany(map, "کیپ کٹ", "कैपकट", "cap cut");
        addMany(map, "اسپاٹیفائی", "स्पॉटिफाई");
        addMany(map, "جی میل", "जीमेल", "g mail");
        addMany(map, "کروم", "क्रोम");
        addMany(map, "گوگل میپس", "गूगल मैप्स");

        // Wake word variants.
        addMany(map, "وزیر",
                "वज़ीर", "वजीर", "वज़ीर",
                "wazir", "wazeer", "vazir", "vazeer");
        addMany(map, "چنٹو", "चिंटू", "चिन्टू", "चिंटु", "चिन्टु", "chintoo", "chinto");

        // Common Hindi and Roman command words.
        addMany(map, "یہ ہے", "यह है", "ये है");
        addMany(map, "جی", "जी");
        addMany(map, "جلدی کرو", "जल्दी करो", "jaldi karo");
        addMany(map, "اس کو", "उसको", "उस को", "usko", "isko");
        addMany(map, "کھولو", "खोलो", "खोल दो", "ओपन करो", "open karo", "open kar do");
        addMany(map, "چلاؤ", "चलाओ", "चला दो", "launch karo");
        addMany(map, "نیچے سکرول کرو", "नीचे स्क्रॉल करो", "स्क्रॉल डाउन", "scroll down", "नीचे scroll करो");
        addMany(map, "اوپر سکرول کرو", "ऊपर स्क्रॉल करो", "स्क्रॉल अप", "scroll up", "ऊपर scroll करो");
        addMany(map, "بائیں سوائپ کرو", "बाएं स्वाइप करो", "बायीं तरफ स्वाइप", "swipe left");
        addMany(map, "دائیں سوائپ کرو", "दाएं स्वाइप करो", "दायीं तरफ स्वाइप", "swipe right");
        addMany(map, "ٹائپ کرو", "टाइप करो", "type karo");
        addMany(map, "لکھو", "लिखो", "लिख दो", "write karo");
        addMany(map, "کلک کرو", "क्लिक करो", "टैप करो", "tap karo");
        addMany(map, "پوسٹ کرو", "पोस्ट करो", "post karo");
        addMany(map, "تصدیق کرو", "कन्फर्म करो", "confirm karo");
        addMany(map, "واپس جاؤ", "वापस जाओ", "go back");
        addMany(map, "ہوم اسکرین", "होम स्क्रीन");
        addMany(map, "میسج کرو", "मैसेज करो", "message karo");
        addMany(map, "کال کرو", "कॉल करो", "call karo");
        addMany(map, "بند کرو", "बंद करो", "ऑफ करो", "off karo");
        addMany(map, "آن کرو", "ऑन करो", "on karo");
        addMany(map, "اور پھر", "और फिर", "and then");
        addMany(map, "اور", "और", "aur", "and");
        addMany(map, "پھر", "फिर", "then");

        return map;
    }

    private static void add(Map<String, String> map, String source, String target) {
        map.put(source, target);
    }

    private static void addMany(Map<String, String> map, String canonical, String... variants) {
        for (String variant : variants) map.put(variant, canonical);
    }
}
