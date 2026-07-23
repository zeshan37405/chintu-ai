package com.zeshan.chintuai;

import android.Manifest;
import android.app.Activity;
import android.app.SearchManager;
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
    private static final int MAX_LISTEN_RETRIES = 2;

    private TextView statusView;
    private Button listenButton;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isListening = false;
    private int listenRetryCount = 0;
    private String pendingContactCommand;

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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.setText("آواز شناخت دستیاب نہیں");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(this);

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                12000L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                2500L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                3500L);
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
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_PERMISSIONS);
            return;
        }

        if (speechRecognizer == null) {
            setupSpeechRecognizer();
        }

        if (speechRecognizer == null || speechIntent == null) {
            speak("آواز شناخت دستیاب نہیں");
            return;
        }

        if (tts != null) {
            tts.stop();
        }

        listenRetryCount = 0;
        beginRecognition();
    }

    private void beginRecognition() {
        if (speechRecognizer == null) {
            return;
        }

        try {
            if (isListening) {
                speechRecognizer.cancel();
            }
            isListening = true;
            listenButton.setText("سن رہا ہوں...");
            statusView.setText("اب بولیں، میں سن رہا ہوں...");
            speechRecognizer.startListening(speechIntent);
        } catch (RuntimeException error) {
            isListening = false;
            listenButton.setText("حکم بولیں");
            statusView.setText("دوبارہ بٹن دبائیں");
        }
    }

    private void retryListening() {
        if (listenRetryCount >= MAX_LISTEN_RETRIES) {
            finishListening("آواز واضح نہیں ملی، دوبارہ کوشش کریں");
            return;
        }

        listenRetryCount++;
        statusView.setText("سن رہا ہوں، دوبارہ بولیں...");
        handler.postDelayed(this::beginRecognition, 500L);
    }

    private void finishListening(String message) {
        isListening = false;
        listenButton.setText("حکم بولیں");
        statusView.setText(message);
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
        // سطحِ آواز کے لیے فی الحال کسی بصری تبدیلی کی ضرورت نہیں۔
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        // ضروری نہیں۔
    }

    @Override
    public void onEndOfSpeech() {
        isListening = false;
        statusView.setText("سمجھ رہا ہوں...");
    }

    @Override
    public void onError(int error) {
        isListening = false;

        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            retryListening();
            return;
        }

        finishListening("آواز سمجھنے میں مسئلہ آیا، دوبارہ کوشش کریں");
    }

    @Override
    public void onResults(Bundle results) {
        isListening = false;
        listenButton.setText("حکم بولیں");

        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

        if (matches == null || matches.isEmpty()) {
            retryListening();
            return;
        }

        String command = chooseBestCommand(matches);
        statusView.setText(command);
        handleCommand(command);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> partial =
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (partial != null && !partial.isEmpty()) {
            statusView.setText(partial.get(0));
        }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        // استعمال نہیں ہو رہا۔
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
        int score = Math.min(raw.length(), 30) / 5;

        if (containsAny(text, "گوگل", "گوجل", "google")) score += 12;
        if (containsAny(text, "موسم", "weather", "درجہ حرارت")) score += 12;
        if (containsAny(text, "فون ڈائریکٹری", "کانٹیکٹ", "رابطہ")) score += 12;
        if (containsAny(text, "نمبر", "ہوم", "home")) score += 8;
        if (containsAny(text, "کال", "فون کرو", "dial", "call")) score += 7;
        if (containsAny(text, "تلاش", "سرچ", "search")) score += 6;
        if (containsAny(text, "انسٹاگرام", "واٹس ایپ", "یوٹیوب", "فیس بک")) score += 6;

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
            if (!ensureContactsPermission(rawCommand)) {
                return;
            }

            String contactName = extractContactName(rawCommand);
            showContactNumber(contactName);
            return;
        }

        if (containsAny(command, "کال کرو", "فون کرو", "dial", "call")) {
            String number = extractPhoneNumber(rawCommand);
            if (!number.isEmpty()) {
                speak("نمبر ڈائلر میں کھول رہا ہوں");
                openIntent(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)));
                return;
            }

            if (!ensureContactsPermission(rawCommand)) {
                return;
            }

            String contactName = extractContactName(rawCommand);
            dialContact(contactName);
            return;
        }

        if (containsAny(command, "میسج کرو", "ایس ایم ایس", "sms")) {
            String number = extractPhoneNumber(rawCommand);
            if (!number.isEmpty()) {
                speak("میسج لکھنے کا صفحہ کھول رہا ہوں");
                openIntent(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number)));
                return;
            }

            if (!ensureContactsPermission(rawCommand)) {
                return;
            }

            String contactName = extractContactName(rawCommand);
            messageContact(contactName);
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
                "گوگل", "گوجل", "google",
                "موسم", "weather", "درجہ حرارت",
                "ویب پر", "انٹرنیٹ پر")) {
            String query = cleanWebSearchQuery(rawCommand);
            if (query.isEmpty()) {
                query = rawCommand;
            }
            speak("گوگل پر تلاش کر رہا ہوں");
            openWebSearch(query);
            return;
        }

        if (containsAny(command,
                "نقشے میں", "میپس میں", "maps میں",
                "راستہ دکھاؤ", "لوکیشن دکھاؤ")) {
            String query = cleanMapQuery(rawCommand);
            if (!query.isEmpty()) {
                speak("نقشے میں تلاش کر رہا ہوں");
                openIntent(new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=" + Uri.encode(query))));
                return;
            }
        }

        if (containsAny(command, "پوسٹ لکھو", "شیئر کرو", "share کرو")) {
            String text = extractAfterAny(
                    rawCommand, "پوسٹ لکھو", "شیئر کرو", "share کرو").trim();
            if (text.isEmpty()) {
                speak("پوسٹ کا متن بھی بولیں");
            } else {
                speak("شیئر کرنے کے لیے ایپ منتخب کریں");
                shareText(text);
            }
            return;
        }

        if (containsAny(command, "یوٹیوب", "youtube")) {
            openPackageCandidates(
                    "یوٹیوب کھول رہا ہوں",
                    "https://www.youtube.com",
                    "com.google.android.youtube");
            return;
        }

        if (containsAny(command, "واٹس ایپ", "whatsapp")) {
            openPackageCandidates(
                    "واٹس ایپ کھول رہا ہوں",
                    "https://www.whatsapp.com",
                    "com.whatsapp.w4b",
                    "com.whatsapp");
            return;
        }

        if (containsAny(command, "انسٹاگرام", "instagram")) {
            openPackageCandidates(
                    "انسٹاگرام کھول رہا ہوں",
                    "https://www.instagram.com",
                    "com.instagram.android");
            return;
        }

        if (containsAny(command, "فیس بک", "facebook")) {
            openPackageCandidates(
                    "فیس بک کھول رہا ہوں",
                    "https://www.facebook.com",
                    "com.facebook.katana",
                    "com.facebook.lite");
            return;
        }

        if (containsAny(command, "میسنجر", "messenger")) {
            openPackageCandidates(
                    "میسنجر کھول رہا ہوں",
                    "https://www.messenger.com",
                    "com.facebook.orca");
            return;
        }

        if (containsAny(command, "ٹک ٹاک", "tiktok")) {
            openPackageCandidates(
                    "ٹک ٹاک کھول رہا ہوں",
                    "https://www.tiktok.com",
                    "com.zhiliaoapp.musically",
                    "com.ss.android.ugc.trill");
            return;
        }

        if (containsAny(command, "ایکس کھولو", "ٹوئٹر", "twitter", "x کھولو")) {
            openPackageCandidates(
                    "ایکس کھول رہا ہوں",
                    "https://x.com",
                    "com.twitter.android");
            return;
        }

        if (containsAny(command, "جی میل", "gmail", "ای میل")) {
            openPackageCandidates(
                    "ای میل کھول رہا ہوں",
                    "https://mail.google.com",
                    "com.google.android.gm");
            return;
        }

        if (containsAny(command, "گوگل میپس", "میپس کھولو", "نقشہ کھولو", "maps کھولو")) {
            openPackageCandidates(
                    "میپس کھول رہا ہوں",
                    "https://maps.google.com",
                    "com.google.android.apps.maps");
            return;
        }

        if (containsAny(command, "کروم", "براؤزر", "browser", "chrome")) {
            openPackageCandidates(
                    "براؤزر کھول رہا ہوں",
                    "https://www.google.com",
                    "com.android.chrome",
                    "com.mi.globalbrowser",
                    "com.android.browser");
            return;
        }

        if (containsAny(command, "کیلکولیٹر", "calculator")) {
            if (!openPackageCandidates(
                    "کیلکولیٹر کھول رہا ہوں",
                    null,
                    "com.miui.calculator",
                    "com.google.android.calculator")) {
                openCategory(Intent.CATEGORY_APP_CALCULATOR);
            }
            return;
        }

        if (containsAny(command, "گیلری", "تصاویر", "فوٹوز", "photos")) {
            openPackageCandidates(
                    "تصاویر کھول رہا ہوں",
                    null,
                    "com.miui.gallery",
                    "com.google.android.apps.photos");
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
        return containsAny(command,
                "فون ڈائریکٹری",
                "کانٹیکٹ",
                "کانٹیکٹس",
                "رابطہ",
                "رابطوں")
                || (containsAny(command, "نمبر نکالو", "نمبر تلاش کرو", "نمبر ڈھونڈو")
                && extractPhoneNumber(command).isEmpty());
    }

    private boolean ensureContactsPermission(String rawCommand) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        pendingContactCommand = rawCommand;
        requestPermissions(
                new String[]{Manifest.permission.READ_CONTACTS},
                REQUEST_PERMISSIONS);
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
            clipboard.setPrimaryClip(
                    ClipData.newPlainText(match.displayName, match.phoneNumber));
        }

        statusView.setText(
                match.displayName + "\n" + match.phoneNumber + "\nنمبر کاپی ہو گیا");
        if (tts != null) {
            tts.speak(
                    match.displayName + " کا نمبر مل گیا اور کاپی کر دیا ہے",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "contact-found");
        }
    }

    private void dialContact(String requestedName) {
        ContactMatch match = findContact(requestedName);
        if (match == null) {
            speak("یہ نام فون ڈائریکٹری میں نہیں ملا");
            return;
        }

        speak(match.displayName + " کا نمبر ڈائلر میں کھول رہا ہوں");
        openIntent(new Intent(
                Intent.ACTION_DIAL,
                Uri.fromParts("tel", match.phoneNumber, null)));
    }

    private void messageContact(String requestedName) {
        ContactMatch match = findContact(requestedName);
        if (match == null) {
            speak("یہ نام فون ڈائریکٹری میں نہیں ملا");
            return;
        }

        speak(match.displayName + " کو میسج لکھ رہا ہوں");
        openIntent(new Intent(
                Intent.ACTION_SENDTO,
                Uri.fromParts("smsto", match.phoneNumber, null)));
    }

    private ContactMatch findContact(String requestedName) {
        String target = normalizeContactKey(requestedName);
        if (target.isEmpty()) {
            speak("کانٹیکٹ کا نام واضح نہیں ملا");
            return null;
        }

        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
        };

        ContactMatch best = null;
        int bestScore = 0;

        try (Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {

            if (cursor == null) {
                return null;
            }

            int nameIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);
            int typeIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.TYPE);
            int labelIndex = cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.LABEL);

            while (cursor.moveToNext()) {
                String displayName = cursor.getString(nameIndex);
                String number = cursor.getString(numberIndex);
                cursor.getInt(typeIndex);
                String customLabel = cursor.getString(labelIndex);

                String displayKey = normalizeContactKey(displayName);
                String labelKey = normalizeContactKey(customLabel);
                int score = 0;

                if (displayKey.equals(target)) {
                    score = 100;
                } else if (!target.isEmpty() && displayKey.contains(target)) {
                    score = 85;
                } else if (!displayKey.isEmpty() && target.contains(displayKey)) {
                    score = 75;
                }

                if (!labelKey.isEmpty() && labelKey.equals(target)) {
                    score = Math.max(score, 70);
                }

                if (score > bestScore && number != null && !number.trim().isEmpty()) {
                    bestScore = score;
                    best = new ContactMatch(displayName, number);
                }
            }
        } catch (SecurityException error) {
            speak("کانٹیکٹس کی اجازت درکار ہے");
            return null;
        }

        return bestScore >= 60 ? best : null;
    }

    private String extractContactName(String original) {
        String text = original.toLowerCase(Locale.ROOT);

        String[] removablePhrases = new String[]{
                "میری فون ڈائریکٹری میں سے",
                "فون ڈائریکٹری میں سے",
                "میری فون ڈائریکٹری",
                "فون ڈائریکٹری",
                "میرے کانٹیکٹس میں سے",
                "کانٹیکٹس میں سے",
                "کانٹیکٹس",
                "کانٹیکٹ",
                "رابطوں میں سے",
                "رابطوں",
                "رابطہ",
                "کا نمبر نکالو",
                "کا نمبر بتاؤ",
                "نمبر تلاش کرو",
                "نمبر ڈھونڈو",
                "نمبر نکالو",
                "کو کال کرو",
                "پر کال کرو",
                "کال کرو",
                "فون کرو",
                "میسج کرو",
                "ایس ایم ایس کرو",
                "جس پر لکھا ہے",
                "جس کے نام سے ہے",
                "جس پر نام ہے",
                "تلاش کرکے",
                "تلاش کر کے",
                "نکال کر دو",
                "دکھاؤ",
                "بتاؤ",
                "please"
        };

        for (String phrase : removablePhrases) {
            text = text.replace(phrase, " ");
        }

        text = text.replaceAll("[،,۔.!?()\\[\\]{}:;\"']", " ");

        String[] words = text.trim().split("\\s+");
        Set<String> unique = new LinkedHashSet<>();

        for (String word : words) {
            String cleaned = word.trim();
            if (cleaned.isEmpty() || isContactStopWord(cleaned)) {
                continue;
            }
            unique.add(cleaned);
        }

        return String.join(" ", unique).trim();
    }

    private boolean isContactStopWord(String word) {
        return word.equals("میری")
                || word.equals("میرے")
                || word.equals("میں")
                || word.equals("سے")
                || word.equals("کو")
                || word.equals("کا")
                || word.equals("کی")
                || word.equals("کے")
                || word.equals("پر")
                || word.equals("جس")
                || word.equals("نام")
                || word.equals("لکھا")
                || word.equals("ہے")
                || word.equals("والا")
                || word.equals("والی")
                || word.equals("نمبر")
                || word.equals("فون")
                || word.equals("تلاش")
                || word.equals("نکالو")
                || word.equals("کرو")
                || word.equals("کر")
                || word.equals("دو")
                || word.equals("the")
                || word.equals("contact")
                || word.equals("contacts")
                || word.equals("call")
                || word.equals("dial")
                || word.equals("message");
    }

    private String normalizeContactKey(String value) {
        if (value == null) {
            return "";
        }

        String key = value.toLowerCase(Locale.ROOT)
                .replace("ٰ", "")
                .replace("ِ", "")
                .replace("ُ", "")
                .replace("َ", "")
                .replace("ّ", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");

        if (key.equals("ہوم") || key.equals("होम") || key.equals("home")) {
            return "home";
        }
        if (key.equals("امی") || key.equals("امّی")
                || key.equals("ammi") || key.equals("mom")) {
            return "ammi";
        }
        if (key.equals("ابو") || key.equals("ابّا")
                || key.equals("abba") || key.equals("dad")) {
            return "abu";
        }

        return key;
    }

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String extractAfterAny(String original, String... phrases) {
        String lower = original.toLowerCase(Locale.ROOT);
        for (String phrase : phrases) {
            int index = lower.indexOf(phrase.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                return original.substring(index + phrase.length()).trim();
            }
        }
        return "";
    }

    private String cleanWebSearchQuery(String query) {
        String cleaned = query.toLowerCase(Locale.ROOT);

        String[] phrases = new String[]{
                "گوگل پر", "گوگل میں", "گوجل پر", "google پر", "google میں",
                "ویب پر", "انٹرنیٹ پر",
                "تلاش کرو", "تلاش کریں", "سرچ کرو", "search کرو",
                "دکھاؤ", "بتاؤ", "حال تلاش کرو"
        };

        for (String phrase : phrases) {
            cleaned = cleaned.replace(phrase, " ");
        }

        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private String cleanMapQuery(String query) {
        String cleaned = query.toLowerCase(Locale.ROOT);

        String[] phrases = new String[]{
                "نقشے میں", "میپس میں", "maps میں",
                "راستہ دکھاؤ", "لوکیشن دکھاؤ",
                "تلاش کرو", "سرچ کرو"
        };

        for (String phrase : phrases) {
            cleaned = cleaned.replace(phrase, " ");
        }

        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private String extractPhoneNumber(String text) {
        String normalized = normalizeDigits(text);
        Matcher matcher = Pattern.compile("\\+?[0-9][0-9\\s-]{6,18}[0-9]")
                .matcher(normalized);
        if (matcher.find()) {
            return matcher.group().replaceAll("[\\s-]", "");
        }
        return "";
    }

    private String normalizeDigits(String text) {
        String arabic = "٠١٢٣٤٥٦٧٨٩";
        String persian = "۰۱۲۳۴۵۶۷۸۹";
        StringBuilder result = new StringBuilder();

        for (char character : text.toCharArray()) {
            int arabicIndex = arabic.indexOf(character);
            int persianIndex = persian.indexOf(character);

            if (arabicIndex >= 0) {
                result.append(arabicIndex);
            } else if (persianIndex >= 0) {
                result.append(persianIndex);
            } else {
                result.append(character);
            }
        }

        return result.toString();
    }

    private void shareText(String text) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        openIntent(Intent.createChooser(shareIntent, "ایپ منتخب کریں"));
    }

    private void openWebSearch(String query) {
        Intent searchIntent = new Intent(Intent.ACTION_WEB_SEARCH);
        searchIntent.putExtra(SearchManager.QUERY, query);

        if (searchIntent.resolveActivity(getPackageManager()) != null) {
            openIntent(searchIntent);
        } else {
            openUri("https://www.google.com/search?q=" + Uri.encode(query));
        }
    }

    private void openCategory(String category) {
        Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category);
        openIntent(intent);
    }

    private boolean openPackageCandidates(
            String spokenMessage,
            String fallbackUrl,
            String... packageNames) {

        for (String packageName : packageNames) {
            Intent launchIntent =
                    getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                speak(spokenMessage);
                openIntent(launchIntent);
                return true;
            }
        }

        if (fallbackUrl != null) {
            speak(spokenMessage);
            openUri(fallbackUrl);
            return true;
        }

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
            Toast.makeText(
                    this,
                    "یہ کام کرنے والی ایپ فون میں موجود نہیں",
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

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }

        if (pendingContactCommand != null) {
            String command = pendingContactCommand;
            pendingContactCommand = null;

            if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED) {
                handleCommand(command);
            } else {
                speak("کانٹیکٹس کی اجازت کے بغیر نمبر تلاش نہیں ہو سکتا");
            }
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ContactMatch {
        final String displayName;
        final String phoneNumber;

        ContactMatch(String displayName, String phoneNumber) {
            this.displayName = displayName;
            this.phoneNumber = phoneNumber;
        }
    }
}
