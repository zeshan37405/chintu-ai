package com.zeshan.chintuai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Parses and executes screen-level voice commands. High-impact actions are always staged and
 * require an explicit second confirmation command.
 */
public final class JarvisAutomationExecutor {
    private static final String PREFS = "chintu_preferences";
    private static final String KEY_PENDING_TYPE = "jarvis_pending_type";
    private static final String KEY_PENDING_ARGUMENT = "jarvis_pending_argument";
    private static final String KEY_PENDING_EXPIRES = "jarvis_pending_expires";
    private static final long CONFIRM_WINDOW_MS = 20_000L;

    private JarvisAutomationExecutor() {
    }

    public static BackgroundCommandExecutor.Result tryExecute(Context context, String raw) {
        String normalized = CommandEngine.normalize(CommandEngine.stripWakeWord(raw));
        if (normalized.isEmpty()) return null;

        if (equalsAny(normalized,
                "تصدیق کرو", "کنفرم کرو", "ہاں کر دو", "confirm", "confirm it")) {
            return confirmPending(context);
        }
        if (equalsAny(normalized,
                "منسوخ کرو", "کینسل کرو", "رہنے دو", "cancel", "cancel it")) {
            clearPending(context);
            return BackgroundCommandExecutor.Result.ok("زیرِ انتظار کام منسوخ کر دیا ہے");
        }

        if (containsAny(normalized,
                "نیچے سکرول", "سکرول نیچے", "اسکرول ڈاؤن", "scroll down")) {
            return accessibilityResult(ChintuAccessibilityService.scrollDown(),
                    "نیچے سکرول کر دیا ہے", "نیچے سکرول نہیں ہوا");
        }
        if (containsAny(normalized,
                "اوپر سکرول", "سکرول اوپر", "اسکرول اپ", "scroll up")) {
            return accessibilityResult(ChintuAccessibilityService.scrollUp(),
                    "اوپر سکرول کر دیا ہے", "اوپر سکرول نہیں ہوا");
        }
        if (containsAny(normalized,
                "بائیں سوائپ", "سوائپ بائیں", "swipe left")) {
            return accessibilityResult(ChintuAccessibilityService.swipeLeft(),
                    "بائیں سوائپ کر دیا ہے", "بائیں سوائپ نہیں ہوا");
        }
        if (containsAny(normalized,
                "دائیں سوائپ", "سوائپ دائیں", "swipe right")) {
            return accessibilityResult(ChintuAccessibilityService.swipeRight(),
                    "دائیں سوائپ کر دیا ہے", "دائیں سوائپ نہیں ہوا");
        }

        if (equalsAny(normalized,
                "فیلڈ صاف کرو", "سب مٹا دو", "متن مٹا دو", "clear text", "clear field")) {
            return accessibilityResult(ChintuAccessibilityService.clearFocusedText(),
                    "متن صاف کر دیا ہے", "کوئی لکھنے والی جگہ منتخب نہیں");
        }
        if (equalsAny(normalized,
                "پیسٹ کرو", "چسپاں کرو", "paste", "paste it")) {
            return accessibilityResult(ChintuAccessibilityService.pasteIntoFocusedField(),
                    "متن پیسٹ کر دیا ہے", "پیسٹ کرنے والی جگہ منتخب نہیں");
        }
        if (equalsAny(normalized,
                "انٹر دباؤ", "انٹر کرو", "enter دباؤ", "press enter")) {
            return accessibilityResult(ChintuAccessibilityService.pressEnter(),
                    "انٹر دبا دیا ہے", "انٹر کا عمل نہیں ہوا");
        }

        String typedText = extractAfterAnyPreservingText(raw,
                "یہ ٹائپ کرو", "ٹائپ کرو", "یہ لکھو", "لکھو", "type this", "type", "write this");
        if (!typedText.isEmpty()) {
            return accessibilityResult(ChintuAccessibilityService.typeIntoFocusedField(typedText),
                    "متن لکھ دیا ہے", "پہلے لکھنے والی جگہ منتخب کریں");
        }

        String clickTarget = extractAfterAnyPreservingText(raw,
                "اس پر کلک کرو", "کلک کرو", "اسے دباؤ", "دباؤ", "tap", "click");
        if (!clickTarget.isEmpty()) {
            if (isHighImpactTarget(clickTarget)) {
                savePending(context, "click", clickTarget);
                return BackgroundCommandExecutor.Result.ok(
                        "یہ حساس بٹن ہے۔ بیس سیکنڈ کے اندر کہیں: چنٹو تصدیق کرو");
            }
            return accessibilityResult(ChintuAccessibilityService.clickByVisibleText(clickTarget),
                    clickTarget + " دبا دیا ہے", clickTarget + " اسکرین پر نہیں ملا");
        }

        if (equalsAny(normalized,
                "پوسٹ کر دو", "پوسٹ کرو", "سینڈ کر دو", "بھیج دو",
                "شائع کر دو", "publish", "post it", "send it")) {
            savePending(context, "submit", "");
            return BackgroundCommandExecutor.Result.ok(
                    "پوسٹ یا سینڈ تیار ہے۔ بیس سیکنڈ کے اندر کہیں: چنٹو تصدیق کرو");
        }

        return null;
    }

    private static BackgroundCommandExecutor.Result confirmPending(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long expires = preferences.getLong(KEY_PENDING_EXPIRES, 0L);
        String type = preferences.getString(KEY_PENDING_TYPE, "");
        String argument = preferences.getString(KEY_PENDING_ARGUMENT, "");
        clearPending(context);
        if (type == null || type.isEmpty() || SystemClock.elapsedRealtime() > expires) {
            return BackgroundCommandExecutor.Result.fail("تصدیق کے لیے کوئی تازہ کام موجود نہیں");
        }
        if ("click".equals(type)) {
            return accessibilityResult(ChintuAccessibilityService.clickByVisibleText(argument),
                    argument + " دبا دیا ہے", argument + " اسکرین پر نہیں ملا");
        }
        if ("submit".equals(type)) {
            return accessibilityResult(ChintuAccessibilityService.clickCommonSubmitButton(),
                    "پوسٹ یا سینڈ کر دیا ہے", "پوسٹ یا سینڈ کا بٹن نہیں ملا");
        }
        return BackgroundCommandExecutor.Result.fail("زیرِ انتظار کام قابلِ شناخت نہیں");
    }

    private static void savePending(Context context, String type, String argument) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_TYPE, type)
                .putString(KEY_PENDING_ARGUMENT, argument == null ? "" : argument)
                .putLong(KEY_PENDING_EXPIRES,
                        SystemClock.elapsedRealtime() + CONFIRM_WINDOW_MS)
                .apply();
    }

    private static void clearPending(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_PENDING_TYPE)
                .remove(KEY_PENDING_ARGUMENT)
                .remove(KEY_PENDING_EXPIRES)
                .apply();
    }

    private static BackgroundCommandExecutor.Result accessibilityResult(
            boolean success, String successMessage, String failureMessage) {
        if (!ChintuAccessibilityService.isConnected()) {
            return BackgroundCommandExecutor.Result.fail(
                    "پورے فون کنٹرول کے لیے Chintu Accessibility سروس آن کریں");
        }
        return success
                ? BackgroundCommandExecutor.Result.ok(successMessage)
                : BackgroundCommandExecutor.Result.fail(failureMessage);
    }

    private static String extractAfterAnyPreservingText(String raw, String... markers) {
        if (raw == null) return "";
        String source = raw.trim().replaceFirst(
                "(?iu)^(چنٹو|چنتو|chintu)(\\s+جی|\\s+سنو|\\s+بھائی)?\\s*", "");
        String lowerSource = source.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            String lowerMarker = marker.toLowerCase(Locale.ROOT);
            int index = lowerSource.indexOf(lowerMarker);
            if (index < 0) continue;
            String value = source.substring(index + marker.length()).trim();
            value = value.replaceFirst("(?iu)^(کہ|کہو|یہ|متن|text)\\s+", "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static boolean isHighImpactTarget(String target) {
        String normalized = CommandEngine.normalize(target);
        return containsAny(normalized,
                "پوسٹ", "سینڈ", "بھیجو", "شائع", "ڈیلیٹ", "حذف", "ان انسٹال",
                "خرید", "آرڈر", "ادائیگی", "پے", "لاگ آؤٹ",
                "post", "send", "publish", "delete", "remove", "uninstall",
                "buy", "order", "pay", "logout");
    }

    private static boolean equalsAny(String text, String... values) {
        String normalized = CommandEngine.normalize(text);
        for (String value : values) {
            if (normalized.equals(CommandEngine.normalize(value))) return true;
        }
        return false;
    }

    private static boolean containsAny(String text, String... values) {
        return CommandEngine.containsAny(text, values);
    }
}
