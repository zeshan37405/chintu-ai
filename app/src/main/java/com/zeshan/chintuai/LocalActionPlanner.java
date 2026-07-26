package com.zeshan.chintuai;

import java.util.ArrayList;
import java.util.List;

/** Builds immediate, network-free plans for common app-plus-screen commands. */
public final class LocalActionPlanner {
    private LocalActionPlanner() {
    }

    public static GeminiActionPlan plan(String rawCommand) {
        String command = UrduTranscriptNormalizer.toUrduScript(rawCommand);
        AppCatalog.AppMatch app = AppCatalog.findBest(command);
        if (app == null || app.score < 78) return null;
        if ("Facebook Lite".equals(app.app.displayName)
                && !CommandEngine.containsAny(command, "لائٹ", "lite")) {
            app = AppCatalog.findBest("فیس بک");
        }

        boolean scroll = CommandEngine.containsAny(command,
                "سکرول", "اسکرول", "scroll");
        boolean swipe = CommandEngine.containsAny(command,
                "سوائپ", "swipe");
        if (!scroll && !swipe) return null;

        List<GeminiActionPlan.Action> actions = new ArrayList<>();
        actions.add(new GeminiActionPlan.Action(
                GeminiActionPlan.Type.OPEN_APP,
                app.app.displayName, "", "", "", 0L));

        if (scroll) {
            boolean up = CommandEngine.containsAny(command,
                    "اوپر", "اپ", "upar", "scroll up");
            actions.add(new GeminiActionPlan.Action(
                    GeminiActionPlan.Type.SCROLL,
                    "", "", up ? "up" : "down", "", 0L));
        }
        if (swipe) {
            boolean left = CommandEngine.containsAny(command,
                    "بائیں", "left", "bayen");
            actions.add(new GeminiActionPlan.Action(
                    GeminiActionPlan.Type.SWIPE,
                    "", "", left ? "left" : "right", "", 0L));
        }
        return GeminiActionPlan.local("ٹھیک ہے", actions);
    }
}
