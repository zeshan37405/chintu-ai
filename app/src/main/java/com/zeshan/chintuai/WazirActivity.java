package com.zeshan.chintuai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
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

/** Main Wazir Gen Z command center. */
@SuppressLint("UnspecifiedRegisterReceiverFlag")
public final class WazirActivity extends Activity {
    public static final String EXTRA_PENDING_COMMAND = "pending_command";

    private static final int REQUEST_CORE_PERMISSIONS = 7001;
    private static final int COLOR_TOP = Color.rgb(3, 11, 22);
    private static final int COLOR_BOTTOM = Color.rgb(9, 28, 45);
    private static final int COLOR_CARD = Color.rgb(16, 39, 60);
    private static final int COLOR_CARD_LIGHT = Color.rgb(25, 57, 82);
    private static final int COLOR_CYAN = Color.rgb(65, 211, 255);
    private static final int COLOR_GREEN = Color.rgb(73, 226, 157);
    private static final int COLOR_AMBER = Color.rgb(255, 190, 82);
    private static final int COLOR_TEXT = Color.rgb(244, 249, 255);
    private static final int COLOR_MUTED = Color.rgb(164, 185, 204);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView transcriptView;
    private TextView engineView;
    private TextView latencyView;
    private TextView accessView;
    private EditText commandInput;
    private Button handsFreeButton;
    private boolean receiverRegistered;
    private boolean serviceReportedRunning;

    private final BroadcastReceiver voiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            String engine = intent.getStringExtra(HandsFreeVoiceService.EXTRA_ENGINE);
            if (engine != null && !engine.trim().isEmpty()) engineView.setText(engine);

            if (HandsFreeVoiceService.ACTION_STATUS.equals(action)) {
                serviceReportedRunning = true;
                String status = intent.getStringExtra(HandsFreeVoiceService.EXTRA_STATUS);
                String detail = intent.getStringExtra(HandsFreeVoiceService.EXTRA_DETAIL);
                setStatus(status, detail, COLOR_CYAN);
                updateHandsFreeButton();
            } else if (HandsFreeVoiceService.ACTION_COMMAND.equals(action)) {
                String command = intent.getStringExtra(HandsFreeVoiceService.EXTRA_COMMAND);
                String result = intent.getStringExtra(HandsFreeVoiceService.EXTRA_RESULT);
                long latency = intent.getLongExtra(
                        HandsFreeVoiceService.EXTRA_LATENCY_MS, -1L);
                if (command != null && !command.trim().isEmpty()) {
                    commandInput.setText(command);
                    transcriptView.setText(command);
                }
                if (latency >= 0L) {
                    latencyView.setText("Voice → action: " + latency + " ms");
                }
                if (result != null && !result.trim().isEmpty()) {
                    setStatus("مکمل", result, COLOR_GREEN);
                }
                updateHandsFreeButton();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_TOP);
        getWindow().setNavigationBarColor(COLOR_TOP);
        setContentView(buildInterface());
        registerVoiceReceiver();
        handlePendingIntent(getIntent());
        setStatus("وزیر تیار ہے", "ہینڈز فری آن کریں اور کہیں: وزیر", COLOR_GREEN);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePendingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHandsFreeButton();
        updateAccessibilityState();
    }

    private ScrollView buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COLOR_TOP, COLOR_BOTTOM});
        scroll.setBackground(background);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = text("وزیر", 40, COLOR_TEXT, true);
        titles.addView(title, matchWrap());
        TextView subtitle = text("WAZIR GEN Z • MOBILE AI OPERATING ASSISTANT",
                12, COLOR_MUTED, false);
        titles.addView(subtitle, matchWrap());

        TextView badge = text("GEN Z\n" + BuildConfig.VERSION_NAME,
                11, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), dp(8), dp(12), dp(8));
        badge.setBackground(rounded(COLOR_CARD_LIGHT, dp(18), COLOR_CYAN));
        header.addView(badge, wrapWrap());

        LinearLayout core = card();
        LinearLayout.LayoutParams coreParams = matchWrap();
        coreParams.topMargin = dp(20);
        root.addView(core, coreParams);

        TextView coreLabel = text("WAZIR CORE", 12, COLOR_CYAN, true);
        coreLabel.setGravity(Gravity.CENTER);
        core.addView(coreLabel, matchWrap());

        TextView orb = text("◉", 78, COLOR_CYAN, false);
        orb.setGravity(Gravity.CENTER);
        orb.setShadowLayer(24f, 0f, 0f, COLOR_CYAN);
        core.addView(orb, matchWrap());

        statusView = text("وزیر تیار ہے", 21, COLOR_GREEN, true);
        statusView.setGravity(Gravity.CENTER);
        core.addView(statusView, matchWrap());

        transcriptView = text("کہیں: وزیر، پھر کمانڈ", 17, COLOR_TEXT, false);
        transcriptView.setGravity(Gravity.CENTER);
        transcriptView.setMinHeight(dp(52));
        transcriptView.setPadding(dp(4), dp(10), dp(4), dp(4));
        core.addView(transcriptView, matchWrap());

        LinearLayout telemetry = new LinearLayout(this);
        telemetry.setOrientation(LinearLayout.HORIZONTAL);
        telemetry.setGravity(Gravity.CENTER);
        core.addView(telemetry, matchWrap());

        engineView = chip("Voice engine: waiting");
        telemetry.addView(engineView, weightedWrap());
        latencyView = chip("Voice → action: —");
        telemetry.addView(latencyView, weightedWrap());

        handsFreeButton = primaryButton("🎙  وزیر ہینڈز فری آن کریں");
        handsFreeButton.setOnClickListener(v -> toggleHandsFree());
        LinearLayout.LayoutParams handsParams = matchWrap();
        handsParams.height = dp(64);
        handsParams.topMargin = dp(14);
        root.addView(handsFreeButton, handsParams);

        LinearLayout commandCard = card();
        LinearLayout.LayoutParams commandParams = matchWrap();
        commandParams.topMargin = dp(14);
        root.addView(commandCard, commandParams);

        TextView commandTitle = text("فوری تحریری کمانڈ", 15, COLOR_CYAN, true);
        commandCard.addView(commandTitle, matchWrap());

        commandInput = new EditText(this);
        commandInput.setHint("مثلاً: فیس بک کھولو اور نیچے سکرول کرو");
        commandInput.setHintTextColor(COLOR_MUTED);
        commandInput.setTextColor(COLOR_TEXT);
        commandInput.setTextSize(17);
        commandInput.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        commandInput.setSingleLine(false);
        commandInput.setMaxLines(3);
        commandInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        commandInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        commandInput.setBackground(rounded(COLOR_TOP, dp(16), COLOR_CARD_LIGHT));
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

        Button run = primaryButton("کمانڈ چلائیں");
        run.setOnClickListener(v -> runTypedCommand());
        LinearLayout.LayoutParams runParams = matchWrap();
        runParams.height = dp(52);
        runParams.topMargin = dp(10);
        commandCard.addView(run, runParams);

        LinearLayout setup = card();
        LinearLayout.LayoutParams setupParams = matchWrap();
        setupParams.topMargin = dp(14);
        root.addView(setup, setupParams);

        TextView setupTitle = text("PHONE CONTROL", 14, COLOR_CYAN, true);
        setup.addView(setupTitle, matchWrap());

        accessView = text("Accessibility چیک ہو رہی ہے…", 15, COLOR_MUTED, false);
        accessView.setPadding(0, dp(6), 0, dp(8));
        setup.addView(accessView, matchWrap());

        Button permissions = secondaryButton("تمام ضروری اجازتیں دیں");
        permissions.setOnClickListener(v -> requestCorePermissions());
        setup.addView(permissions, buttonParams());

        Button accessibility = secondaryButton("فون کنٹرول / Accessibility آن کریں");
        accessibility.setOnClickListener(v -> openAccessibilitySettings());
        setup.addView(accessibility, buttonParams());

        Button battery = secondaryButton("Redmi بیٹری پابندی ہٹائیں");
        battery.setOnClickListener(v -> openBatterySettings());
        setup.addView(battery, buttonParams());

        TextView safety = text(
                "حفاظت: رقم کی منتقلی، PIN، پاس ورڈ اور حساس اکاؤنٹ تبدیلیاں بند رہیں گی۔ "
                        + "Post/Send جیسے کام واضح تصدیق کے بعد ہوں گے۔",
                13, COLOR_MUTED, false);
        safety.setPadding(dp(4), dp(16), dp(4), 0);
        root.addView(safety, matchWrap());

        return scroll;
    }

    private void toggleHandsFree() {
        if (HandsFreeVoiceService.isEnabled(this) || serviceReportedRunning) {
            Intent stop = new Intent(this, HandsFreeVoiceService.class)
                    .setAction(HandsFreeVoiceService.ACTION_STOP);
            startService(stop);
            serviceReportedRunning = false;
            setStatus("بند کر رہا ہوں", "وزیر کا مائیک بند ہو رہا ہے", COLOR_AMBER);
            updateHandsFreeButton();
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestCorePermissions();
            return;
        }
        startHandsFree();
    }

    private void startHandsFree() {
        Intent start = new Intent(this, HandsFreeVoiceService.class)
                .setAction(HandsFreeVoiceService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
            else startService(start);
            serviceReportedRunning = true;
            setStatus("وزیر شروع ہو رہا ہے", "ایک لمحہ… پھر کہیں: وزیر", COLOR_CYAN);
            updateHandsFreeButton();
        } catch (RuntimeException error) {
            setStatus("ہینڈز فری شروع نہیں ہوا",
                    "Microphone اور Notifications کی اجازت چیک کریں", COLOR_AMBER);
        }
    }

    private void requestCorePermissions() {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.RECORD_AUDIO);
        addIfMissing(missing, Manifest.permission.READ_CONTACTS);
        addIfMissing(missing, Manifest.permission.CALL_PHONE);
        addIfMissing(missing, Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33) addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        if (missing.isEmpty()) {
            Toast.makeText(this, "ضروری اجازتیں پہلے سے موجود ہیں", Toast.LENGTH_SHORT).show();
            startHandsFree();
            return;
        }
        requestPermissions(missing.toArray(new String[0]), REQUEST_CORE_PERMISSIONS);
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
        if (requestCode != REQUEST_CORE_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startHandsFree();
        } else {
            setStatus("مائیکروفون اجازت ضروری ہے",
                    "Settings میں Microphone کو Allow کریں", COLOR_AMBER);
        }
    }

    private void runTypedCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            Toast.makeText(this, "پہلے کمانڈ لکھیں", Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard();
        transcriptView.setText(command);
        setStatus("عمل کر رہا ہوں", command, COLOR_CYAN);
        long started = android.os.SystemClock.uptimeMillis();
        worker.execute(() -> {
            BackgroundCommandExecutor.Result result =
                    BackgroundCommandExecutor.execute(getApplicationContext(), command);
            long elapsed = android.os.SystemClock.uptimeMillis() - started;
            runOnUiThread(() -> {
                latencyView.setText("Typed → action: " + elapsed + " ms");
                setStatus(result.handled ? "مکمل" : "کمانڈ مکمل نہیں ہوئی",
                        result.message, result.handled ? COLOR_GREEN : COLOR_AMBER);
            });
        });
    }

    private void handlePendingIntent(Intent intent) {
        if (intent == null) return;
        String command = intent.getStringExtra(EXTRA_PENDING_COMMAND);
        if (command == null || command.trim().isEmpty()) return;
        intent.removeExtra(EXTRA_PENDING_COMMAND);
        commandInput.setText(command);
        runTypedCommand();
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
            Intent miui = new Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST");
            startActivity(miui);
            return;
        } catch (ActivityNotFoundException ignored) {
            // Standard Android fallback below.
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (ActivityNotFoundException error) {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(details);
        }
    }

    private void updateAccessibilityState() {
        boolean enabled = isAccessibilityEnabled();
        accessView.setText(enabled
                ? "✓ وزیر فون کنٹرول فعال ہے"
                : "⚠ وزیر فون کنٹرول بند ہے — Scroll/Type/Click کے لیے آن کریں");
        accessView.setTextColor(enabled ? COLOR_GREEN : COLOR_AMBER);
    }

    private boolean isAccessibilityEnabled() {
        ComponentName component = new ComponentName(this, ChintuAccessibilityService.class);
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledServices != null
                && enabledServices.toLowerCase().contains(
                component.flattenToString().toLowerCase());
    }

    private void updateHandsFreeButton() {
        boolean enabled = serviceReportedRunning || HandsFreeVoiceService.isEnabled(this);
        handsFreeButton.setText(enabled
                ? "■  وزیر ہینڈز فری بند کریں"
                : "🎙  وزیر ہینڈز فری آن کریں");
        handsFreeButton.setBackground(rounded(
                enabled ? Color.rgb(137, 53, 64) : Color.rgb(22, 119, 159),
                dp(22), enabled ? Color.rgb(255, 113, 126) : COLOR_CYAN));
    }

    private void setStatus(String status, String detail, int color) {
        statusView.setText(status == null ? "" : status);
        statusView.setTextColor(color);
        transcriptView.setText(detail == null ? "" : detail);
    }

    private void registerVoiceReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(HandsFreeVoiceService.ACTION_STATUS);
        filter.addAction(HandsFreeVoiceService.ACTION_COMMAND);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(voiceReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void hideKeyboard() {
        InputMethodManager input =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null && commandInput != null) {
            input.hideSoftInputFromWindow(commandInput.getWindowToken(), 0);
        }
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        layout.setBackground(rounded(COLOR_CARD, dp(22), COLOR_CARD_LIGHT));
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView chip(String value) {
        TextView view = text(value, 11, COLOR_MUTED, false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(7), dp(8), dp(7));
        view.setBackground(rounded(COLOR_TOP, dp(14), COLOR_CARD_LIGHT));
        return view;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(18);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(rounded(Color.rgb(22, 119, 159), dp(22), COLOR_CYAN));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(COLOR_TEXT);
        button.setBackground(rounded(COLOR_CARD_LIGHT, dp(16), COLOR_CYAN));
        return button;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
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

    private LinearLayout.LayoutParams weightedWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(4), dp(5), dp(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.height = dp(48);
        params.topMargin = dp(7);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(voiceReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered by the framework.
            }
            receiverRegistered = false;
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
