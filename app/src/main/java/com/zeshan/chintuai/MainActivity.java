package com.zeshan.chintuai;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int REQUEST_SPEECH = 1001;
    private static final int REQUEST_MIC = 1002;

    private TextView statusView;
    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tts = new TextToSpeech(this, this);
        setContentView(buildInterface());
        ensureMicrophonePermission();
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

        Button listenButton = new Button(this);
        listenButton.setText("حکم بولیں");
        listenButton.setTextSize(20);
        listenButton.setOnClickListener(v -> startListening());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64));
        buttonParams.topMargin = dp(28);
        root.addView(listenButton, buttonParams);

        TextView examples = new TextView(this);
        examples.setText("مثال: انسٹاگرام کھولو، گوگل پر موسم تلاش کرو، 0300 پر کال کرو");
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

    private void ensureMicrophonePermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        }
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensureMicrophonePermission();
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "حکم بولیں");

        try {
            statusView.setText("سن رہا ہوں...");
            startActivityForResult(intent, REQUEST_SPEECH);
        } catch (ActivityNotFoundException error) {
            statusView.setText("آواز شناخت دستیاب نہیں");
            Toast.makeText(this, "Speech Recognition انسٹال کریں", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String command = results.get(0).trim();
                statusView.setText(command);
                handleCommand(command);
                return;
            }
        }

        statusView.setText("دوبارہ کوشش کریں");
    }

    private void handleCommand(String rawCommand) {
        String command = rawCommand.toLowerCase(Locale.ROOT).trim();

        if (containsAny(command,
                "بینک", "bank", "ایزی پیسہ", "easypaisa", "جاز کیش", "jazzcash",
                "رقم ٹرانسفر", "پیسے بھیجو", "ادائیگی کرو", "payment", "transfer money")) {
            speak("معاف کیجیے، میں بینک یا مالی لین دین نہیں کروں گا");
            return;
        }

        if (containsAny(command, "یوٹیوب پر", "youtube پر", "youtube میں")) {
            String query = cleanSearchQuery(extractAfterAny(rawCommand, "یوٹیوب پر", "youtube پر", "youtube میں"));
            if (!query.isEmpty()) {
                speak("یوٹیوب پر تلاش کر رہا ہوں");
                openUri("https://www.youtube.com/results?search_query=" + Uri.encode(query));
                return;
            }
        }

        if (containsAny(command, "گوگل پر", "گوگل میں", "google پر", "google میں")) {
            String query = cleanSearchQuery(extractAfterAny(rawCommand, "گوگل پر", "گوگل میں", "google پر", "google میں"));
            if (!query.isEmpty()) {
                speak("گوگل پر تلاش کر رہا ہوں");
                openUri("https://www.google.com/search?q=" + Uri.encode(query));
                return;
            }
        }

        if (containsAny(command, "نقشے میں", "میپس میں", "maps میں", "راستہ دکھاؤ", "لوکیشن دکھاؤ")) {
            String query = cleanSearchQuery(extractAfterAny(rawCommand,
                    "نقشے میں", "میپس میں", "maps میں", "راستہ دکھاؤ", "لوکیشن دکھاؤ"));
            if (!query.isEmpty()) {
                speak("نقشے میں تلاش کر رہا ہوں");
                openIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))));
                return;
            }
        }

        if (containsAny(command, "کال کرو", "فون کرو", "dial", "call")) {
            String number = extractPhoneNumber(rawCommand);
            speak(number.isEmpty() ? "فون کھول رہا ہوں" : "نمبر ڈائلر میں کھول رہا ہوں");
            openIntent(new Intent(Intent.ACTION_DIAL,
                    Uri.parse(number.isEmpty() ? "tel:" : "tel:" + number)));
            return;
        }

        if (containsAny(command, "میسج کرو", "ایس ایم ایس", "sms")) {
            String number = extractPhoneNumber(rawCommand);
            speak("میسج لکھنے کا صفحہ کھول رہا ہوں");
            openIntent(new Intent(Intent.ACTION_SENDTO,
                    Uri.parse(number.isEmpty() ? "smsto:" : "smsto:" + number)));
            return;
        }

        if (containsAny(command, "پوسٹ لکھو", "شیئر کرو", "share کرو")) {
            String text = extractAfterAny(rawCommand, "پوسٹ لکھو", "شیئر کرو", "share کرو").trim();
            if (text.isEmpty()) {
                speak("پوسٹ کا متن بھی بولیں");
            } else {
                speak("شیئر کرنے کے لیے ایپ منتخب کریں");
                shareText(text);
            }
            return;
        }

        if (containsAny(command, "یوٹیوب", "youtube")) {
            speak("یوٹیوب کھول رہا ہوں");
            openPackage("com.google.android.youtube", "https://www.youtube.com");
            return;
        }

        if (containsAny(command, "واٹس ایپ", "whatsapp")) {
            speak("واٹس ایپ کھول رہا ہوں");
            if (!openPackage("com.whatsapp.w4b", null)) {
                openPackage("com.whatsapp", "https://www.whatsapp.com");
            }
            return;
        }

        if (containsAny(command, "انسٹاگرام", "instagram")) {
            speak("انسٹاگرام کھول رہا ہوں");
            openPackage("com.instagram.android", "https://www.instagram.com");
            return;
        }

        if (containsAny(command, "فیس بک", "facebook")) {
            speak("فیس بک کھول رہا ہوں");
            openPackage("com.facebook.katana", "https://www.facebook.com");
            return;
        }

        if (containsAny(command, "میسنجر", "messenger")) {
            speak("میسنجر کھول رہا ہوں");
            openPackage("com.facebook.orca", "https://www.messenger.com");
            return;
        }

        if (containsAny(command, "ٹک ٹاک", "tiktok")) {
            speak("ٹک ٹاک کھول رہا ہوں");
            openPackage("com.zhiliaoapp.musically", "https://www.tiktok.com");
            return;
        }

        if (containsAny(command, "ایکس کھولو", "ٹوئٹر", "twitter", "x کھولو")) {
            speak("ایکس کھول رہا ہوں");
            openPackage("com.twitter.android", "https://x.com");
            return;
        }

        if (containsAny(command, "جی میل", "gmail", "ای میل")) {
            speak("ای میل کھول رہا ہوں");
            openCategory(Intent.CATEGORY_APP_EMAIL);
            return;
        }

        if (containsAny(command, "گوگل میپس", "میپس کھولو", "نقشہ کھولو", "maps کھولو")) {
            speak("میپس کھول رہا ہوں");
            openPackage("com.google.android.apps.maps", "https://maps.google.com");
            return;
        }

        if (containsAny(command, "کروم", "براؤزر", "browser", "chrome")) {
            speak("براؤزر کھول رہا ہوں");
            openUri("https://www.google.com");
            return;
        }

        if (containsAny(command, "کیلکولیٹر", "calculator")) {
            speak("کیلکولیٹر کھول رہا ہوں");
            openCategory(Intent.CATEGORY_APP_CALCULATOR);
            return;
        }

        if (containsAny(command, "گیلری", "تصاویر", "فوٹوز", "photos")) {
            speak("تصاویر کھول رہا ہوں");
            openPackage("com.google.android.apps.photos", null);
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

    private String cleanSearchQuery(String query) {
        return query
                .replace("تلاش کرو", "")
                .replace("سرچ کرو", "")
                .replace("search", "")
                .trim();
    }

    private String extractPhoneNumber(String text) {
        String normalized = normalizeDigits(text);
        Matcher matcher = Pattern.compile("\\+?[0-9][0-9\\s-]{6,18}[0-9]").matcher(normalized);
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

    private void openCategory(String category) {
        Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category);
        openIntent(intent);
    }

    private boolean openPackage(String packageName, String fallbackUrl) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            openIntent(launchIntent);
            return true;
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
            Toast.makeText(this, "یہ کام کرنے والی ایپ فون میں موجود نہیں", Toast.LENGTH_LONG).show();
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
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            tts.setSpeechRate(0.9f);
            tts.setPitch(0.85f);
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
