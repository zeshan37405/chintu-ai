package com.zeshan.chintuai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StableChintuActivity extends Activity implements RecognitionListener, TextToSpeech.OnInitListener {
    private static final int REQ_MIC = 5001;
    private static final int REQ_CONTACTS = 5002;
    private static final int REQ_SYSTEM_SPEECH = 5003;
    private static final long START_TIMEOUT_MS = 5000L;
    private static final long LISTEN_TIMEOUT_MS = 14000L;
    private static final long RESULT_TIMEOUT_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private TextView status;
    private TextView heard;
    private EditText input;
    private Button mic;
    private String partial = "";
    private boolean listening;
    private boolean waitingResult;
    private int directAttempts;
    private String pendingContactCommand;

    private final Runnable startTimeout = () -> {
        if (listening && partial.isEmpty()) failDirectRecognition("آواز شناخت شروع نہیں ہوئی");
    };
    private final Runnable listenTimeout = () -> {
        if (!listening) return;
        if (!partial.isEmpty()) finishSpeech(partial);
        else failDirectRecognition("آواز نہیں ملی");
    };
    private final Runnable resultTimeout = () -> {
        if (!waitingResult) return;
        if (!partial.isEmpty()) finishSpeech(partial);
        else failDirectRecognition("نتیجہ نہیں ملا");
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7, 17, 30));
        getWindow().setNavigationBarColor(Color.rgb(7, 17, 30));
        tts = new TextToSpeech(this, this);
        setContentView(buildUi());
        setStatus("تیار ہوں", "حکم بولیں یا لکھیں");
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(40), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(7, 17, 30));

        TextView title = new TextView(this);
        title.setText("Chintu 2.1");
        title.setTextSize(34);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, mw());

        status = new TextView(this);
        status.setTextSize(20);
        status.setTextColor(Color.rgb(84, 211, 151));
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = mw(); sp.topMargin = dp(28);
        root.addView(status, sp);

        heard = new TextView(this);
        heard.setTextSize(16);
        heard.setTextColor(Color.LTGRAY);
        heard.setGravity(Gravity.CENTER);
        heard.setMinHeight(dp(60));
        root.addView(heard, mw());

        mic = new Button(this);
        mic.setAllCaps(false);
        mic.setText("🎙️ حکم بولیں");
        mic.setTextSize(21);
        mic.setOnClickListener(v -> { if (listening || waitingResult) stopVoice(); else startVoice(); });
        LinearLayout.LayoutParams mp = mw(); mp.height = dp(68); mp.topMargin = dp(14);
        root.addView(mic, mp);

        input = new EditText(this);
        input.setHint("مثلاً: ہوم کو کال کرو");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setTextSize(17);
        input.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams ip = mw(); ip.topMargin = dp(18);
        root.addView(input, ip);

        Button run = new Button(this);
        run.setAllCaps(false);
        run.setText("کمانڈ چلائیں");
        run.setOnClickListener(v -> execute(input.getText().toString()));
        LinearLayout.LayoutParams rp = mw(); rp.height = dp(54); rp.topMargin = dp(10);
        root.addView(run, rp);

        TextView tips = new TextView(this);
        tips.setText("موسم تلاش کرو • ہوم کا نمبر نکالو • ہوم کو کال کرو • یوٹیوب کھولو");
        tips.setTextColor(Color.GRAY);
        tips.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = mw(); tp.topMargin = dp(24);
        root.addView(tips, tp);
        return root;
    }

    private void startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (tts != null) tts.stop();
        destroyRecognizer();
        directAttempts = 0;
        beginDirect();
    }

    private void beginDirect() {
        clearTimers();
        partial = "";
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { launchSystemSpeech(); return; }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3200L);
            listening = true;
            waitingResult = false;
            mic.setText("🎙️ سن رہا ہوں...");
            setStatus("سن رہا ہوں", "واضح آواز میں بولیں");
            recognizer.startListening(i);
            handler.postDelayed(startTimeout, START_TIMEOUT_MS);
            handler.postDelayed(listenTimeout, LISTEN_TIMEOUT_MS);
        } catch (RuntimeException e) { failDirectRecognition("Recognizer error"); }
    }

    private void failDirectRecognition(String reason) {
        clearTimers();
        destroyRecognizer();
        listening = false;
        waitingResult = false;
        if (directAttempts < 1) {
            directAttempts++;
            setStatus("دوبارہ کوشش", "اب بولیں");
            handler.postDelayed(this::beginDirect, 450L);
        } else {
            setStatus("متبادل وائس شناخت", reason);
            launchSystemSpeech();
        }
    }

    private void launchSystemSpeech() {
        clearTimers();
        destroyRecognizer();
        listening = false;
        waitingResult = false;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "چنٹو کو حکم بولیں");
        try {
            mic.setText("🎙️ سن رہا ہوں...");
            startActivityForResult(i, REQ_SYSTEM_SPEECH);
        } catch (ActivityNotFoundException e) {
            resetVoiceUi();
            setStatus("آواز شناخت دستیاب نہیں", "کمانڈ لکھ کر چلائیں");
        }
    }

    private void finishSpeech(String text) {
        clearTimers();
        destroyRecognizer();
        listening = false;
        waitingResult = false;
        resetVoiceUi();
        String command = text == null ? "" : text.trim();
        if (command.isEmpty()) { setStatus("آواز واضح نہیں ملی", "دوبارہ کوشش کریں"); return; }
        input.setText(command);
        execute(command);
    }

    private void stopVoice() {
        clearTimers();
        destroyRecognizer();
        listening = false;
        waitingResult = false;
        resetVoiceUi();
        setStatus("رک گیا", "");
    }

    private void destroyRecognizer() {
        if (recognizer == null) return;
        try { recognizer.cancel(); } catch (RuntimeException ignored) { }
        try { recognizer.destroy(); } catch (RuntimeException ignored) { }
        recognizer = null;
    }

    private void clearTimers() {
        handler.removeCallbacks(startTimeout);
        handler.removeCallbacks(listenTimeout);
        handler.removeCallbacks(resultTimeout);
    }

    private void resetVoiceUi() { if (mic != null) mic.setText("🎙️ حکم بولیں"); }
    private void setStatus(String s, String d) { status.setText(s); heard.setText(d == null ? "" : d); }

    @Override public void onReadyForSpeech(Bundle params) { handler.removeCallbacks(startTimeout); setStatus("سن رہا ہوں", "اب بولیں"); }
    @Override public void onBeginningOfSpeech() { handler.removeCallbacks(startTimeout); setStatus("آواز مل گئی", partial); }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { listening = false; waitingResult = true; handler.removeCallbacks(listenTimeout); handler.postDelayed(resultTimeout, RESULT_TIMEOUT_MS); setStatus("سمجھ رہا ہوں", partial); }
    @Override public void onError(int error) {
        if (!partial.isEmpty() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_CLIENT)) { finishSpeech(partial); return; }
        failDirectRecognition("آواز شناخت کا مسئلہ " + error);
    }
    @Override public void onResults(Bundle results) {
        ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        finishSpeech(r == null || r.isEmpty() ? partial : choose(r));
    }
    @Override public void onPartialResults(Bundle results) {
        ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (r != null && !r.isEmpty()) { partial = choose(r); heard.setText(partial); }
    }
    @Override public void onEvent(int eventType, Bundle params) { }

    private String choose(ArrayList<String> values) {
        String best = values.get(0);
        int score = commandScore(best);
        for (String v : values) { int s = commandScore(v); if (s > score) { best = v; score = s; } }
        return best;
    }

    private int commandScore(String value) {
        String s = norm(value);
        int score = Math.min(30, s.length());
        if (has(s, "گوگل", "موسم", "تلاش", "سرچ")) score += 30;
        if (has(s, "کال", "نمبر", "کانٹیکٹ", "ہوم")) score += 30;
        if (has(s, "کھولو", "یوٹیوب", "واٹس ایپ", "انسٹاگرام", "میپس")) score += 20;
        return score;
    }

    private void execute(String raw) {
        String command = raw == null ? "" : raw.trim();
        if (command.isEmpty()) { setStatus("کمانڈ موجود نہیں", ""); return; }
        String n = norm(command);
        setStatus("کمانڈ موصول ہوئی", command);

        if (has(n, "بینک", "ایزی پیسہ", "جاز کیش", "رقم ٹرانسفر", "payment", "transfer money")) { speak("مالی لین دین کی کمانڈ بند ہے"); return; }
        if (has(n, "نمبر نکالو", "نمبر تلاش", "نمبر دکھاؤ", "فون ڈائریکٹری", "کانٹیکٹ")) { contactAction(command, 0); return; }
        if (has(n, "کال کرو", "فون کرو", "کال لگاؤ", "call", "dial")) { contactAction(command, 1); return; }
        if (has(n, "میسج کرو", "پیغام بھیجو", "sms", "message")) { contactAction(command, 2); return; }
        if (has(n, "موسم", "weather", "درجہ حرارت")) { googleSearch(cleanSearch(command).isEmpty() ? "حسن ابدال موسم آج" : cleanSearch(command) + " موسم"); return; }
        if (has(n, "گوگل", "تلاش", "سرچ", "search")) { String q = cleanSearch(command); googleSearch(q.isEmpty() ? command : q); return; }
        if (has(n, "یوٹیوب") && has(n, "تلاش", "سرچ", "پر")) { openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(cleanAppWords(command, "یوٹیوب", "تلاش", "سرچ", "کرو", "پر"))); return; }
        if (has(n, "یوٹیوب")) { openApp("com.google.android.youtube", "https://www.youtube.com"); return; }
        if (has(n, "واٹس ایپ بزنس")) { openApp("com.whatsapp.w4b", "https://wa.me/"); return; }
        if (has(n, "واٹس ایپ")) { openApp("com.whatsapp", "https://wa.me/"); return; }
        if (has(n, "انسٹاگرام")) { openApp("com.instagram.android", "https://www.instagram.com"); return; }
        if (has(n, "فیس بک")) { openApp("com.facebook.katana", "https://www.facebook.com"); return; }
        if (has(n, "میپس", "نقشہ", "راستہ", "لوکیشن")) { openApp("com.google.android.apps.maps", "https://maps.google.com"); return; }
        if (has(n, "کیمرہ")) { safeStart(new Intent(MediaStore.ACTION_IMAGE_CAPTURE)); return; }
        if (has(n, "وائی فائی")) { safeStart(new Intent(Settings.ACTION_WIFI_SETTINGS)); return; }
        if (has(n, "بلوٹوتھ")) { safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return; }
        if (has(n, "سیٹنگ")) { safeStart(new Intent(Settings.ACTION_SETTINGS)); return; }
        if (has(n, "کھولو", "اوپن", "open")) { String app = cleanAppWords(command, "کھولو", "اوپن", "open", "کرو"); if (openByLabel(app)) return; }
        googleSearch(command);
    }

    private void contactAction(String raw, int action) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingContactCommand = raw + "\n" + action;
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
            return;
        }
        String name = extractContactName(raw);
        List<Contact> matches = findContacts(name);
        if (matches.isEmpty()) { speak("یہ نام فون ڈائریکٹری میں نہیں ملا"); return; }
        if (matches.size() > 1 && matches.get(0).score - matches.get(1).score < 10) {
            String[] labels = new String[Math.min(5, matches.size())];
            for (int i = 0; i < labels.length; i++) labels[i] = matches.get(i).name + "\n" + matches.get(i).number;
            new AlertDialog.Builder(this).setTitle("کانٹیکٹ منتخب کریں").setItems(labels, (d, which) -> runContactAction(matches.get(which), action)).show();
        } else runContactAction(matches.get(0), action);
    }

    private void runContactAction(Contact c, int action) {
        if (action == 1) safeStart(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", c.number, null)));
        else if (action == 2) safeStart(new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", c.number, null)));
        else { setStatus("نمبر مل گیا", c.name + "\n" + c.number); speak(c.name + " کا نمبر مل گیا"); }
    }

    private List<Contact> findContacts(String requested) {
        String target = contactNorm(requested);
        Map<String, Contact> best = new LinkedHashMap<>();
        String[] p = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, p, null, null, null)) {
            if (c == null) return new ArrayList<>();
            int ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            while (c.moveToNext()) {
                String name = c.getString(ni), number = c.getString(pi);
                if (name == null || number == null) continue;
                int score = similarity(target, contactNorm(name));
                if (score < 60) continue;
                String key = number.replaceAll("\\D", "");
                Contact old = best.get(key);
                if (old == null || score > old.score) best.put(key, new Contact(name, number, score));
            }
        } catch (RuntimeException e) { Toast.makeText(this, "کانٹیکٹس پڑھنے میں مسئلہ", Toast.LENGTH_SHORT).show(); }
        ArrayList<Contact> out = new ArrayList<>(best.values());
        out.sort((a,b) -> Integer.compare(b.score, a.score));
        return out;
    }

    private int similarity(String a, String b) {
        if (a.equals(b)) return 100;
        if ((" " + b + " ").contains(" " + a + " ")) return 95;
        if (b.contains(a) || a.contains(b)) return 85;
        for (String x : a.split(" ")) for (String y : b.split(" ")) if (x.equals(y)) return 80;
        return 0;
    }

    private String extractContactName(String raw) {
        return cleanAppWords(norm(raw), "میری", "میرے", "فون", "ڈائریکٹری", "میں", "سے", "کا", "کی", "کے", "نمبر", "نکالو", "تلاش", "کرو", "ڈھونڈو", "دکھاؤ", "بتاؤ", "کو", "کال", "لگاؤ", "پیغام", "میسج", "بھیجو", "کانٹیکٹ", "رابطہ", "نام", "والا", "والی", "call", "dial", "message", "sms");
    }

    private void googleSearch(String q) { openUrl("https://www.google.com/search?q=" + Uri.encode(q)); }
    private void openUrl(String u) { safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
    private void openApp(String pkg, String fallback) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) { safeStart(launch); return; }
        openUrl(fallback);
    }
    private boolean openByLabel(String wanted) {
        String target = norm(wanted);
        Intent main = new Intent(Intent.ACTION_MAIN, null); main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(main, 0)) {
            String label = norm(String.valueOf(r.loadLabel(getPackageManager())));
            if (label.equals(target) || label.contains(target) || target.contains(label)) {
                Intent i = getPackageManager().getLaunchIntentForPackage(r.activityInfo.packageName);
                if (i != null) { safeStart(i); return true; }
            }
        }
        return false;
    }
    private void safeStart(Intent i) {
        try { startActivity(i); }
        catch (ActivityNotFoundException | SecurityException e) { setStatus("ایپ نہیں کھلی", "متبادل راستہ دستیاب نہیں"); }
        catch (RuntimeException e) { setStatus("کمانڈ مکمل نہیں ہوئی", "دوبارہ کوشش کریں"); }
    }

    private String cleanSearch(String s) { return cleanAppWords(s, "گوگل", "گوجل", "google", "پر", "ویب", "انٹرنیٹ", "تلاش", "سرچ", "search", "کرو", "کریں", "کا", "حال", "بتاؤ", "دکھاؤ"); }
    private String cleanAppWords(String s, String... remove) {
        String out = norm(s);
        for (String x : remove) out = out.replace(norm(x), " ");
        return out.replaceAll("\\s+", " ").trim();
    }
    private String contactNorm(String s) { return norm(s).replace("ہوم", "home").replace("هوم", "home").replace("ھوم", "home").replace("ہم", "home").replace("گھر", "home"); }
    private String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ي','ی').replace('ك','ک').replaceAll("[،۔!?]", " ").replaceAll("\\s+", " ").trim(); }
    private boolean has(String s, String... keys) { for (String k : keys) if (s.contains(norm(k))) return true; return false; }
    private void speak(String text) { setStatus("مکمل", text); if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chintu"); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SYSTEM_SPEECH) return;
        resetVoiceUi();
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) { finishSpeech(choose(r)); return; }
        }
        setStatus("آواز واضح نہیں ملی", "کمانڈ لکھ کر چلائیں");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startVoice();
        if (requestCode == REQ_CONTACTS && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && pendingContactCommand != null) {
            String[] p = pendingContactCommand.split("\\n");
            pendingContactCommand = null;
            contactAction(p[0], p.length > 1 ? Integer.parseInt(p[1]) : 0);
        }
    }

    @Override public void onInit(int status) { if (tts != null) tts.setLanguage(new Locale("ur", "PK")); }
    @Override protected void onPause() { super.onPause(); stopVoice(); }
    @Override protected void onDestroy() { clearTimers(); destroyRecognizer(); if (tts != null) { tts.stop(); tts.shutdown(); } super.onDestroy(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams mw() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private static class Contact { final String name, number; final int score; Contact(String n, String p, int s) { name=n; number=p; score=s; } }
}
