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
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChintuActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int REQUEST_PERMISSIONS = 2101;
    private static final int REQUEST_SPEECH = 2102;

    private TextView statusView;
    private EditText commandInput;
    private Button voiceButton;
    private TextToSpeech tts;
    private String pendingContactCommand;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tts = new TextToSpeech(this, this);
        setContentView(buildUi());
        requestStartupPermissions();
    }

    private ScrollView buildUi() {
        int padding = dp(22);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 15, 27));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, dp(40), padding, dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Chintu AI");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("آپ کا ذاتی موبائل اسسٹنٹ");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(18);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(8);
        root.addView(subtitle, subtitleParams);

        statusView = new TextView(this);
        statusView.setText("تیار ہوں");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        statusView.setMinHeight(dp(70));
        statusView.setPadding(dp(10), dp(18), dp(10), dp(18));
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(26);
        root.addView(statusView, statusParams);

        commandInput = new EditText(this);
        commandInput.setHint("کمانڈ لکھیں، مثلاً: ہوم کو کال کرو");
        commandInput.setTextColor(Color.WHITE);
        commandInput.setHintTextColor(Color.GRAY);
        commandInput.setSingleLine(false);
        commandInput.setMinHeight(dp(58));
        commandInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(14);
        root.addView(commandInput, inputParams);

        voiceButton = new Button(this);
        voiceButton.setText("🎤 حکم بولیں");
        voiceButton.setTextSize(19);
        voiceButton.setOnClickListener(v -> startVoiceInput());
        LinearLayout.LayoutParams voiceParams = matchWrap();
        voiceParams.topMargin = dp(18);
        voiceParams.height = dp(62);
        root.addView(voiceButton, voiceParams);

        Button runButton = new Button(this);
        runButton.setText("کمانڈ چلائیں");
        runButton.setTextSize(18);
        runButton.setOnClickListener(v -> runTypedCommand());
        LinearLayout.LayoutParams runParams = matchWrap();
        runParams.topMargin = dp(10);
        runParams.height = dp(58);
        root.addView(runButton, runParams);

        TextView examples = new TextView(this);
        examples.setText(
                "مثالیں:\n" +
                "گوگل پر حسن ابدال کا موسم تلاش کرو\n" +
                "ہوم کا نمبر دکھاؤ / ہوم کو کال کرو\n" +
                "یوٹیوب پر نعت تلاش کرو\n" +
                "انسٹاگرام، واٹس ایپ، کیمرہ یا میپس کھولو\n" +
                "ساڑھے سات بجے الارم لگاؤ");
        examples.setTextColor(Color.GRAY);
        examples.setTextSize(15);
        examples.setGravity(Gravity.CENTER);
        examples.setLineSpacing(0f, 1.2f);
        LinearLayout.LayoutParams examplesParams = matchWrap();
        examplesParams.topMargin = dp(22);
        root.addView(examples, examplesParams);

        return scroll;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void requestStartupPermissions() {
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

    private void startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
            return;
        }
        if (tts != null) tts.stop();

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "اب واضح آواز میں حکم بولیں");
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);

        try {
            voiceButton.setText("سن رہا ہوں...");
            statusView.setText("اب بولیں...");
            startActivityForResult(intent, REQUEST_SPEECH);
        } catch (ActivityNotFoundException error) {
            finishVoiceUi("آواز شناخت دستیاب نہیں، کمانڈ لکھ کر چلائیں");
        }
    }

    private void finishVoiceUi(String message) {
        voiceButton.setText("🎤 حکم بولیں");
        statusView.setText(message);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SPEECH) return;

        voiceButton.setText("🎤 حکم بولیں");
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> matches =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                String command = chooseBestCommand(matches);
                commandInput.setText(command);
                executeCommand(command);
                return;
            }
        }
        statusView.setText("آواز واضح نہیں ملی، دوبارہ بولیں یا کمانڈ لکھیں");
    }

    private void runTypedCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            statusView.setText("پہلے کمانڈ لکھیں یا بولیں");
            return;
        }
        executeCommand(command);
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
        int score = Math.min(raw.length(), 50) / 5;
        if (containsAny(text, "گوگل", "google", "موسم", "weather")) score += 20;
        if (containsAny(text, "کال", "فون", "نمبر", "contact", "home", "ہوم")) score += 18;
        if (containsAny(text, "یوٹیوب", "instagram", "انسٹاگرام", "whatsapp", "واٹس")) score += 14;
        if (containsAny(text, "کھولو", "تلاش", "سرچ", "میپس", "maps")) score += 10;
        return score;
    }

    private void executeCommand(String rawCommand) {
        String command = normalize(rawCommand);
        statusView.setText(rawCommand);

        if (containsAny(command,
                "بینک", "bank", "ایزی پیسہ", "easypaisa", "جاز کیش", "jazzcash",
                "رقم ٹرانسفر", "پیسے بھیجو", "payment", "transfer money")) {
            speak("مالی لین دین کی کمانڈ محفوظ طریقے سے بند ہے");
            return;
        }

        if (isContactLookupCommand(command)) {
            if (!ensureContactsPermission(rawCommand)) return;
            showContactNumber(extractContactName(rawCommand));
            return;
        }

        if (containsAny(command, "کال کرو", "فون کرو", "کال لگاؤ", "فون لگاؤ", "call", "dial")) {
            String number = extractPhoneNumber(rawCommand);
            if (!number.isEmpty()) {
                openDialer(number);
            } else if (ensureContactsPermission(rawCommand)) {
                dialContact(extractContactName(rawCommand));
            }
            return;
        }

        if (containsAny(command, "میسج کرو", "پیغام بھیجو", "ایس ایم ایس", "sms", "message")) {
            String number = extractPhoneNumber(rawCommand);
            if (!number.isEmpty()) {
                openMessage(number);
            } else if (ensureContactsPermission(rawCommand)) {
                messageContact(extractContactName(rawCommand));
            }
            return;
        }

        if (containsAny(command, "وقت", "ٹائم", "time")
                && !containsAny(command, "ٹائمر", "timer")) {
            String time = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date());
            speak("اس وقت " + time + " ہوئے ہیں");
            return;
        }

        if (containsAny(command, "تاریخ", "آج کون سا دن", "date")) {
            String date = new SimpleDateFormat("EEEE، d MMMM yyyy", new Locale("ur", "PK"))
                    .format(new Date());
            speak("آج " + date + " ہے");
            return;
        }

        if (containsAny(command, "الارم", "alarm")) {
            openAlarm(rawCommand);
            return;
        }

        if (containsAny(command, "ٹائمر", "timer")) {
            openTimer(rawCommand);
            return;
        }

        if (containsAny(command, "یوٹیوب", "youtube")
                && containsAny(command, "تلاش", "سرچ", "search", "پر")) {
            String query = cleanQuery(rawCommand,
                    "یوٹیوب پر", "youtube پر", "youtube میں", "یوٹیوب",
                    "تلاش کرو", "تلاش کریں", "سرچ کرو", "سرچ کریں", "search");
            if (query.isEmpty()) query = rawCommand;
            speak("یوٹیوب پر تلاش کر رہا ہوں");
            openUri("https://www.youtube.com/results?search_query=" + Uri.encode(query));
            return;
        }

        if (containsAny(command, "موسم", "weather", "درجہ حرارت")) {
            String query = cleanQuery(rawCommand,
                    "گوگل پر", "گوگل", "google", "تلاش کرو", "سرچ کرو");
            if (query.isEmpty() || query.equals("موسم")) query = "حسن ابدال موسم آج";
            speak("موسم تلاش کر رہا ہوں");
            openGoogleSearch(query);
            return;
        }

        if (containsAny(command, "گوگل", "google", "ویب پر", "انٹرنیٹ پر", "تلاش کرو", "سرچ کرو")) {
            String query = cleanQuery(rawCommand,
                    "گوگل پر", "گوگل میں", "گوگل", "google پر", "google",
                    "ویب پر", "انٹرنیٹ پر", "تلاش کرو", "تلاش کریں", "سرچ کرو", "سرچ کریں");
            if (query.isEmpty()) query = rawCommand;
            speak("گوگل پر تلاش کر رہا ہوں");
            openGoogleSearch(query);
            return;
        }

        if (containsAny(command, "راستہ", "لوکیشن", "نقشے میں", "میپس میں", "maps")) {
            String query = cleanQuery(rawCommand,
                    "راستہ دکھاؤ", "لوکیشن دکھاؤ", "نقشے میں", "میپس میں", "maps میں", "تلاش کرو");
            if (query.isEmpty()) {
                openApp("میپس کھول رہا ہوں", "https://maps.google.com",
                        "com.google.android.apps.maps");
            } else {
                speak("میپس میں تلاش کر رہا ہوں");
                openIntent(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=" + Uri.encode(query))));
            }
            return;
        }

        if (containsAny(command, "واٹس ایپ", "whatsapp")) {
            openApp("واٹس ایپ کھول رہا ہوں", "https://www.whatsapp.com",
                    "com.whatsapp.w4b", "com.whatsapp");
            return;
        }
        if (containsAny(command, "انسٹاگرام", "instagram")) {
            openApp("انسٹاگرام کھول رہا ہوں", "https://www.instagram.com",
                    "com.instagram.android");
            return;
        }
        if (containsAny(command, "فیس بک", "facebook")) {
            openApp("فیس بک کھول رہا ہوں", "https://www.facebook.com",
                    "com.facebook.katana", "com.facebook.lite");
            return;
        }
        if (containsAny(command, "میسنجر", "messenger")) {
            openApp("میسنجر کھول رہا ہوں", "https://www.messenger.com",
                    "com.facebook.orca");
            return;
        }
        if (containsAny(command, "ٹک ٹاک", "tiktok")) {
            openApp("ٹک ٹاک کھول رہا ہوں", "https://www.tiktok.com",
                    "com.zhiliaoapp.musically", "com.ss.android.ugc.trill");
            return;
        }
        if (containsAny(command, "یوٹیوب", "youtube")) {
            openApp("یوٹیوب کھول رہا ہوں", "https://www.youtube.com",
                    "com.google.android.youtube");
            return;
        }
        if (containsAny(command, "جی میل", "gmail", "ای میل")) {
            openApp("جی میل کھول رہا ہوں", "https://mail.google.com",
                    "com.google.android.gm");
            return;
        }
        if (containsAny(command, "کروم", "براؤزر", "browser", "chrome")) {
            openApp("براؤزر کھول رہا ہوں", "https://www.google.com",
                    "com.android.chrome", "com.mi.globalbrowser", "com.android.browser");
            return;
        }
        if (containsAny(command, "کیلکولیٹر", "calculator")) {
            if (!openApp("کیلکولیٹر کھول رہا ہوں", null,
                    "com.miui.calculator", "com.google.android.calculator")) {
                openIntent(Intent.makeMainSelectorActivity(
                        Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR));
            }
            return;
        }
        if (containsAny(command, "گیلری", "فوٹوز", "تصاویر", "photos")) {
            openApp("گیلری کھول رہا ہوں", null,
                    "com.miui.gallery", "com.google.android.apps.photos");
            return;
        }
        if (containsAny(command, "کیمرہ", "camera")) {
            speak("کیمرہ کھول رہا ہوں");
            openIntent(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
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
        if (containsAny(command, "کانٹیکٹس کھولو", "رابطے کھولو", "contacts کھولو")) {
            openIntent(new Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI));
            return;
        }

        speak("یہ کمانڈ گوگل پر تلاش کر رہا ہوں");
        openGoogleSearch(rawCommand);
    }

    private boolean isContactLookupCommand(String command) {
        return containsAny(command,
                "فون ڈائریکٹری", "کانٹیکٹ", "کانٹیکٹس", "رابطہ", "رابطوں")
                || (containsAny(command,
                "نمبر نکالو", "نمبر تلاش کرو", "نمبر ڈھونڈو", "نمبر دکھاؤ", "نمبر بتاؤ")
                && extractPhoneNumber(command).isEmpty());
    }

    private boolean ensureContactsPermission(String rawCommand) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) return true;
        pendingContactCommand = rawCommand;
        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_PERMISSIONS);
        statusView.setText("کانٹیکٹس کی اجازت دیں");
        return false;
    }

    private void showContactNumber(String requestedName) {
        ContactMatch match = findContact(requestedName);
        if (match == null) return;
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
        if (match != null) openDialer(match.phoneNumber);
    }

    private void messageContact(String requestedName) {
        ContactMatch match = findContact(requestedName);
        if (match != null) openMessage(match.phoneNumber);
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
        int bestScore = 0;

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
                String name = cursor.getString(nameIndex);
                String number = cursor.getString(numberIndex);
                if (name == null || number == null) continue;
                int score = contactScore(target, normalizeContactKey(name));
                if (score > bestScore) {
                    bestScore = score;
                    best = new ContactMatch(name, number);
                }
            }
        } catch (SecurityException error) {
            speak("کانٹیکٹس کی اجازت درکار ہے");
            return null;
        }

        if (bestScore < 80 || best == null) {
            speak("یہ نام فون ڈائریکٹری میں نہیں ملا");
            return null;
        }
        return best;
    }

    private int contactScore(String target, String candidate) {
        if (candidate.equals(target)) return 100;
        if (candidate.startsWith(target + " ") || target.startsWith(candidate + " ")) return 92;
        if (candidate.contains(" " + target + " ") || target.contains(" " + candidate + " ")) return 86;
        if (candidate.contains(target) || target.contains(candidate)) return 80;
        return 0;
    }

    private String extractContactName(String raw) {
        String cleaned = normalize(raw);
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

    private void openAlarm(String raw) {
        ArrayList<Integer> numbers = extractNumbers(raw);
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
        if (!numbers.isEmpty()) {
            int hour = Math.max(0, Math.min(23, numbers.get(0)));
            int minute = numbers.size() > 1 ? Math.max(0, Math.min(59, numbers.get(1))) : 0;
            intent.putExtra(AlarmClock.EXTRA_HOUR, hour);
            intent.putExtra(AlarmClock.EXTRA_MINUTES, minute);
        }
        intent.putExtra(AlarmClock.EXTRA_MESSAGE, "Chintu Alarm");
        speak("الارم کھول رہا ہوں");
        openIntent(intent);
    }

    private void openTimer(String raw) {
        ArrayList<Integer> numbers = extractNumbers(raw);
        int seconds = numbers.isEmpty() ? 60 : numbers.get(0) * 60;
        if (containsAny(normalize(raw), "سیکنڈ", "second")) {
            seconds = numbers.isEmpty() ? 30 : numbers.get(0);
        }
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER);
        intent.putExtra(AlarmClock.EXTRA_LENGTH, Math.max(1, seconds));
        intent.putExtra(AlarmClock.EXTRA_MESSAGE, "Chintu Timer");
        speak("ٹائمر کھول رہا ہوں");
        openIntent(intent);
    }

    private ArrayList<Integer> extractNumbers(String text) {
        ArrayList<Integer> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d{1,2}").matcher(normalizeDigits(text));
        while (matcher.find() && values.size() < 2) {
            try {
                values.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                // ignore malformed number
            }
        }
        return values;
    }

    private String cleanQuery(String raw, String... phrases) {
        String result = normalize(raw);
        for (String phrase : phrases) result = result.replace(normalize(phrase), " ");
        return result.replaceAll("\\s+", " ").trim();
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[،,۔.!?؛:()\\[\\]{}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(normalize(phrase))) return true;
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
        String arabic = "٠١٢٣٤٥٦٧٨٩";
        String persian = "۰۱۲۳۴۵۶۷۸۹";
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            int a = arabic.indexOf(c);
            int p = persian.indexOf(c);
            if (a >= 0) result.append(a);
            else if (p >= 0) result.append(p);
            else result.append(c);
        }
        return result.toString();
    }

    private void openGoogleSearch(String query) {
        openUri("https://www.google.com/search?q=" + Uri.encode(query));
    }

    private boolean openApp(String spokenText, String fallbackUrl, String... packageNames) {
        speak(spokenText);
        for (String packageName : packageNames) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch != null) {
                openIntent(launch);
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
        } catch (Exception error) {
            statusView.setText("یہ کام فون پر دستیاب نہیں");
            Toast.makeText(this, "مطلوبہ ایپ یا سہولت دستیاب نہیں", Toast.LENGTH_LONG).show();
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
        if (pendingContactCommand != null
                && checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            String command = pendingContactCommand;
            pendingContactCommand = null;
            executeCommand(command);
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
            tts.setSpeechRate(0.92f);
            tts.setPitch(0.9f);
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

    private static class ContactMatch {
        final String displayName;
        final String phoneNumber;

        ContactMatch(String displayName, String phoneNumber) {
            this.displayName = displayName;
            this.phoneNumber = phoneNumber;
        }
    }
}
