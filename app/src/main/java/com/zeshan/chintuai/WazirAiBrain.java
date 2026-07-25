package com.zeshan.chintuai;

import android.content.Context;

import java.io.IOException;

/** Gemini-first command brain with an explicit local fallback. */
public final class WazirAiBrain {
    public static final class Execution {
        public final BackgroundCommandExecutor.Result result;
        public final boolean usedGemini;
        public final String mode;
        public final String diagnostic;

        Execution(BackgroundCommandExecutor.Result result, boolean usedGemini,
                  String mode, String diagnostic) {
            this.result = result;
            this.usedGemini = usedGemini;
            this.mode = mode == null ? "" : mode;
            this.diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    private WazirAiBrain() {
    }

    public static Execution execute(Context context, String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.trim();
        if (command.isEmpty()) {
            return new Execution(
                    BackgroundCommandExecutor.Result.fail("کمانڈ خالی ہے"),
                    false, "Local", "empty transcript");
        }

        if (!WazirSecretStore.hasGeminiApiKey(context)) {
            BackgroundCommandExecutor.Result local =
                    BackgroundCommandExecutor.execute(context, command);
            return new Execution(local, false, "Local rules",
                    "Gemini API key not configured");
        }

        try {
            GeminiActionPlan plan = GeminiActionPlanner.plan(context, command);
            BackgroundCommandExecutor.Result result =
                    StructuredActionExecutor.execute(context, command, plan);
            return new Execution(result, true,
                    "Gemini " + GeminiActionPlanner.MODEL,
                    "structured actions: " + plan.actions.size());
        } catch (IOException | RuntimeException error) {
            BackgroundCommandExecutor.Result local =
                    BackgroundCommandExecutor.execute(context, command);
            String reason = error.getMessage() == null
                    ? "Gemini connection failed" : error.getMessage().trim();
            String message = local.message;
            if (message == null || message.trim().isEmpty()) message = reason;
            else message = "Gemini دستیاب نہیں تھا؛ local mode: " + message;
            BackgroundCommandExecutor.Result fallback = new BackgroundCommandExecutor.Result(
                    local.handled, message, local.stopHandsFree);
            return new Execution(fallback, false, "Local fallback", reason);
        }
    }
}
