package com.zeshan.chintuai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChintuActivity extends Activity
        implements TextToSpeech.OnInitListener, RecognitionListener {

    private static final int REQUEST_MICROPHONE = 3101;
    private static final int REQUEST_CONTACTS = 3102;
    private static final int REQUEST_CAMERA = 3103;
    private static final int REQUEST_SYSTEM_SPEECH = 3104;

    private static final int MAX_DIRECT_RETRIES = 1;
    private static final long LISTEN_WATCHDOG_MS = 16000L;
    private static final long RESULT_WATCHDOG_MS = 4500L;

    private static final String PREFS = "chintu_preferences";
    private static final String PREF_HISTORY = "command_history";
    private static final int MAX_HISTORY = 6;

    private static final int COLOR_BACKGROUND_TOP = Color.rgb(5, 13, 25);
    private static final int COLOR_BACKGROUND_BOTTOM = Color.rgb(12, 29, 48);
    private static final int COLOR_CARD = Color.rgb(18, 35, 55);
    private static final int COLOR_CARD_LIGHT = Color.rgb(27, 48, 72);
    private static final int COLOR_ACCENT = Color.rgb(62, 168, 255);
    private static final int COLOR_ACCENT_DARK = Color.rgb(32, 116, 194);
    private static final int COLOR_TEXT = Color.rgb(244, 248, 255);
    private static final int COLOR_MUTED = Color.rgb(170, 185, 202);
    private static final int COLOR_SUCCESS = Color.rgb(87, 211, 151);
    private static final int COLOR_WARNING = Color.rgb(255, 190, 86);

    private TextView statusView;
    private TextView heardView;
    private TextView historyView;
    private EditText commandInput;
    private Button micButton;
    private Button runButton;
    private Button stopButton;

    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isListening;
    private boolean awaitingResult;
    private int retryCount;
    private long suppressErrorsUntil;
    private String lastPartial = "";
    private String pendingCommand;
    private PendingPermission pendingPermission = PendingPermission.NONE;

    private final Runnable listenWatchdog = () -> {
        if (!isListening) return;
        String partial = lastPartial.trim();
        destroyRecognizer();
        if (!partial.isEmpty()) {
            finishRecognizedCommand(partial);
        } else {
            retryOrUseSystemRecognizer();
        }
    };

    private final Runnable resultWatchdog = () -> {
        if (!awaitingResult) return;
        awaitingResult = false;
        String partial = lastPartial.trim();
        destroyRecognizer();
        if (!partial.isEmpty()) {
            finishRecognizedCommand(partial);
        } else {
            retryOrUseSystemRecognizer();
        }
    };

    private enum PendingPermission {
        NONE,
        VOICE,
        CONTACT_COMMAND,
        TORCH_COMMAND
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND_TOP);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND_TOP);
        tts = new TextToSpeech(this, this);
        setContentView(buildInterface());
        showHistory();
        updateStatus("تیار ہوں", "کمانڈ بولیں یا نیچے لکھیں", COLOR_SUCCESS);
    }

    private ScrollView buildInterface() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackground(backgroundGradient());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(30), dp(18), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleColumnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleColumn, titleColumnParams);

        TextView title = new TextView(this);
        title.setText("Chintu");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(36);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleColumn.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("آپ کا ذاتی موبائل اسسٹنٹ");
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(16);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(2);
        titleColumn.addView(subtitle, subtitleParams);

        TextView versionBadge = new TextView(this);
        versionBadge.setText("FINAL 2.0");
        versionBadge.setTextColor(Color.WHITE);
        versionBadge.setTextSize(12);
        versionBadge.setGravity(Gravity.CENTER);
        versionBadge.setPadding(dp(12), dp(7), dp(12), dp(7));
        versionBadge.setBackground(roundedBackground(COLOR_ACCENT_DARK, dp(18), 0, 0));
        header.addView(versionBadge, wrapWrap());

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        statusCard.setBackground(roundedBackground(COLOR_CARD, dp(22), 1, COLOR_CARD_LIGHT));
        LinearLayout.LayoutParams statusCardParams = matchWrap();
        statusCardParams.topMargin = dp(24);
        root.addView(statusCard, statusCardParams);

        statusView = new TextView(this);
        statusView.setTextColor(COLOR_SUCCESS);
        statusView.setTextSize(18);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setGravity(Gravity.CENTER);
        statusCard.addView(statusView, matchWrap());

        heardView = new TextView(this);
        heardView.setTextColor(COLOR_TEXT);
        heardView.setTextSize(16);
        heardView.setGravity(Gravity.CENTER);
        heardView.setMinHeight(dp(42));
        heardView.setPadding(0, dp(8), 0, 0);
        statusCard.addView(heardView, matchWrap());

        micButton = new Button(this);
        micButton.setAllCaps(false);
        micButton.setText("🎙️  حکم بولیں");
        micButton.setTextColor(Color.WHITE);
        micButton.setTextSize(21);
        micButton.setTypeface(Typeface.DEFAULT_BOLD);
        micButton.setBackground(roundedBackground(COLOR_ACCENT_DARK, dp(24), 1, COLOR_ACCENT));
        micButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (isListening || awaitingResult) {
                stopListening("سننا روک دیا");
            } else {
                startVoiceRecognition();
            }
        });
        LinearLayout.LayoutParams micParams = matchWrap();
        micParams.height = dp(68);
        micParams.topMargin = dp(18);
        root.addView(micButton, micParams);

        stopButton = new Button(this);
        stopButton.setAllCaps(false);
        stopButton.setText("روکیں");
        stopButton.setTextColor(COLOR_MUTED);
        stopButton.setTextSize(15);
        stopButton.setBackground(roundedBackground(COLOR_CARD, dp(18), 1, COLOR_CARD_LIGHT));
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> stopListening("سننا روک دیا"));
        LinearLayout.LayoutParams stopParams = matchWrap();
        stopParams.height = dp(46);
        stopParams.topMargin = dp(8);
        root.addView(stopButton, stopParams);

        LinearLayout inputCard = new LinearLayout(this);
        inputCard.setOrientation(LinearLayout.VERTICAL);
        inputCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        inputCard.setBackground(roundedBackground(COLOR_CARD, dp(20), 1, COLOR_CARD_LIGHT));
        LinearLayout.LayoutParams inputCardParams = matchWrap();
        inputCardParams.topMargin = dp(18);
        root.addView(inputCard, inputCardParams);

        commandInput = new EditText(this);
        commandInput.setHint("مثلاً: ہوم کو کال کرو");
        commandInput.setHintTextColor(COLOR_MUTED);
        commandInput.setTextColor(COLOR_TEXT);
        commandInput.setTextSize(17);
        commandInput.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        commandInput.setSingleLine(false);
        commandInput.setMaxLines(3);
        commandInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        commandInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        commandInput.setBackgroundColor(Color.TRANSPARENT);
        commandInput.setPadding(dp(6), dp(6), dp(6), dp(6));
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runTypedCommand();
                return true;
            }
            return false;
        });
        inputCard.addView(commandInput, matchWrap());

        runButton = new Button(this);
        runButton.setAllCaps(false);
        runButton.setText("کمانڈ چلائیں");
        runButton.setTextColor(Color.WHITE);
        runButton.setTextSize(17);
        runButton.setTypeface(Typeface.DEFAULT_BOLD);
        runButton.setBackground(roundedBackground(COLOR_ACCENT_DARK, dp(18), 0, 0));
        runButton.setOnClickListener(v -> runTypedCommand());
        LinearLayout.LayoutParams runParams = matchWrap();
        runParams.height = dp(52);
        runParams.topMargin = dp(8);
        inputCard.addView(runButton, runParams);

        TextView quickTitle = sectionTitle("فوری کام");
        LinearLayout.LayoutParams quickTitleParams = matchWrap();
        quickTitleParams.topMargin = dp(22);
        root.addView(quickTitle, quickTitleParams);

        GridLayout quickGrid = new GridLayout(this);
        quickGrid.setColumnCount(2);
        quickGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        quickGrid.setUseDefaultMargins(false);
        LinearLayout.LayoutParams gridParams = matchWrap();
        gridParams.topMargin = dp(8);
        root.addView(quickGrid, gridParams);

        addQuickAction(quickGrid, "🌤️ موسم", "آج حسن ابدال کا موسم تلاش کرو");
        addQuickAction(quickGrid, "📞 ہوم کو کال", "ہوم کو کال کرو");
        addQuickAction(quickGrid, "▶️ یوٹیوب", "یوٹیوب کھولو");
        addQuickAction(quickGrid, "💬 واٹس ایپ", "واٹس ایپ کھولو");
        addQuickAction(quickGrid, "📍 میپس", "گوگل میپس کھولو");
        addQuickAction(quickGrid, "📷 کیمرہ", "کیمرہ کھولو");

        TextView historyTitle = sectionTitle("حالیہ کمانڈز");
        LinearLayout.LayoutParams historyTitleParams = matchWrap();
        historyTitleParams.topMargin = dp(22);
        root.addView(historyTitle, historyTitleParams);

        historyView = new TextView(this);
        historyView.setTextColor(COLOR_MUTED);
        historyView.setTextSize(14);
        historyView.setGravity(Gravity.RIGHT);
        historyView.setLineSpacing(dp(3), 1.05f);
        historyView.setPadding(dp(14), dp(12), dp(14), dp(12));
        historyView.setBackground(roundedBackground(COLOR_CARD, dp(18), 1, COLOR_CARD_LIGHT));
        LinearLayout.LayoutParams historyParams = matchWrap();
        historyParams.topMargin = dp(8);
        root.addView(historyView, historyParams);

        TextView hint = new TextView(this);
        hint.setText("آواز واضح نہ آئے تو کمانڈ لکھ کر چلائیں۔ نامعلوم کمانڈ خودکار طور پر گوگل پر تلاش ہوگی۔");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(10), dp(12), dp(10), 0);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(8);
        root.addView(hint, hintParams);

        return scrollView;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.RIGHT);
        return view;
    }

    private void addQuickAction(GridLayout grid, String label, String command) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(15);
        button.setBackground(roundedBackground(COLOR_CARD_LIGHT, dp(18), 1, COLOR_ACCENT_DARK));
        button.setOnClickListener(v -> {
            commandInput.setText(command);
            executeCommand(command);
        });

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(54);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        grid.addView(button, params);
    }

    private GradientDrawable backgroundGradient() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COLOR_BACKGROUND_TOP, COLOR_BACKGROUND_BOTTOM});
        drawable.setShape(GradientDrawable.RECTANGLE);
        return drawable;
    }

    private GradientDrawable roundedBackground(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void updateStatus(String status, String detail, int statusColor) {
        if (statusView != null) {
            statusView.setText(status);
            statusView.setTextColor(statusColor);
        }
        if (heardView != null) heardView.setText(detail == null ? "" : detail);
    }

    private void runTypedCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            updateStatus("کمانڈ موجود نہیں", "پہلے کمانڈ لکھیں یا بولیں", COLOR_WARNING);
            return;
        }
        hideKeyboard();
        executeCommand(command);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) focus = commandInput;
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        commandInput.clearFocus();
    }

    private void startVoiceRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = PendingPermission.VOICE;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }

        if (tts != null) tts.stop();
        stopListeningInternal();
        retryCount = 0;
        lastPartial = "";
        prepareRecognizer();

        if (speechRecognizer == null) {
            launchSystemRecognizer();
            return;
        }

        handler.postDelayed(this::beginDirectRecognition, 180L);
    }

    private void prepareRecognizer() {
        destroyRecognizer();
        speechIntent = createSpeechIntent();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = null;
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(this);
    }

    private Intent createSpeechIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        intent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                2800L);
        return intent;
    }

    private void beginDirectRecognition() {
        if (speechRecognizer == null || speechIntent == null) {
            launchSystemRecognizer();
            return;
        }

        try {
            isListening = true;
            awaitingResult = false;
            lastPartial = "";
            micButton.setText("🎙️  سن رہا ہوں...");
            stopButton.setVisibility(View.VISIBLE);
            updateStatus("سن رہا ہوں", "اب واضح آواز میں حکم بولیں", COLOR_ACCENT);
            speechRecognizer.startListening(speechIntent);
            handler.removeCallbacks(listenWatchdog);
            handler.postDelayed(listenWatchdog, LISTEN_WATCHDOG_MS);
        } catch (RuntimeException error) {
            retryOrUseSystemRecognizer();
        }
    }

    private void retryOrUseSystemRecognizer() {
        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        isListening = false;
        awaitingResult = false;

        if (retryCount < MAX_DIRECT_RETRIES) {
            retryCount++;
            updateStatus("دوبارہ سن رہا ہوں", "اب کمانڈ بولیں", COLOR_ACCENT);
            prepareRecognizer();
            handler.postDelayed(this::beginDirectRecognition, 420L);
            return;
        }

        launchSystemRecognizer();
    }

    private void launchSystemRecognizer() {
        stopListeningInternal();
        Intent fallback = createSpeechIntent();
        fallback.putExtra(RecognizerIntent.EXTRA_PROMPT, "چنٹو کو حکم بولیں");
        try {
            micButton.setText("🎙️  سن رہا ہوں...");
            stopButton.setVisibility(View.GONE);
            updateStatus("سن رہا ہوں", "Google وائس ونڈو میں حکم بولیں", COLOR_ACCENT);
            startActivityForResult(fallback, REQUEST_SYSTEM_SPEECH);
        } catch (ActivityNotFoundException error) {
            resetVoiceUi();
            updateStatus("آواز شناخت دستیاب نہیں",
                    "کمانڈ نیچے لکھ کر چلائیں", COLOR_WARNING);
        }
    }

    private void stopListening(String message) {
        stopListeningInternal();
        resetVoiceUi();
        updateStatus("رک گیا", message, COLOR_WARNING);
    }

    private void stopListeningInternal() {
        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        isListening = false;
        awaitingResult = false;
        if (speechRecognizer != null) {
            suppressErrorsUntil = SystemClock.uptimeMillis() + 900L;
            try {
                speechRecognizer.cancel();
            } catch (RuntimeException ignored) {
                // Some Xiaomi builds throw while cancelling an inactive recognizer.
            }
        }
    }

    private void resetVoiceUi() {
        micButton.setText("🎙️  حکم بولیں");
        stopButton.setVisibility(View.GONE);
    }

    private void finishRecognizedCommand(String command) {
        String cleaned = command == null ? "" : command.trim();
        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        isListening = false;
        awaitingResult = false;
        resetVoiceUi();

        if (cleaned.isEmpty()) {
            updateStatus("آواز واضح نہیں ملی",
                    "دوبارہ بولیں یا کمانڈ لکھیں", COLOR_WARNING);
            return;
        }

        commandInput.setText(cleaned);
        executeCommand(cleaned);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        updateStatus("سن رہا ہوں", "اب بولیں...", COLOR_ACCENT);
    }

    @Override
    public void onBeginningOfSpeech() {
        updateStatus("آواز مل گئی", "بولتے رہیں...", COLOR_ACCENT);
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // Deliberately lightweight to avoid UI jank on low-memory devices.
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // Not needed.
    }

    @Override
    public void onEndOfSpeech() {
        handler.removeCallbacks(listenWatchdog);
        isListening = false;
        awaitingResult = true;
        micButton.setText("سمجھ رہا ہوں...");
        updateStatus("سمجھ رہا ہوں", lastPartial, COLOR_ACCENT);
        handler.removeCallbacks(resultWatchdog);
        handler.postDelayed(resultWatchdog, RESULT_WATCHDOG_MS);
    }

    @Override
    public void onError(int error) {
        if (SystemClock.uptimeMillis() < suppressErrorsUntil) return;

        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        isListening = false;
        awaitingResult = false;

        if (!lastPartial.trim().isEmpty()
                && (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_CLIENT)) {
            finishRecognizedCommand(lastPartial);
            return;
        }

        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_NETWORK
                || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER) {
            destroyRecognizer();
            retryOrUseSystemRecognizer();
            return;
        }

        resetVoiceUi();
        updateStatus("آواز شناخت میں مسئلہ",
                "کمانڈ لکھ کر چلائیں یا دوبارہ کوشش کریں", COLOR_WARNING);
    }

    @Override
    public void onResults(Bundle results) {
        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        isListening = false;
        awaitingResult = false;

        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        destroyRecognizer();

        if (matches == null || matches.isEmpty()) {
            if (!lastPartial.trim().isEmpty()) {
                finishRecognizedCommand(lastPartial);
            } else {
                retryOrUseSystemRecognizer();
            }
            return;
        }

        finishRecognizedCommand(chooseBestCommand(matches));
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> partial =
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (partial == null || partial.isEmpty()) return;
        lastPartial = chooseBestCommand(partial);
        heardView.setText(lastPartial);
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // Not needed.
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SYSTEM_SPEECH) return;

        resetVoiceUi();
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                finishRecognizedCommand(chooseBestCommand(matches));
                return;
            }
        }

        updateStatus("آواز واضح نہیں ملی",
                "دوبارہ بولیں یا کمانڈ لکھیں", COLOR_WARNING);
    }

    private String chooseBestCommand(ArrayList<String> matches) {
        String best = matches.get(0);
        int bestScore = scoreCommand(best);
        for (String candidate : matches) {
            int score = scoreCommand(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best.trim();
    }

    private int scoreCommand(String raw) {
        String text = normalize(raw);
        int score = Math.min(raw.length(), 60) / 4;
        if (containsAny(text, "گوگل", "گوجل", "google", "موسم", "weather")) score += 22;
        if (containsAny(text, "فون ڈائریکٹری", "کانٹیکٹ", "رابطہ", "نمبر")) score += 22;
        if (containsAny(text, "کال", "فون کرو", "کال لگاؤ", "call", "dial")) score += 18;
        if (containsAny(text, "ہوم", "home", "امی", "ابو")) score += 14;
        if (containsAny(text, "تلاش", "سرچ", "search", "کھولو", "اوپن")) score += 12;
        if (containsAny(text, "انسٹاگرام", "واٹس ایپ", "یوٹیوب", "فیس بک", "میپس")) score += 10;
        return score;
    }

    private void executeCommand(String rawCommand) {
        String raw = rawCommand == null ? "" : rawCommand.trim();
        if (raw.isEmpty()) return;

        String command = normalize(raw);
        addHistory(raw);
        updateStatus("کمانڈ موصول ہوئی", raw, COLOR_SUCCESS);

        if (containsAny(command,
                "بینک", "bank", "ایزی پیسہ", "easypaisa", "جاز کیش", "jazzcash",
                "رقم ٹرانسفر", "پیسے بھیجو", "ادائیگی کرو", "payment", "transfer money")) {
            speak("مالی لین دین کی کمانڈ محفوظ طریقے سے بند ہے");
            return;
        }

        if (containsAny(command, "مدد", "کیا کر سکتے ہو", "help", "commands")) {
            showHelp();
            return;
        }

        if (containsAny(command, "وقت", "ٹائم", "time")
                && !containsAny(command, "ٹائمر", "timer")) {
            String time = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());
            speak("اس وقت " + time + " ہوئے ہیں");
            return;
        }

        if (containsAny(command, "تاریخ", "آج کون سا دن", "آج کیا تاریخ", "date")) {
            String date = new SimpleDateFormat(
                    "EEEE، d MMMM yyyy", new Locale("ur", "PK")).format(new Date());
            speak("آج " + date + " ہے");
            return;
        }

        if (containsAny(command, "ٹارچ", "فلیش لائٹ", "flashlight", "torch")) {
            boolean turnOn = !containsAny(command, "بند", "آف", "off");
            handleTorchCommand(raw, turnOn);
            return;
        }

        if (containsAny(command, "الارم", "alarm")) {
            setAlarm(raw);
            return;
        }

        if (containsAny(command, "ٹائمر", "timer")) {
            setTimer(raw);
            return;
        }

        if (isContactLookupCommand(command)) {
            if (!ensureContactsPermission(raw)) return;
            showContactNumber(extractContactName(raw));
            return;
        }

        if (containsAny(command,
                "کال کرو", "فون کرو", "کال لگاؤ", "فون لگاؤ", "call", "dial")) {
            String number = extractPhoneNumber(raw);
            if (!number.isEmpty()) {
                openDialer(number);
            } else if (ensureContactsPermission(raw)) {
                dialContact(extractContactName(raw));
            }
            return;
        }

        if (containsAny(command,
                "میسج کرو", "پیغام بھیجو", "ایس ایم ایس", "sms", "message")) {
            String number = extractPhoneNumber(raw);
            if (!number.isEmpty()) {
                openMessage(number);
            } else if (ensureContactsPermission(raw)) {
                messageContact(extractContactName(raw));
            }
            return;
        }

        if (containsAny(command, "کاپی کرو", "copy")) {
            String text = cleanQuery(raw, "کاپی کرو", "کاپی کریں", "copy");
            if (text.isEmpty()) {
                speak("کاپی کرنے کے لیے متن بھی بولیں");
            } else {
                copyToClipboard("Chintu", text);
                speak("متن کاپی ہو گیا");
            }
            return;
        }

        if (containsAny(command, "شیئر کرو", "share")) {
            String text = cleanQuery(raw, "شیئر کرو", "شیئر کریں", "share");
            if (text.isEmpty()) {
                speak("شیئر کرنے کے لیے متن بھی بولیں");
            } else {
                shareText(text);
            }
            return;
        }

        if (containsAny(command, "یوٹیوب", "youtube")
                && containsAny(command, "تلاش", "سرچ", "search", "پر")) {
            String query = cleanQuery(raw,
                    "یوٹیوب پر", "youtube پر", "youtube میں", "یوٹیوب", "youtube",
                    "تلاش کرو", "تلاش کریں", "سرچ کرو", "سرچ کریں", "search");
            if (query.isEmpty()) query = raw;
            speak("یوٹیوب پر تلاش کر رہا ہوں");
            openUri("https://www.youtube.com/results?search_query=" + Uri.encode(query));
            return;
        }

        if (containsAny(command, "موسم", "weather", "درجہ حرارت")) {
            String query = cleanSearchQuery(raw);
            if (query.isEmpty()
                    || query.equals("موسم")
                    || query.equals("موسم کا حال")
                    || query.equals("آج کا موسم")) {
                query = "حسن ابدال موسم آج";
            } else if (!containsAny(normalize(query), "حسن ابدال", "hasan abdal")) {
                query = query + " موسم";
            }
            speak("موسم تلاش کر رہا ہوں");
            openGoogleSearch(query);
            return;
        }

        if (containsAny(command,
                "گوگل", "گوجل", "google", "ویب پر", "انٹرنیٹ پر",
                "تلاش کرو", "سرچ کرو", "search")) {
            String query = cleanSearchQuery(raw);
            if (query.isEmpty()) query = raw;
            speak("گوگل پر تلاش کر رہا ہوں");
            openGoogleSearch(query);
            return;
        }

        if (containsAny(command,
                "راستہ", "لوکیشن", "نقشے میں", "میپس میں", "maps",
                "کہاں ہے", "راستہ دکھاؤ")) {
            String query = cleanQuery(raw,
                    "راستہ دکھاؤ", "راستہ بتاؤ", "لوکیشن دکھاؤ",
                    "نقشے میں", "میپس میں", "maps میں", "تلاش کرو", "کہاں ہے");
            if (query.isEmpty() || containsAny(command, "میپس کھولو", "نقشہ کھولو")) {
                openKnownApp("میپس کھول رہا ہوں", "https://maps.google.com",
                        "com.google.android.apps.maps");
            } else {
                speak("میپس میں تلاش کر رہا ہوں");
                openIntent(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=" + Uri.encode(query))));
            }
            return;
        }

        if (containsAny(command, "کانٹیکٹس کھولو", "رابطے کھولو", "contacts کھولو")) {
            openIntent(new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI));
            return;
        }

        if (containsAny(command, "فون کھولو", "ڈائلر کھولو", "dialer")) {
            openIntent(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")));
            return;
        }

        if (containsAny(command, "میسجز کھولو", "پیغامات کھولو", "sms کھولو")) {
            openIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING));
            return;
        }

        if (containsAny(command, "جی میل", "gmail", "ای میل")) {
            openKnownApp("جی میل کھول رہا ہوں", "https://mail.google.com",
                    "com.google.android.gm");
            return;
        }

        if (containsAny(command, "کیلکولیٹر", "calculator")) {
            if (!openKnownApp("کیلکولیٹر کھول رہا ہوں", null,
                    "com.miui.calculator", "com.google.android.calculator")) {
                openIntent(Intent.makeMainSelectorActivity(
                        Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR));
            }
            return;
        }

        if (containsAny(command, "کیمرہ", "camera")) {
            speak("کیمرہ کھول رہا ہوں");
            openIntent(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
            return;
        }

        if (containsAny(command, "گیلری", "فوٹوز", "تصاویر", "photos")) {
            openKnownApp("گیلری کھول رہا ہوں", null,
                    "com.miui.gallery", "com.google.android.apps.photos");
            return;
        }

        if (containsAny(command, "وائی فائی", "wifi")) {
            speak("وائی فائی سیٹنگز کھول رہا ہوں");
            openIntent(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }

        if (containsAny(command, "بلوٹوتھ", "bluetooth")) {
            speak("بلوٹوتھ سیٹنگز کھول رہا ہوں");
            openIntent(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }

        if (containsAny(command, "سیٹنگ", "settings")) {
            speak("سیٹنگز کھول رہا ہوں");
            openIntent(new Intent(Settings.ACTION_SETTINGS));
            return;
        }

        if (containsAny(command, "پلے اسٹور", "play store")) {
            openKnownApp("پلے اسٹور کھول رہا ہوں",
                    "https://play.google.com/store/apps",
                    "com.android.vending");
            return;
        }

        if (containsAny(command, "کھولو", "اوپن کرو", "open", "چلاؤ", "چلاو")) {
            String requestedApp = extractAppName(raw);
            if (!requestedApp.isEmpty() && openAnyInstalledApp(requestedApp)) return;
        }

        speak("یہ کمانڈ گوگل پر تلاش کر رہا ہوں");
        openGoogleSearch(raw);
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("چنٹو کی کمانڈز")
                .setMessage(
                        "• گوگل یا یوٹیوب سرچ\n" +
                        "• موسم اور میپس\n" +
                        "• نام سے نمبر، کال اور میسج\n" +
                        "• انسٹال شدہ ایپ کھولنا\n" +
                        "• الارم، ٹائمر، وقت اور تاریخ\n" +
                        "• ٹارچ، کیمرہ، گیلری اور سیٹنگز\n" +
                        "• متن کاپی یا شیئر کرنا")
                .setPositiveButton("ٹھیک ہے", null)
                .show();
    }

    private boolean isContactLookupCommand(String command) {
        return containsAny(command,
                "فون ڈائریکٹری", "کانٹیکٹ", "کانٹیکٹس", "رابطہ", "رابطوں")
                || (containsAny(command,
                "نمبر نکالو", "نمبر تلاش کرو", "نمبر ڈھونڈو",
                "نمبر دکھاؤ", "نمبر بتاؤ")
                && extractPhoneNumber(command).isEmpty());
    }

    private boolean ensureContactsPermission(String rawCommand) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        pendingPermission = PendingPermission.CONTACT_COMMAND;
        pendingCommand = rawCommand;
        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_CONTACTS);
        updateStatus("اجازت درکار ہے",
                "کانٹیکٹس کی اجازت Allow کریں", COLOR_WARNING);
        return false;
    }

    private void showContactNumber(String requestedName) {
        List<ContactMatch> matches = findContacts(requestedName);
        if (matches.isEmpty()) return;
        if (shouldAskForContactChoice(matches)) {
            showContactPicker(matches, ContactAction.SHOW);
        } else {
            presentContactNumber(matches.get(0));
        }
    }

    private void dialContact(String requestedName) {
        List<ContactMatch> matches = findContacts(requestedName);
        if (matches.isEmpty()) return;
        if (shouldAskForContactChoice(matches)) {
            showContactPicker(matches, ContactAction.DIAL);
        } else {
            openDialer(matches.get(0).phoneNumber);
        }
    }

    private void messageContact(String requestedName) {
        List<ContactMatch> matches = findContacts(requestedName);
        if (matches.isEmpty()) return;
        if (shouldAskForContactChoice(matches)) {
            showContactPicker(matches, ContactAction.MESSAGE);
        } else {
            openMessage(matches.get(0).phoneNumber);
        }
    }

    private enum ContactAction {
        SHOW,
        DIAL,
        MESSAGE
    }

    private boolean shouldAskForContactChoice(List<ContactMatch> matches) {
        return matches.size() > 1 && matches.get(0).score - matches.get(1).score < 8;
    }

    private void showContactPicker(List<ContactMatch> matches, ContactAction action) {
        int count = Math.min(matches.size(), 5);
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            ContactMatch match = matches.get(i);
            labels[i] = match.displayName + "\n" + match.phoneNumber;
        }

        new AlertDialog.Builder(this)
                .setTitle("صحیح کانٹیکٹ منتخب کریں")
                .setItems(labels, (dialog, which) -> {
                    ContactMatch selected = matches.get(which);
                    if (action == ContactAction.DIAL) {
                        openDialer(selected.phoneNumber);
                    } else if (action == ContactAction.MESSAGE) {
                        openMessage(selected.phoneNumber);
                    } else {
                        presentContactNumber(selected);
                    }
                })
                .setNegativeButton("منسوخ", null)
                .show();
    }

    private void presentContactNumber(ContactMatch match) {
        copyToClipboard(match.displayName, match.phoneNumber);
        updateStatus("نمبر مل گیا",
                match.displayName + "\n" + match.phoneNumber + "\nنمبر کاپی ہو گیا",
                COLOR_SUCCESS);
        if (tts != null) {
            tts.speak(match.displayName + " کا نمبر مل گیا اور کاپی کر دیا ہے",
                    TextToSpeech.QUEUE_FLUSH, null, "contact-found");
        }
    }

    private List<ContactMatch> findContacts(String requestedName) {
        String target = normalizeContactKey(requestedName);
        if (target.isEmpty()) {
            speak("کانٹیکٹ کا نام واضح بولیں");
            return new ArrayList<>();
        }

        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
        };

        Map<String, ContactMatch> bestByNumber = new LinkedHashMap<>();

        try (Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {

            if (cursor == null) return new ArrayList<>();

            int nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
            int normalizedNumberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER);
            int typeIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.TYPE);
            int labelIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.LABEL);

            while (cursor.moveToNext()) {
                String displayName = safeCursorString(cursor, nameIndex);
                String number = safeCursorString(cursor, numberIndex);
                String normalizedNumber = safeCursorString(cursor, normalizedNumberIndex);
                int type = typeIndex >= 0 ? cursor.getInt(typeIndex) : 0;
                String customLabel = safeCursorString(cursor, labelIndex);

                if (displayName.isEmpty() || number.isEmpty()) continue;

                CharSequence typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        getResources(), type, customLabel);
                String searchable = displayName + " " + typeLabel;
                String candidate = normalizeContactKey(searchable);
                int score = similarityScore(target, candidate);

                if (score < 56) continue;

                String key = normalizedNumber.isEmpty()
                        ? normalizeDigits(number).replaceAll("\\D", "")
                        : normalizedNumber;
                ContactMatch current = bestByNumber.get(key);
                if (current == null || score > current.score) {
                    bestByNumber.put(key,
                            new ContactMatch(displayName, number, typeLabel.toString(), score));
                }
            }
        } catch (SecurityException error) {
            speak("کانٹیکٹس کی اجازت درکار ہے");
            return new ArrayList<>();
        } catch (RuntimeException error) {
            speak("فون ڈائریکٹری پڑھنے میں مسئلہ آیا");
            return new ArrayList<>();
        }

        ArrayList<ContactMatch> result = new ArrayList<>(bestByNumber.values());
        result.sort((a, b) -> Integer.compare(b.score, a.score));

        if (result.isEmpty()) {
            speak("یہ نام فون ڈائریکٹری میں نہیں ملا");
        }
        return result;
    }

    private String safeCursorString(Cursor cursor, int index) {
        if (index < 0 || cursor.isNull(index)) return "";
        String value = cursor.getString(index);
        return value == null ? "" : value.trim();
    }

    private int similarityScore(String target, String candidate) {
        if (candidate.equals(target)) return 100;
        if (containsWholeToken(candidate, target)) return 96;
        if (candidate.startsWith(target + " ") || target.startsWith(candidate + " ")) return 92;
        if (candidate.contains(target) || target.contains(candidate)) return 84;

        String[] targetWords = target.split(" ");
        String[] candidateWords = candidate.split(" ");
        int best = 0;
        for (String targetWord : targetWords) {
            for (String candidateWord : candidateWords) {
                if (targetWord.equals(candidateWord)) best = Math.max(best, 90);
                else if (targetWord.length() >= 3 && candidateWord.startsWith(targetWord)) {
                    best = Math.max(best, 78);
                } else if (candidateWord.length() >= 3 && targetWord.startsWith(candidateWord)) {
                    best = Math.max(best, 74);
                } else {
                    int distance = levenshtein(targetWord, candidateWord);
                    int max = Math.max(targetWord.length(), candidateWord.length());
                    if (max > 0) {
                        int fuzzy = 100 - ((distance * 100) / max);
                        best = Math.max(best, fuzzy);
                    }
                }
            }
        }
        return best;
    }

    private boolean containsWholeToken(String text, String token) {
        return (" " + text + " ").contains(" " + token + " ");
    }

    private int levenshtein(String first, String second) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];

        for (int j = 0; j <= second.length(); j++) previous[j] = j;

        for (int i = 1; i <= first.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int cost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()];
    }

    private String extractContactName(String raw) {
        String cleaned = normalize(raw);
        String[] stopWords = {
                "میری", "میرے", "میرا", "فون", "ڈائریکٹری", "میں", "سے",
                "کا", "کی", "کے", "نمبر", "نکالو", "تلاش", "کرو", "کر", "دو",
                "ڈھونڈو", "دکھاؤ", "بتاؤ", "جس", "پر", "لکھا", "ہے", "کو",
                "کال", "لگاؤ", "فون", "پیغام", "میسج", "بھیجو", "کانٹیکٹ",
                "کانٹیکٹس", "رابطہ", "رابطوں", "نام", "والا", "والی",
                "phone", "directory", "contact", "contacts", "number", "find",
                "show", "call", "dial", "message", "sms", "the", "named"
        };

        Set<String> result = new LinkedHashSet<>();
        outer:
        for (String word : cleaned.split(" ")) {
            if (word.isEmpty()) continue;
            for (String stopWord : stopWords) {
                if (word.equals(stopWord)) continue outer;
            }
            result.add(word);
        }
        return String.join(" ", result).trim();
    }

    private String normalizeContactKey(String text) {
        if (text == null) return "";
        return normalizeDigits(normalize(text))
                .replace("ہوم", "home")
                .replace("هوم", "home")
                .replace("ھوم", "home")
                .replace("ہم", "home")
                .replace("موبائل", "mobile")
                .replace("گھر", "home")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void openDialer(String number) {
        speak("نمبر ڈائلر میں کھول رہا ہوں");
        openIntent(new Intent(Intent.ACTION_DIAL,
                Uri.fromParts("tel", number, null)));
    }

    private void openMessage(String number) {
        speak("میسج لکھنے کا صفحہ کھول رہا ہوں");
        openIntent(new Intent(Intent.ACTION_SENDTO,
                Uri.fromParts("smsto", number, null)));
    }

    private void handleTorchCommand(String rawCommand, boolean turnOn) {
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = PendingPermission.TORCH_COMMAND;
            pendingCommand = rawCommand;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            updateStatus("اجازت درکار ہے", "کیمرہ کی اجازت Allow کریں", COLOR_WARNING);
            return;
        }
        setTorch(turnOn);
    }

    private void setTorch(boolean enabled) {
        CameraManager cameraManager =
                (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            speak("ٹارچ دستیاب نہیں");
            return;
        }

        try {
            String cameraId = findFlashCamera(cameraManager);
            if (cameraId == null) {
                speak("اس فون میں ٹارچ دستیاب نہیں");
                return;
            }
            cameraManager.setTorchMode(cameraId, enabled);
            speak(enabled ? "ٹارچ آن کر دی ہے" : "ٹارچ بند کر دی ہے");
        } catch (CameraAccessException | SecurityException error) {
            speak("ٹارچ چلانے میں مسئلہ آیا");
        }
    }

    private String findFlashCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(flash)
                    && (facing == null
                    || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                return id;
            }
        }
        return null;
    }

    private void setAlarm(String raw) {
        ParsedTime time = parseClockTime(raw);
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
        intent.putExtra(AlarmClock.EXTRA_MESSAGE, "Chintu Alarm");
        if (time != null) {
            intent.putExtra(AlarmClock.EXTRA_HOUR, time.hour);
            intent.putExtra(AlarmClock.EXTRA_MINUTES, time.minute);
            intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        }
        speak(time == null ? "الارم ایپ کھول رہا ہوں" : "الارم تیار کر رہا ہوں");
        openIntent(intent);
    }

    private void setTimer(String raw) {
        int seconds = parseDurationSeconds(raw);
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER);
        intent.putExtra(AlarmClock.EXTRA_LENGTH, Math.max(1, seconds));
        intent.putExtra(AlarmClock.EXTRA_MESSAGE, "Chintu Timer");
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        speak("ٹائمر تیار کر رہا ہوں");
        openIntent(intent);
    }

    private ParsedTime parseClockTime(String raw) {
        String text = normalizeDigits(normalize(raw));
        int hour = -1;
        int minute = 0;

        Matcher colon = Pattern.compile("\\b([0-2]?\\d)\\s*[:.]\\s*([0-5]?\\d)\\b")
                .matcher(text);
        if (colon.find()) {
            hour = parseSafeInt(colon.group(1), -1);
            minute = parseSafeInt(colon.group(2), 0);
        }

        if (hour < 0) {
            int wordNumber = findFirstNumber(text);
            if (wordNumber >= 0) hour = wordNumber;
        }

        if (containsAny(text, "ساڑھے")) {
            minute = 30;
        } else if (containsAny(text, "سوا")) {
            minute = 15;
        } else if (containsAny(text, "پونے") && hour > 0) {
            hour = hour - 1;
            minute = 45;
        }

        if (hour < 0) return null;

        boolean pm = containsAny(text, "شام", "رات", "دوپہر", "pm");
        boolean am = containsAny(text, "صبح", "فجر", "am");

        if (pm && hour < 12) hour += 12;
        if (am && hour == 12) hour = 0;

        hour = Math.max(0, Math.min(23, hour));
        minute = Math.max(0, Math.min(59, minute));
        return new ParsedTime(hour, minute);
    }

    private int parseDurationSeconds(String raw) {
        String text = normalizeDigits(normalize(raw));
        int value = findFirstNumber(text);
        if (value < 0) value = 1;

        if (containsAny(text, "گھنٹہ", "گھنٹے", "hour")) return value * 3600;
        if (containsAny(text, "سیکنڈ", "second")) return value;
        return value * 60;
    }

    private int findFirstNumber(String text) {
        Matcher matcher = Pattern.compile("\\b\\d{1,3}\\b").matcher(text);
        if (matcher.find()) return parseSafeInt(matcher.group(), -1);

        LinkedHashMap<String, Integer> words = new LinkedHashMap<>();
        words.put("صفر", 0);
        words.put("ایک", 1);
        words.put("دو", 2);
        words.put("تین", 3);
        words.put("چار", 4);
        words.put("پانچ", 5);
        words.put("چھ", 6);
        words.put("سات", 7);
        words.put("آٹھ", 8);
        words.put("اٹھ", 8);
        words.put("نو", 9);
        words.put("دس", 10);
        words.put("گیارہ", 11);
        words.put("بارہ", 12);
        words.put("تیرہ", 13);
        words.put("چودہ", 14);
        words.put("پندرہ", 15);
        words.put("سولہ", 16);
        words.put("سترہ", 17);
        words.put("اٹھارہ", 18);
        words.put("انیس", 19);
        words.put("بیس", 20);

        for (Map.Entry<String, Integer> entry : words.entrySet()) {
            if (containsWholeToken(normalize(text), entry.getKey())) return entry.getValue();
        }
        return -1;
    }

    private int parseSafeInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private String cleanSearchQuery(String raw) {
        return cleanQuery(raw,
                "گوگل پر", "گوگل میں", "گوگل", "گوجل پر", "گوجل",
                "google پر", "google میں", "google",
                "ویب پر", "انٹرنیٹ پر",
                "تلاش کرو", "تلاش کریں", "سرچ کرو", "سرچ کریں", "search",
                "دکھاؤ", "بتاؤ", "کھولو");
    }

    private String cleanQuery(String raw, String... phrases) {
        String result = normalize(raw);
        for (String phrase : phrases) {
            result = result.replace(normalize(phrase), " ");
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replace('ي', 'ی')
                .replace('ك', 'ک')
                .replaceAll("[،,۔.!?؛:()\\[\\]{}\"']", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsAny(String text, String... phrases) {
        String normalizedText = normalize(text);
        for (String phrase : phrases) {
            if (normalizedText.contains(normalize(phrase))) return true;
        }
        return false;
    }

    private String extractPhoneNumber(String text) {
        Matcher matcher = Pattern.compile("\\+?[0-9][0-9\\s-]{6,18}[0-9]")
                .matcher(normalizeDigits(text));
        if (matcher.find()) return matcher.group().replaceAll("[\\s-]", "");
        return "";
    }

    private String normalizeDigits(String text) {
        if (text == null) return "";
        String arabic = "٠١٢٣٤٥٦٧٨٩";
        String persian = "۰۱۲۳۴۵۶۷۸۹";
        StringBuilder result = new StringBuilder();
        for (char character : text.toCharArray()) {
            int arabicIndex = arabic.indexOf(character);
            int persianIndex = persian.indexOf(character);
            if (arabicIndex >= 0) result.append(arabicIndex);
            else if (persianIndex >= 0) result.append(persianIndex);
            else result.append(character);
        }
        return result.toString();
    }

    private String extractAppName(String raw) {
        return cleanQuery(raw,
                "ایپ", "ایپلیکیشن", "app", "application",
                "کھولو", "کھول دو", "اوپن کرو", "اوپن", "open",
                "چلاؤ", "چلاو", "چلا دو", "start", "launch");
    }

    private boolean openAnyInstalledApp(String requestedName) {
        String target = normalizeAppKey(requestedName);
        if (target.isEmpty()) return false;

        Map<String, String[]> aliases = commonAppAliases();
        for (Map.Entry<String, String[]> entry : aliases.entrySet()) {
            for (String alias : entry.getValue()) {
                if (similarityScore(target, normalizeAppKey(alias)) >= 84) {
                    Intent launch = getPackageManager()
                            .getLaunchIntentForPackage(entry.getKey());
                    if (launch != null) {
                        speak(requestedName + " کھول رہا ہوں");
                        openIntent(launch);
                        return true;
                    }
                }
            }
        }

        Intent launcherQuery = new Intent(Intent.ACTION_MAIN);
        launcherQuery.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities =
                getPackageManager().queryIntentActivities(launcherQuery, 0);

        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo info : activities) {
            CharSequence labelSequence = info.loadLabel(getPackageManager());
            if (labelSequence == null) continue;
            String label = labelSequence.toString();
            int score = similarityScore(target, normalizeAppKey(label));
            if (score > bestScore) {
                bestScore = score;
                best = info;
            }
        }

        if (best == null || bestScore < 62) return false;

        Intent launch = getPackageManager().getLaunchIntentForPackage(
                best.activityInfo.packageName);
        if (launch == null) return false;

        CharSequence label = best.loadLabel(getPackageManager());
        speak((label == null ? requestedName : label.toString()) + " کھول رہا ہوں");
        openIntent(launch);
        return true;
    }

    private Map<String, String[]> commonAppAliases() {
        Map<String, String[]> aliases = new HashMap<>();
        aliases.put("com.google.android.youtube",
                new String[]{"یوٹیوب", "youtube"});
        aliases.put("com.whatsapp",
                new String[]{"واٹس ایپ", "whatsapp"});
        aliases.put("com.whatsapp.w4b",
                new String[]{"واٹس ایپ بزنس", "whatsapp business"});
        aliases.put("com.instagram.android",
                new String[]{"انسٹاگرام", "instagram"});
        aliases.put("com.facebook.katana",
                new String[]{"فیس بک", "facebook"});
        aliases.put("com.facebook.lite",
                new String[]{"فیس بک لائٹ", "facebook lite"});
        aliases.put("com.facebook.orca",
                new String[]{"میسنجر", "messenger"});
        aliases.put("com.zhiliaoapp.musically",
                new String[]{"ٹک ٹاک", "tiktok"});
        aliases.put("com.ss.android.ugc.trill",
                new String[]{"ٹک ٹاک", "tiktok"});
        aliases.put("com.google.android.apps.maps",
                new String[]{"میپس", "گوگل میپس", "maps"});
        aliases.put("com.google.android.gm",
                new String[]{"جی میل", "gmail", "ای میل"});
        aliases.put("com.android.chrome",
                new String[]{"کروم", "chrome", "براؤزر"});
        aliases.put("com.mi.globalbrowser",
                new String[]{"ایم آئی براؤزر", "mi browser", "براؤزر"});
        aliases.put("com.miui.gallery",
                new String[]{"گیلری", "gallery", "تصاویر"});
        aliases.put("com.google.android.apps.photos",
                new String[]{"فوٹوز", "photos", "تصاویر"});
        aliases.put("com.miui.calculator",
                new String[]{"کیلکولیٹر", "calculator"});
        aliases.put("com.android.vending",
                new String[]{"پلے اسٹور", "play store"});
        return aliases;
    }

    private String normalizeAppKey(String text) {
        return normalize(text)
                .replace("واٹس ایپ", "whatsapp")
                .replace("انسٹاگرام", "instagram")
                .replace("فیس بک", "facebook")
                .replace("میسنجر", "messenger")
                .replace("یوٹیوب", "youtube")
                .replace("میپس", "maps")
                .replace("جی میل", "gmail")
                .replace("کروم", "chrome")
                .replace("کیلکولیٹر", "calculator")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean openKnownApp(String spokenText, String fallbackUrl,
                                 String... packageNames) {
        for (String packageName : packageNames) {
            Intent launchIntent =
                    getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                speak(spokenText);
                openIntent(launchIntent);
                return true;
            }
        }

        if (fallbackUrl != null) {
            speak(spokenText);
            openUri(fallbackUrl);
            return true;
        }

        speak("یہ ایپ فون میں نہیں ملی");
        return false;
    }

    private void openGoogleSearch(String query) {
        String url = "https://www.google.com/search?q=" + Uri.encode(query);
        Intent chrome = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        chrome.setPackage("com.android.chrome");
        try {
            startActivity(chrome);
        } catch (Exception error) {
            openUri(url);
        }
    }

    private void openUri(String url) {
        openIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void openIntent(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception error) {
            updateStatus("یہ کام دستیاب نہیں",
                    "مطلوبہ ایپ یا فون کی سہولت نہیں ملی", COLOR_WARNING);
            Toast.makeText(this,
                    "مطلوبہ ایپ یا سہولت دستیاب نہیں",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareText(String text) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        speak("شیئر کرنے کے لیے ایپ منتخب کریں");
        openIntent(Intent.createChooser(share, "ایپ منتخب کریں"));
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        }
    }

    private void speak(String text) {
        updateStatus("مکمل", text, COLOR_SUCCESS);
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chintu-response");
        }
    }

    private void addHistory(String command) {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String existing = preferences.getString(PREF_HISTORY, "");
        LinkedHashSet<String> history = new LinkedHashSet<>();
        history.add(command);
        if (existing != null && !existing.isEmpty()) {
            history.addAll(Arrays.asList(existing.split("\\n")));
        }

        ArrayList<String> trimmed = new ArrayList<>();
        for (String item : history) {
            String value = item.trim();
            if (!value.isEmpty()) trimmed.add(value);
            if (trimmed.size() >= MAX_HISTORY) break;
        }

        preferences.edit()
                .putString(PREF_HISTORY, String.join("\n", trimmed))
                .apply();
        showHistory();
    }

    private void showHistory() {
        if (historyView == null) return;
        String history = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_HISTORY, "");
        if (history == null || history.trim().isEmpty()) {
            historyView.setText("ابھی کوئی کمانڈ نہیں");
            return;
        }

        StringBuilder formatted = new StringBuilder();
        for (String line : history.split("\\n")) {
            if (!line.trim().isEmpty()) {
                if (formatted.length() > 0) formatted.append("\n");
                formatted.append("• ").append(line.trim());
            }
        }
        historyView.setText(formatted.toString());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        PendingPermission action = pendingPermission;
        String command = pendingCommand;
        pendingPermission = PendingPermission.NONE;
        pendingCommand = null;

        if (!granted) {
            updateStatus("اجازت نہیں ملی",
                    "متعلقہ کام کے لیے فون کی اجازت درکار ہے", COLOR_WARNING);
            return;
        }

        if (requestCode == REQUEST_MICROPHONE
                && action == PendingPermission.VOICE) {
            startVoiceRecognition();
        } else if (requestCode == REQUEST_CONTACTS
                && action == PendingPermission.CONTACT_COMMAND
                && command != null) {
            executeCommand(command);
        } else if (requestCode == REQUEST_CAMERA
                && action == PendingPermission.TORCH_COMMAND
                && command != null) {
            executeCommand(command);
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;

        int result = tts.setLanguage(new Locale("ur", "PK"));
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
        }
        tts.setSpeechRate(0.92f);
        tts.setPitch(0.9f);
    }

    private void destroyRecognizer() {
        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        if (speechRecognizer == null) return;

        suppressErrorsUntil = SystemClock.uptimeMillis() + 900L;
        try {
            speechRecognizer.cancel();
        } catch (RuntimeException ignored) {
            // Xiaomi compatibility.
        }
        try {
            speechRecognizer.destroy();
        } catch (RuntimeException ignored) {
            // Xiaomi compatibility.
        }
        speechRecognizer = null;
    }

    @Override
    protected void onStop() {
        stopListeningInternal();
        destroyRecognizer();
        resetVoiceUi();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class ParsedTime {
        final int hour;
        final int minute;

        ParsedTime(int hour, int minute) {
            this.hour = hour;
            this.minute = minute;
        }
    }

    private static class ContactMatch {
        final String displayName;
        final String phoneNumber;
        final String typeLabel;
        final int score;

        ContactMatch(String displayName, String phoneNumber,
                     String typeLabel, int score) {
            this.displayName = displayName;
            this.phoneNumber = phoneNumber;
            this.typeLabel = typeLabel;
            this.score = score;
        }
    }
}
