package com.zeshan.chintuai;

/** Pure helpers for merging Gemini Live transcription updates and enforcing the Wazir wake word. */
public final class LiveTranscriptGate {
    private LiveTranscriptGate() {
    }

    public static String merge(String current, String update) {
        String existing = normalizeSpacing(current);
        String incoming = normalizeSpacing(update);
        if (incoming.isEmpty()) return existing;
        if (existing.isEmpty()) return incoming;
        if (incoming.equals(existing)) return existing;
        if (incoming.startsWith(existing)) return incoming;
        if (existing.startsWith(incoming) || existing.endsWith(incoming)) return existing;
        if (incoming.endsWith(existing)) return incoming;
        return normalizeSpacing(existing + " " + incoming);
    }

    public static boolean hasWakeWord(String transcript) {
        String value = AccentCommandNormalizer.canonicalize(transcript == null ? "" : transcript);
        return WazirWakeWord.startsWithWakeWord(value);
    }

    public static String commandAfterWakeWord(String transcript) {
        String value = AccentCommandNormalizer.canonicalize(transcript == null ? "" : transcript);
        return WazirWakeWord.startsWithWakeWord(value) ? WazirWakeWord.strip(value) : value.trim();
    }

    public static boolean isUsableCommand(String transcript, boolean directMode) {
        String value = transcript == null ? "" : transcript.trim();
        if (value.length() < 2) return false;
        if (!directMode && !hasWakeWord(value)) return false;
        String command = directMode ? value : commandAfterWakeWord(value);
        return command.length() >= 2 && !VoiceTurnPolicy.isNoise(command)
                && !VoiceTurnPolicy.isFixedSelfEcho(command);
    }

    private static String normalizeSpacing(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
