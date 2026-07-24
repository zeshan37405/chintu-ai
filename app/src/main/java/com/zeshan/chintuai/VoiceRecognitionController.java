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
 * Main-thread speech recognizer state machine with stale-callback isolation,
 * Redmi/HyperOS watchdogs, bounded retry, and system-recognizer fallback.
 */
public final class VoiceRecognitionController {
    private static final int MAX_DIRECT_RETRIES = 1;
    private static final long CREATE_DELAY_MS = 280L;
    private static final long RETRY_DELAY_MS = 900L;
    private static final long BUSY_RETRY_DELAY_MS = 1450L;
    private static final long START_WATCHDOG_MS = 5500L;
    private static final long LISTEN_WATCHDOG_MS = 22000L;
    private static final long RESULT_WATCHDOG_MS = 6500L;

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

    private Runnable prepareRunnable;
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

    public void setForeground(boolean foreground) {
        runOnMain(() -> {
            this.foreground = foreground;
            if (!foreground) cancelInternal(false, "");
        });
    }

    public void start() {
        runOnMain(() -> {
            if (released) return;
            if (!foreground) {
                callback.onVoiceUnavailable("ایپ سامنے آنے کے بعد دوبارہ کوشش کریں");
                return;
            }
            cancelInternal(false, "");
            retryCount = 0;
            lastPartial = "";
            startDirect(CREATE_DELAY_MS);
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
            callback.onVoiceUnavailable("فون میں وائس شناخت سروس دستیاب نہیں");
        });
    }

    public void release() {
        runOnMain(() -> {
            released = true;
            foreground = false;
            cancelInternal(false, "");
            callback.onVoiceState(State.IDLE, "", "");
        });
    }

    private void startDirect(long delayMs) {
        clearTimers();
        destroyRecognizer();
        if (released || !foreground) return;
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            requestSystemFallback("براہ راست وائس شناخت دستیاب نہیں");
            return;
        }

        final int session = ++generation;
        state = State.PREPARING;
        callback.onVoiceState(state, "وائس تیار ہو رہی ہے", "ایک لمحہ...");

        prepareRunnable = () -> {
            if (!isCurrent(session) || !foreground) return;
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(createListener(session));
                Intent intent = createRecognitionIntent();
                state = State.STARTING;
                callback.onVoiceState(state, "سننے کی تیاری", "اب واضح آواز میں بولیں");
                recognizer.startListening(intent);
                scheduleStartWatchdog(session);
                scheduleListenWatchdog(session);
            } catch (RuntimeException error) {
                recover(session, "وائس شناخت شروع نہیں ہوئی", SpeechRecognizer.ERROR_CLIENT);
            }
        };
        handler.postDelayed(prepareRunnable, Math.max(0L, delayMs));
    }

    private RecognitionListener createListener(final int session) {
        return new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                if (!isCurrent(session)) return;
                removeStartWatchdog();
                state = State.LISTENING;
                callback.onVoiceState(state, "سن رہا ہوں", "اب حکم بولیں");
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
                // Deliberately ignored to avoid UI churn on low-memory Redmi devices.
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
                    recover(session, "نتیجہ نہیں ملا", SpeechRecognizer.ERROR_NO_MATCH);
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
                // No vendor-specific events are required.
            }
        };
    }

    private void finish(int session, String command) {
        if (!isCurrent(session)) return;
        clearTimers();
        String cleaned = command == null ? "" : command.trim();
        destroyRecognizer();
        generation++;
        state = State.IDLE;
        lastPartial = "";
        if (cleaned.isEmpty()) {
            callback.onVoiceUnavailable("آواز واضح نہیں ملی");
        } else {
            callback.onCommandRecognized(cleaned);
        }
    }

    private void recover(int session, String reason, int errorCode) {
        if (!isCurrent(session)) return;
        clearTimers();
        destroyRecognizer();
        generation++;
        if (released || !foreground) {
            state = State.IDLE;
            return;
        }

        if (retryCount < MAX_DIRECT_RETRIES) {
            retryCount++;
            state = State.RECOVERING;
            callback.onVoiceState(state, "دوبارہ کوشش", reason);
            long delay = errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    ? BUSY_RETRY_DELAY_MS : RETRY_DELAY_MS;
            startDirect(delay);
        } else {
            requestSystemFallback(reason);
        }
    }

    private void requestSystemFallback(String reason) {
        clearTimers();
        destroyRecognizer();
        generation++;
        if (released || !foreground) {
            state = State.IDLE;
            return;
        }
        state = State.SYSTEM_FALLBACK;
        callback.onVoiceState(state, "متبادل وائس شناخت", reason);
        callback.onSystemRecognizerRequested(createRecognitionIntent());
    }

    private Intent createRecognitionIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                2400L);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                3400L);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "چنٹو کو حکم بولیں");
        return intent;
    }

    private void scheduleStartWatchdog(final int session) {
        removeStartWatchdog();
        startWatchdog = () -> {
            if (!isCurrent(session)) return;
            if (!lastPartial.isEmpty()) finish(session, lastPartial);
            else recover(session, "آواز شناخت شروع نہیں ہوئی", SpeechRecognizer.ERROR_CLIENT);
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
        destroyRecognizer();
        lastPartial = "";
        state = State.IDLE;
        if (notify) callback.onVoiceState(state, "سننا روک دیا", detail == null ? "" : detail);
    }

    private void destroyRecognizer() {
        if (recognizer == null) return;
        suppressErrorsUntil = SystemClock.uptimeMillis() + 1000L;
        try {
            recognizer.cancel();
        } catch (RuntimeException ignored) {
            // Xiaomi recognition services can throw while an inactive session is cancelled.
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
        if (prepareRunnable != null) handler.removeCallbacks(prepareRunnable);
        removeStartWatchdog();
        removeListenWatchdog();
        removeResultWatchdog();
        prepareRunnable = null;
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
