package com.zeshan.chintuai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java Urdu/English command parser shared by button and hands-free voice modes. */
public final class CommandEngine {
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\+?[0-9][0-9\\s-]{6,18}[0-9]");
    private static final Map<String, Integer> NUMBER_WORDS = buildNumberWords();

    private CommandEngine() {
    }

    public enum Type {
        BLOCKED_FINANCIAL,
        HELP,
        HANDS_FREE_ON,
        HANDS_FREE_OFF,
        TIME,
        DATE,
        TORCH_ON,
        TORCH_OFF,
        ALARM,
        TIMER,
        CONTACT_LOOKUP,
        CALL,
        SMS,
        COPY,
        SHARE,
        YOUTUBE_SEARCH,
        WEATHER,
        GOOGLE_SEARCH,
        MAP_SEARCH,
        VOLUME_UP,
        VOLUME_DOWN,
        VOLUME_MUTE,
        VOLUME_MAX,
        MEDIA_PLAY_PAUSE,
        MEDIA_NEXT,
        MEDIA_PREVIOUS,
        MEDIA_STOP,
        BRIGHTNESS_UP,
        BRIGHTNESS_DOWN,
        BRIGHTNESS_MAX,
        GLOBAL_HOME,
        GLOBAL_BACK,
        GLOBAL_RECENTS,
        GLOBAL_NOTIFICATIONS,
        GLOBAL_QUICK_SETTINGS,
        GLOBAL_LOCK,
        GLOBAL_SCREENSHOT,
        OPEN_CONTACTS,
        OPEN_DIALER,
        OPEN_MESSAGES,
        OPEN_GMAIL,
        OPEN_CALCULATOR,
        OPEN_CAMERA,
        OPEN_GALLERY,
        OPEN_WIFI,
        OPEN_BLUETOOTH,
        OPEN_MOBILE_DATA,
        OPEN_SETTINGS,
        OPEN_PLAY_STORE,
        OPEN_CALENDAR,
        OPEN_CLOCK,
        OPEN_FILES,
        OPEN_APP,
        UNKNOWN
    }

    public static final class ParsedCommand {
        public final Type type;
        public final String raw;
        public final String argument;

        ParsedCommand(Type type, String raw, String argument) {
            this.type = type;
            this.raw = raw == null ? "" : raw.trim();
            this.argument = argument == null ? "" : argument.trim();
        }
    }

    public static final class ParsedTime {
        public final int hour;
        public final int minute;

        ParsedTime(int hour, int minute) {
            this.hour = hour;
            this.minute = minute;
        }
    }

    public static ParsedCommand parse(String rawInput) {
        String raw = rawInput == null ? "" : rawInput.trim();
        String command = normalize(stripWakeWord(raw));
        if (command.isEmpty()) return new ParsedCommand(Type.UNKNOWN, raw, "");

        if (containsAny(command,
                "بینک", "bank", "ایزی پیسہ", "easypaisa", "جاز کیش", "jazzcash",
                "رقم ٹرانسفر", "پیسے بھیجو", "ادائیگی کرو", "payment", "transfer money",
                "پاس ورڈ بدل", "password change", "پن بدل", "change pin")) {
            return new ParsedCommand(Type.BLOCKED_FINANCIAL, raw, "");
        }

        if (containsAny(command,
                "ہینڈز فری بند", "ہینڈ فری بند", "ہمیشہ سننا بند", "مائیک بند کرو",
                "hands free off", "stop listening always")) {
            return new ParsedCommand(Type.HANDS_FREE_OFF, raw, "");
        }
        if (containsAny(command,
                "ہینڈز فری چالو", "ہینڈ فری چالو", "ہمیشہ سنو", "مائیک ہمیشہ آن",
                "hands free on", "always listen")) {
            return new ParsedCommand(Type.HANDS_FREE_ON, raw, "");
        }

        if (containsAny(command, "مدد", "کیا کر سکتے ہو", "تمام کمانڈ", "help", "commands")) {
            return new ParsedCommand(Type.HELP, raw, "");
        }

        if (containsAny(command, "ہوم اسکرین", "گھر کی اسکرین", "home screen", "گھر جاؤ")) {
            return new ParsedCommand(Type.GLOBAL_HOME, raw, "");
        }
        if (containsAny(command, "واپس جاؤ", "پیچھے جاؤ", "بیک کرو", "go back")) {
            return new ParsedCommand(Type.GLOBAL_BACK, raw, "");
        }
        if (containsAny(command, "حالیہ ایپس", "ریسنٹ ایپس", "recent apps", "ملٹی ٹاسک")) {
            return new ParsedCommand(Type.GLOBAL_RECENTS, raw, "");
        }
        if (containsAny(command, "نوٹیفکیشن کھولو", "اطلاعات کھولو", "notification shade")) {
            return new ParsedCommand(Type.GLOBAL_NOTIFICATIONS, raw, "");
        }
        if (containsAny(command, "کوئیک سیٹنگ", "فوری سیٹنگ", "quick settings")) {
            return new ParsedCommand(Type.GLOBAL_QUICK_SETTINGS, raw, "");
        }
        if (containsAny(command, "فون لاک", "اسکرین لاک", "lock screen")) {
            return new ParsedCommand(Type.GLOBAL_LOCK, raw, "");
        }
        if (containsAny(command, "اسکرین شاٹ", "screenshot")) {
            return new ParsedCommand(Type.GLOBAL_SCREENSHOT, raw, "");
        }

        if (containsAny(command, "آواز تیز", "والیوم بڑھ", "volume up")) {
            return new ParsedCommand(Type.VOLUME_UP, raw, "");
        }
        if (containsAny(command, "آواز کم", "والیوم کم", "volume down")) {
            return new ParsedCommand(Type.VOLUME_DOWN, raw, "");
        }
        if (containsAny(command, "آواز بند", "والیوم بند", "میوٹ", "mute")) {
            return new ParsedCommand(Type.VOLUME_MUTE, raw, "");
        }
        if (containsAny(command, "آواز فل", "والیوم فل", "زیادہ سے زیادہ آواز", "max volume")) {
            return new ParsedCommand(Type.VOLUME_MAX, raw, "");
        }
        if (containsAny(command, "اگلا گانا", "اگلی ویڈیو", "next song", "next track")) {
            return new ParsedCommand(Type.MEDIA_NEXT, raw, "");
        }
        if (containsAny(command, "پچھلا گانا", "پچھلی ویڈیو", "previous song", "previous track")) {
            return new ParsedCommand(Type.MEDIA_PREVIOUS, raw, "");
        }
        if (containsAny(command, "میوزک بند", "گانا بند", "media stop", "stop music")) {
            return new ParsedCommand(Type.MEDIA_STOP, raw, "");
        }
        if (containsAny(command,
                "گانا چلاؤ", "گانا روکو", "میوزک چلاؤ", "میوزک روکو",
                "پلے کرو", "پاز کرو", "play music", "pause music", "play pause")) {
            return new ParsedCommand(Type.MEDIA_PLAY_PAUSE, raw, "");
        }

        if (containsAny(command, "روشنی تیز", "برائٹنس بڑھ", "brightness up")) {
            return new ParsedCommand(Type.BRIGHTNESS_UP, raw, "");
        }
        if (containsAny(command, "روشنی کم", "برائٹنس کم", "brightness down")) {
            return new ParsedCommand(Type.BRIGHTNESS_DOWN, raw, "");
        }
        if (containsAny(command, "روشنی فل", "برائٹنس فل", "max brightness")) {
            return new ParsedCommand(Type.BRIGHTNESS_MAX, raw, "");
        }

        if (containsAny(command, "کانٹیکٹس کھولو", "رابطے کھولو", "contacts کھولو")) {
            return new ParsedCommand(Type.OPEN_CONTACTS, raw, "");
        }
        if (containsAny(command, "فون کھولو", "ڈائلر کھولو", "dialer کھولو", "open dialer")) {
            return new ParsedCommand(Type.OPEN_DIALER, raw, "");
        }
        if (containsAny(command,
                "میسجز کھولو", "پیغامات کھولو", "sms کھولو", "میسج ایپ کھولو",
                "open messages")) {
            return new ParsedCommand(Type.OPEN_MESSAGES, raw, "");
        }
        if (containsAny(command, "جی میل", "gmail", "ای میل ایپ")) {
            return new ParsedCommand(Type.OPEN_GMAIL, raw, "جی میل");
        }
        if (containsAny(command, "کیلکولیٹر", "calculator")) {
            return new ParsedCommand(Type.OPEN_CALCULATOR, raw, "کیلکولیٹر");
        }
        if (containsAny(command, "کیمرہ", "camera")
                && !containsAny(command, "ٹارچ", "فلیش لائٹ", "flashlight", "torch")) {
            return new ParsedCommand(Type.OPEN_CAMERA, raw, "کیمرہ");
        }
        if (containsAny(command, "گیلری", "فوٹوز", "تصاویر", "photos", "gallery")) {
            return new ParsedCommand(Type.OPEN_GALLERY, raw, "گیلری");
        }
        if (containsAny(command, "وائی فائی", "wifi", "wi fi")) {
            return new ParsedCommand(Type.OPEN_WIFI, raw, "");
        }
        if (containsAny(command, "بلوٹوتھ", "bluetooth")) {
            return new ParsedCommand(Type.OPEN_BLUETOOTH, raw, "");
        }
        if (containsAny(command, "موبائل ڈیٹا", "mobile data", "سم انٹرنیٹ")) {
            return new ParsedCommand(Type.OPEN_MOBILE_DATA, raw, "");
        }
        if (containsAny(command, "کیلنڈر", "calendar")) {
            return new ParsedCommand(Type.OPEN_CALENDAR, raw, "کیلنڈر");
        }
        if (containsAny(command, "گھڑی کھولو", "کلاک کھولو", "clock app")) {
            return new ParsedCommand(Type.OPEN_CLOCK, raw, "گھڑی");
        }
        if (containsAny(command, "فائلز", "فائل مینیجر", "files", "file manager")) {
            return new ParsedCommand(Type.OPEN_FILES, raw, "فائلز");
        }
        if (containsAny(command, "پلے اسٹور", "play store")) {
            return new ParsedCommand(Type.OPEN_PLAY_STORE, raw, "پلے اسٹور");
        }
        if (containsAny(command, "سیٹنگ", "settings")) {
            return new ParsedCommand(Type.OPEN_SETTINGS, raw, "");
        }

        if (containsAny(command, "ٹائمر", "timer")) {
            return new ParsedCommand(Type.TIMER, raw, "");
        }
        if (containsAny(command, "الارم", "alarm")) {
            return new ParsedCommand(Type.ALARM, raw, "");
        }
        if (containsAny(command, "وقت", "ٹائم", "time")) {
            return new ParsedCommand(Type.TIME, raw, "");
        }
        if (containsAny(command, "تاریخ", "آج کون سا دن", "آج کیا تاریخ", "date")) {
            return new ParsedCommand(Type.DATE, raw, "");
        }
        if (containsAny(command, "ٹارچ", "فلیش لائٹ", "flashlight", "torch")) {
            boolean off = containsAny(command, "بند", "آف", "off", "close");
            return new ParsedCommand(off ? Type.TORCH_OFF : Type.TORCH_ON, raw, "");
        }

        if (isContactLookup(command)) {
            return new ParsedCommand(Type.CONTACT_LOOKUP, raw, extractContactName(raw));
        }
        if (containsAny(command,
                "کال کرو", "فون کرو", "کال لگاؤ", "فون لگاؤ", "call", "dial")) {
            String number = extractPhoneNumber(raw);
            return new ParsedCommand(Type.CALL, raw,
                    number.isEmpty() ? extractContactName(raw) : number);
        }
        if (containsAny(command,
                "میسج کرو", "پیغام بھیجو", "ایس ایم ایس کرو", "sms کرو",
                "message", "send sms")) {
            String number = extractPhoneNumber(raw);
            return new ParsedCommand(Type.SMS, raw,
                    number.isEmpty() ? extractContactName(raw) : number);
        }

        if (containsAny(command, "کاپی کرو", "کاپی کریں", "copy")) {
            return new ParsedCommand(Type.COPY, raw,
                    cleanQuery(raw, "کاپی کرو", "کاپی کریں", "copy"));
        }
        if (containsAny(command, "شیئر کرو", "شیئر کریں", "share")) {
            return new ParsedCommand(Type.SHARE, raw,
                    cleanQuery(raw, "شیئر کرو", "شیئر کریں", "share"));
        }

        if (containsAny(command, "یوٹیوب", "youtube")
                && containsAny(command, "تلاش", "سرچ", "search", "پر", "میں")) {
            return new ParsedCommand(Type.YOUTUBE_SEARCH, raw,
                    cleanQuery(raw,
                            "یوٹیوب پر", "یوٹیوب میں", "youtube پر", "youtube میں",
                            "یوٹیوب", "youtube", "تلاش کرو", "تلاش کریں",
                            "سرچ کرو", "سرچ کریں", "search"));
        }

        if (containsAny(command, "موسم", "weather", "درجہ حرارت")) {
            return new ParsedCommand(Type.WEATHER, raw,
                    cleanQuery(raw,
                            "آج کا موسم", "موسم کا حال", "موسم", "weather",
                            "درجہ حرارت", "بتاؤ", "دکھاؤ", "تلاش کرو", "سرچ کرو"));
        }

        if (containsAny(command,
                "راستہ", "لوکیشن", "نقشے میں", "میپس میں", "maps",
                "کہاں ہے", "راستہ دکھاؤ", "گوگل میپس")) {
            return new ParsedCommand(Type.MAP_SEARCH, raw,
                    cleanQuery(raw,
                            "راستہ دکھاؤ", "راستہ بتاؤ", "لوکیشن دکھاؤ",
                            "نقشے میں", "میپس میں", "گوگل میپس", "maps میں",
                            "میپس", "maps", "تلاش کرو", "کہاں ہے", "کھولو"));
        }

        if (containsAny(command,
                "گوگل", "گوجل", "google", "ویب پر", "انٹرنیٹ پر",
                "تلاش کرو", "سرچ کرو", "search")) {
            return new ParsedCommand(Type.GOOGLE_SEARCH, raw,
                    cleanQuery(raw,
                            "گوگل پر", "گوگل میں", "گوگل", "گوجل پر", "گوجل",
                            "google پر", "google میں", "google", "ویب پر", "انٹرنیٹ پر",
                            "تلاش کرو", "تلاش کریں", "سرچ کرو", "سرچ کریں", "search",
                            "دکھاؤ", "بتاؤ"));
        }

        if (containsAny(command, "کھولو", "کھول دو", "اوپن", "open", "چلاؤ", "چلاو", "launch")) {
            return new ParsedCommand(Type.OPEN_APP, raw, extractAppName(raw));
        }

        String known = detectKnownAppName(command);
        if (!known.isEmpty()) return new ParsedCommand(Type.OPEN_APP, raw, known);

        return new ParsedCommand(Type.UNKNOWN, raw, raw);
    }

    public static boolean hasWakeWord(String raw) {
        String text = normalize(raw);
        return text.startsWith("چنٹو") || text.startsWith("چنتو")
                || text.startsWith("chintu");
    }

    public static String stripWakeWord(String raw) {
        String text = normalize(raw);
        return text
                .replaceFirst("^(چنٹو|چنتو|chintu)(\\s+جی|\\s+سنو|\\s+بھائی)?\\s*", "")
                .trim();
    }

    private static boolean isContactLookup(String command) {
        return containsAny(command,
                "فون ڈائریکٹری", "کانٹیکٹ", "کانٹیکٹس", "رابطہ", "رابطوں")
                || (containsAny(command,
                "نمبر نکالو", "نمبر تلاش کرو", "نمبر ڈھونڈو",
                "نمبر دکھاؤ", "نمبر بتاؤ")
                && extractPhoneNumber(command).isEmpty());
    }

    public static int scoreRecognitionCandidate(String raw) {
        ParsedCommand parsed = parse(raw);
        int score = Math.min(raw == null ? 0 : raw.length(), 80) / 4;
        if (parsed.type != Type.UNKNOWN) score += 45;
        if (parsed.type == Type.CALL || parsed.type == Type.SMS
                || parsed.type == Type.CONTACT_LOOKUP) score += 18;
        if (parsed.type == Type.WEATHER || parsed.type == Type.GOOGLE_SEARCH
                || parsed.type == Type.YOUTUBE_SEARCH || parsed.type == Type.MAP_SEARCH) score += 14;
        if (hasWakeWord(raw)) score += 12;
        if (!parsed.argument.isEmpty()) score += Math.min(12, parsed.argument.length());
        return score;
    }

    public static String chooseBest(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        String best = values.get(0) == null ? "" : values.get(0);
        int bestScore = scoreRecognitionCandidate(best);
        for (String value : values) {
            if (value == null) continue;
            int score = scoreRecognitionCandidate(value);
            if (score > bestScore) {
                best = value;
                bestScore = score;
            }
        }
        return best.trim();
    }

    public static String normalize(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ي', 'ی')
                .replace('ى', 'ی')
                .replace('ك', 'ک')
                .replace('ۀ', 'ہ')
                .replace('ة', 'ہ');
        return normalizeDigits(normalized)
                .replaceAll("[\\u064B-\\u065F\\u0670]", "")
                .replaceAll("[،,۔.!?؛:()\\[\\]{}\"'`~]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String normalizeDigits(String text) {
        if (text == null) return "";
        String arabic = "٠١٢٣٤٥٦٧٨٩";
        String persian = "۰۱۲۳۴۵۶۷۸۹";
        StringBuilder result = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            int arabicIndex = arabic.indexOf(character);
            int persianIndex = persian.indexOf(character);
            if (arabicIndex >= 0) result.append(arabicIndex);
            else if (persianIndex >= 0) result.append(persianIndex);
            else result.append(character);
        }
        return result.toString();
    }

    public static boolean containsAny(String text, String... phrases) {
        String normalizedText = normalize(text);
        for (String phrase : phrases) {
            if (normalizedText.contains(normalize(phrase))) return true;
        }
        return false;
    }

    public static String extractPhoneNumber(String text) {
        Matcher matcher = PHONE_PATTERN.matcher(normalizeDigits(text));
        if (matcher.find()) return matcher.group().replaceAll("[\\s-]", "");
        return "";
    }

    public static String extractContactName(String raw) {
        String cleaned = normalize(stripWakeWord(raw));
        String[] stopWords = {
                "میری", "میرے", "میرا", "فون", "ڈائریکٹری", "میں", "سے",
                "کا", "کی", "کے", "نمبر", "نکالو", "تلاش", "کرو", "کر", "دو",
                "ڈھونڈو", "دکھاؤ", "بتاؤ", "جس", "پر", "لکھا", "ہے", "کو",
                "کال", "لگاؤ", "فون", "پیغام", "میسج", "بھیجو", "کانٹیکٹ",
                "کانٹیکٹس", "رابطہ", "رابطوں", "نام", "والا", "والی",
                "phone", "directory", "contact", "contacts", "number", "find",
                "show", "call", "dial", "message", "sms", "the", "named", "send"
        };
        Set<String> result = new LinkedHashSet<>();
        outer:
        for (String word : cleaned.split(" ")) {
            if (word.isEmpty()) continue;
            for (String stopWord : stopWords) {
                if (word.equals(normalize(stopWord))) continue outer;
            }
            result.add(word);
        }
        return String.join(" ", result).trim();
    }

    public static String extractAppName(String raw) {
        return cleanQuery(raw,
                "ایپ", "ایپلیکیشن", "app", "application",
                "کھولو", "کھول دو", "اوپن کرو", "اوپن", "open",
                "چلاؤ", "چلاو", "چلا دو", "start", "launch");
    }

    public static String cleanQuery(String raw, String... phrases) {
        String result = normalize(stripWakeWord(raw));
        for (String phrase : phrases) {
            result = result.replace(normalize(phrase), " ");
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    public static ParsedTime parseClockTime(String raw) {
        String text = replaceNumberWords(normalize(raw));
        int hour = -1;
        int minute = 0;
        Matcher colon = Pattern.compile("\\b([0-2]?\\d)\\s*[:.]\\s*([0-5]?\\d)\\b")
                .matcher(text);
        if (colon.find()) {
            hour = parseSafeInt(colon.group(1), -1);
            minute = parseSafeInt(colon.group(2), 0);
        }
        if (hour < 0) hour = findFirstNumber(text);
        if (containsAny(text, "ساڑھے")) {
            minute = 30;
        } else if (containsAny(text, "سوا")) {
            minute = 15;
        } else if (containsAny(text, "پونے") && hour > 0) {
            hour -= 1;
            minute = 45;
        }
        if (hour < 0) return null;
        boolean pm = containsAny(text, "شام", "رات", "دوپہر", "pm");
        boolean am = containsAny(text, "صبح", "فجر", "am");
        if (pm && hour < 12) hour += 12;
        if (am && hour == 12) hour = 0;
        return new ParsedTime(Math.max(0, Math.min(23, hour)),
                Math.max(0, Math.min(59, minute)));
    }

    public static int parseDurationSeconds(String raw) {
        String text = replaceNumberWords(normalize(raw));
        int total = 0;
        total += sumUnits(text, "گھنٹہ|گھنٹے|گھنٹوں|hour|hours", 3600);
        total += sumUnits(text, "منٹ|منٹوں|minute|minutes", 60);
        total += sumUnits(text, "سیکنڈ|سیکنڈز|second|seconds", 1);
        if (total > 0) return total;
        int value = findFirstNumber(text);
        if (value < 0) return 0;
        if (containsAny(text, "گھنٹہ", "گھنٹے", "hour")) return value * 3600;
        if (containsAny(text, "سیکنڈ", "second")) return value;
        return value * 60;
    }

    private static int sumUnits(String text, String unitPattern, int multiplier) {
        int total = 0;
        Matcher matcher = Pattern.compile("(?<!\\d)(\\d{1,4})\\s*(?:" + unitPattern + ")(?!\\p{L})")
                .matcher(text);
        while (matcher.find()) {
            total += Math.max(0, parseSafeInt(matcher.group(1), 0)) * multiplier;
        }
        return total;
    }

    private static String replaceNumberWords(String raw) {
        String normalized = normalize(raw);
        List<String> out = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            Integer number = NUMBER_WORDS.get(token);
            out.add(number == null ? token : String.valueOf(number));
        }
        return String.join(" ", out);
    }

    private static int findFirstNumber(String text) {
        Matcher matcher = Pattern.compile("\\b\\d{1,4}\\b").matcher(text);
        return matcher.find() ? parseSafeInt(matcher.group(), -1) : -1;
    }

    private static int parseSafeInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String detectKnownAppName(String command) {
        String[] names = {
                "یوٹیوب", "youtube", "واٹس ایپ بزنس", "whatsapp business",
                "واٹس ایپ", "whatsapp", "انسٹاگرام", "instagram",
                "فیس بک لائٹ", "facebook lite", "فیس بک", "facebook",
                "میسنجر", "messenger", "ٹک ٹاک", "tiktok", "ٹیلیگرام", "telegram",
                "سنیپ چیٹ", "snapchat", "پنٹرسٹ", "pinterest", "کینوا", "canva",
                "کیپ کٹ", "capcut", "ایکس", "twitter", "threads", "تھریڈز",
                "اسپاٹیفائی", "spotify", "کروم", "chrome", "براؤزر", "browser",
                "میپس", "maps"
        };
        for (String name : names) {
            if (containsAny(command, name)) return name;
        }
        return "";
    }

    private static Map<String, Integer> buildNumberWords() {
        LinkedHashMap<String, Integer> words = new LinkedHashMap<>();
        String[] values = {
                "صفر", "ایک", "دو", "تین", "چار", "پانچ", "چھ", "سات", "آٹھ", "نو",
                "دس", "گیارہ", "بارہ", "تیرہ", "چودہ", "پندرہ", "سولہ", "سترہ", "اٹھارہ", "انیس", "بیس",
                "اکیس", "بائیس", "تئیس", "چوبیس", "پچیس", "چھبیس", "ستائیس", "اٹھائیس", "انتیس", "تیس",
                "اکتیس", "بتیس", "تینتیس", "چونتیس", "پینتیس", "چھتیس", "سینتیس", "اڑتیس", "انتالیس", "چالیس",
                "اکتالیس", "بیالیس", "تینتالیس", "چوالیس", "پینتالیس", "چھیالیس", "سینتالیس", "اڑتالیس", "انچاس", "پچاس",
                "اکیاون", "باون", "ترپن", "چون", "پچپن", "چھپن", "ستاون", "اٹھاون", "انسٹھ", "ساٹھ"
        };
        for (int i = 0; i < values.length; i++) words.put(values[i], i);
        words.put("اٹھ", 8);
        return words;
    }
}
