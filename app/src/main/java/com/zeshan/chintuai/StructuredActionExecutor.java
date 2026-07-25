package com.zeshan.chintuai;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Validates and executes Gemini-planned actions through Wazir's existing Android safety layer. */
public final class StructuredActionExecutor {
    private static final int MAX_ACTIONS = 6;

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
                // Give Android and the target app enough time to expose its Accessibility tree.
                SystemClock.sleep(1_350L);
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
                return BackgroundCommandExecutor.execute(context,
                        "up".equals(action.direction) || "اوپر".equals(action.direction)
                                ? "اوپر سکرول کرو" : "نیچے سکرول کرو");
            case SWIPE:
                if ("left".equals(action.direction) || "بائیں".equals(action.direction)) {
                    return BackgroundCommandExecutor.execute(context, "بائیں سوائپ کرو");
                }
                return BackgroundCommandExecutor.execute(context, "دائیں سوائپ کرو");
            case TYPE_TEXT:
                if (action.text.isEmpty()) return fail("ٹائپ کرنے کا متن موجود نہیں");
                return BackgroundCommandExecutor.execute(context, "ٹائپ کرو " + action.text);
            case CLICK_TEXT:
                if (action.target.isEmpty()) return fail("کلک کرنے کا بٹن موجود نہیں");
                return BackgroundCommandExecutor.execute(context, "کلک کرو " + action.target);
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
