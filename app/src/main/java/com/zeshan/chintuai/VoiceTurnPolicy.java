package com.zeshan.chintuai;

import java.util.Arrays;
import java.util.List;

/** Pure decision helpers for Wazir's wake/command turn state machine. */
public final class VoiceTurnPolicy {
    private static final List<String> FIXED_SELF_ECHOES = Arrays.asList(
            "جی ذیشان",
            "جی ذیشان حکم کریں",
            "حکم کریں",
            "کمانڈ بولیں",
            "کمانڈ مکمل بولیں",
            "وزیر تیار ہے",
            "کہیں وزیر پھر کمانڈ",
            "وقت ختم ہوا دوبارہ کہیں وزیر");

    private static final List<String> ACTION_WORDS = Arrays.asList(
            "کھولو", "کھول دو", "اوپن", "open", "چلاؤ", "چلا دو",
            "سکرول", "اسکرول", "scroll", "سوائپ", "swipe",
            "ٹائپ", "لکھو", "لکھ دو", "paste", "پیسٹ",
            "کلک", "دباؤ", "دبا دو", "click",
            "کال", "فون", "پیغام", "میسج", "message", "send",
            "واپس", "ہوم", "home", "بند", "آن", "آف",
            "تلاش", "سرچ", "search", "دکھاؤ", "بتاؤ",
            "پوسٹ", "publish", "تصدیق", "confirm");

    private VoiceTurnPolicy() {
    }

    public static String normalize(String raw) {
        String canonical = AccentCommandNormalizer.canonicalize(raw == null ? "" : raw);
        return CommandEngine.normalize(canonical).trim();
    }

    public static boolean isFixedSelfEcho(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return false;
        for (String phrase : FIXED_SELF_ECHOES) {
            String fixed = normalize(phrase);
            if (normalized.equals(fixed)) return true;
            if (normalized.length() >= 4 && fixed.contains(normalized)) return true;
        }
        return false;
    }

    public static boolean matchesRecentSpokenText(String raw, String recentSpokenText) {
        String heard = normalize(raw);
        String spoken = normalize(recentSpokenText);
        if (heard.isEmpty() || spoken.isEmpty()) return false;
        if (heard.equals(spoken)) return true;
        if (heard.length() >= 4 && spoken.contains(heard)) return true;
        return spoken.length() >= 4 && heard.contains(spoken);
    }

    public static boolean isNoise(String raw) {
        String normalized = normalize(raw);
        if (normalized.length() < 2) return true;
        return normalized.equals("جی")
                || normalized.equals("ہاں")
                || normalized.equals("نہیں")
                || normalized.equals("اچھا")
                || normalized.equals("اوکے")
                || normalized.equals("ہم")
                || normalized.equals("ہوں")
                || normalized.equals("اور")
                || normalized.equals("دیکھو")
                || normalized.equals("اور دیکھو")
                || normalized.equals("hello");
    }

    /**
     * Partial text is committed only when it already resembles a complete phone action.
     * Unknown conversational fragments are left open for more words or a final callback.
     */
    public static boolean isLikelyCompleteCommand(String raw) {
        String canonical = AccentCommandNormalizer.canonicalize(raw == null ? "" : raw).trim();
        if (WazirWakeWord.startsWithWakeWord(canonical)) {
            canonical = WazirWakeWord.strip(canonical);
        }
        if (canonical.isEmpty() || isNoise(canonical) || isFixedSelfEcho(canonical)) return false;

        CommandEngine.ParsedCommand parsed = CommandEngine.parse(canonical);
        if (parsed.type != CommandEngine.Type.UNKNOWN) return true;

        AppCatalog.AppMatch app = AppCatalog.findBest(canonical);
        String normalized = normalize(canonical);
        boolean hasAction = false;
        for (String action : ACTION_WORDS) {
            if (normalized.contains(normalize(action))) {
                hasAction = true;
                break;
            }
        }
        if (app != null && hasAction) return true;

        String[] words = normalized.split("\\s+");
        return hasAction && words.length >= 2;
    }
}
