package com.zeshan.chintuai;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity
        implements TextToSpeech.OnInitListener, RecognitionListener {

    private static final int REQUEST_PERMISSIONS = 1002;
    private static final int REQUEST_SPEECH_FALLBACK = 1003;
    private static final int MAX_DIRECT_RETRIES = 1;
    private static final long LISTEN_WATCHDOG_MS = 18000L;
    private static final long RESULT_WATCHDOG_MS = 5000L;

    private TextView statusView;
    private Button listenButton;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isListening;
    private boolean awaitingResult;
    private int directRetryCount;
    private long suppressErrorsUntil;
    private String lastPartial = "";
    private String pendingContactCommand;

    private final Runnable listenWatchdog = () -> {
        if (!isListening) {
            return;
        }
        cancelCurrentRecognition();
        if (!lastPartial.trim().isEmpty()) {
            completeVoiceCommand(lastPartial);
        } else {
            retryOrUseSystemRecognizer();
        }
    };

    private final Runnable resultWatchdog = () -> {
        if (!awaitingResult) {
            return;
        }
        awaitingResult = false;
        if (!lastPartial.trim().isEmpty()) {
            completeVoiceCommand(lastPartial);
        } else {
            retryOrUseSystemRecognizer();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tts = new TextToSpeech(this, this);
        setContentView(buildInterface());
        setupSpeechRecognizer();
        ensureStartupPermissions();
    }

    private LinearLayout buildInterface() {
        int padding = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding * 2, padding, padding);
        root.setBackgroundColor(Color.rgb(10, 18, 30));

        TextView title = new TextView(this);
        title.setText("Chintu AI");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("آپ کا ذاتی موبائل اسسٹنٹ");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(20);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(12);
        root.addView(subtitle, subtitleParams);

        statusView = new TextView(this);
        statusView.setText("تیار ہوں");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(40);
        root.addView(statusView, statusParams);

        listenButton = new Button(this);
        listenButton.setText("حکم بولیں");
        listenButton.setTextSize(20);
        listenButton.setOnClickListener(v -> startListening());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64));
        buttonParams.topMargin = dp(28);
        root.addView(listenButton, buttonParams);

        TextView examples = new TextView(this);
        examples.setText("مثال: گوگل پر موسم تلاش کرو، ہوم کا نمبر نکالو، ہوم کو کال کرو");
        examples.setTextColor(Color.GRAY);
        examples.setTextSize(15);
        examples.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams examplesParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        examplesParams.topMargin = dp(24);
        root.addView(examples, examplesParams);

        return root;
    }

    private void setupSpeechRecognizer() {
        destroySpeechRecognizer();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speechIntent = createSpeechIntent();
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(this);
        speechIntent = createSpeechIntent();
    }

    private Intent createSpeechIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 7);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 9000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2800L);
        return intent;
    }

    private void ensureStartupPermissions() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_CONTACTS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
            return;
        }

        if (tts != null) {
            tts.stop();
        }

        cancelCurrentRecognition();
        directRetryCount = 0;
        lastPartial = "";
        setupSpeechRecognizer();

        if (speechRecognizer == null) {
            launchSystemSpeechRecognizer();
            return;
        }

        handler.postDelayed(this::beginDirectRecognition, 250L);
    }

    private void beginDirectRecognition() {
        if (speechRecognizer == null || speechIntent == null) {
            launchSystemSpeechRecognizer();
            return;
        }

        try {
            isListening = true;
            awaitingResult = false;
            lastPartial = "";
            listenButton.setText("سن رہا ہوں...");
            statusView.setText("اب بولیں، میں سن رہا ہوں...");
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

        if (directRetryCount < MAX_DIRECT_RETRIES) {
            directRetryCount++;
            statusView.setText("دوبارہ سن رہا ہوں...");
            setupSpeechRecognizer();
            handler.postDelayed(this::beginDirectRecognition, 450L);
            return;
        }

        launchSystemSpeechRecognizer();
    }

    private void launchSystemSpeechRecognizer() {
        finishListeningUi("اب بولیں...");
        Intent fallback = createSpeechIntent();
        fallback.putExtra(RecognizerIntent.EXTRA_PROMPT, "حکم بولیں");
        try {
            startActivityForResult(fallback, REQUEST_SPEECH_FALLBACK);
        } catch (ActivityNotFoundException error) {
            finishListeningUi("آواز شناخت دستیاب نہیں");
        }
    }

    private void cancelCurrentRecognition() {
        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        isListening = false;
        awaitingResult = false;
        if (speechRecognizer != null) {
            suppressErrorsUntil = SystemClock.uptimeMillis() + 900L;
            try {
                speechRecognizer.cancel();
            } catch (RuntimeException ignored) {
                // Xiaomi کے بعض ورژنز cancel پر exception دیتے ہیں۔
            }
        }
    }

    private void finishListeningUi(String message) {
        isListening = false;
        awaitingResult = false;
        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        listenButton.setText("حکم بولیں");
        statusView.setText(message);
    }

    private void completeVoiceCommand(String command) {
        String cleaned = command == null ? "" : command.trim();
        finishListeningUi(cleaned.isEmpty() ? "دوبارہ کوشش کریں" : cleaned);
        if (!cleaned.isEmpty()) {
            handleCommand(cleaned);
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        statusView.setText("اب بولیں...");
    }

    @Override
    public void onBeginningOfSpeech() {
        statusView.setText("سن رہا ہوں...");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        // آواز کی سطح کے لیے فی الحال بصری تبدیلی ضروری نہیں۔
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // استعمال نہیں ہو رہا۔
    }

    @Override
    public void onEndOfSpeech() {
        handler.removeCallbacks(listenWatchdog);
        isListening = false;
        awaitingResult = true;
        listenButton.setText("سمجھ رہا ہوں...");
        statusView.setText("سمجھ رہا ہوں...");
        handler.removeCallbacks(resultWatchdog);
        handler.postDelayed(resultWatchdog, RESULT_WATCHDOG_MS);
    }

    @Override
    public void onError(int error) {
        if (SystemClock.uptimeMillis() < suppressErrorsUntil) {
            return;
        }

        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        isListening = false;
        awaitingResult = false;

        if (!lastPartial.trim().isEmpty()
                && (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_CLIENT)) {
            completeVoiceCommand(lastPartial);
            return;
        }

        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_NETWORK
                || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
            retryOrUseSystemRecognizer();
            return;
        }

        finishListeningUi("آواز سمجھنے میں مسئلہ آیا، دوبارہ کوشش کریں");
    }

    @Override
    public void onResults(Bundle results) {
        handler.removeCallbacks(listenWatchdog);
        handler.removeCallbacks(resultWatchdog);
        isListening = false;
        awaitingResult = false;

        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) {
            if (!lastPartial.trim().isEmpty()) {
                completeVoiceCommand(lastPartial);
            } else {
                retryOrUseSystemRecognizer();
            }
            return;
        }
        completeVoiceCommand(chooseBestCommand(matches));
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> partial =
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (partial != null && !partial.isEmpty()) {
            lastPartial = chooseBestCommand(partial);
            statusView.setText(lastPartial);
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // استعمال نہیں ہو رہا۔
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SPEECH_FALLBACK) {
            return;
        }

        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                completeVoiceCommand(chooseBestCommand(matches));
                return;
            }
        }
        finishListeningUi("دوبارہ کوشش کریں");
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
        String text = raw.toLowerCase(Locale.ROOT);
        int score = Math.min(raw.length(), 40) / 4;
        if (containsAny(text, "گوگل", "گوجل", "google")) score += 18;
        if (containsAny(text, "موسم", "weather", "درجہ حرارت")) score += 18;
        if (containsAny(text, "فون ڈائریکٹری", "کانٹیکٹ", "رابطہ")) score += 18;
        if (containsAny(text, "نمبر", "ہوم", "home")) score += 12;
        if (containsAny(text, "کال", "فون کرو", "dial", "call")) score += 10;
        if (containsAny(text, "تلاش", "سرچ", "search")) score += 8;
        if (containsAny(text, "انسٹاگرام", "واٹس ایپ", "یوٹیوب", "فیس بک")) score += 8;
        return score;
    }

    private void handleCommand(String rawCommand) {
        String command = rawCommand.toLowerCase(Locale.ROOT).trim();

        if (containsAny(command,
                "بینک", "bank", "ایزی پیسہ", "easypaisa", "جاز کیش", "jazzcash",
                "رقم ٹرانسفر", "پیسے بھیجو", "ادائیگی کرو", "payment", "transfer money")) {
            speak("معاف کیجیے، میں بینک یا مالی لین دین نہیں کروں گا");
            return;
        }

        if (isContactLookupCommand(command)) {
            if (!ensureContactsPermission(rawCommand)) return;
            showContactNumber(extractContactName(rawCommand));
            return;
        }

        if (containsAny(command, "کال کرو", "فون کرو", "کال لگاؤ", "dial", "call")) {
            String number = extractPhoneNumber(rawCommand);
            if (!number.isEmpty()) {
                speak("نمبر ڈائلر میں کھول رہا ہوں");
                openIntent(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)));
                return;
            }
            if (!ensureContactsPermission(rawCommand)) return;
            dialContact(extractContactName(rawCommand));
            return;
        }

        if (containsAny(command, "میسج کرو", "پیغام بھیجو", "ایس ایم ایس", "sms")) {
            String number = extractPhoneNumber(rawCommand);
            if (!number.isEmpty()) {
                speak("میسج لکھنے کا صفحہ کھول رہا ہوں");
                openIntent(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number)));
                return;
            }
            if (!ensureContactsPermission(rawCommand)) return;
            messageContact(extractContactName(rawCommand));
            return;
        }

        if (containsAny(command, "یوٹیوب پر", "youtube پر", "youtube میں")) {
            String query = cleanWebSearchQuery(rawCommand);
            if (!query.isEmpty()) {
                speak("یوٹیوب پر تلاش کر رہا ہوں");
                openUri("https://www.youtube.com/results?search_query=" + Uri.encode(query));
                return;
            }
        }

        if (containsAny(command,
                "گوگل", "گوجل", "google", "موسم", "weather", "درجہ حرارت",
                "ویب پر", "انٹرنیٹ پر", "تلاش کرو", "سرچ کرو")) {
            String query = cleanWebSearchQuery(rawCommand);
            if (query.isEmpty()) query = rawCommand;
            speak("گوگل پر تلاش کر رہا ہوں");
            openGoogleSearch(query);
            return;
        }

        if (containsAny(command,
                "نقشے میں", "میپس میں", "maps میں", "راستہ دکھاؤ", "لوکیشن دکھاؤ")) {
            String query = cleanMapQuery(rawCommand);
            if (!query.isEmpty()) {
                speak("نقشے میں تلاش کر رہا ہوں");
                openIntent(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=" + Uri.encode(query))));
                return;
            }
        }

        if (containsAny(command, "پوسٹ لکھو", "شیئر کرو", "share کرو")) {
            String text = extractAfterAny(rawCommand,
                    "پوسٹ لکھو", "شیئر کرو", "share کرو").trim();
            if (text.isEmpty()) {
                speak("پوسٹ کا متن بھی بولیں");
            } else {
                speak("شیئر کرنے کے لیے ایپ منتخب کریں");
                shareText(text);
            }
            return;
        }

        if (containsAny(command, "یوٹیوب", "youtube")) {
            openPackageCandidates("یوٹیوب کھول رہا ہوں", "https://www.youtube.com",
                    "com.google.android.youtube");
            return;
        }
        if (containsAny(command, "واٹس ایپ", "whatsapp")) {
            openPackageCandidates("واٹس ایپ کھول رہا ہوں", "https://www.whatsapp.com",
                    "com.whatsapp.w4b", "com.whatsapp");
            return;
        }
        if (containsAny(command, "انسٹاگرام", "instagram")) {
            openPackageCandidates("انسٹاگرام کھول رہا ہوں", "https://www.instagram.com",
                    "com.instagram.android");
            return;
        }
        if (containsAny(command, "فیس بک", "facebook")) {
            openPackageCandidates("فیس بک کھول رہا ہوں", "https://www.facebook.com",
                    "com.facebook.katana", "com.facebook.lite");
            return;
        }
        if (containsAny(command, "میسنجر", "messenger")) {
            openPackageCandidates("میسنجر کھول رہا ہوں", "https://www.messenger.com",
                    "com.facebook.orca");
            return;
        }
        if (containsAny(command, "ٹک ٹاک", "tiktok")) {
            openPackageCandidates("ٹک ٹاک کھول رہا ہوں", "https://www.tiktok.com",
                    "com.zhiliaoapp.musically", "com.ss.android.ugc.trill");
            return;
        }
        if (containsAny(command, "ایکس کھولو", "ٹوئٹر", "twitter", "x کھولو")) {
            openPackageCandidates("ایکس کھول رہا ہوں", "https://x.com",
                    "com.twitter.android");
            return;
        }
        if (containsAny(command, "جی میل", "gmail", "ای میل")) {
            openPackageCandidates("ای میل کھول رہا ہوں", "https://mail.google.com",
                    "com.google.android.gm");
            return;
        }
        if (containsAny(command, "گوگل میپس", "میپس کھولو", "نقشہ کھولو", "maps کھولو")) {
            openPackageCandidates("میپس کھول رہا ہوں", "https://maps.google.com",
                    "com.google.android.apps.maps");
            return;
        }
        if (containsAny(command, "کروم", "براؤزر", "browser", "chrome")) {
            openPackageCandidates("براؤزر کھول رہا ہوں", "https://www.google.com",
                    "com.android.chrome", "com.mi.globalbrowser", "com.android.browser");
            return;
        }
        if (containsAny(command, "کیلکولیٹر", "calculator")) {
            if (!openPackageCandidates("کیلکولیٹر کھول رہا ہوں", null,
                    "com.miui.calculator", "com.google.android.calculator")) {
                openCategory(Intent.CATEGORY_APP_CALCULATOR);
            }
            return;
        }
        if (containsAny(command, "گیلری", "تصاویر", "فوٹوز", "photos")) {
            openPackageCandidates("تصاویر کھول رہا ہوں", null,
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
        if (containsAny(command, "کیمرہ", "camera")) {
            speak("کیمرہ کھول رہا ہوں");
            openIntent(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
            return;
        }

        speak("یہ حکم ابھی شامل نہیں ہے");
        statusView.setText("نامعلوم حکم: " + rawCommand);
    }

    private boolean isContactLookupCommand(String command) {
        return containsAny(command, "فون ڈائریکٹری", "کانٹیکٹ", "کانٹیکٹس", "رابطہ", "رابطوں")
                || (containsAny(command, "نمبر نکالو", "نمبر تلاش کرو", "نمبر ڈھونڈو",
                "نمبر دکھاؤ", "نمبر بتاؤ") && extractPhoneNumber(command).isEmpty());
    }

    private boolean ensureContactsPermission(String rawCommand) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        pendingContactCommand = rawCommand;
        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_PERMISSIONS);
        statusView.setText("کانٹیکٹس کی اجازت دیں");
        return false;
    }

    private void showContactNumber(String requestedName) {
        ContactMatch match = findContact(requestedName);
        if (match == null) {
            speak("یہ نام فون ڈائریکٹری میں نہیں ملا");
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(match.displayName, match.phoneNumber));
        }
        statusView.setText(match.displayName + "\n" + match.phoneNumber + "\nنمبر کاپی ہو گیا");
        speak(match.displayName + " کا نمبر مل گیا اور کاپی کر دیا ہے");
    }

    private void dialContact(String requestedName) {
        ContactMatch match = findContact(requestedName);
        if (match == null) {
            speak("یہ نام فون ڈائریکٹری میں نہیں ملا");
            return;
        }
        speak(match.displayName + " کا نمبر ڈائلر میں کھول رہا ہوں");
        openIntent(new Intent(Intent.ACTION_DIAL,
                Uri.fromParts("tel", match.phoneNumber, null)));
    }

    private void messageContact(String requestedName) {
        ContactMatch match = findContact(requestedName);
        if (match == null) {
            speak("یہ نام فون ڈائریکٹری میں نہیں ملا");
            return;
        }
        speak(match.displayName + " کو میسج لکھ رہا ہوں");
        openIntent(new Intent(Intent.ACTION_SENDTO,
                Uri.fromParts("smsto", match.phoneNumber, null)));
    }

    private ContactMatch findContact(String requestedName) {
        String target = normalizeContactKey(requestedName);
        if (target.isEmpty()) {
            speak("کانٹیکٹ کا نام واضح بولیں");
            return null;
        }

        String[] projection = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };

        ContactMatch best = null;
        int bestScore = -1;
        try (Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cursor == null) return null;
            int nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
            while (cursor.moveToNext()) {
                String displayName = cursor.getString(nameIndex);
                String phoneNumber = cursor.getString(numberIndex);
                if (displayName == null || phoneNumber == null) continue;
                String key = normalizeContactKey(displayName);
                int score = contactScore(target, key);
                if (score > bestScore) {
                    bestScore = score;
                    best = new ContactMatch(displayName, phoneNumber);
                }
            }
        } catch (SecurityException error) {
            speak("کانٹیکٹس کی اجازت درکار ہے");
            return null;
        }
        return bestScore >= 55 ? best : null;
    }

    private int contactScore(String target, String candidate) {
        if (candidate.equals(target)) return 100;
        if (candidate.startsWith(target) || target.startsWith(candidate)) return 85;
        if (candidate.contains(target) || target.contains(candidate)) return 72;
        String[] targetWords = target.split(" ");
        String[] candidateWords = candidate.split(" ");
        for (String targetWord : targetWords) {
            for (String candidateWord : candidateWords) {
                if (targetWord.equals(candidateWord)) return 68;
                if (targetWord.length() >= 3 && candidateWord.startsWith(targetWord)) return 58;
            }
        }
        return 0;
    }

    private String extractContactName(String raw) {
        String cleaned = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[،,۔.!?؛:()\\[\\]{}]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        String[] stopWords = {
                "میری", "میرے", "میرا", "فون", "ڈائریکٹری", "میں", "سے", "کا", "کی", "کے",
                "نمبر", "نکالو", "تلاش", "کرو", "کر", "دو", "ڈھونڈو", "دکھاؤ", "بتاؤ",
                "جس", "پر", "لکھا", "ہے", "کو", "کال", "لگاؤ", "پیغام", "میسج", "بھیجو",
                "کانٹیکٹ", "کانٹیکٹس", "رابطہ", "رابطوں", "نام", "والا", "والی",
                "phone", "directory", "contact", "contacts", "number", "find", "show", "call",
                "dial", "message", "sms", "the", "named"
        };

        Set<String> result = new LinkedHashSet<>();
        outer:
        for (String word : cleaned.split(" ")) {
            if (word.trim().isEmpty()) continue;
            for (String stopWord : stopWords) {
                if (word.equals(stopWord)) continue outer;
            }
            result.add(word);
        }
        return String.join(" ", result).trim();
    }

    private String cleanWebSearchQuery(String raw) {
        String result = raw;
        String[] remove = {
                "گوگل پر", "گوگل میں", "گوگل", "گوجل پر", "گوجل", "google پر", "google میں",
                "google", "ویب پر", "انٹرنیٹ پر", "تلاش کرو", "تلاش کریں", "سرچ کرو",
                "سرچ کریں", "search", "کھولو", "دکھاؤ"
        };
        for (String phrase : remove) result = result.replace(phrase, " ");
        return result.replaceAll("\\s+", " ").trim();
    }

    private String cleanMapQuery(String raw) {
        String result = raw;
        String[] remove = {
                "نقشے میں", "میپس میں", "maps میں", "راستہ دکھاؤ", "لوکیشن دکھاؤ",
                "تلاش کرو", "سرچ کرو"
        };
        for (String phrase : remove) result = result.replace(phrase, " ");
        return result.replaceAll("\\s+", " ").trim();
    }

    private String extractAfterAny(String original, String... phrases) {
        String lower = original.toLowerCase(Locale.ROOT);
        for (String phrase : phrases) {
            int index = lower.indexOf(phrase.toLowerCase(Locale.ROOT));
            if (index >= 0) return original.substring(index + phrase.length()).trim();
        }
        return "";
    }

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String extractPhoneNumber(String text) {
        String normalized = normalizeDigits(text);
        Matcher matcher = Pattern.compile("\\+?[0-9][0-9\\s-]{6,18}[0-9]").matcher(normalized);
        if (matcher.find()) return matcher.group().replaceAll("[\\s-]", "");
        return "";
    }

    private String normalizeDigits(String text) {
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

    private String normalizeContactKey(String text) {
        if (text == null) return "";
        return normalizeDigits(text.toLowerCase(Locale.ROOT))
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void openGoogleSearch(String query) {
        String url = "https://www.google.com/search?q=" + Uri.encode(query);
        Intent chrome = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        chrome.setPackage("com.android.chrome");
        try {
            startActivity(chrome);
        } catch (ActivityNotFoundException error) {
            openUri(url);
        }
    }

    private void shareText(String text) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        openIntent(Intent.createChooser(shareIntent, "ایپ منتخب کریں"));
    }

    private void openCategory(String category) {
        openIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category));
    }

    private boolean openPackageCandidates(String spokenText, String fallbackUrl,
                                          String... packageNames) {
        speak(spokenText);
        for (String packageName : packageNames) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                openIntent(launchIntent);
                return true;
            }
        }
        if (fallbackUrl != null) {
            openUri(fallbackUrl);
            return true;
        }
        speak("یہ ایپ فون میں نہیں ملی");
        return false;
    }

    private void openUri(String url) {
        openIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void openIntent(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            statusView.setText("مطلوبہ ایپ نہیں ملی");
            Toast.makeText(this, "یہ کام کرنے والی ایپ فون میں موجود نہیں",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void speak(String text) {
        statusView.setText(text);
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chintu-response");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            setupSpeechRecognizer();
        }

        if (pendingContactCommand != null
                && checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            String command = pendingContactCommand;
            pendingContactCommand = null;
            handleCommand(command);
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(new Locale("ur", "PK"));
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            tts.setSpeechRate(0.9f);
            tts.setPitch(0.85f);
        }
    }

    private void destroySpeechRecognizer() {
        if (speechRecognizer != null) {
            suppressErrorsUntil = SystemClock.uptimeMillis() + 900L;
            try {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (RuntimeException ignored) {
                // Xiaomi compatibility
            }
            speechRecognizer = null;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        destroySpeechRecognizer();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class ContactMatch {
        final String displayName;
        final String phoneNumber;

        ContactMatch(String displayName, String phoneNumber) {
            this.displayName = displayName;
            this.phoneNumber = phoneNumber;
        }
    }
}
