package com.zeshan.chintuai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executes phone-control commands in the main application process.
 *
 * HandsFreeVoiceService runs in a separate process so a slow Xiaomi speech binder cannot freeze
 * the visible activity. This receiver bridges recognized commands back to the main process where
 * the Accessibility service and Android command executors live.
 */
public final class CommandExecutionReceiver extends BroadcastReceiver {
    public static final String ACTION_EXECUTE =
            "com.zeshan.chintuai.action.EXECUTE_RECOGNIZED_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_REPLY = "reply";
    public static final String RESULT_HANDLED = "handled";
    public static final String RESULT_MESSAGE = "message";
    public static final String RESULT_STOP_HANDS_FREE = "stop_hands_free";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_EXECUTE.equals(intent.getAction())) return;

        String command = intent.getStringExtra(EXTRA_COMMAND);
        ResultReceiver reply = readReply(intent);
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();

        EXECUTOR.execute(() -> {
            BackgroundCommandExecutor.Result result;
            try {
                result = BackgroundCommandExecutor.execute(appContext,
                        command == null ? "" : command.trim());
            } catch (RuntimeException error) {
                result = new BackgroundCommandExecutor.Result(
                        false, "کمانڈ مکمل نہیں ہوئی، دوبارہ کوشش کریں", false);
            }

            if (reply != null) {
                Bundle data = new Bundle();
                data.putBoolean(RESULT_HANDLED, result.handled);
                data.putString(RESULT_MESSAGE, result.message);
                data.putBoolean(RESULT_STOP_HANDS_FREE, result.stopHandsFree);
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
