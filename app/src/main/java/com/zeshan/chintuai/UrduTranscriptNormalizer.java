package com.zeshan.chintuai;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Gemini Live's Hindustani transcription into one stable Urdu-script display form.
 *
 * Gemini may choose Devanagari or Roman text for the same spoken Urdu sentence. Commands still
 * need to remain readable and wake-word compatible, so known phrases are canonicalized first,
 * remaining Devanagari is transliterated, and any remaining Latin words are rendered phonetically.
 */
public final class UrduTranscriptNormalizer {
    private static final Map<String, String> LATIN_PHRASES = buildLatinPhrases();
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]+(?:'[A-Za-z]+)?");

    private UrduTranscriptNormalizer() {
    }

    public static String toUrduScript(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";

        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        value = AccentCommandNormalizer.canonicalize(value);
        value = replaceLatinPhrases(value);
        value = transliterateDevanagari(value);
        value = transliterateRemainingLatin(value);

        StringBuilder clean = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isUnsupportedIndic(codePoint)) {
                clean.append(' ');
                continue;
            }
            if (codePoint == ',') clean.append('،');
            else if (codePoint == '.' || codePoint == '।' || codePoint == '॥') clean.append('۔');
            else if (codePoint == '?') clean.append('؟');
            else clean.appendCodePoint(codePoint);
        }
        return clean.toString()
                .replaceAll("\\s+([،۔؟!])", "$1")
                .replaceAll("([،۔؟!]){2,}", "$1")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String replaceLatinPhrases(String source) {
        String result = source;
        for (Map.Entry<String, String> entry : LATIN_PHRASES.entrySet()) {
            Pattern pattern = Pattern.compile(
                    "(?iu)(?<![\\p{L}\\p{N}])"
                            + Pattern.quote(entry.getKey())
                            + "(?![\\p{L}\\p{N}])");
            result = pattern.matcher(result).replaceAll(
                    Matcher.quoteReplacement(entry.getValue()));
        }
        return result;
    }

    private static String transliterateRemainingLatin(String source) {
        Matcher matcher = LATIN_WORD.matcher(source);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(romanWordToUrdu(matcher.group())));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String romanWordToUrdu(String raw) {
        String word = raw.toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder(word.length() * 2);
        for (int index = 0; index < word.length();) {
            String rest = word.substring(index);
            if (rest.startsWith("ch")) {
                output.append('چ');
                index += 2;
            } else if (rest.startsWith("sh")) {
                output.append('ش');
                index += 2;
            } else if (rest.startsWith("kh")) {
                output.append('خ');
                index += 2;
            } else if (rest.startsWith("gh")) {
                output.append('غ');
                index += 2;
            } else if (rest.startsWith("zh")) {
                output.append('ژ');
                index += 2;
            } else if (rest.startsWith("ph")) {
                output.append("پھ");
                index += 2;
            } else if (rest.startsWith("bh")) {
                output.append("بھ");
                index += 2;
            } else if (rest.startsWith("th")) {
                output.append("تھ");
                index += 2;
            } else if (rest.startsWith("dh")) {
                output.append("دھ");
                index += 2;
            } else if (rest.startsWith("aa")) {
                output.append('آ');
                index += 2;
            } else if (rest.startsWith("ee") || rest.startsWith("ie")) {
                output.append('ی');
                index += 2;
            } else if (rest.startsWith("oo")) {
                output.append('و');
                index += 2;
            } else if (rest.startsWith("ai") || rest.startsWith("ay")) {
                output.append('ے');
                index += 2;
            } else {
                char value = word.charAt(index++);
                switch (value) {
                    case 'a': output.append('ا'); break;
                    case 'b': output.append('ب'); break;
                    case 'c': output.append('ک'); break;
                    case 'd': output.append('د'); break;
                    case 'e': output.append('ے'); break;
                    case 'f': output.append('ف'); break;
                    case 'g': output.append('گ'); break;
                    case 'h': output.append('ہ'); break;
                    case 'i': output.append('ی'); break;
                    case 'j': output.append('ج'); break;
                    case 'k': output.append('ک'); break;
                    case 'l': output.append('ل'); break;
                    case 'm': output.append('م'); break;
                    case 'n': output.append('ن'); break;
                    case 'o': output.append('و'); break;
                    case 'p': output.append('پ'); break;
                    case 'q': output.append('ق'); break;
                    case 'r': output.append('ر'); break;
                    case 's': output.append('س'); break;
                    case 't': output.append('ت'); break;
                    case 'u': output.append('و'); break;
                    case 'v':
                    case 'w': output.append('و'); break;
                    case 'x': output.append("کس"); break;
                    case 'y': output.append('ی'); break;
                    case 'z': output.append('ز'); break;
                    default: break;
                }
            }
        }
        return output.toString();
    }

    private static String transliterateDevanagari(String source) {
        StringBuilder output = new StringBuilder(source.length() * 2);
        for (int index = 0; index < source.length();) {
            int codePoint = source.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case 0x0900:
                case 0x0901:
                case 0x0902: output.append('ں'); break;
                case 0x0903: output.append('ہ'); break;
                case 0x0905: output.append('ا'); break;
                case 0x0906: output.append('آ'); break;
                case 0x0907:
                case 0x0908: output.append('ی'); break;
                case 0x0909:
                case 0x090A: output.append('و'); break;
                case 0x090B: output.append("رِ"); break;
                case 0x090F: output.append("اے"); break;
                case 0x0910: output.append("اَے"); break;
                case 0x0913: output.append("او"); break;
                case 0x0914: output.append("اَو"); break;
                case 0x0915: output.append('ک'); break;
                case 0x0916: output.append("کھ"); break;
                case 0x0917: output.append('گ'); break;
                case 0x0918: output.append("گھ"); break;
                case 0x0919: output.append("نگ"); break;
                case 0x091A: output.append('چ'); break;
                case 0x091B: output.append("چھ"); break;
                case 0x091C: output.append('ج'); break;
                case 0x091D: output.append("جھ"); break;
                case 0x091E: output.append('ن'); break;
                case 0x091F: output.append('ٹ'); break;
                case 0x0920: output.append("ٹھ"); break;
                case 0x0921: output.append('ڈ'); break;
                case 0x0922: output.append("ڈھ"); break;
                case 0x0923: output.append('ن'); break;
                case 0x0924: output.append('ت'); break;
                case 0x0925: output.append("تھ"); break;
                case 0x0926: output.append('د'); break;
                case 0x0927: output.append("دھ"); break;
                case 0x0928: output.append('ن'); break;
                case 0x092A: output.append('پ'); break;
                case 0x092B: output.append('ف'); break;
                case 0x092C: output.append('ب'); break;
                case 0x092D: output.append("بھ"); break;
                case 0x092E: output.append('م'); break;
                case 0x092F: output.append('ی'); break;
                case 0x0930: output.append('ر'); break;
                case 0x0932: output.append('ل'); break;
                case 0x0933: output.append('ل'); break;
                case 0x0935: output.append('و'); break;
                case 0x0936:
                case 0x0937: output.append('ش'); break;
                case 0x0938: output.append('س'); break;
                case 0x0939: output.append('ہ'); break;
                case 0x093C: break;
                case 0x093E: output.append('ا'); break;
                case 0x093F:
                case 0x0940: output.append('ی'); break;
                case 0x0941: break;
                case 0x0942: output.append('و'); break;
                case 0x0943:
                case 0x0944: output.append('ر'); break;
                case 0x0947: output.append('ے'); break;
                case 0x0948: output.append("َے"); break;
                case 0x094B: output.append('و'); break;
                case 0x094C: output.append("َو"); break;
                case 0x094D: break;
                case 0x0958: output.append('ق'); break;
                case 0x0959: output.append('خ'); break;
                case 0x095A: output.append('غ'); break;
                case 0x095B: output.append('ز'); break;
                case 0x095C: output.append('ڑ'); break;
                case 0x095D: output.append("ڑھ"); break;
                case 0x095E: output.append('ف'); break;
                case 0x095F: output.append('ی'); break;
                case 0x0964:
                case 0x0965: output.append('۔'); break;
                case 0x0966: output.append('۰'); break;
                case 0x0967: output.append('۱'); break;
                case 0x0968: output.append('۲'); break;
                case 0x0969: output.append('۳'); break;
                case 0x096A: output.append('۴'); break;
                case 0x096B: output.append('۵'); break;
                case 0x096C: output.append('۶'); break;
                case 0x096D: output.append('۷'); break;
                case 0x096E: output.append('۸'); break;
                case 0x096F: output.append('۹'); break;
                default: output.appendCodePoint(codePoint); break;
            }
        }
        return output.toString();
    }

    private static boolean isUnsupportedIndic(int codePoint) {
        return (codePoint >= 0x0900 && codePoint <= 0x0D7F)
                || (codePoint >= 0xA800 && codePoint <= 0xABFF);
    }

    private static Map<String, String> buildLatinPhrases() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("whatsapp business", "واٹس ایپ بزنس");
        map.put("google maps", "گوگل میپس");
        map.put("facebook lite", "فیس بک لائٹ");
        map.put("scroll down", "نیچے سکرول کرو");
        map.put("scroll up", "اوپر سکرول کرو");
        map.put("scroll karo", "سکرول کرو");
        map.put("swipe left", "بائیں سوائپ کرو");
        map.put("swipe right", "دائیں سوائپ کرو");
        map.put("open kar do", "کھول دو");
        map.put("open karo", "کھولو");
        map.put("khol do", "کھول دو");
        map.put("jaldi karo", "جلدی کرو");
        map.put("hands free", "ہینڈز فری");
        map.put("face book", "فیس بک");
        map.put("play store", "پلے اسٹور");
        map.put("snap chat", "سنیپ چیٹ");
        map.put("tik tok", "ٹک ٹاک");
        map.put("you tube", "یوٹیوب");
        map.put("g mail", "جی میل");

        map.put("whatsapp", "واٹس ایپ");
        map.put("facebook", "فیس بک");
        map.put("instagram", "انسٹاگرام");
        map.put("youtube", "یوٹیوب");
        map.put("messenger", "میسنجر");
        map.put("telegram", "ٹیلیگرام");
        map.put("snapchat", "سنیپ چیٹ");
        map.put("pinterest", "پنٹرسٹ");
        map.put("tiktok", "ٹک ٹاک");
        map.put("spotify", "اسپاٹیفائی");
        map.put("capcut", "کیپ کٹ");
        map.put("canva", "کینوا");
        map.put("chrome", "کروم");
        map.put("gmail", "جی میل");
        map.put("gemini", "جیمنی");
        map.put("wazeer", "وزیر");
        map.put("wazir", "وزیر");
        map.put("vazeer", "وزیر");
        map.put("vazir", "وزیر");
        map.put("kholo", "کھولو");
        map.put("karo", "کرو");
        map.put("aur", "اور");
        map.put("usko", "اس کو");
        map.put("isko", "اس کو");
        map.put("jaldi", "جلدی");
        map.put("scroll", "سکرول");
        map.put("swipe", "سوائپ");
        map.put("open", "کھولو");
        map.put("live", "لائیو");
        map.put("gen", "جین");
        map.put("z", "زی");
        return map;
    }
}
