package com.zeshan.chintuai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executes voice commands in the main application process where the Accessibility service lives.
 * Gemini Live can send an already structured plan; text-only callers still use WazirAiBrain.
 */
public final class CommandExecutionReceiver extends BroadcastReceiver {
    public static final String ACTION_EXECUTE =
            "com.zeshan.chintuai.action.EXECUTE_RECOGNIZED_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_PLAN_JSON = "plan_json";
    public static final String EXTRA_REPLY = "reply";
    public static final String RESULT_HANDLED = "handled";
    public static final String RESULT_MESSAGE = "message";
    public static final String RESULT_STOP_HANDS_FREE = "stop_hands_free";
    public static final String RESULT_AI_MODE = "ai_mode";
    public static final String RESULT_USED_GEMINI = "used_gemini";
    public static final String RESULT_DIAGNOSTIC = "diagnostic";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_EXECUTE.equals(intent.getAction())) return;

        String command = intent.getStringExtra(EXTRA_COMMAND);
        String planJson = intent.getStringExtra(EXTRA_PLAN_JSON);
        ResultReceiver reply = readReply(intent);
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();

        EXECUTOR.execute(() -> {
            WazirAiBrain.Execution execution;
            try {
                String safeCommand = command == null ? "" : command.trim();
                if (planJson != null && !planJson.trim().isEmpty()) {
                    GeminiActionPlan plan = GeminiActionPlan.fromFunctionArgs(
                            new JSONObject(planJson));
                    BackgroundCommandExecutor.Result result =
                            StructuredActionExecutor.execute(appContext, safeCommand, plan);
                    execution = new WazirAiBrain.Execution(
                            result, true, "Gemini Live " + GeminiLiveProtocol.MODEL,
                            "live structured actions: " + plan.actions.size());
                } else {
                    execution = WazirAiBrain.execute(appContext, safeCommand);
                }
            } catch (Exception error) {
                execution = new WazirAiBrain.Execution(
                        new BackgroundCommandExecutor.Result(
                                false, "کمانڈ مکمل نہیں ہوئی، دوبارہ کوشش کریں", false),
                        false, "Error", error.getClass().getSimpleName());
            }

            if (reply != null) {
                Bundle data = new Bundle();
                data.putBoolean(RESULT_HANDLED, execution.result.handled);
                data.putString(RESULT_MESSAGE, execution.result.message);
                data.putBoolean(RESULT_STOP_HANDS_FREE, execution.result.stopHandsFree);
                data.putString(RESULT_AI_MODE, execution.mode);
                data.putBoolean(RESULT_USED_GEMINI, execution.usedGemini);
                data.putString(RESULT_DIAGNOSTIC, execution.diagnostic);
                reply.send(0, data);
            }
            pending.finish();
        });
    }

    private ResultReceiver readReply(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_REPLY, ResultReceiver.class);
        }
        return intent.getParcelableExtra(EXTRA_REPLY);
    }
}
