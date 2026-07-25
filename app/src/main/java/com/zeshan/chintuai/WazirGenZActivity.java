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
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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

/** Wazir Gen Z 5.0 command center powered by a persistent Gemini Live PCM stream. */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
public final class WazirGenZActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 8101;

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
    private Button directVoiceButton;
    private boolean receiverRegistered;
    private boolean liveSessionRunning;
    private boolean directSession;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            String engine = intent.getStringExtra(GeminiLiveVoiceService.EXTRA_ENGINE);
            String mode = intent.getStringExtra(GeminiLiveVoiceService.EXTRA_MODE);
            if (engine != null && !engine.trim().isEmpty()) engineView.setText(engine);
            if (mode != null && !mode.trim().isEmpty()) {
                aiView.setText("AI/Voice: " + mode);
                directSession = mode.contains("Direct");
            }

            if (GeminiLiveVoiceService.ACTION_STATUS.equals(action)) {
                liveSessionRunning = true;
                setStatus(intent.getStringExtra(GeminiLiveVoiceService.EXTRA_STATUS),
                        intent.getStringExtra(GeminiLiveVoiceService.EXTRA_DETAIL), CYAN);
                updateVoiceButtons();
                return;
            }
            if (GeminiLiveVoiceService.ACTION_TRANSCRIPT.equals(action)) {
                String transcript = intent.getStringExtra(
                        GeminiLiveVoiceService.EXTRA_TRANSCRIPT);
                if (transcript != null && !transcript.trim().isEmpty()) {
                    commandInput.setText(transcript);
                    commandInput.setSelection(commandInput.length());
                    setStatus("Live transcript", transcript, CYAN);
                }
                return;
            }
            if (GeminiLiveVoiceService.ACTION_COMMAND.equals(action)) {
                String command = intent.getStringExtra(GeminiLiveVoiceService.EXTRA_COMMAND);
                String result = intent.getStringExtra(GeminiLiveVoiceService.EXTRA_RESULT);
                long latency = intent.getLongExtra(
                        GeminiLiveVoiceService.EXTRA_LATENCY_MS, -1L);
                if (command != null && !command.trim().isEmpty()) {
                    commandInput.setText(command);
                    commandInput.setSelection(commandInput.length());
                }
                if (latency >= 0L) latencyView.setText(
                        "Live voice → action: " + latency + " ms");
                if (result != null && !result.trim().isEmpty()) {
                    setStatus("مکمل", result, GREEN);
                }
                if (directSession) liveSessionRunning = false;
                updateVoiceButtons();
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
                "Gemini Live مسلسل آڈیو stream استعمال کرتا ہے؛ Google کا دو سیکنڈ والا dialog ختم ہے",
                GREEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateVoiceButtons();
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
        titles.addView(text("GEN Z • GEMINI LIVE • PHONE CONTROL",
                12, MUTED, false), matchWrap());

        TextView badge = text("LIVE\n" + BuildConfig.VERSION_NAME, 11, TEXT, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), dp(8), dp(12), dp(8));
        badge.setBackground(rounded(CARD_LIGHT, dp(18), CYAN));
        header.addView(badge, wrapWrap());

        LinearLayout core = card();
        LinearLayout.LayoutParams coreParams = matchWrap();
        coreParams.topMargin = dp(18);
        root.addView(core, coreParams);
        TextView coreTitle = text("WAZIR LIVE VOICE CORE", 13, CYAN, true);
        coreTitle.setGravity(Gravity.CENTER);
        core.addView(coreTitle, matchWrap());
        TextView orb = text("◉", 82, CYAN, false);
        orb.setGravity(Gravity.CENTER);
        orb.setShadowLayer(25f, 0f, 0f, CYAN);
        core.addView(orb, matchWrap());

        statusView = text("وزیر تیار ہے", 23, GREEN, true);
        statusView.setGravity(Gravity.CENTER);
        core.addView(statusView, matchWrap());
        detailView = text("کہیں: وزیر، پھر پوری کمانڈ", 17, TEXT, false);
        detailView.setGravity(Gravity.CENTER);
        detailView.setMinHeight(dp(78));
        detailView.setPadding(dp(4), dp(10), dp(4), dp(8));
        core.addView(detailView, matchWrap());

        engineView = chip("Voice: Gemini Live 3.1");
        core.addView(engineView, matchWrap());
        aiView = chip("AI: checking Gemini key");
        core.addView(aiView, matchWrap());
        latencyView = chip("Live voice → action: —");
        core.addView(latencyView, matchWrap());

        handsFreeButton = primaryButton("🎙  وزیر Live ہینڈز فری آن کریں");
        handsFreeButton.setOnClickListener(v -> toggleHandsFree());
        LinearLayout.LayoutParams handsParams = matchWrap();
        handsParams.height = dp(64);
        handsParams.topMargin = dp(14);
        root.addView(handsFreeButton, handsParams);

        directVoiceButton = primaryButton("⚡ فوری Gemini Live — 35 سیکنڈ");
        directVoiceButton.setOnClickListener(v -> startDirectVoice());
        LinearLayout.LayoutParams directParams = matchWrap();
        directParams.height = dp(62);
        directParams.topMargin = dp(10);
        root.addView(directVoiceButton, directParams);

        TextView directNote = text(
                "یہ بٹن Google voice dialog نہیں کھولتا۔ وزیر کے اندر ہی microphone stream شروع ہوتی ہے، "
                        + "پوری عبارت live لکھی جاتی ہے اور Gemini structured actions چلاتا ہے۔",
                13, MUTED, false);
        directNote.setPadding(dp(6), dp(10), dp(6), 0);
        root.addView(directNote, matchWrap());

        LinearLayout aiCard = card();
        LinearLayout.LayoutParams aiParams = matchWrap();
        aiParams.topMargin = dp(14);
        root.addView(aiCard, aiParams);
        aiCard.addView(text("GEMINI LIVE CONNECTION", 15, CYAN, true), matchWrap());
        TextView aiExplanation = text(
                "Google Notes میں محفوظ Gemini API key یہاں ایک بار paste کریں۔ Key APK یا GitHub "
                        + "میں شامل نہیں ہوگی اور Android Keystore سے encrypted رہے گی۔",
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
        commandInput.setHint("مثلاً: فیس بک کھولو اور تین بار نیچے سکرول کرو");
        commandInput.setHintTextColor(MUTED);
        commandInput.setTextColor(TEXT);
        commandInput.setTextSize(17);
        commandInput.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        commandInput.setMaxLines(5);
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
                        + "بند ہیں۔ Post/Send/Publish الگ تصدیق مانگیں گے۔",
                13, MUTED, false);
        safety.setPadding(dp(5), dp(16), dp(5), 0);
        root.addView(safety, matchWrap());
        return scroll;
    }

    private void toggleHandsFree() {
        boolean enabled = GeminiLiveVoiceService.isEnabled(this) ||
                (liveSessionRunning && !directSession);
        if (enabled) {
            startService(new Intent(this, GeminiLiveVoiceService.class)
                    .setAction(GeminiLiveVoiceService.ACTION_STOP));
            liveSessionRunning = false;
            directSession = false;
            setStatus("بند کر رہا ہوں", "Gemini Live microphone بند ہو رہا ہے", AMBER);
            updateVoiceButtons();
            return;
        }
        if (!canStartLiveVoice()) return;
        directSession = false;
        liveSessionRunning = true;
        startLiveService(GeminiLiveVoiceService.ACTION_START_HANDS_FREE);
        setStatus("وزیر Live شروع ہو رہا ہے",
                "Connection مکمل ہونے کے بعد کہیں: وزیر، پھر پوری کمانڈ", CYAN);
        updateVoiceButtons();
    }

    private void startDirectVoice() {
        if (!canStartLiveVoice()) return;
        directSession = true;
        liveSessionRunning = true;
        startLiveService(GeminiLiveVoiceService.ACTION_START_DIRECT);
        setStatus("فوری Gemini Live شروع ہو رہا ہے",
                "Connection کے بعد پوری کمانڈ بولیں؛ دو سیکنڈ کی حد نہیں ہے", CYAN);
        updateVoiceButtons();
    }

    private boolean canStartLiveVoice() {
        if (!hasMicPermission()) {
            requestCorePermissions();
            return false;
        }
        if (!WazirSecretStore.hasGeminiApiKey(this)) {
            setStatus("Gemini key درکار ہے",
                    "پہلے Gemini API key محفوظ کریں", AMBER);
            showGeminiKeyDialog();
            return false;
        }
        return true;
    }

    private void startLiveService(String action) {
        Intent start = new Intent(this, GeminiLiveVoiceService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
            else startService(start);
        } catch (RuntimeException error) {
            liveSessionRunning = false;
            setStatus("Gemini Live شروع نہیں ہوئی",
                    "Microphone، Notifications، Internet اور API key چیک کریں", AMBER);
            updateVoiceButtons();
        }
    }

    private void runTypedCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            Toast.makeText(this, "کمانڈ لکھیں", Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard();
        setStatus("Gemini سمجھ رہا ہے", command, CYAN);
        aiView.setText("AI: planning structured actions…");
        long started = android.os.SystemClock.uptimeMillis();
        worker.execute(() -> {
            WazirAiBrain.Execution execution =
                    WazirAiBrain.execute(getApplicationContext(), command);
            long latency = android.os.SystemClock.uptimeMillis() - started;
            runOnUiThread(() -> {
                aiView.setText("AI: " + execution.mode);
                latencyView.setText("Typed/AI → action: " + latency + " ms");
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
                .setTitle("Gemini Live Voice")
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
            aiView.setText("AI: Gemini Live 3.1 • key encrypted");
            aiView.setTextColor(GREEN);
        } else {
            aiView.setText("AI: Gemini key درکار");
            aiView.setTextColor(AMBER);
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
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        }
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
        filter.addAction(GeminiLiveVoiceService.ACTION_STATUS);
        filter.addAction(GeminiLiveVoiceService.ACTION_TRANSCRIPT);
        filter.addAction(GeminiLiveVoiceService.ACTION_COMMAND);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void updateVoiceButtons() {
        if (handsFreeButton == null || directVoiceButton == null) return;
        boolean handsFree = GeminiLiveVoiceService.isEnabled(this)
                || (liveSessionRunning && !directSession);
        handsFreeButton.setText(handsFree
                ? "■  وزیر Live ہینڈز فری بند کریں"
                : "🎙  وزیر Live ہینڈز فری آن کریں");
        handsFreeButton.setBackground(rounded(
                handsFree ? Color.rgb(146, 52, 67) : Color.rgb(25, 137, 181),
                dp(22), handsFree ? RED : CYAN));
        directVoiceButton.setText(liveSessionRunning && directSession
                ? "■  فوری Gemini Live بند کریں"
                : "⚡ فوری Gemini Live — 35 سیکنڈ");
        directVoiceButton.setOnClickListener(v -> {
            if (liveSessionRunning && directSession) {
                startService(new Intent(this, GeminiLiveVoiceService.class)
                        .setAction(GeminiLiveVoiceService.ACTION_STOP));
                liveSessionRunning = false;
                directSession = false;
                updateVoiceButtons();
            } else {
                startDirectVoice();
            }
        });
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
        params.height = dp(52);
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
            }
            receiverRegistered = false;
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
