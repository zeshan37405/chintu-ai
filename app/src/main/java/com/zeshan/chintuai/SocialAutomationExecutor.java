package com.zeshan.chintuai;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;

/** App-opening and delayed screen actions for common social-media voice workflows. */
public final class SocialAutomationExecutor {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SocialAutomationExecutor() {
    }

    public static BackgroundCommandExecutor.Result tryExecute(Context context, String raw) {
        String canonical = AccentCommandNormalizer.canonicalize(raw);
        String normalized = CommandEngine.normalize(canonical);
        if (normalized.isEmpty()) return null;

        AppCatalog.AppSpec mentionedApp = findMentionedApp(canonical);
        boolean asksOpen = containsAny(normalized,
                "کھولو", "کھول دو", "اوپن", "چلاؤ", "launch", "open");
        boolean asksScrollDown = containsAny(normalized,
                "نیچے سکرول", "سکرول نیچے", "اسکرول ڈاؤن", "scroll down")
                || (containsAny(normalized, "سکرول", "اسکرول", "scroll")
                && !containsAny(normalized, "اوپر", "up"));
        boolean asksScrollUp = containsAny(normalized,
                "اوپر سکرول", "سکرول اوپر", "اسکرول اپ", "scroll up");

        if (mentionedApp != null && asksOpen && (asksScrollDown || asksScrollUp)) {
            if (!ChintuAccessibilityService.isConnected()) {
                return BackgroundCommandExecutor.Result.fail(
                        "سکرول کے لیے Chintu phone control Accessibility سروس آن کریں");
            }
            if (!launchApp(context, mentionedApp)) {
                return BackgroundCommandExecutor.Result.fail(
                        mentionedApp.displayName + " فون میں نہیں ملی");
            }
            boolean down = asksScrollDown && !asksScrollUp;
            MAIN.postDelayed(() -> {
                if (down) ChintuAccessibilityService.scrollDown();
                else ChintuAccessibilityService.scrollUp();
            }, 1_700L);
            MAIN.postDelayed(() -> {
                if (down) ChintuAccessibilityService.scrollDown();
                else ChintuAccessibilityService.scrollUp();
            }, 2_450L);
            return BackgroundCommandExecutor.Result.ok(
                    mentionedApp.displayName + " کھول کر "
                            + (down ? "نیچے" : "اوپر") + " سکرول کر رہا ہوں");
        }

        String postText = extractAfterAny(canonical,
                "فیس بک پر پوسٹ لکھو", "فیس بک پوسٹ لکھو",
                "انسٹاگرام پر پوسٹ لکھو", "انسٹاگرام پوسٹ لکھو",
                "پوسٹ کا متن لکھو", "پوسٹ لکھو", "write post", "type post");
        if (!postText.isEmpty()) {
            if (!ChintuAccessibilityService.isConnected()) {
                return BackgroundCommandExecutor.Result.fail(
                        "پوسٹ لکھنے کے لیے Chintu phone control Accessibility سروس آن کریں");
            }
            AppCatalog.AppSpec social = mentionedApp;
            if (social != null && launchApp(context, social)) {
                MAIN.postDelayed(() -> ChintuAccessibilityService.prepareSocialPost(postText),
                        1_900L);
                return BackgroundCommandExecutor.Result.ok(
                        social.displayName + " کھول کر پوسٹ کا متن لکھ رہا ہوں؛ شائع نہیں کروں گا جب تک آپ تصدیق نہ کریں");
            }
            boolean prepared = ChintuAccessibilityService.prepareSocialPost(postText);
            return prepared
                    ? BackgroundCommandExecutor.Result.ok(
                    "پوسٹ کا متن لکھ دیا ہے؛ شائع کرنے کے لیے الگ تصدیق دیں")
                    : BackgroundCommandExecutor.Result.fail(
                    "پوسٹ لکھنے والی جگہ نہیں ملی؛ پہلے پوسٹ باکس کھولیں");
        }

        String typedText = extractAfterAny(canonical,
                "اور پھر ٹائپ کرو", "پھر ٹائپ کرو", "اور ٹائپ کرو",
                "اور پھر لکھو", "پھر لکھو", "اور لکھو");
        if (mentionedApp != null && asksOpen && !typedText.isEmpty()) {
            if (!ChintuAccessibilityService.isConnected()) {
                return BackgroundCommandExecutor.Result.fail(
                        "ایپ میں لکھنے کے لیے Chintu phone control Accessibility سروس آن کریں");
            }
            if (!launchApp(context, mentionedApp)) {
                return BackgroundCommandExecutor.Result.fail(
                        mentionedApp.displayName + " فون میں نہیں ملی");
            }
            MAIN.postDelayed(() -> ChintuAccessibilityService.typeIntoFocusedField(typedText),
                    1_900L);
            return BackgroundCommandExecutor.Result.ok(
                    mentionedApp.displayName + " کھول کر متن لکھنے کی کوشش کر رہا ہوں");
        }

        if (containsAny(normalized,
                "پوسٹ باکس کھولو", "پوسٹ لکھنے والی جگہ", "کیا سوچ رہے", "what s on your mind")) {
            return ChintuAccessibilityService.focusSocialComposer()
                    ? BackgroundCommandExecutor.Result.ok("پوسٹ لکھنے والی جگہ کھول دی ہے")
                    : BackgroundCommandExecutor.Result.fail("پوسٹ لکھنے والی جگہ نہیں ملی");
        }

        return null;
    }

    private static AppCatalog.AppSpec findMentionedApp(String command) {
        String normalized = AppCatalog.normalizeAppKey(command);
        AppCatalog.AppSpec best = null;
        int bestLength = 0;
        for (AppCatalog.AppSpec app : AppCatalog.all()) {
            String display = AppCatalog.normalizeAppKey(app.displayName);
            if (!display.isEmpty() && normalized.contains(display) && display.length() > bestLength) {
                best = app;
                bestLength = display.length();
            }
            for (String alias : app.aliases) {
                String key = AppCatalog.normalizeAppKey(alias);
                if (!key.isEmpty() && normalized.contains(key) && key.length() > bestLength) {
                    best = app;
                    bestLength = key.length();
                }
            }
        }
        return best;
    }

    private static boolean launchApp(Context context, AppCatalog.AppSpec app) {
        PackageManager manager = context.getPackageManager();
        for (String packageName : app.packageNames) {
            Intent launch = manager.getLaunchIntentForPackage(packageName);
            if (launch != null) {
                try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    context.startActivity(launch);
                    return true;
                } catch (RuntimeException ignored) {
                    // Continue to launcher-label matching.
                }
            }
        }

        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = manager.queryIntentActivities(query, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo info : activities) {
            if (info.activityInfo == null) continue;
            CharSequence label = info.loadLabel(manager);
            if (label == null) continue;
            int score = AppCatalog.scoreName(app.displayName, label.toString());
            for (String alias : app.aliases) {
                score = Math.max(score, AppCatalog.scoreName(alias, label.toString()));
            }
            if (score > bestScore) {
                best = info;
                bestScore = score;
            }
        }
        if (best == null || bestScore < 70) return false;
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(
                            best.activityInfo.packageName, best.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(launch);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String extractAfterAny(String source, String... markers) {
        if (source == null) return "";
        String lower = source.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            int index = lower.indexOf(marker.toLowerCase(Locale.ROOT));
            if (index < 0) continue;
            String value = source.substring(index + marker.length()).trim();
            value = value.replaceFirst("(?iu)^(کہ|یہ|متن|text)\\s+", "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static boolean containsAny(String text, String... values) {
        return CommandEngine.containsAny(text, values);
    }
}
