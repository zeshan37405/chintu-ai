package com.zeshan.chintuai;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Validates and executes Gemini-planned actions through Wazir's Android safety layer. */
public final class StructuredActionExecutor {
    private static final int MAX_ACTIONS = 6;
    private static final long APP_WINDOW_TIMEOUT_MS = 6_000L;

    private StructuredActionExecutor() {
    }

    public static BackgroundCommandExecutor.Result execute(
            Context context, String originalCommand, GeminiActionPlan plan) {
        if (plan == null) {
            return BackgroundCommandExecutor.Result.fail("Gemini کا action plan خالی تھا");
        }
        if (containsForbidden(originalCommand)) {
            return BackgroundCommandExecutor.Result.fail(
                    "مالی لین دین، PIN، پاس ورڈ، OTP اور حساس اکاؤنٹ تبدیلیاں بند ہیں");
        }

        List<String> results = new ArrayList<>();
        boolean handledAnything = false;
        boolean stopHandsFree = false;
        int count = 0;

        for (GeminiActionPlan.Action action : plan.actions) {
            if (action == null || count++ >= MAX_ACTIONS) break;
            if (containsForbidden(action.target + " " + action.text + " " + action.query)) {
                return BackgroundCommandExecutor.Result.fail(
                        "Gemini plan میں حساس مالی یا credential action تھا؛ وزیر نے روک دیا");
            }
            if (action.delayMs > 0L) SystemClock.sleep(action.delayMs);

            long windowBaseline = ChintuAccessibilityService.getLastWindowEventAt();
            BackgroundCommandExecutor.Result result = executeOne(context, originalCommand, action);
            if (result == null) continue;
            if (!result.message.isEmpty()) results.add(result.message);
            handledAnything |= result.handled;
            stopHandsFree |= result.stopHandsFree;

            if (!result.handled && action.type != GeminiActionPlan.Type.SPEAK) {
                String message = join(plan.reply, results);
                return new BackgroundCommandExecutor.Result(false, message, stopHandsFree);
            }

            if (action.type == GeminiActionPlan.Type.OPEN_APP && result.handled) {
                waitForExternalWindow(context, windowBaseline, APP_WINDOW_TIMEOUT_MS);
            }
        }

        String message = join(plan.reply, results);
        if (message.isEmpty()) message = handledAnything
                ? "Gemini action plan مکمل ہو گیا"
                : "Gemini نے کوئی فون action منتخب نہیں کیا";
        return new BackgroundCommandExecutor.Result(
                handledAnything || !plan.reply.isEmpty(), message, stopHandsFree);
    }

    private static BackgroundCommandExecutor.Result executeOne(
            Context context, String originalCommand, GeminiActionPlan.Action action) {
        switch (action.type) {
            case OPEN_APP:
                if (action.target.isEmpty()) return fail("ایپ کا نام موجود نہیں");
                return BackgroundCommandExecutor.execute(context, action.target + " کھولو");
            case SCROLL:
                return repeatedScroll("up".equals(action.direction)
                        || "اوپر".equals(action.direction));
            case SWIPE:
                return repeatedSwipe("left".equals(action.direction)
                        || "بائیں".equals(action.direction));
            case TYPE_TEXT:
                if (action.text.isEmpty()) return fail("ٹائپ کرنے کا متن موجود نہیں");
                return BackgroundCommandExecutor.execute(context, "ٹائپ کرو " + action.text);
            case CLICK_TEXT:
                if (action.target.isEmpty()) return fail("کلک کرنے کا بٹن موجود نہیں");
                return retryVisibleClick(action.target);
            case BACK:
                return BackgroundCommandExecutor.execute(context, "واپس جاؤ");
            case HOME:
                return BackgroundCommandExecutor.execute(context, "ہوم اسکرین");
            case RECENTS:
                return BackgroundCommandExecutor.execute(context, "حالیہ ایپس کھولو");
            case CALL_CONTACT:
                if (action.target.isEmpty()) return fail("کال کے لیے رابطہ موجود نہیں");
                return BackgroundCommandExecutor.execute(context, action.target + " کو کال کرو");
            case MESSAGE_CONTACT:
                if (action.target.isEmpty()) return fail("میسج کے لیے رابطہ موجود نہیں");
                return BackgroundCommandExecutor.execute(context, action.target + " کو میسج کرو");
            case DRAFT_SOCIAL_POST:
                if (action.text.isEmpty()) return fail("پوسٹ کا متن موجود نہیں");
                String app = action.target.isEmpty() ? "فیس بک" : action.target;
                return BackgroundCommandExecutor.execute(
                        context, app + " پر پوسٹ لکھو " + action.text);
            case GOOGLE_SEARCH:
                String query = action.query.isEmpty() ? action.text : action.query;
                if (query.isEmpty()) return fail("سرچ عبارت موجود نہیں");
                return BackgroundCommandExecutor.execute(
                        context, "گوگل پر تلاش کرو " + query);
            case STAGE_SUBMIT:
                return BackgroundCommandExecutor.execute(context,
                        action.target.toLowerCase().contains("send")
                                || action.target.contains("بھیج")
                                ? "بھیج دو" : "پوسٹ کرو");
            case CONFIRM:
                return BackgroundCommandExecutor.execute(context, "تصدیق کرو");
            case CANCEL:
                return BackgroundCommandExecutor.execute(context, "منسوخ کرو");
            case LOCAL_COMMAND:
                String local = action.query.isEmpty() ? originalCommand : action.query;
                if (local == null || local.trim().isEmpty()) return fail("Local command خالی ہے");
                return BackgroundCommandExecutor.execute(context, local);
            case SPEAK:
                String speech = action.text.isEmpty() ? action.query : action.text;
                return BackgroundCommandExecutor.Result.ok(speech);
            case WAIT:
                SystemClock.sleep(action.delayMs > 0L ? action.delayMs : 700L);
                return BackgroundCommandExecutor.Result.ok("");
            default:
                return fail("یہ structured action دستیاب نہیں");
        }
    }

    private static BackgroundCommandExecutor.Result repeatedScroll(boolean up) {
        if (!ChintuAccessibilityService.isConnected()) {
            return fail("سکرول کے لیے Wazir phone control Accessibility آن کریں");
        }
        boolean accepted = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean value = up
                    ? ChintuAccessibilityService.scrollUp()
                    : ChintuAccessibilityService.scrollDown();
            accepted |= value;
            if (attempt < 2) SystemClock.sleep(attempt == 0 ? 800L : 650L);
        }
        if (!accepted) return fail(up ? "اوپر سکرول نہیں ہوا" : "نیچے سکرول نہیں ہوا");
        return BackgroundCommandExecutor.Result.ok(
                up ? "اوپر سکرول کر دیا ہے" : "نیچے سکرول کر دیا ہے");
    }

    private static BackgroundCommandExecutor.Result repeatedSwipe(boolean left) {
        if (!ChintuAccessibilityService.isConnected()) {
            return fail("سوائپ کے لیے Wazir phone control Accessibility آن کریں");
        }
        boolean accepted = left
                ? ChintuAccessibilityService.swipeLeft()
                : ChintuAccessibilityService.swipeRight();
        if (!accepted) return fail(left ? "بائیں سوائپ نہیں ہوا" : "دائیں سوائپ نہیں ہوا");
        return BackgroundCommandExecutor.Result.ok(
                left ? "بائیں سوائپ کر دیا ہے" : "دائیں سوائپ کر دیا ہے");
    }

    private static BackgroundCommandExecutor.Result retryVisibleClick(String target) {
        if (!ChintuAccessibilityService.isConnected()) {
            return fail("کلک کے لیے Wazir phone control Accessibility آن کریں");
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            if (ChintuAccessibilityService.clickByVisibleText(target)) {
                return BackgroundCommandExecutor.Result.ok(target + " دبا دیا ہے");
            }
            SystemClock.sleep(450L + attempt * 200L);
        }
        return fail(target + " اسکرین پر نہیں ملا");
    }

    private static void waitForExternalWindow(Context context, long baseline, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        String ownPackage = context.getPackageName();
        while (SystemClock.uptimeMillis() < deadline) {
            String packageName = ChintuAccessibilityService.getActivePackageName();
            long eventAt = ChintuAccessibilityService.getLastWindowEventAt();
            if (!packageName.isEmpty() && !ownPackage.equals(packageName) && eventAt > baseline) {
                SystemClock.sleep(700L);
                return;
            }
            SystemClock.sleep(160L);
        }
        // The target app may hide package/window events. Still allow a conservative settle period.
        SystemClock.sleep(500L);
    }

    private static BackgroundCommandExecutor.Result fail(String message) {
        return BackgroundCommandExecutor.Result.fail(message);
    }

    private static boolean containsForbidden(String value) {
        return CommandEngine.containsAny(value == null ? "" : value,
                "بینک", "bank", "ایزی پیسہ", "easypaisa", "جاز کیش", "jazzcash",
                "رقم ٹرانسفر", "پیسے بھیجو", "money transfer", "payment", "pay now",
                "خرید", "purchase", "buy now", "آرڈر", "order now",
                "پن", "pin", "پاس ورڈ", "password", "otp", "او ٹی پی",
                "verification code", "سیکیورٹی کوڈ", "account security");
    }

    private static String join(String reply, List<String> results) {
        StringBuilder message = new StringBuilder();
        if (reply != null && !reply.trim().isEmpty()) message.append(reply.trim());
        for (String result : results) {
            if (result == null || result.trim().isEmpty()) continue;
            if (message.length() > 0) message.append(" — ");
            message.append(result.trim());
            if (message.length() > 900) break;
        }
        if (message.length() > 900) return message.substring(0, 900).trim();
        return message.toString();
    }
}
