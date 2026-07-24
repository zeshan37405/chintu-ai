package com.zeshan.chintuai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.Log;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChintuActivity extends Activity
        implements TextToSpeech.OnInitListener, VoiceRecognitionController.Callback {

    public static final String EXTRA_PENDING_COMMAND = "pending_command";

    private static final String TAG = "ChintuActivity";
    private static final int REQUEST_MICROPHONE = 4101;
    private static final int REQUEST_CONTACTS = 4102;
    private static final int REQUEST_CAMERA = 4103;
    private static final int REQUEST_CALL_PHONE = 4104;
    private static final int REQUEST_SYSTEM_SPEECH = 4105;
    private static final int REQUEST_HANDS_FREE = 4106;

    private static final String PREFS = "chintu_preferences";
    private static final String PREF_HISTORY = "command_history";
    private static final int MAX_HISTORY = 10;

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

    private static volatile boolean appVisible;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView heardView;
    private TextView historyView;
    private EditText commandInput;
    private Button micButton;
    private Button stopButton;
    private Button fallbackVoiceButton;
    private Button handsFreeButton;
    private Button accessibilityButton;

    private TextToSpeech tts;
    private boolean ttsReady;
    private VoiceRecognitionController voiceController;
    private boolean destroyed;
    private int contactGeneration;
    private String pendingCommand;
    private PendingPermission pendingPermission = PendingPermission.NONE;
    private boolean receiverRegistered;

    private final BroadcastReceiver handsFreeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (HandsFreeVoiceService.ACTION_STATUS.equals(action)) {
                String status = intent.getStringExtra(HandsFreeVoiceService.EXTRA_STATUS);
                String detail = intent.getStringExtra(HandsFreeVoiceService.EXTRA_DETAIL);
                updateStatus(status, detail, COLOR_ACCENT);
            } else if (HandsFreeVoiceService.ACTION_COMMAND.equals(action)) {
                String command = intent.getStringExtra(HandsFreeVoiceService.EXTRA_COMMAND);
                String result = intent.getStringExtra(HandsFreeVoiceService.EXTRA_RESULT);
                if (command != null && !command.trim().isEmpty()) {
                    commandInput.setText(command);
                    addHistory(command);
                }
                if (result != null && !result.trim().isEmpty()) {
                    updateStatus("مکمل", result, COLOR_SUCCESS);
                }
                updateHandsFreeUi();
            }
        }
    };

    private enum PendingPermission {
        NONE,
        VOICE,
        HANDS_FREE,
        CONTACT_COMMAND,
        CALL_COMMAND,
        TORCH_COMMAND
    }

    private enum ContactAction {
        SHOW,
        CALL,
        MESSAGE
    }

    public static boolean isAppVisible() {
        return appVisible;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND_TOP);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND_TOP);
        tts = new TextToSpeech(this, this);
        voiceController = new VoiceRecognitionController(this, this);
        setContentView(buildInterface());
        registerHandsFreeReceiver();
        showHistory();
        updateHandsFreeUi();
        updateAccessibilityUi();
        updateStatus("تیار ہوں", "بٹن دبائیں یا ہینڈز فری موڈ چلائیں", COLOR_SUCCESS);
        handlePendingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePendingIntent(intent);
    }

    private void handlePendingIntent(Intent intent) {
        if (intent == null) return;
        String command = intent.getStringExtra(EXTRA_PENDING_COMMAND);
        if (command == null || command.trim().isEmpty()) return;
        intent.removeExtra(EXTRA_PENDING_COMMAND);
        mainHandler.post(() -> {
            commandInput.setText(command);
            executeCommand(command);
        });
    }

    private ScrollView buildInterface() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackground(backgroundGradient());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        header.addView(titleColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("Chintu");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(36);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleColumn.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("ہینڈز فری موبائل اسسٹنٹ");
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(16);
        titleColumn.addView(subtitle, matchWrap());

        TextView versionBadge = new TextView(this);
        versionBadge.setText("VOICE " + BuildConfig.VERSION_NAME);
        versionBadge.setTextColor(Color.WHITE);
        versionBadge.setTextSize(11);
        versionBadge.setGravity(Gravity.CENTER);
        versionBadge.setPadding(dp(11), dp(7), dp(11), dp(7));
        versionBadge.setBackground(roundedBackground(COLOR_ACCENT_DARK, dp(18), 0, 0));
        header.addView(versionBadge, wrapWrap());

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        statusCard.setBackground(roundedBackground(COLOR_CARD, dp(22), 1, COLOR_CARD_LIGHT));
        LinearLayout.LayoutParams statusCardParams = matchWrap();
        statusCardParams.topMargin = dp(22);
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
        heardView.setMinHeight(dp(44));
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
            if (HandsFreeVoiceService.isEnabled(this)) {
                updateStatus("ہینڈز فری چل رہا ہے",
                        "بٹن کی ضرورت نہیں، سیدھا کمانڈ بولیں", COLOR_SUCCESS);
            } else if (voiceController.isActive()) {
                voiceController.cancel("سننا روک دیا");
            } else {
                startVoiceRecognition();
            }
        });
        LinearLayout.LayoutParams micParams = matchWrap();
        micParams.height = dp(68);
        micParams.topMargin = dp(18);
        root.addView(micButton, micParams);

        handsFreeButton = new Button(this);
        handsFreeButton.setAllCaps(false);
        handsFreeButton.setTextColor(Color.WHITE);
        handsFreeButton.setTextSize(17);
        handsFreeButton.setTypeface(Typeface.DEFAULT_BOLD);
        handsFreeButton.setBackground(roundedBackground(COLOR_CARD_LIGHT, dp(20), 1, COLOR_ACCENT));
        handsFreeButton.setOnClickListener(v -> toggleHandsFree());
        LinearLayout.LayoutParams handsParams = matchWrap();
        handsParams.height = dp(58);
        handsParams.topMargin = dp(10);
        root.addView(handsFreeButton, handsParams);

        LinearLayout voiceControls = new LinearLayout(this);
        voiceControls.setOrientation(LinearLayout.HORIZONTAL);
        voiceControls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams voiceControlParams = matchWrap();
        voiceControlParams.topMargin = dp(8);
        root.addView(voiceControls, voiceControlParams);

        stopButton = secondaryButton("روکیں");
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> voiceController.cancel("سننا روک دیا"));
        voiceControls.addView(stopButton, weightedButtonParams());

        fallbackVoiceButton = secondaryButton("Google وائس");
        fallbackVoiceButton.setOnClickListener(v -> launchSystemRecognizer(
                voiceController.createRecognitionIntent()));
        voiceControls.addView(fallbackVoiceButton, weightedButtonParams());

        accessibilityButton = secondaryButton("فون کنٹرول آن کریں");
        accessibilityButton.setOnClickListener(v -> openAccessibilitySettings());
        LinearLayout.LayoutParams accessParams = matchWrap();
        accessParams.height = dp(48);
        accessParams.topMargin = dp(8);
        root.addView(accessibilityButton, accessParams);

        Button batteryButton = secondaryButton("Redmi بیٹری پابندی ہٹائیں");
        batteryButton.setOnClickListener(v -> openBatterySettings());
        LinearLayout.LayoutParams batteryParams = matchWrap();
        batteryParams.height = dp(46);
        batteryParams.topMargin = dp(6);
        root.addView(batteryButton, batteryParams);

        LinearLayout inputCard = new LinearLayout(this);
        inputCard.setOrientation(LinearLayout.VERTICAL);
        inputCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        inputCard.setBackground(roundedBackground(COLOR_CARD, dp(20), 1, COLOR_CARD_LIGHT));
        LinearLayout.LayoutParams inputCardParams = matchWrap();
        inputCardParams.topMargin = dp(16);
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

        Button runButton = new Button(this);
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

        addGridSection(root, "فوری کام", new String[][]{
                {"📞 ہوم کو کال", "ہوم کو کال کرو"},
                {"🌤️ موسم", "آج حسن ابدال کا موسم تلاش کرو"},
                {"▶️ یوٹیوب", "یوٹیوب کھولو"},
                {"💬 واٹس ایپ", "واٹس ایپ کھولو"},
                {"📍 میپس", "گوگل میپس کھولو"},
                {"📷 کیمرہ", "کیمرہ کھولو"},
                {"⏱️ ٹائمر", "پانچ منٹ کا ٹائمر لگاؤ"},
                {"🔦 ٹارچ", "ٹارچ آن کرو"}
        });

        addGridSection(root, "پورا فون کنٹرول", new String[][]{
                {"🏠 ہوم اسکرین", "ہوم اسکرین کھولو"},
                {"↩️ واپس", "واپس جاؤ"},
                {"▣ حالیہ ایپس", "حالیہ ایپس کھولو"},
                {"🔔 نوٹیفکیشن", "نوٹیفکیشن کھولو"},
                {"⚙️ کوئیک سیٹنگ", "کوئیک سیٹنگ کھولو"},
                {"🔒 فون لاک", "فون لاک کرو"},
                {"📸 اسکرین شاٹ", "اسکرین شاٹ لو"},
                {"🔊 آواز تیز", "آواز تیز کرو"},
                {"🔉 آواز کم", "آواز کم کرو"},
                {"⏯️ پلے/پاز", "میوزک پلے یا پاز کرو"},
                {"⏭️ اگلا گانا", "اگلا گانا چلاؤ"},
                {"☀️ روشنی تیز", "روشنی تیز کرو"},
                {"📶 انٹرنیٹ", "وائی فائی کھولو"},
                {"🟦 بلوٹوتھ", "بلوٹوتھ کھولو"},
                {"🗓️ کیلنڈر", "کیلنڈر کھولو"},
                {"📁 فائلز", "فائلز کھولو"}
        });

        LinearLayout historyHeader = new LinearLayout(this);
        historyHeader.setOrientation(LinearLayout.HORIZONTAL);
        historyHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams historyHeaderParams = matchWrap();
        historyHeaderParams.topMargin = dp(22);
        root.addView(historyHeader, historyHeaderParams);

        TextView historyTitle = sectionTitle("حالیہ کمانڈز");
        historyHeader.addView(historyTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button helpButton = secondaryButton("تمام کمانڈز");
        helpButton.setOnClickListener(v -> showHelp());
        historyHeader.addView(helpButton, new LinearLayout.LayoutParams(dp(130), dp(42)));

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

        Button clearHistory = secondaryButton("ہسٹری صاف کریں");
        clearHistory.setOnClickListener(v -> clearHistory());
        LinearLayout.LayoutParams clearParams = matchWrap();
        clearParams.height = dp(44);
        clearParams.topMargin = dp(7);
        root.addView(clearHistory, clearParams);

        TextView hint = new TextView(this);
        hint.setText("ہینڈز فری آن ہو تو ایپ کے اندر سیدھا کمانڈ بولیں۔ دوسری ایپس یا اسکرین آف ہونے پر پہلے ‘چنٹو’ کہیں، پھر کمانڈ بولیں۔");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(10), dp(12), dp(10), 0);
        root.addView(hint, matchWrap());

        return scrollView;
    }

    private void addGridSection(LinearLayout root, String title, String[][] actions) {
        TextView section = sectionTitle(title);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(22);
        root.addView(section, titleParams);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        LinearLayout.LayoutParams gridParams = matchWrap();
        gridParams.topMargin = dp(8);
        root.addView(grid, gridParams);
        for (String[] action : actions) addQuickAction(grid, action[0], action[1]);
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(COLOR_MUTED);
        button.setTextSize(14);
        button.setBackground(roundedBackground(COLOR_CARD, dp(17), 1, COLOR_CARD_LIGHT));
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
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
        button.setTextSize(14);
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

    private void runTypedCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            updateStatus("کمانڈ موجود نہیں", "پہلے کمانڈ لکھیں یا بولیں", COLOR_WARNING);
            return;
        }
        hideKeyboard();
        executeCommand(command);
    }

    private void startVoiceRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = PendingPermission.VOICE;
            requestPermission(Manifest.permission.RECORD_AUDIO, REQUEST_MICROPHONE,
                    "وائس کمانڈ کے لیے مائیکروفون کی اجازت درکار ہے");
            return;
        }
        if (tts != null) tts.stop();
        voiceController.start();
    }

    private void launchSystemRecognizer(Intent intent) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = PendingPermission.VOICE;
            requestPermission(Manifest.permission.RECORD_AUDIO, REQUEST_MICROPHONE,
                    "Google وائس کے لیے مائیکروفون کی اجازت درکار ہے");
            return;
        }
        try {
            updateStatus("Google وائس", "مکمل کمانڈ بولیں", COLOR_ACCENT);
            startActivityForResult(intent, REQUEST_SYSTEM_SPEECH);
        } catch (ActivityNotFoundException | SecurityException error) {
            voiceController.onSystemLaunchFailed();
        }
    }

    private void toggleHandsFree() {
        if (HandsFreeVoiceService.isEnabled(this)) {
            stopHandsFreeService();
        } else {
            requestHandsFreePermissions();
        }
    }

    private void requestHandsFreePermissions() {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.RECORD_AUDIO);
        addIfMissing(missing, Manifest.permission.READ_CONTACTS);
        addIfMissing(missing, Manifest.permission.CALL_PHONE);
        addIfMissing(missing, Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33) addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        if (missing.isEmpty()) {
            startHandsFreeService();
            return;
        }
        pendingPermission = PendingPermission.HANDS_FREE;
        requestPermissions(missing.toArray(new String[0]), REQUEST_HANDS_FREE);
    }

    private void addIfMissing(List<String> permissions, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(permission);
        }
    }

    private void startHandsFreeService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            updateStatus("مائیکروفون اجازت درکار ہے", "ہینڈز فری شروع نہیں ہوا", COLOR_WARNING);
            return;
        }
        voiceController.cancel("");
        Intent intent = new Intent(this, HandsFreeVoiceService.class)
                .setAction(HandsFreeVoiceService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            updateStatus("ہینڈز فری شروع ہو رہا ہے",
                    "اب بٹن کے بغیر کمانڈ بولیں", COLOR_ACCENT);
            mainHandler.postDelayed(this::updateHandsFreeUi, 400L);
        } catch (RuntimeException error) {
            Log.w(TAG, "Hands-free service failed", error);
            updateStatus("ہینڈز فری شروع نہیں ہوا",
                    "ایپ سامنے رکھ کر دوبارہ کوشش کریں", COLOR_WARNING);
        }
    }

    private void stopHandsFreeService() {
        Intent intent = new Intent(this, HandsFreeVoiceService.class)
                .setAction(HandsFreeVoiceService.ACTION_STOP);
        try {
            startService(intent);
        } catch (RuntimeException error) {
            stopService(new Intent(this, HandsFreeVoiceService.class));
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("hands_free_enabled", false).apply();
        updateHandsFreeUi();
        updateStatus("ہینڈز فری بند", "وائس بٹن دوبارہ دستیاب ہے", COLOR_WARNING);
    }

    private void updateHandsFreeUi() {
        if (handsFreeButton == null || micButton == null) return;
        boolean enabled = HandsFreeVoiceService.isEnabled(this);
        handsFreeButton.setText(enabled
                ? "🟢 ہینڈز فری چل رہا ہے — بند کریں"
                : "🎧 ہینڈز فری آن کریں — بٹن کے بغیر وائس");
        handsFreeButton.setTextColor(enabled ? COLOR_SUCCESS : Color.WHITE);
        micButton.setText(enabled ? "🎧  چنٹو مسلسل سن رہا ہے" : "🎙️  حکم بولیں");
        stopButton.setVisibility(enabled ? View.GONE : stopButton.getVisibility());
        fallbackVoiceButton.setEnabled(!enabled);
    }

    private void executeCommand(String rawCommand) {
        String raw = rawCommand == null ? "" : rawCommand.trim();
        if (raw.isEmpty()) return;
        CommandEngine.ParsedCommand command = CommandEngine.parse(raw);
        addHistory(raw);
        updateStatus("کمانڈ موصول ہوئی", raw, COLOR_SUCCESS);

        switch (command.type) {
            case HANDS_FREE_ON:
                requestHandsFreePermissions();
                return;
            case HANDS_FREE_OFF:
                stopHandsFreeService();
                return;
            case HELP:
                showHelp();
                return;
            case BLOCKED_FINANCIAL:
                speak("مالی لین دین، پاس ورڈ اور حساس اکاؤنٹ تبدیلیاں بند ہیں");
                return;
            case CONTACT_LOOKUP:
                runContactCommand(raw, command.argument, ContactAction.SHOW);
                return;
            case CALL:
                if (!ensureCallPermission(raw)) return;
                if (isPhoneNumber(command.argument)) placeCall(command.argument);
                else runContactCommand(raw, command.argument, ContactAction.CALL);
                return;
            case SMS:
                if (isPhoneNumber(command.argument)) openMessage(command.argument);
                else runContactCommand(raw, command.argument, ContactAction.MESSAGE);
                return;
            case TORCH_ON:
            case TORCH_OFF:
                if (checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingPermission = PendingPermission.TORCH_COMMAND;
                    pendingCommand = raw;
                    requestPermission(Manifest.permission.CAMERA, REQUEST_CAMERA,
                            "ٹارچ کے لیے کیمرہ کی اجازت درکار ہے");
                    return;
                }
                break;
            case GLOBAL_HOME:
            case GLOBAL_BACK:
            case GLOBAL_RECENTS:
            case GLOBAL_NOTIFICATIONS:
            case GLOBAL_QUICK_SETTINGS:
            case GLOBAL_LOCK:
            case GLOBAL_SCREENSHOT:
                if (!ChintuAccessibilityService.isConnected()) {
                    promptAccessibility();
                    return;
                }
                break;
            default:
                break;
        }

        worker.execute(() -> {
            BackgroundCommandExecutor.Result result =
                    BackgroundCommandExecutor.execute(getApplicationContext(), raw);
            mainHandler.post(() -> {
                if (destroyed) return;
                if (result.message.isEmpty()) return;
                if (result.handled) speak(result.message);
                else updateStatus("کمانڈ مکمل نہیں ہوئی", result.message, COLOR_WARNING);
            });
        });
    }

    private boolean ensureCallPermission(String raw) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) return true;
        pendingPermission = PendingPermission.CALL_COMMAND;
        pendingCommand = raw;
        requestPermission(Manifest.permission.CALL_PHONE, REQUEST_CALL_PHONE,
                "براہ راست کال ملانے کے لیے فون کی اجازت درکار ہے");
        return false;
    }

    private void runContactCommand(String raw, String requestedName, ContactAction action) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = PendingPermission.CONTACT_COMMAND;
            pendingCommand = raw;
            requestPermission(Manifest.permission.READ_CONTACTS, REQUEST_CONTACTS,
                    "نام سے نمبر، کال یا میسج کے لیے کانٹیکٹس کی اجازت درکار ہے");
            return;
        }
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty()) {
            speak("کانٹیکٹ کا نام واضح بولیں");
            return;
        }
        final int requestId = ++contactGeneration;
        updateStatus("فون ڈائریکٹری دیکھ رہا ہوں", name, COLOR_ACCENT);
        worker.execute(() -> {
            ContactLookupResult result = findContacts(name);
            mainHandler.post(() -> {
                if (destroyed || isFinishing() || requestId != contactGeneration) return;
                if (result.error != null) {
                    updateStatus("فون ڈائریکٹری مسئلہ", result.error, COLOR_WARNING);
                    return;
                }
                if (result.matches.isEmpty()) {
                    speak("یہ نام فون ڈائریکٹری میں نہیں ملا");
                    return;
                }
                presentContactMatches(result.matches, action);
            });
        });
    }

    private ContactLookupResult findContacts(String requestedName) {
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
                projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cursor == null) return new ContactLookupResult(Collections.emptyList(),
                    "فون ڈائریکٹری دستیاب نہیں");
            int nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
            int normalizedIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER);
            int typeIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.TYPE);
            int labelIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.LABEL);
            int minimum = ContactMatcher.minimumAcceptedScore(requestedName);
            while (cursor.moveToNext()) {
                String displayName = safeCursorString(cursor, nameIndex);
                String number = safeCursorString(cursor, numberIndex);
                String normalizedNumber = safeCursorString(cursor, normalizedIndex);
                if (displayName.isEmpty() || number.isEmpty()) continue;
                int type = typeIndex >= 0 && !cursor.isNull(typeIndex) ? cursor.getInt(typeIndex) : 0;
                String customLabel = safeCursorString(cursor, labelIndex);
                String typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        getResources(), type, customLabel).toString();
                int score = ContactMatcher.score(requestedName, displayName, typeLabel);
                if (score < minimum) continue;
                if (ContactMatcher.isExactName(requestedName, displayName)) score += 30;
                if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) score += 3;
                String key = ContactMatcher.phoneKey(normalizedNumber, number);
                if (key.isEmpty()) key = displayName + "|" + number;
                ContactMatch current = bestByNumber.get(key);
                if (current == null || score > current.score) {
                    bestByNumber.put(key,
                            new ContactMatch(displayName, number, typeLabel, score));
                }
            }
        } catch (SecurityException error) {
            return new ContactLookupResult(Collections.emptyList(),
                    "کانٹیکٹس کی اجازت درکار ہے");
        } catch (RuntimeException error) {
            Log.w(TAG, "Contact lookup failed", error);
            return new ContactLookupResult(Collections.emptyList(),
                    "فون ڈائریکٹری پڑھنے میں مسئلہ آیا");
        }
        ArrayList<ContactMatch> matches = new ArrayList<>(bestByNumber.values());
        matches.sort((first, second) -> Integer.compare(second.score, first.score));
        return new ContactLookupResult(matches, null);
    }

    private void presentContactMatches(List<ContactMatch> matches, ContactAction action) {
        boolean ambiguous = matches.size() > 1
                && matches.get(0).score - matches.get(1).score < 7;
        if (!ambiguous) {
            runContactAction(matches.get(0), action);
            return;
        }
        int count = Math.min(matches.size(), 6);
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            ContactMatch match = matches.get(i);
            labels[i] = match.displayName + "  (" + match.typeLabel + ")\n" + match.phoneNumber;
        }
        new AlertDialog.Builder(this)
                .setTitle("صحیح کانٹیکٹ منتخب کریں")
                .setItems(labels, (dialog, which) -> runContactAction(matches.get(which), action))
                .setNegativeButton("منسوخ", null)
                .show();
    }

    private void runContactAction(ContactMatch match, ContactAction action) {
        switch (action) {
            case CALL:
                placeCall(match.phoneNumber);
                break;
            case MESSAGE:
                openMessage(match.phoneNumber);
                break;
            case SHOW:
            default:
                copyToClipboard(match.displayName, match.phoneNumber);
                speak(match.displayName + " کا نمبر " + match.phoneNumber + " ہے اور کاپی ہو گیا ہے");
                break;
        }
    }

    private void placeCall(String number) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            updateStatus("فون اجازت درکار ہے", "براہ راست کال نہیں مل سکتی", COLOR_WARNING);
            return;
        }
        try {
            speak("کال ملا رہا ہوں");
            startActivity(new Intent(Intent.ACTION_CALL,
                    Uri.fromParts("tel", number, null)));
        } catch (SecurityException | ActivityNotFoundException error) {
            updateStatus("کال نہیں ملی", "ڈائلر یا فون اجازت دستیاب نہیں", COLOR_WARNING);
        }
    }

    private void openMessage(String number) {
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO,
                    Uri.fromParts("smsto", number, null)));
        } catch (ActivityNotFoundException error) {
            updateStatus("میسج ایپ نہیں ملی", "", COLOR_WARNING);
        }
    }

    private boolean isPhoneNumber(String value) {
        return value != null && value.matches("\\+?[0-9]{7,20}");
    }

    private String safeCursorString(Cursor cursor, int index) {
        if (index < 0 || cursor.isNull(index)) return "";
        String value = cursor.getString(index);
        return value == null ? "" : value.trim();
    }

    private void promptAccessibility() {
        new AlertDialog.Builder(this)
                .setTitle("پورا فون کنٹرول")
                .setMessage("ہوم، واپس، حالیہ ایپس، نوٹیفکیشن، لاک اور اسکرین شاٹ کے لیے Chintu AI Accessibility سروس آن کریں۔ چنٹو اسکرین کا متن نہیں پڑھتا؛ صرف آپ کی واضح کمانڈ پر global action چلاتا ہے۔")
                .setPositiveButton("Accessibility کھولیں", (dialog, which) -> openAccessibilitySettings())
                .setNegativeButton("منسوخ", null)
                .show();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException error) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (ActivityNotFoundException error) {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(details);
        }
    }

    private void updateAccessibilityUi() {
        if (accessibilityButton == null) return;
        boolean connected = ChintuAccessibilityService.isConnected();
        accessibilityButton.setText(connected
                ? "🟢 پورا فون کنٹرول آن ہے"
                : "⚙️ پورا فون کنٹرول آن کریں");
        accessibilityButton.setTextColor(connected ? COLOR_SUCCESS : COLOR_MUTED);
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("چنٹو کی مکمل کمانڈز")
                .setMessage(
                        "ہینڈز فری:\n" +
                        "• ہینڈز فری آن کرو / بند کرو\n" +
                        "• دوسری ایپ میں: چنٹو، ہوم کو کال کرو\n\n" +
                        "فون کنٹرول:\n" +
                        "• ہوم اسکرین، واپس، حالیہ ایپس\n" +
                        "• نوٹیفکیشن، کوئیک سیٹنگز، فون لاک، اسکرین شاٹ\n" +
                        "• آواز تیز/کم/بند/فل، پلے، پاز، اگلا یا پچھلا گانا\n" +
                        "• روشنی تیز/کم/فل\n\n" +
                        "روزمرہ کام:\n" +
                        "• نام سے براہ راست کال، نمبر نکالنا یا میسج\n" +
                        "• کوئی بھی انسٹال شدہ ایپ کھولنا\n" +
                        "• گوگل، یوٹیوب، موسم اور میپس سرچ\n" +
                        "• الارم، ٹائمر، وقت، تاریخ، ٹارچ، کیمرہ، گیلری\n" +
                        "• وائی فائی، بلوٹوتھ، موبائل ڈیٹا، کیلنڈر، فائلز اور سیٹنگز\n" +
                        "• متن کاپی یا شیئر کرنا\n\n" +
                        "بینک، رقم ٹرانسفر، پاس ورڈ اور حساس اکاؤنٹ تبدیلیاں بند ہیں۔")
                .setPositiveButton("ٹھیک ہے", null)
                .show();
    }

    private void requestPermission(String permission, int requestCode, String explanation) {
        if (shouldShowRequestPermissionRationale(permission)) {
            new AlertDialog.Builder(this)
                    .setTitle("اجازت درکار ہے")
                    .setMessage(explanation)
                    .setPositiveButton("اجازت دیں", (dialog, which) ->
                            requestPermissions(new String[]{permission}, requestCode))
                    .setNegativeButton("منسوخ", null)
                    .show();
        } else {
            requestPermissions(new String[]{permission}, requestCode);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_HANDS_FREE) {
            pendingPermission = PendingPermission.NONE;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                startHandsFreeService();
            } else {
                updateStatus("مائیکروفون اجازت نہیں ملی",
                        "ہینڈز فری شروع نہیں ہوا", COLOR_WARNING);
            }
            return;
        }

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
        if (requestCode == REQUEST_MICROPHONE && action == PendingPermission.VOICE) {
            startVoiceRecognition();
        } else if (requestCode == REQUEST_CONTACTS
                && action == PendingPermission.CONTACT_COMMAND && command != null) {
            executeCommand(command);
        } else if (requestCode == REQUEST_CALL_PHONE
                && action == PendingPermission.CALL_COMMAND && command != null) {
            executeCommand(command);
        } else if (requestCode == REQUEST_CAMERA
                && action == PendingPermission.TORCH_COMMAND && command != null) {
            executeCommand(command);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SYSTEM_SPEECH) return;
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            voiceController.onSystemResult(matches);
        } else {
            voiceController.onSystemCancelled();
        }
    }

    @Override
    public void onVoiceState(VoiceRecognitionController.State state,
                             String status, String detail) {
        switch (state) {
            case PREPARING:
            case STARTING:
            case LISTENING:
                micButton.setText("🎙️  سن رہا ہوں...");
                stopButton.setVisibility(View.VISIBLE);
                fallbackVoiceButton.setEnabled(false);
                break;
            case PROCESSING:
                micButton.setText("سمجھ رہا ہوں...");
                stopButton.setVisibility(View.VISIBLE);
                fallbackVoiceButton.setEnabled(false);
                break;
            case RECOVERING:
                micButton.setText("🎙️  سن رہا ہوں...");
                stopButton.setVisibility(View.VISIBLE);
                fallbackVoiceButton.setEnabled(false);
                break;
            case IDLE:
            case SYSTEM_FALLBACK:
            default:
                resetVoiceUi();
                break;
        }
        if (status != null && !status.isEmpty()) {
            updateStatus(status, detail,
                    state == VoiceRecognitionController.State.IDLE ? COLOR_WARNING : COLOR_ACCENT);
        }
    }

    @Override
    public void onPartialText(String text) {
        if (heardView != null) heardView.setText(text == null ? "" : text);
    }

    @Override
    public void onCommandRecognized(String command) {
        resetVoiceUi();
        if (command == null || command.trim().isEmpty()) {
            updateStatus("آواز واضح نہیں ملی", "دوبارہ بولیں یا کمانڈ لکھیں", COLOR_WARNING);
            return;
        }
        commandInput.setText(command.trim());
        executeCommand(command.trim());
    }

    @Override
    public void onSystemRecognizerRequested(Intent intent) {
        // Automatic popup fallback is deliberately disabled; user can tap Google Voice manually.
    }

    @Override
    public void onVoiceUnavailable(String reason) {
        resetVoiceUi();
        updateStatus("آواز شناخت میں مسئلہ",
                reason == null ? "کمانڈ لکھ کر چلائیں" : reason, COLOR_WARNING);
    }

    private void resetVoiceUi() {
        if (HandsFreeVoiceService.isEnabled(this)) {
            updateHandsFreeUi();
            return;
        }
        if (micButton != null) micButton.setText("🎙️  حکم بولیں");
        if (stopButton != null) stopButton.setVisibility(View.GONE);
        if (fallbackVoiceButton != null) fallbackVoiceButton.setEnabled(true);
    }

    private void updateStatus(String status, String detail, int statusColor) {
        if (statusView != null) {
            statusView.setText(status == null ? "" : status);
            statusView.setTextColor(statusColor);
        }
        if (heardView != null) heardView.setText(detail == null ? "" : detail);
    }

    private void speak(String text) {
        updateStatus("مکمل", text, COLOR_SUCCESS);
        if (ttsReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chintu-response");
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) return;
        int result = tts.setLanguage(new Locale("ur", "PK"));
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
        }
        tts.setSpeechRate(0.92f);
        tts.setPitch(0.9f);
        ttsReady = true;
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        }
    }

    private void addHistory(String command) {
        String sanitized = command.replace('\n', ' ').trim();
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String existing = preferences.getString(PREF_HISTORY, "");
        LinkedHashSet<String> history = new LinkedHashSet<>();
        history.add(sanitized);
        if (existing != null && !existing.isEmpty()) {
            history.addAll(Arrays.asList(existing.split("\\n")));
        }
        ArrayList<String> trimmed = new ArrayList<>();
        for (String item : history) {
            String value = item.trim();
            if (!value.isEmpty()) trimmed.add(value);
            if (trimmed.size() >= MAX_HISTORY) break;
        }
        preferences.edit().putString(PREF_HISTORY, String.join("\n", trimmed)).apply();
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

    private void clearHistory() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_HISTORY).apply();
        showHistory();
        updateStatus("ہسٹری صاف ہو گئی", "", COLOR_SUCCESS);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) focus = commandInput;
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null && focus != null) {
            manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        if (commandInput != null) commandInput.clearFocus();
    }

    private void registerHandsFreeReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(HandsFreeVoiceService.ACTION_STATUS);
        filter.addAction(HandsFreeVoiceService.ACTION_COMMAND);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(handsFreeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(handsFreeReceiver, filter);
        }
        receiverRegistered = true;
    }

    private GradientDrawable backgroundGradient() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COLOR_BACKGROUND_TOP, COLOR_BACKGROUND_BOTTOM});
        drawable.setShape(GradientDrawable.RECTANGLE);
        return drawable;
    }

    private GradientDrawable roundedBackground(int color, int radius,
                                               int strokeWidth, int strokeColor) {
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        appVisible = true;
        if (voiceController != null) {
            voiceController.setForeground(true);
            voiceController.prepare();
        }
        updateHandsFreeUi();
        updateAccessibilityUi();
    }

    @Override
    protected void onStop() {
        appVisible = false;
        if (voiceController != null) voiceController.setForeground(false);
        resetVoiceUi();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        appVisible = false;
        contactGeneration++;
        worker.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (voiceController != null) voiceController.release();
        if (receiverRegistered) {
            try {
                unregisterReceiver(handsFreeReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private static final class ContactMatch {
        final String displayName;
        final String phoneNumber;
        final String typeLabel;
        final int score;

        ContactMatch(String displayName, String phoneNumber, String typeLabel, int score) {
            this.displayName = displayName;
            this.phoneNumber = phoneNumber;
            this.typeLabel = typeLabel;
            this.score = score;
        }
    }

    private static final class ContactLookupResult {
        final List<ContactMatch> matches;
        final String error;

        ContactLookupResult(List<ContactMatch> matches, String error) {
            this.matches = matches;
            this.error = error;
        }
    }
}
