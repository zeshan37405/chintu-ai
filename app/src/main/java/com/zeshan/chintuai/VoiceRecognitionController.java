package com.zeshan.chintuai;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Fast one-shot recognizer for the large voice button. It is pre-warmed while the activity is
 * visible, never opens the Google popup automatically, and silently re-arms when Redmi/HyperOS
 * ends a session too early.
 */
public final class VoiceRecognitionController {
    private static final int MAX_DIRECT_RETRIES = 4;
    private static final long NORMAL_RETRY_MS = 180L;
    private static final long BUSY_RETRY_MS = 850L;
    private static final long START_WATCHDOG_MS = 3200L;
    private static final long LISTEN_WATCHDOG_MS = 45000L;
    private static final long RESULT_WATCHDOG_MS = 8000L;

    public enum State {
        IDLE,
        PREPARING,
        STARTING,
        LISTENING,
        PROCESSING,
        RECOVERING,
        SYSTEM_FALLBACK
    }

    public interface Callback {
        void onVoiceState(State state, String status, String detail);

        void onPartialText(String text);

        void onCommandRecognized(String command);

        void onSystemRecognizerRequested(Intent intent);

        void onVoiceUnavailable(String reason);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Callback callback;

    private SpeechRecognizer recognizer;
    private State state = State.IDLE;
    private int generation;
    private int retryCount;
    private String lastPartial = "";
    private long suppressErrorsUntil;
    private boolean foreground = true;
    private boolean released;

    private Runnable retryRunnable;
    private Runnable startWatchdog;
    private Runnable listenWatchdog;
    private Runnable resultWatchdog;

    public VoiceRecognitionController(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    public State getState() {
        return state;
    }

    public boolean isActive() {
        return state != State.IDLE && state != State.SYSTEM_FALLBACK;
    }

    /** Creates the speech service before the user taps, removing first-tap startup lag. */
    public void prepare() {
        runOnMain(() -> {
            if (released || !foreground || recognizer != null) return;
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return;
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            } catch (RuntimeException ignored) {
                recognizer = null;
            }
        });
    }

    public void setForeground(boolean foreground) {
        runOnMain(() -> {
            this.foreground = foreground;
            if (foreground) {
                prepare();
            } else {
                cancelInternal(false, "");
                destroyRecognizer();
            }
        });
    }

    public void start() {
        runOnMain(() -> {
            if (released) return;
            if (!foreground) {
                callback.onVoiceUnavailable("ایپ سامنے آنے کے بعد دوبارہ کوشش کریں");
                return;
            }
            clearTimers();
            cancelRecognizerOnly();
            retryCount = 0;
            lastPartial = "";
            startSession(0L);
        });
    }

    public void cancel(String detail) {
        runOnMain(() -> cancelInternal(true, detail));
    }

    public void onSystemResult(List<String> matches) {
        runOnMain(() -> {
            if (released) return;
            state = State.IDLE;
            lastPartial = "";
            String best = CommandEngine.chooseBest(matches);
            if (best.isEmpty()) {
                callback.onVoiceUnavailable("آواز واضح نہیں ملی، دوبارہ بولیں یا کمانڈ لکھیں");
            } else {
                callback.onCommandRecognized(best);
            }
        });
    }

    public void onSystemCancelled() {
        runOnMain(() -> {
            state = State.IDLE;
            callback.onVoiceState(State.IDLE, "آواز واضح نہیں ملی",
                    "دوبارہ بولیں یا کمانڈ لکھیں");
        });
    }

    public void onSystemLaunchFailed() {
        runOnMain(() -> {
            state = State.IDLE;
            callback.onVoiceUnavailable("فون میں Google وائس ونڈو دستیاب نہیں");
        });
    }

    public void release() {
        runOnMain(() -> {
            released = true;
            foreground = false;
            cancelInternal(false, "");
            destroyRecognizer();
        });
    }

    private void startSession(long delayMs) {
        clearTimers();
        if (released || !foreground) return;
        final int session = ++generation;
        state = State.PREPARING;
        callback.onVoiceState(state, "سننے کے لیے تیار", "اب بولیں");

        retryRunnable = () -> {
            if (!isCurrent(session)) return;
            if (!ensureRecognizer()) {
                failCompletely("فون کی وائس شناخت سروس دستیاب نہیں");
                return;
            }
            try {
                recognizer.setRecognitionListener(createListener(session));
                state = State.STARTING;
                callback.onVoiceState(state, "سن رہا ہوں", "اب حکم بولیں");
                recognizer.startListening(createRecognitionIntent());
                scheduleStartWatchdog(session);
                scheduleListenWatchdog(session);
            } catch (RuntimeException error) {
                recover(session, "وائس شناخت شروع نہیں ہوئی", SpeechRecognizer.ERROR_CLIENT);
            }
        };
        if (delayMs <= 0L) retryRunnable.run();
        else handler.postDelayed(retryRunnable, delayMs);
    }

    private boolean ensureRecognizer() {
        if (recognizer != null) return true;
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            return true;
        } catch (RuntimeException error) {
            recognizer = null;
            return false;
        }
    }

    private RecognitionListener createListener(final int session) {
        return new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                if (!isCurrent(session)) return;
                removeStartWatchdog();
                state = State.LISTENING;
                callback.onVoiceState(state, "سن رہا ہوں", "آرام سے مکمل کمانڈ بولیں");
            }

            @Override
            public void onBeginningOfSpeech() {
                if (!isCurrent(session)) return;
                removeStartWatchdog();
                state = State.LISTENING;
                callback.onVoiceState(state, "آواز مل گئی", "بولتے رہیں...");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                // Avoid frequent UI work on the Redmi Note 11.
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
                // Not required.
            }

            @Override
            public void onEndOfSpeech() {
                if (!isCurrent(session)) return;
                removeStartWatchdog();
                removeListenWatchdog();
                state = State.PROCESSING;
                callback.onVoiceState(state, "سمجھ رہا ہوں", lastPartial);
                scheduleResultWatchdog(session);
            }

            @Override
            public void onError(int error) {
                if (!isCurrent(session)) return;
                if (SystemClock.uptimeMillis() < suppressErrorsUntil) return;
                if (!lastPartial.trim().isEmpty() && canUsePartial(error)) {
                    finish(session, lastPartial);
                    return;
                }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    cancelInternal(false, "");
                    callback.onVoiceUnavailable("مائیکروفون کی اجازت درکار ہے");
                    return;
                }
                recover(session, errorDetail(error), error);
            }

            @Override
            public void onResults(Bundle results) {
                if (!isCurrent(session)) return;
                ArrayList<String> matches = results == null ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String best = CommandEngine.chooseBest(matches);
                if (best.isEmpty()) best = lastPartial;
                if (best == null || best.trim().isEmpty()) {
                    recover(session, "آواز واضح نہیں ملی", SpeechRecognizer.ERROR_NO_MATCH);
                } else {
                    finish(session, best);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (!isCurrent(session)) return;
                ArrayList<String> matches = partialResults == null ? null
                        : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String best = CommandEngine.chooseBest(matches);
                if (best.isEmpty()) return;
                lastPartial = best;
                removeStartWatchdog();
                state = State.LISTENING;
                callback.onPartialText(best);
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
                // No vendor-specific events required.
            }
        };
    }

    private void finish(int session, String command) {
        if (!isCurrent(session)) return;
        clearTimers();
        generation++;
        state = State.IDLE;
        String cleaned = command == null ? "" : command.trim();
        lastPartial = "";
        if (cleaned.isEmpty()) callback.onVoiceUnavailable("آواز واضح نہیں ملی");
        else callback.onCommandRecognized(cleaned);
    }

    private void recover(int session, String reason, int errorCode) {
        if (!isCurrent(session)) return;
        clearTimers();
        cancelRecognizerOnly();
        generation++;
        if (released || !foreground) {
            state = State.IDLE;
            return;
        }
        if (retryCount < MAX_DIRECT_RETRIES) {
            retryCount++;
            state = State.RECOVERING;
            callback.onVoiceState(state, "سن رہا ہوں", "آواز کا انتظار ہے...");
            if (errorCode == SpeechRecognizer.ERROR_CLIENT
                    || errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                destroyRecognizer();
            }
            startSession(errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    ? BUSY_RETRY_MS : NORMAL_RETRY_MS);
        } else {
            failCompletely(reason + " — دوبارہ بٹن دبائیں یا ہینڈز فری چلائیں");
        }
    }

    private void failCompletely(String reason) {
        clearTimers();
        generation++;
        state = State.IDLE;
        lastPartial = "";
        callback.onVoiceUnavailable(reason);
    }

    public Intent createRecognitionIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 30000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                8000L);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "چنٹو کو مکمل حکم بولیں");
        return intent;
    }

    private void scheduleStartWatchdog(final int session) {
        removeStartWatchdog();
        startWatchdog = () -> {
            if (!isCurrent(session)) return;
            if (!lastPartial.isEmpty()) finish(session, lastPartial);
            else recover(session, "وائس سروس اٹک گئی", SpeechRecognizer.ERROR_CLIENT);
        };
        handler.postDelayed(startWatchdog, START_WATCHDOG_MS);
    }

    private void scheduleListenWatchdog(final int session) {
        removeListenWatchdog();
        listenWatchdog = () -> {
            if (!isCurrent(session)) return;
            if (!lastPartial.isEmpty()) finish(session, lastPartial);
            else recover(session, "آواز نہیں ملی", SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
        };
        handler.postDelayed(listenWatchdog, LISTEN_WATCHDOG_MS);
    }

    private void scheduleResultWatchdog(final int session) {
        removeResultWatchdog();
        resultWatchdog = () -> {
            if (!isCurrent(session)) return;
            if (!lastPartial.isEmpty()) finish(session, lastPartial);
            else recover(session, "نتیجہ نہیں ملا", SpeechRecognizer.ERROR_NO_MATCH);
        };
        handler.postDelayed(resultWatchdog, RESULT_WATCHDOG_MS);
    }

    private void cancelInternal(boolean notify, String detail) {
        clearTimers();
        generation++;
        cancelRecognizerOnly();
        lastPartial = "";
        state = State.IDLE;
        if (notify) callback.onVoiceState(state, "سننا روک دیا", detail == null ? "" : detail);
    }

    private void cancelRecognizerOnly() {
        if (recognizer == null) return;
        suppressErrorsUntil = SystemClock.uptimeMillis() + 800L;
        try {
            recognizer.cancel();
        } catch (RuntimeException ignored) {
            // Xiaomi speech services occasionally reject cancel on an idle recognizer.
        }
    }

    private void destroyRecognizer() {
        if (recognizer == null) return;
        suppressErrorsUntil = SystemClock.uptimeMillis() + 900L;
        try {
            recognizer.cancel();
        } catch (RuntimeException ignored) {
            // Xiaomi compatibility.
        }
        try {
            recognizer.destroy();
        } catch (RuntimeException ignored) {
            // Xiaomi compatibility.
        }
        recognizer = null;
    }

    private boolean isCurrent(int session) {
        return !released && foreground && session == generation;
    }

    private boolean canUsePartial(int error) {
        return error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_NETWORK
                || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER;
    }

    private String errorDetail(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "مائیکروفون آڈیو میں مسئلہ";
            case SpeechRecognizer.ERROR_CLIENT:
                return "وائس سروس نے جواب نہیں دیا";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "انٹرنیٹ یا وائس نیٹ ورک میں مسئلہ";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "آواز واضح نہیں ملی";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "وائس سروس مصروف ہے";
            case SpeechRecognizer.ERROR_SERVER:
                return "وائس سرور نے جواب نہیں دیا";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "آواز سنائی نہیں دی";
            default:
                return "وائس شناخت میں مسئلہ " + error;
        }
    }

    private void clearTimers() {
        if (retryRunnable != null) handler.removeCallbacks(retryRunnable);
        retryRunnable = null;
        removeStartWatchdog();
        removeListenWatchdog();
        removeResultWatchdog();
    }

    private void removeStartWatchdog() {
        if (startWatchdog != null) handler.removeCallbacks(startWatchdog);
        startWatchdog = null;
    }

    private void removeListenWatchdog() {
        if (listenWatchdog != null) handler.removeCallbacks(listenWatchdog);
        listenWatchdog = null;
    }

    private void removeResultWatchdog() {
        if (resultWatchdog != null) handler.removeCallbacks(resultWatchdog);
        resultWatchdog = null;
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else handler.post(runnable);
    }
}
