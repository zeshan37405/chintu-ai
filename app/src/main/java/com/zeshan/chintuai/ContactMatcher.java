package com.zeshan.chintuai;

import java.util.ArrayList;
import java.util.List;

/** Contact-name normalization and fuzzy scoring, isolated for regression tests. */
public final class ContactMatcher {
    private ContactMatcher() {
    }

    public static String normalizeContactKey(String text) {
        String normalized = CommandEngine.normalize(text);
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            if (token.isEmpty()) continue;
            switch (token) {
                case "ہوم":
                case "هوم":
                case "ھوم":
                case "ہم":
                case "گھر":
                    tokens.add("home");
                    break;
                case "موبائل":
                    tokens.add("mobile");
                    break;
                default:
                    tokens.add(token);
                    break;
            }
        }
        return String.join(" ", tokens)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static int score(String requested, String displayName, String typeLabel) {
        String target = normalizeContactKey(requested);
        if (target.isEmpty()) return 0;
        int nameScore = similarity(target, normalizeContactKey(displayName));
        int labelScore = similarity(target, normalizeContactKey(typeLabel));
        // Phone labels such as "Home" are common and must never outrank a real name match.
        return Math.max(nameScore, Math.min(45, labelScore));
    }

    public static int similarity(String target, String candidate) {
        if (target == null || candidate == null || target.isEmpty() || candidate.isEmpty()) return 0;
        if (candidate.equals(target)) return 100;
        if (containsWholeToken(candidate, target)) return 96;
        if (candidate.startsWith(target + " ") || target.startsWith(candidate + " ")) return 92;
        if (candidate.contains(target) || target.contains(candidate)) return 84;

        String[] targetWords = target.split(" ");
        String[] candidateWords = candidate.split(" ");
        int best = 0;
        for (String targetWord : targetWords) {
            for (String candidateWord : candidateWords) {
                if (targetWord.equals(candidateWord)) {
                    best = Math.max(best, 90);
                } else if (targetWord.length() >= 3 && candidateWord.startsWith(targetWord)) {
                    best = Math.max(best, 80);
                } else if (candidateWord.length() >= 3 && targetWord.startsWith(candidateWord)) {
                    best = Math.max(best, 76);
                } else {
                    int distance = levenshtein(targetWord, candidateWord);
                    int max = Math.max(targetWord.length(), candidateWord.length());
                    if (max > 0) best = Math.max(best, 100 - ((distance * 100) / max));
                }
            }
        }
        return best;
    }

    public static String phoneKey(String normalizedNumber, String displayedNumber) {
        String value = normalizedNumber == null || normalizedNumber.trim().isEmpty()
                ? displayedNumber : normalizedNumber;
        return CommandEngine.normalizeDigits(value == null ? "" : value)
                .replaceAll("\\D", "");
    }

    private static boolean containsWholeToken(String text, String token) {
        return (" " + text + " ").contains(" " + token + " ");
    }

    private static int levenshtein(String first, String second) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) previous[j] = j;
        for (int i = 1; i <= first.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int cost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()];
    }
}
