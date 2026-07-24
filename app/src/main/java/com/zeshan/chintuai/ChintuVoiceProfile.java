package com.zeshan.chintuai;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.Locale;
import java.util.Set;

/** Selects the best available Indian-Urdu voice and applies a deeper Jarvis-style profile. */
public final class ChintuVoiceProfile {
    private ChintuVoiceProfile() {
    }

    public static final class Selection {
        public final boolean configured;
        public final String voiceName;
        public final String localeTag;
        public final boolean indianUrdu;
        public final boolean maleHint;

        Selection(boolean configured, String voiceName, String localeTag,
                  boolean indianUrdu, boolean maleHint) {
            this.configured = configured;
            this.voiceName = voiceName == null ? "" : voiceName;
            this.localeTag = localeTag == null ? "" : localeTag;
            this.indianUrdu = indianUrdu;
            this.maleHint = maleHint;
        }
    }

    public static Selection configure(TextToSpeech tts) {
        if (tts == null) return new Selection(false, "", "", false, false);

        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        Set<Voice> voices = tts.getVoices();
        if (voices != null) {
            for (Voice voice : voices) {
                int score = score(voice);
                if (score > bestScore) {
                    best = voice;
                    bestScore = score;
                }
            }
        }

        boolean configured = false;
        String voiceName = "";
        String localeTag = "";
        boolean indianUrdu = false;
        boolean maleHint = false;

        if (best != null && isUrdu(best.getLocale())) {
            configured = tts.setVoice(best) == TextToSpeech.SUCCESS;
            voiceName = best.getName();
            localeTag = best.getLocale().toLanguageTag();
            indianUrdu = "IN".equalsIgnoreCase(best.getLocale().getCountry());
            maleHint = looksMale(best.getName());
        }

        if (!configured) {
            Locale indianUrduLocale = new Locale("ur", "IN");
            int result = tts.setLanguage(indianUrduLocale);
            if (result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                configured = true;
                localeTag = indianUrduLocale.toLanguageTag();
                indianUrdu = true;
            }
        }

        if (!configured) {
            Locale pakistanUrdu = new Locale("ur", "PK");
            int result = tts.setLanguage(pakistanUrdu);
            if (result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                configured = true;
                localeTag = pakistanUrdu.toLanguageTag();
            }
        }

        if (!configured) {
            Locale genericUrdu = new Locale("ur");
            int result = tts.setLanguage(genericUrdu);
            if (result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                configured = true;
                localeTag = genericUrdu.toLanguageTag();
            }
        }

        if (!configured) {
            tts.setLanguage(Locale.getDefault());
            Voice current = tts.getVoice();
            if (current != null) {
                voiceName = current.getName();
                localeTag = current.getLocale().toLanguageTag();
                maleHint = looksMale(voiceName);
            }
        }

        // Android voices do not expose a reliable gender field. A lower pitch makes the selected
        // Urdu voice sound deeper while preserving intelligibility.
        tts.setSpeechRate(0.96f);
        tts.setPitch(0.72f);
        return new Selection(configured, voiceName, localeTag, indianUrdu, maleHint);
    }

    private static int score(Voice voice) {
        if (voice == null || voice.getLocale() == null) return Integer.MIN_VALUE;
        Locale locale = voice.getLocale();
        int score = 0;
        if (isUrdu(locale)) score += 1_000;
        else return -1_000;

        String country = locale.getCountry();
        if ("IN".equalsIgnoreCase(country)) score += 500;
        else if ("PK".equalsIgnoreCase(country)) score += 350;
        else score += 150;

        String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
        if (looksMale(name)) score += 180;
        if (looksFemale(name)) score -= 220;
        if (!voice.isNetworkConnectionRequired()) score += 35;
        score += Math.max(0, voice.getQuality()) / 10;
        score -= Math.max(0, voice.getLatency()) / 20;
        return score;
    }

    private static boolean isUrdu(Locale locale) {
        return locale != null && "ur".equalsIgnoreCase(locale.getLanguage());
    }

    private static boolean looksMale(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("male")
                || value.contains("masculine")
                || value.contains("-m-")
                || value.contains("_m_")
                || value.endsWith("-m")
                || value.endsWith("_m");
    }

    private static boolean looksFemale(String name) {
        if (name == null) return false;
        String value = name.toLowerCase(Locale.ROOT);
        return value.contains("female")
                || value.contains("feminine")
                || value.contains("-f-")
                || value.contains("_f_")
                || value.endsWith("-f")
                || value.endsWith("_f");
    }
}
