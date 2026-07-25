package com.zeshan.chintuai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wazir Gen Z 4.1 command center: one-shot voice transcript + Gemini structured actions. */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
public final class WazirGenZActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 8101;
    private static final int REQUEST_ONE_SHOT_VOICE = 8102;

    private static final int TOP = Color.rgb(2, 10, 20);
    private static final int BOTTOM = Color.rgb(8, 30, 46);
    private static final int CARD = Color.rgb(16, 42, 63);
    private static final int CARD_LIGHT = Color.rgb(27, 63, 88);
    private static final int CYAN = Color.rgb(67, 215, 255);
    private static final int GREEN = Color.rgb(72, 226, 157);
    private static final int AMBER = Color.rgb(255, 191, 83);
    private static final int RED = Color.rgb(255, 103, 125);
    private static final int TEXT = Color.rgb(245, 250, 255);
    private static final int MUTED = Color.rgb(166, 187, 206);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView detailView;
    private TextView engineView;
    private TextView latencyView;
    private TextView aiView;
    private TextView accessibilityView;
    private EditText commandInput;
    private Button handsFreeButton;
    private boolean receiverRegistered;
    private boolean serviceReportedRunning;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            String engine = intent.getStringExtra(WazirVoiceService.EXTRA_ENGINE);
            if (engine != null && !engine.trim().isEmpty()) engineView.setText(engine);

            if (WazirVoiceService.ACTION_STATUS.equals(action)) {
                serviceReportedRunning = true;
                setStatus(intent.getStringExtra(WazirVoiceService.EXTRA_STATUS),
                        intent.getStringExtra(WazirVoiceService.EXTRA_DETAIL), CYAN);
                updateHandsFreeButton();
            } else if (WazirVoiceService.ACTION_COMMAND.equals(action)) {
                String command = intent.getStringExtra(WazirVoiceService.EXTRA_COMMAND);
                String result = intent.getStringExtra(WazirVoiceService.EXTRA_RESULT);
                long latency = intent.getLongExtra(WazirVoiceService.EXTRA_LATENCY_MS, -1L);
                if (command != null && !command.trim().isEmpty()) commandInput.setText(command);
                if (latency >= 0L) latencyView.setText("Voice → action: " + latency + " ms");
                if (result != null && !result.trim().isEmpty()) setStatus("مکمل", result, GREEN);
                updateHandsFreeButton();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(TOP);
        getWindow().setNavigationBarColor(TOP);
        setContentView(buildInterface());
        registerVoiceReceiver();
        updateAiState();
        setStatus("وزیر تیار ہے",
                "فوری وائس بٹن الگ one-shot transcript لیتا ہے؛ ہینڈز فری آن نہیں کرتا",
                GREEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHandsFreeButton();
        updateAccessibilityState();
        updateAiState();
    }

    private ScrollView buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{TOP, BOTTOM}));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(34));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titles.addView(text("وزیر", 42, TEXT, true), matchWrap());
        titles.addView(text("GEN Z • VOICE TRANSCRIPT • GEMINI BRAIN",
                12, MUTED, false), matchWrap());

        TextView badge = text("GEN Z\n" + BuildConfig.VERSION_NAME, 11, TEXT, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), dp(8), dp(12), dp(8));
        badge.setBackground(rounded(CARD_LIGHT, dp(18), CYAN));
        header.addView(badge, wrapWrap());

        LinearLayout core = card();
        LinearLayout.LayoutParams coreParams = matchWrap();
        coreParams.topMargin = dp(18);
        root.addView(core, coreParams);

        TextView coreTitle = text("WAZIR AI CORE", 13, CYAN, true);
        coreTitle.setGravity(Gravity.CENTER);
        core.addView(coreTitle, matchWrap());
        TextView orb = text("◉", 82, CYAN, false);
        orb.setGravity(Gravity.CENTER);
        orb.setShadowLayer(25f, 0f, 0f, CYAN);
        core.addView(orb, matchWrap());

        statusView = text("وزیر تیار ہے", 23, GREEN, true);
        statusView.setGravity(Gravity.CENTER);
        core.addView(statusView, matchWrap());
        detailView = text("فوری وائس یا تحریری کمانڈ دیں", 17, TEXT, false);
        detailView.setGravity(Gravity.CENTER);
        detailView.setMinHeight(dp(66));
        detailView.setPadding(dp(4), dp(10), dp(4), dp(8));
        core.addView(detailView, matchWrap());

        LinearLayout telemetry = new LinearLayout(this);
        telemetry.setOrientation(LinearLayout.VERTICAL);
        core.addView(telemetry, matchWrap());
        engineView = chip("Voice engine: waiting");
        telemetry.addView(engineView, matchWrap());
        aiView = chip("AI: checking Gemini key");
        telemetry.addView(aiView, matchWrap());
        latencyView = chip("Voice/AI → action: —");
        telemetry.addView(latencyView, matchWrap());

        handsFreeButton = primaryButton("🎙  وزیر ہینڈز فری آن کریں");
        handsFreeButton.setOnClickListener(v -> toggleHandsFree());
        LinearLayout.LayoutParams handsParams = matchWrap();
        handsParams.height = dp(62);
        handsParams.topMargin = dp(14);
        root.addView(handsFreeButton, handsParams);

        Button oneShot = primaryButton("⚡ فوری وائس → Gemini کمانڈ");
        oneShot.setOnClickListener(v -> launchOneShotVoice());
        LinearLayout.LayoutParams oneShotParams = matchWrap();
        oneShotParams.height = dp(60);
        oneShotParams.topMargin = dp(10);
        root.addView(oneShot, oneShotParams);

        TextView oneShotNote = text(
                "یہ بٹن ہینڈز فری سروس شروع نہیں کرتا۔ Android کا one-shot voice recognizer "
                        + "عبارت واپس دے گا، پھر Gemini اسے structured actions میں بدلے گا۔",
                13, MUTED, false);
        oneShotNote.setPadding(dp(6), dp(10), dp(6), 0);
        root.addView(oneShotNote, matchWrap());

        LinearLayout aiCard = card();
        LinearLayout.LayoutParams aiParams = matchWrap();
        aiParams.topMargin = dp(14);
        root.addView(aiCard, aiParams);
        aiCard.addView(text("GEMINI AI BRAIN", 15, CYAN, true), matchWrap());
        TextView aiExplanation = text(
                "Google Notes میں محفوظ Gemini key یہاں ایک بار paste کریں۔ Key GitHub یا APK "
                        + "میں شامل نہیں ہوگی؛ فون کے Android Keystore سے encrypt ہوگی۔",
                13, MUTED, false);
        aiExplanation.setPadding(0, dp(7), 0, dp(6));
        aiCard.addView(aiExplanation, matchWrap());
        Button configureAi = secondaryButton("Gemini API key محفوظ / تبدیل کریں");
        configureAi.setOnClickListener(v -> showGeminiKeyDialog());
        aiCard.addView(configureAi, buttonParams());

        LinearLayout commandCard = card();
        LinearLayout.LayoutParams commandParams = matchWrap();
        commandParams.topMargin = dp(14);
        root.addView(commandCard, commandParams);
        commandCard.addView(text("تحریری Gemini کمانڈ", 15, CYAN, true), matchWrap());

        commandInput = new EditText(this);
        commandInput.setHint("مثلاً: فیس بک کھولو، پوسٹ لکھو مگر شائع نہ کرنا");
        commandInput.setHintTextColor(MUTED);
        commandInput.setTextColor(TEXT);
        commandInput.setTextSize(17);
        commandInput.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        commandInput.setMaxLines(4);
        commandInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        commandInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        commandInput.setBackground(rounded(TOP, dp(15), CARD_LIGHT));
        commandInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runTypedCommand();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(10);
        commandCard.addView(commandInput, inputParams);

        Button run = primaryButton("Gemini سے سمجھ کر کمانڈ چلائیں");
        run.setOnClickListener(v -> runTypedCommand());
        commandCard.addView(run, buttonParams());

        LinearLayout setup = card();
        LinearLayout.LayoutParams setupParams = matchWrap();
        setupParams.topMargin = dp(14);
        root.addView(setup, setupParams);
        setup.addView(text("PHONE CONTROL SETUP", 14, CYAN, true), matchWrap());
        accessibilityView = text("Accessibility چیک ہو رہی ہے…", 15, MUTED, false);
        accessibilityView.setPadding(0, dp(8), 0, dp(8));
        setup.addView(accessibilityView, matchWrap());

        Button permissions = secondaryButton("تمام ضروری اجازتیں دیں");
        permissions.setOnClickListener(v -> requestCorePermissions());
        setup.addView(permissions, buttonParams());
        Button accessibility = secondaryButton("Wazir phone control آن کریں");
        accessibility.setOnClickListener(v -> openAccessibilitySettings());
        setup.addView(accessibility, buttonParams());
        Button battery = secondaryButton("Redmi بیٹری پابندی ہٹائیں");
        battery.setOnClickListener(v -> openBatterySettings());
        setup.addView(battery, buttonParams());

        TextView safety = text(
                "حفاظت: رقم کی منتقلی، خریداری، PIN، OTP، پاس ورڈ اور حساس اکاؤنٹ تبدیلیاں "
                        + "AI plan میں بھی بند ہیں۔ Post/Send/Publish الگ تصدیق مانگیں گے۔",
                13, MUTED, false);
        safety.setPadding(dp(5), dp(16), dp(5), 0);
        root.addView(safety, matchWrap());
        return scroll;
    }

    private void toggleHandsFree() {
        if (WazirVoiceService.isEnabled(this) || serviceReportedRunning) {
            startService(new Intent(this, WazirVoiceService.class)
                    .setAction(WazirVoiceService.ACTION_STOP));
            serviceReportedRunning = false;
            setStatus("بند کر رہا ہوں", "وزیر کا مسلسل مائیک بند ہو رہا ہے", AMBER);
            updateHandsFreeButton();
            return;
        }
        if (!hasMicPermission()) {
            requestCorePermissions();
            return;
        }
        startVoiceService(WazirVoiceService.ACTION_START);
    }

    private void launchOneShotVoice() {
        if (!hasMicPermission()) {
            requestCorePermissions();
            return;
        }
        Intent voice = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
                .putExtra(RecognizerIntent.EXTRA_PROMPT,
                        "وزیر کے لیے پوری کمانڈ بولیں");
        try {
            setStatus("فوری وائس سن رہا ہے",
                    "یہ one-shot mode ہے؛ ہینڈز فری آن نہیں ہوگا", CYAN);
            startActivityForResult(voice, REQUEST_ONE_SHOT_VOICE);
        } catch (ActivityNotFoundException error) {
            setStatus("Voice recognizer دستیاب نہیں",
                    "Google Speech Services اپڈیٹ یا فعال کریں", AMBER);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ONE_SHOT_VOICE) return;
        if (resultCode != RESULT_OK || data == null) {
            setStatus("فوری وائس منسوخ ہوئی",
                    "دوبارہ بٹن دبائیں اور پوری کمانڈ بولیں", AMBER);
            return;
        }
        ArrayList<String> matches =
                data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        String transcript = CommandEngine.chooseBest(matches);
        if (transcript.isEmpty()) {
            setStatus("عبارت نہیں ملی", "دوبارہ واضح کمانڈ بولیں", AMBER);
            return;
        }
        commandInput.setText(transcript);
        setStatus("Voice transcript مل گیا", transcript, CYAN);
        runAiCommand(transcript, "Voice");
    }

    private void runTypedCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            Toast.makeText(this, "کمانڈ لکھیں", Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard();
        runAiCommand(command, "Typed");
    }

    private void runAiCommand(String command, String source) {
        setStatus("Gemini سمجھ رہا ہے", command, CYAN);
        aiView.setText("AI: planning structured actions…");
        long started = android.os.SystemClock.uptimeMillis();
        worker.execute(() -> {
            WazirAiBrain.Execution execution =
                    WazirAiBrain.execute(getApplicationContext(), command);
            long latency = android.os.SystemClock.uptimeMillis() - started;
            runOnUiThread(() -> {
                aiView.setText("AI: " + execution.mode);
                latencyView.setText(source + "/AI → action: " + latency + " ms");
                setStatus(execution.result.handled ? "مکمل" : "کمانڈ مکمل نہیں ہوئی",
                        execution.result.message,
                        execution.result.handled ? GREEN : AMBER);
            });
        });
    }

    private void showGeminiKeyDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(22), dp(8), dp(22), 0);

        TextView note = new TextView(this);
        note.setText(WazirSecretStore.hasGeminiApiKey(this)
                ? "Gemini key پہلے سے محفوظ ہے۔ تبدیل کرنے کے لیے نئی key paste کریں۔"
                : "Google Notes سے Gemini API key paste کریں۔ اسے chat یا GitHub میں مت بھیجیں۔");
        note.setTextSize(14);
        container.addView(note, matchWrap());

        EditText input = new EditText(this);
        input.setHint("Gemini API key");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(8);
        container.addView(input, inputParams);

        new AlertDialog.Builder(this)
                .setTitle("Gemini AI Brain")
                .setView(container)
                .setPositiveButton("محفوظ کریں", (dialog, which) -> {
                    boolean saved = WazirSecretStore.saveGeminiApiKey(
                            getApplicationContext(), input.getText().toString());
                    Toast.makeText(this,
                            saved ? "Gemini key encrypted ہو کر محفوظ ہو گئی"
                                    : "Key درست طرح محفوظ نہیں ہوئی",
                            Toast.LENGTH_LONG).show();
                    updateAiState();
                })
                .setNeutralButton("Key حذف کریں", (dialog, which) -> {
                    WazirSecretStore.clearGeminiApiKey(getApplicationContext());
                    updateAiState();
                    Toast.makeText(this, "Gemini key حذف ہو گئی", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("منسوخ", null)
                .show();
    }

    private void updateAiState() {
        if (aiView == null) return;
        if (WazirSecretStore.hasGeminiApiKey(this)) {
            aiView.setText("AI: Gemini 2.5 Flash • key encrypted");
            aiView.setTextColor(GREEN);
        } else {
            aiView.setText("AI: Gemini key درکار • ابھی local rules");
            aiView.setTextColor(AMBER);
        }
    }

    private void startVoiceService(String action) {
        Intent start = new Intent(this, WazirVoiceService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
            else startService(start);
            serviceReportedRunning = true;
            setStatus("وزیر ہینڈز فری شروع ہو رہا ہے",
                    "مسلسل wake word mode؛ فوری وائس بٹن اس سے الگ ہے", CYAN);
            updateHandsFreeButton();
        } catch (RuntimeException error) {
            setStatus("ہینڈز فری شروع نہیں ہوئی",
                    "Microphone اور Notifications اجازت چیک کریں", AMBER);
        }
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCorePermissions() {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.RECORD_AUDIO);
        addIfMissing(missing, Manifest.permission.READ_CONTACTS);
        addIfMissing(missing, Manifest.permission.CALL_PHONE);
        addIfMissing(missing, Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33) addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        if (missing.isEmpty()) {
            Toast.makeText(this, "ضروری اجازتیں موجود ہیں", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private void addIfMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            Toast.makeText(this,
                    hasMicPermission() ? "وزیر کی اجازتیں محفوظ ہو گئیں"
                            : "Microphone اجازت ضروری ہے",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void registerVoiceReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WazirVoiceService.ACTION_STATUS);
        filter.addAction(WazirVoiceService.ACTION_COMMAND);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    private void updateHandsFreeButton() {
        if (handsFreeButton == null) return;
        boolean enabled = serviceReportedRunning || WazirVoiceService.isEnabled(this);
        handsFreeButton.setText(enabled
                ? "■  وزیر ہینڈز فری بند کریں"
                : "🎙  وزیر ہینڈز فری آن کریں");
        handsFreeButton.setBackground(rounded(
                enabled ? Color.rgb(146, 52, 67) : Color.rgb(25, 137, 181),
                dp(22), enabled ? RED : CYAN));
    }

    private void updateAccessibilityState() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName component = new ComponentName(this, ChintuAccessibilityService.class);
        boolean active = enabled != null && enabled.contains(component.flattenToString());
        accessibilityView.setText(active
                ? "✓ Wazir phone control فعال ہے"
                : "⚠ Wazir phone control بند ہے");
        accessibilityView.setTextColor(active ? GREEN : AMBER);
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void hideKeyboard() {
        InputMethodManager keyboard =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null && getCurrentFocus() != null) {
            keyboard.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void setStatus(String status, String detail, int color) {
        if (statusView == null || detailView == null) return;
        statusView.setText(status == null || status.trim().isEmpty() ? "وزیر" : status);
        statusView.setTextColor(color);
        detailView.setText(detail == null ? "" : detail);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(rounded(CARD, dp(24), CARD_LIGHT));
        return layout;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(TEXT);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(Color.rgb(25, 137, 181), dp(22), CYAN));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(TEXT);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackground(rounded(CARD_LIGHT, dp(16), CYAN));
        return button;
    }

    private TextView chip(String value) {
        TextView view = text(value, 12, MUTED, false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setBackground(rounded(TOP, dp(16), CARD_LIGHT));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(5);
        view.setLayoutParams(params);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.height = dp(50);
        params.topMargin = dp(9);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            receiverRegistered = false;
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
