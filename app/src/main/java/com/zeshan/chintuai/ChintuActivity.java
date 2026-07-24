package com.zeshan.chintuai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
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
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChintuActivity extends Activity
        implements TextToSpeech.OnInitListener, VoiceRecognitionController.Callback {

    private static final String TAG = "ChintuActivity";
    private static final int REQUEST_MICROPHONE = 4101;
    private static final int REQUEST_CONTACTS = 4102;
    private static final int REQUEST_CAMERA = 4103;
    private static final int REQUEST_SYSTEM_SPEECH = 4104;

    private static final String PREFS = "chintu_preferences";
    private static final String PREF_HISTORY = "command_history";
    private static final int MAX_HISTORY = 8;

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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService contactExecutor = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView heardView;
    private TextView historyView;
    private EditText commandInput;
    private Button micButton;
    private Button stopButton;
    private Button fallbackVoiceButton;

    private TextToSpeech tts;
    private boolean ttsReady;
    private VoiceRecognitionController voiceController;
    private boolean awaitingSystemSpeech;
    private boolean destroyed;
    private int contactGeneration;

    private String pendingCommand;
    private PendingPermission pendingPermission = PendingPermission.NONE;

    private enum PendingPermission {
        NONE,
        VOICE,
        CONTACT_COMMAND,
        TORCH_COMMAND
    }

    private enum ContactAction {
        SHOW,
        DIAL,
        MESSAGE
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND_TOP);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND_TOP);
        tts = new TextToSpeech(this, this);
        voiceController = new VoiceRecognitionController(this, this);
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
        subtitle.setText("آپ کا ذاتی موبائل اسسٹنٹ");
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(16);
        titleColumn.addView(subtitle, matchWrap());

        TextView versionBadge = new TextView(this);
        versionBadge.setText("STABLE " + BuildConfig.VERSION_NAME);
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
        micButton.setContentDescription("وائس کمانڈ شروع کریں");
        micButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (voiceController.isActive()) voiceController.cancel("سننا روک دیا");
            else startVoiceRecognition();
        });
        LinearLayout.LayoutParams micParams = matchWrap();
        micParams.height = dp(68);
        micParams.topMargin = dp(18);
        root.addView(micButton, micParams);

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

        fallbackVoiceButton = secondaryButton("متبادل وائس");
        fallbackVoiceButton.setOnClickListener(v -> launchSystemRecognizer(createSpeechIntent()));
        voiceControls.addView(fallbackVoiceButton, weightedButtonParams());

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
        addQuickAction(quickGrid, "⏱️ ٹائمر", "پانچ منٹ کا ٹائمر لگاؤ");
        addQuickAction(quickGrid, "🔦 ٹارچ", "ٹارچ آن کرو");

        LinearLayout historyHeader = new LinearLayout(this);
        historyHeader.setOrientation(LinearLayout.HORIZONTAL);
        historyHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams historyHeaderParams = matchWrap();
        historyHeaderParams.topMargin = dp(22);
        root.addView(historyHeader, historyHeaderParams);

        TextView historyTitle = sectionTitle("حالیہ کمانڈز");
        historyHeader.addView(historyTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button clearHistory = secondaryButton("صاف کریں");
        clearHistory.setOnClickListener(v -> clearHistory());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(100), dp(42));
        historyHeader.addView(clearHistory, clearParams);

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
        hint.setText("وائس رک جائے تو چنٹو خود دوبارہ کوشش کرے گا اور پھر Google وائس ونڈو کھولے گا۔ نامعلوم کمانڈ گوگل پر تلاش ہوگی۔");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(10), dp(12), dp(10), 0);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(8);
        root.addView(hint, hintParams);

        return scrollView;
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

    private Intent createSpeechIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "چنٹو کو حکم بولیں");
        return intent;
    }

    private void launchSystemRecognizer(Intent intent) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = PendingPermission.VOICE;
            requestPermission(Manifest.permission.RECORD_AUDIO, REQUEST_MICROPHONE,
                    "وائس کمانڈ کے لیے مائیکروفون کی اجازت درکار ہے");
            return;
        }
        try {
            awaitingSystemSpeech = true;
            updateStatus("متبادل وائس شناخت", "Google وائس ونڈو میں حکم بولیں", COLOR_ACCENT);
            startActivityForResult(intent, REQUEST_SYSTEM_SPEECH);
        } catch (ActivityNotFoundException | SecurityException error) {
            awaitingSystemSpeech = false;
            voiceController.onSystemLaunchFailed();
        } catch (RuntimeException error) {
            awaitingSystemSpeech = false;
            Log.w(TAG, "System speech launch failed", error);
            voiceController.onSystemLaunchFailed();
        }
    }

    private void executeCommand(String rawCommand) {
        String raw = rawCommand == null ? "" : rawCommand.trim();
        if (raw.isEmpty()) return;
        try {
            CommandEngine.ParsedCommand command = CommandEngine.parse(raw);
            addHistory(raw);
            updateStatus("کمانڈ موصول ہوئی", raw, COLOR_SUCCESS);

            switch (command.type) {
                case BLOCKED_FINANCIAL:
                    speak("مالی لین دین، پاس ورڈ اور حساس اکاؤنٹ تبدیلیاں محفوظ طریقے سے بند ہیں");
                    break;
                case HELP:
                    showHelp();
                    break;
                case TIME:
                    speak("اس وقت " + new SimpleDateFormat("hh:mm a", Locale.getDefault())
                            .format(new Date()) + " ہوئے ہیں");
                    break;
                case DATE:
                    speak("آج " + new SimpleDateFormat(
                            "EEEE، d MMMM yyyy", new Locale("ur", "PK")).format(new Date()) + " ہے");
                    break;
                case TORCH_ON:
                case TORCH_OFF:
                    handleTorchCommand(raw, command.type == CommandEngine.Type.TORCH_ON);
                    break;
                case ALARM:
                    setAlarm(raw);
                    break;
                case TIMER:
                    setTimer(raw);
                    break;
                case CONTACT_LOOKUP:
                    runContactCommand(raw, command.argument, ContactAction.SHOW);
                    break;
                case CALL:
                    if (isPhoneNumber(command.argument)) openDialer(command.argument);
                    else runContactCommand(raw, command.argument, ContactAction.DIAL);
                    break;
                case SMS:
                    if (isPhoneNumber(command.argument)) openMessage(command.argument);
                    else runContactCommand(raw, command.argument, ContactAction.MESSAGE);
                    break;
                case COPY:
                    if (command.argument.isEmpty()) speak("کاپی کرنے کے لیے متن بھی بولیں");
                    else {
                        copyToClipboard("Chintu", command.argument);
                        speak("متن کاپی ہو گیا");
                    }
                    break;
                case SHARE:
                    if (command.argument.isEmpty()) speak("شیئر کرنے کے لیے متن بھی بولیں");
                    else shareText(command.argument);
                    break;
                case YOUTUBE_SEARCH:
                    if (command.argument.isEmpty()) openRequestedApp("یوٹیوب");
                    else {
                        speak("یوٹیوب پر تلاش کر رہا ہوں");
                        openUrl("https://www.youtube.com/results?search_query="
                                + Uri.encode(command.argument));
                    }
                    break;
                case WEATHER:
                    String weatherQuery = command.argument.isEmpty()
                            ? "حسن ابدال موسم آج" : command.argument + " موسم";
                    speak("موسم تلاش کر رہا ہوں");
                    openGoogleSearch(weatherQuery);
                    break;
                case GOOGLE_SEARCH:
                    openGoogleSearch(command.argument.isEmpty() ? raw : command.argument);
                    break;
                case MAP_SEARCH:
                    if (command.argument.isEmpty()) openRequestedApp("گوگل میپس");
                    else openMapSearch(command.argument);
                    break;
                case OPEN_CONTACTS:
                    safeStart(new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
                            "کانٹیکٹس ایپ دستیاب نہیں");
                    break;
                case OPEN_DIALER:
                    safeStart(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
                            "ڈائلر دستیاب نہیں");
                    break;
                case OPEN_MESSAGES:
                    safeStart(Intent.makeMainSelectorActivity(
                                    Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
                            "میسجز ایپ دستیاب نہیں");
                    break;
                case OPEN_GMAIL:
                    openRequestedApp("جی میل");
                    break;
                case OPEN_CALCULATOR:
                    if (!openRequestedApp("کیلکولیٹر")) {
                        safeStart(Intent.makeMainSelectorActivity(
                                        Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR),
                                "کیلکولیٹر دستیاب نہیں");
                    }
                    break;
                case OPEN_CAMERA:
                    openCamera();
                    break;
                case OPEN_GALLERY:
                    if (!openRequestedApp("گیلری")) {
                        safeStart(new Intent(Intent.ACTION_VIEW,
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
                                "گیلری دستیاب نہیں");
                    }
                    break;
                case OPEN_WIFI:
                    safeStart(new Intent(Settings.ACTION_WIFI_SETTINGS),
                            "وائی فائی سیٹنگز دستیاب نہیں");
                    break;
                case OPEN_BLUETOOTH:
                    safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                            "بلوٹوتھ سیٹنگز دستیاب نہیں");
                    break;
                case OPEN_SETTINGS:
                    safeStart(new Intent(Settings.ACTION_SETTINGS),
                            "سیٹنگز دستیاب نہیں");
                    break;
                case OPEN_PLAY_STORE:
                    openRequestedApp("پلے اسٹور");
                    break;
                case OPEN_APP:
                    if (!openRequestedApp(command.argument)) showAppNotFound(command.argument);
                    break;
                case UNKNOWN:
                default:
                    speak("یہ کمانڈ گوگل پر تلاش کر رہا ہوں");
                    openGoogleSearch(raw);
                    break;
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Command execution failed", error);
            updateStatus("کمانڈ مکمل نہیں ہوئی",
                    "ایپ محفوظ حالت میں واپس آ گئی، دوبارہ کوشش کریں", COLOR_WARNING);
            Toast.makeText(this, "کمانڈ مکمل نہیں ہوئی", Toast.LENGTH_LONG).show();
        }
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("چنٹو کی کمانڈز")
                .setMessage(
                        "• Redmi/HyperOS وائس شناخت اور متبادل Google وائس\n" +
                        "• گوگل، یوٹیوب، موسم اور میپس سرچ\n" +
                        "• نام سے نمبر، کال اور میسج\n" +
                        "• انسٹال شدہ ایپس نام سے کھولنا\n" +
                        "• الارم، ٹائمر، وقت اور تاریخ\n" +
                        "• ٹارچ، کیمرہ، گیلری اور سیٹنگز\n" +
                        "• متن کاپی یا شیئر کرنا\n\n" +
                        "بینک، رقم ٹرانسفر، پاس ورڈ اور حساس اکاؤنٹ تبدیلیاں بند ہیں۔")
                .setPositiveButton("ٹھیک ہے", null)
                .show();
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
        contactExecutor.execute(() -> {
            ContactLookupResult result = findContacts(name);
            mainHandler.post(() -> {
                if (destroyed || isFinishing() || requestId != contactGeneration) return;
                if (result.error != null) {
                    speak(result.error);
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
                projection,
                null,
                null,
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
                if (score < 58) continue;
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
        if (matches.size() > 1 && matches.get(0).score - matches.get(1).score < 9) {
            int count = Math.min(matches.size(), 6);
            String[] labels = new String[count];
            for (int i = 0; i < count; i++) {
                ContactMatch match = matches.get(i);
                labels[i] = match.displayName + "  (" + match.typeLabel + ")\n" + match.phoneNumber;
            }
            new AlertDialog.Builder(this)
                    .setTitle("صحیح کانٹیکٹ منتخب کریں")
                    .setItems(labels, (dialog, which) ->
                            runContactAction(matches.get(which), action))
                    .setNegativeButton("منسوخ", null)
                    .show();
        } else {
            runContactAction(matches.get(0), action);
        }
    }

    private void runContactAction(ContactMatch match, ContactAction action) {
        switch (action) {
            case DIAL:
                openDialer(match.phoneNumber);
                break;
            case MESSAGE:
                openMessage(match.phoneNumber);
                break;
            case SHOW:
            default:
                copyToClipboard(match.displayName, match.phoneNumber);
                updateStatus("نمبر مل گیا",
                        match.displayName + "\n" + match.phoneNumber + "\nنمبر کاپی ہو گیا",
                        COLOR_SUCCESS);
                speak(match.displayName + " کا نمبر مل گیا اور کاپی ہو گیا");
                break;
        }
    }

    private String safeCursorString(Cursor cursor, int index) {
        if (index < 0 || cursor.isNull(index)) return "";
        String value = cursor.getString(index);
        return value == null ? "" : value.trim();
    }

    private boolean openRequestedApp(String requestedName) {
        String requested = requestedName == null ? "" : requestedName.trim();
        if (requested.isEmpty()) return false;

        AppCatalog.AppMatch known = AppCatalog.findBest(requested);
        if (known != null && known.score >= 82) {
            for (String packageName : known.app.packageNames) {
                if (launchPackage(packageName, known.app.displayName)) return true;
            }
        }

        List<LauncherCandidate> candidates = launcherCandidates(requested);
        if (!candidates.isEmpty()) {
            LauncherCandidate best = candidates.get(0);
            int secondScore = candidates.size() > 1 ? candidates.get(1).score : 0;
            if (best.score >= 88 && best.score - secondScore >= 8) {
                return launchCandidate(best);
            }
            if (best.score >= 64) {
                showAppPicker(candidates);
                return true;
            }
        }

        if (known != null && known.score >= 76 && known.app.fallbackUrl != null) {
            speak(known.app.displayName + " ایپ نہیں ملی، ویب صفحہ کھول رہا ہوں");
            return openUrl(known.app.fallbackUrl);
        }
        return false;
    }

    private boolean launchPackage(String packageName, String spokenName) {
        PackageManager manager = getPackageManager();
        Intent launch = manager.getLaunchIntentForPackage(packageName);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            speak(spokenName + " کھول رہا ہوں");
            return safeStart(launch, spokenName + " نہیں کھلی");
        }

        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : manager.queryIntentActivities(query, 0)) {
            if (info.activityInfo == null
                    || !packageName.equals(info.activityInfo.packageName)) continue;
            Intent explicit = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(
                            info.activityInfo.packageName, info.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            speak(spokenName + " کھول رہا ہوں");
            return safeStart(explicit, spokenName + " نہیں کھلی");
        }
        return false;
    }

    private List<LauncherCandidate> launcherCandidates(String requestedName) {
        ArrayList<LauncherCandidate> candidates = new ArrayList<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : getPackageManager().queryIntentActivities(query, 0)) {
            if (info.activityInfo == null
                    || getPackageName().equals(info.activityInfo.packageName)) continue;
            CharSequence labelSequence = info.loadLabel(getPackageManager());
            if (labelSequence == null) continue;
            String label = labelSequence.toString().trim();
            int score = AppCatalog.scoreName(requestedName, label);
            if (score >= 52) {
                candidates.add(new LauncherCandidate(label,
                        info.activityInfo.packageName, info.activityInfo.name, score));
            }
        }
        candidates.sort((first, second) -> Integer.compare(second.score, first.score));
        return candidates;
    }

    private void showAppPicker(List<LauncherCandidate> candidates) {
        int count = Math.min(candidates.size(), 6);
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) labels[i] = candidates.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("ایپ منتخب کریں")
                .setItems(labels, (dialog, which) -> launchCandidate(candidates.get(which)))
                .setNegativeButton("منسوخ", null)
                .show();
    }

    private boolean launchCandidate(LauncherCandidate candidate) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(candidate.packageName, candidate.className))
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        speak(candidate.label + " کھول رہا ہوں");
        return safeStart(intent, candidate.label + " نہیں کھلی");
    }

    private void showAppNotFound(String requestedName) {
        String name = requestedName == null || requestedName.trim().isEmpty()
                ? "یہ ایپ" : requestedName.trim();
        new AlertDialog.Builder(this)
                .setTitle("ایپ نہیں ملی")
                .setMessage(name + " فون میں نہیں ملی۔")
                .setPositiveButton("پلے اسٹور میں تلاش", (dialog, which) -> {
                    String market = "market://search?q=" + Uri.encode(name);
                    String web = "https://play.google.com/store/search?q="
                            + Uri.encode(name) + "&c=apps";
                    if (!safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse(market)), "")) {
                        openUrl(web);
                    }
                })
                .setNegativeButton("منسوخ", null)
                .show();
    }

    private void openCamera() {
        Intent primary = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        Intent fallback = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (!safeStart(primary, "")) safeStart(fallback, "کیمرہ دستیاب نہیں");
    }

    private void openMapSearch(String query) {
        speak("میپس میں تلاش کر رہا ہوں");
        Intent geo = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + Uri.encode(query)));
        Intent web = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query)));
        if (!safeStart(geo, "")) safeStart(web, "میپس دستیاب نہیں");
    }

    private void openGoogleSearch(String query) {
        String cleaned = query == null ? "" : query.trim();
        if (cleaned.isEmpty()) {
            speak("تلاش کے لیے الفاظ واضح بولیں");
            return;
        }
        speak("گوگل پر تلاش کر رہا ہوں");
        openUrl("https://www.google.com/search?q=" + Uri.encode(cleaned));
    }

    private boolean openUrl(String url) {
        return safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                "براؤزر دستیاب نہیں");
    }

    private void openDialer(String number) {
        speak("نمبر ڈائلر میں کھول رہا ہوں");
        safeStart(new Intent(Intent.ACTION_DIAL,
                Uri.fromParts("tel", number, null)), "ڈائلر دستیاب نہیں");
    }

    private void openMessage(String number) {
        speak("میسج لکھنے کا صفحہ کھول رہا ہوں");
        safeStart(new Intent(Intent.ACTION_SENDTO,
                Uri.fromParts("smsto", number, null)), "میسج ایپ دستیاب نہیں");
    }

    private boolean isPhoneNumber(String value) {
        return value != null && value.matches("\\+?[0-9]{7,20}");
    }

    private void handleTorchCommand(String rawCommand, boolean turnOn) {
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = PendingPermission.TORCH_COMMAND;
            pendingCommand = rawCommand;
            requestPermission(Manifest.permission.CAMERA, REQUEST_CAMERA,
                    "ٹارچ چلانے کے لیے کیمرہ کی اجازت درکار ہے");
            return;
        }
        setTorch(turnOn);
    }

    private void setTorch(boolean enabled) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            speak("ٹارچ دستیاب نہیں");
            return;
        }
        try {
            String cameraId = findFlashCamera(manager);
            if (cameraId == null) {
                speak("اس فون میں ٹارچ دستیاب نہیں");
                return;
            }
            manager.setTorchMode(cameraId, enabled);
            speak(enabled ? "ٹارچ آن کر دی ہے" : "ٹارچ بند کر دی ہے");
        } catch (CameraAccessException | SecurityException error) {
            Log.w(TAG, "Torch command failed", error);
            speak("ٹارچ چلانے میں مسئلہ آیا");
        }
    }

    private String findFlashCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Boolean flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (Boolean.TRUE.equals(flash)
                    && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                return id;
            }
        }
        return null;
    }

    private void setAlarm(String raw) {
        CommandEngine.ParsedTime time = CommandEngine.parseClockTime(raw);
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Chintu Alarm")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        if (time != null) {
            intent.putExtra(AlarmClock.EXTRA_HOUR, time.hour);
            intent.putExtra(AlarmClock.EXTRA_MINUTES, time.minute);
            speak("الارم تیار کر رہا ہوں");
        } else {
            speak("وقت واضح نہیں ملا، الارم ایپ کھول رہا ہوں");
        }
        safeStart(intent, "الارم ایپ دستیاب نہیں");
    }

    private void setTimer(String raw) {
        int seconds = CommandEngine.parseDurationSeconds(raw);
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Chintu Timer")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        if (seconds > 0) {
            intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
            speak("ٹائمر تیار کر رہا ہوں");
        } else {
            speak("مدت واضح نہیں ملی، ٹائمر ایپ کھول رہا ہوں");
        }
        safeStart(intent, "ٹائمر ایپ دستیاب نہیں");
    }

    private void shareText(String text) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        speak("شیئر کرنے کے لیے ایپ منتخب کریں");
        safeStart(Intent.createChooser(share, "ایپ منتخب کریں"),
                "شیئر کرنے والی ایپ دستیاب نہیں");
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        }
    }

    private boolean safeStart(Intent intent, String failureMessage) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            Log.w(TAG, "Intent unavailable: " + intent, error);
        } catch (RuntimeException error) {
            Log.w(TAG, "Intent failed: " + intent, error);
        }
        if (failureMessage != null && !failureMessage.isEmpty()) {
            updateStatus("یہ کام دستیاب نہیں", failureMessage, COLOR_WARNING);
            Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show();
        }
        return false;
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

    private void showPermissionSettings(String explanation) {
        new AlertDialog.Builder(this)
                .setTitle("اجازت بند ہے")
                .setMessage(explanation + "\n\nسیٹنگز میں جا کر اجازت Allow کریں۔")
                .setPositiveButton("ایپ سیٹنگز", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    safeStart(intent, "ایپ سیٹنگز نہیں کھلیں");
                })
                .setNegativeButton("منسوخ", null)
                .show();
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
            String permission = permissions.length > 0 ? permissions[0] : "";
            boolean permanentlyDenied = !permission.isEmpty()
                    && !shouldShowRequestPermissionRationale(permission);
            if (permanentlyDenied) {
                showPermissionSettings("اس کام کے لیے متعلقہ اجازت ضروری ہے۔");
            } else {
                updateStatus("اجازت نہیں ملی",
                        "متعلقہ کام کے لیے فون کی اجازت درکار ہے", COLOR_WARNING);
            }
            return;
        }

        if (requestCode == REQUEST_MICROPHONE && action == PendingPermission.VOICE) {
            startVoiceRecognition();
        } else if (requestCode == REQUEST_CONTACTS
                && action == PendingPermission.CONTACT_COMMAND && command != null) {
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
        awaitingSystemSpeech = false;
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
                micButton.setText("دوبارہ کوشش...");
                stopButton.setVisibility(View.VISIBLE);
                fallbackVoiceButton.setEnabled(false);
                break;
            case SYSTEM_FALLBACK:
                micButton.setText("🎙️  حکم بولیں");
                stopButton.setVisibility(View.GONE);
                fallbackVoiceButton.setEnabled(true);
                break;
            case IDLE:
            default:
                resetVoiceUi();
                break;
        }
        if (status != null && !status.isEmpty()) {
            int color = state == VoiceRecognitionController.State.IDLE
                    ? COLOR_WARNING : COLOR_ACCENT;
            updateStatus(status, detail, color);
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
        launchSystemRecognizer(intent);
    }

    @Override
    public void onVoiceUnavailable(String reason) {
        resetVoiceUi();
        updateStatus("آواز شناخت میں مسئلہ",
                reason == null ? "کمانڈ لکھ کر چلائیں" : reason, COLOR_WARNING);
    }

    private void resetVoiceUi() {
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
        if (voiceController != null) voiceController.setForeground(true);
    }

    @Override
    protected void onStop() {
        if (voiceController != null) voiceController.setForeground(false);
        resetVoiceUi();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        contactGeneration++;
        contactExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (voiceController != null) voiceController.release();
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

    private static final class LauncherCandidate {
        final String label;
        final String packageName;
        final String className;
        final int score;

        LauncherCandidate(String label, String packageName, String className, int score) {
            this.label = label;
            this.packageName = packageName;
            this.className = className;
            this.score = score;
        }
    }
}
