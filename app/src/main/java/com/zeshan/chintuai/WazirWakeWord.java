package com.zeshan.chintuai;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Urdu, Hindi and Roman spellings accepted for the Wazir wake word. */
public final class WazirWakeWord {
    private static final List<String> FORMS = Arrays.asList(
            "وزیر", "وزير", "وازیر", "وازير", "بزیر", "بزير",
            "wazir", "wazeer", "vazir", "vazeer",
            "वज़ीर", "वजीर", "वज़ीर");

    private static final Pattern PREFIX_PATTERN = Pattern.compile(
            "(?iu)^(وزیر|وزير|وازیر|وازير|بزیر|بزير|wazir|wazeer|vazir|vazeer|वज़ीर|वजीर|वज़ीर)"
                    + "(?:\\s+(?:جی|سنو|بھائی|ذیشان|sir|जी))?\\s*[،,:-]?\\s*");

    private WazirWakeWord() {
    }

    public static boolean startsWithWakeWord(String raw) {
        String normalized = normalized(raw);
        if (normalized.isEmpty()) return false;
        for (String form : FORMS) {
            String key = normalized(form);
            if (normalized.equals(key) || normalized.startsWith(key + " ")) return true;
        }
        return false;
    }

    public static String strip(String raw) {
        String canonical = AccentCommandNormalizer.canonicalize(raw == null ? "" : raw).trim();
        return PREFIX_PATTERN.matcher(canonical).replaceFirst("").trim();
    }

    public static boolean isExactStopCommand(String raw) {
        String value = normalized(strip(raw));
        return value.equals("ہینڈز فری بند کرو")
                || value.equals("ہینڈ فری بند کرو")
                || value.equals("مسلسل سننا بند کرو")
                || value.equals("بند ہو جاؤ")
                || value.equals("بند ہو جاو")
                || value.equals("hands free off")
                || value.equals("stop listening");
    }

    private static String normalized(String raw) {
        return CommandEngine.normalize(AccentCommandNormalizer.canonicalize(
                raw == null ? "" : raw));
    }
}
