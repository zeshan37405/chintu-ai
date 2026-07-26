package com.zeshan.chintuai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Immutable structured plan returned by the Gemini function call. */
public final class GeminiActionPlan {
    public enum Type {
        OPEN_APP,
        SCROLL,
        SWIPE,
        TYPE_TEXT,
        CLICK_TEXT,
        BACK,
        HOME,
        RECENTS,
        CALL_CONTACT,
        MESSAGE_CONTACT,
        DRAFT_SOCIAL_POST,
        GOOGLE_SEARCH,
        STAGE_SUBMIT,
        CONFIRM,
        CANCEL,
        LOCAL_COMMAND,
        SPEAK,
        WAIT
    }

    public static final class Action {
        public final Type type;
        public final String target;
        public final String text;
        public final String direction;
        public final String query;
        public final long delayMs;

        Action(Type type, String target, String text, String direction,
               String query, long delayMs) {
            this.type = type;
            this.target = safe(target, 160);
            this.text = safe(text, 2000);
            this.direction = safe(direction, 24).toLowerCase(Locale.ROOT);
            this.query = safe(query, 500);
            this.delayMs = Math.max(0L, Math.min(5_000L, delayMs));
        }
    }

    public final String reply;
    public final List<Action> actions;

    private GeminiActionPlan(String reply, List<Action> actions) {
        this.reply = safe(reply, 700);
        this.actions = Collections.unmodifiableList(actions);
    }

    public static GeminiActionPlan fromFunctionArgs(JSONObject args) {
        if (args == null) return new GeminiActionPlan("", Collections.emptyList());
        String reply = args.optString("reply", "");
        JSONArray values = args.optJSONArray("actions");
        List<Action> actions = new ArrayList<>();
        if (values != null) {
            for (int i = 0; i < values.length() && actions.size() < 8; i++) {
                JSONObject value = values.optJSONObject(i);
                if (value == null) continue;
                Type type;
                try {
                    type = Type.valueOf(value.optString("type", "SPEAK")
                            .trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException error) {
                    continue;
                }
                actions.add(new Action(
                        type,
                        value.optString("target", ""),
                        value.optString("text", ""),
                        value.optString("direction", ""),
                        value.optString("query", ""),
                        value.optLong("delay_ms", 0L)));
            }
        }
        return new GeminiActionPlan(reply, actions);
    }

    public static GeminiActionPlan speakOnly(String reply) {
        List<Action> actions = new ArrayList<>();
        actions.add(new Action(Type.SPEAK, "", reply, "", "", 0L));
        return new GeminiActionPlan(reply, actions);
    }

    static GeminiActionPlan local(String reply, List<Action> actions) {
        List<Action> safeActions = actions == null
                ? Collections.emptyList() : new ArrayList<>(actions);
        return new GeminiActionPlan(reply, safeActions);
    }

    private static String safe(String value, int maxLength) {
        String safe = value == null ? "" : value.trim();
        if (safe.length() <= maxLength) return safe;
        return safe.substring(0, maxLength).trim();
    }
}
