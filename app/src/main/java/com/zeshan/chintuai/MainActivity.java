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
        subtitle.setText("اپنا حکم بولیں");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(20);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(12);
        root.addView(subtitle, subtitleParams);

        statusView = new TextView(this);
        statusView.setText("تیار");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(40);
        root.addView(statusView, statusParams);

        Button listenButton = new Button(this);
        listenButton.setText("بولیں");
        listenButton.setTextSize(20);
        listenButton.setOnClickListener(v -> startListening());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64));
        buttonParams.topMargin = dp(28);
        root.addView(listenButton, buttonParams);

        TextView examples = new TextView(this);
        examples.setText("مثال: یوٹیوب کھولو، واٹس ایپ کھولو، کیمرہ کھولو");
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
                String command = results.get(0);
                statusView.setText(command);
                handleCommand(command);
                return;
            }
        }

        statusView.setText("دوبارہ کوشش کریں");
    }

    private void handleCommand(String rawCommand) {
        String command = rawCommand.toLowerCase(Locale.ROOT);

        if (command.contains("یوٹیوب") || command.contains("youtube")) {
            speak("یوٹیوب کھول رہا ہوں");
            openPackage("com.google.android.youtube", "https://www.youtube.com");
            return;
        }

        if (command.contains("واٹس ایپ") || command.contains("whatsapp")) {
            speak("واٹس ایپ کھول رہا ہوں");
            if (!openPackage("com.whatsapp.w4b", null)) {
                openPackage("com.whatsapp", "https://www.whatsapp.com");
            }
            return;
        }

        if (command.contains("کیمرہ") || command.contains("camera")) {
            speak("کیمرہ کھول رہا ہوں");
            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            try {
                startActivity(cameraIntent);
            } catch (ActivityNotFoundException error) {
                statusView.setText("کیمرہ نہیں ملا");
            }
            return;
        }

        speak("یہ حکم ابھی شامل نہیں ہے");
        statusView.setText("نامعلوم حکم: " + rawCommand);
    }

    private boolean openPackage(String packageName, String fallbackUrl) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            startActivity(launchIntent);
            return true;
        }

        if (fallbackUrl != null) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)));
            return true;
        }

        return false;
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
