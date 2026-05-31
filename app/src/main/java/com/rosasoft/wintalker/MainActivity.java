package com.rosasoft.wintalker;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Locale;

/**
 * Launcher screen — a from-scratch in-app UI to try the Lithuanian voice.
 * Speaks the entered text through this very engine via the Android TTS API.
 *
 * Includes an on-screen diagnostic log: because we cannot read logcat on the
 * user's device, the synthesis lifecycle (start / audio bytes / done / error)
 * is surfaced directly in the UI to pinpoint where playback breaks.
 */
public class MainActivity extends AppCompatActivity {

    private static final Locale LT = new Locale("lit", "LTU");

    private TextToSpeech tts;
    private boolean ttsReady = false;

    private TextInputEditText input;
    private Slider rateSlider;
    private TextView status;
    private TextView diag;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private int audioChunks;
    private long audioBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        input = findViewById(R.id.inputText);
        rateSlider = findViewById(R.id.rateSlider);
        status = findViewById(R.id.statusText);
        diag = findViewById(R.id.diagText);

        MaterialButton speak = findViewById(R.id.speakButton);
        MaterialButton stop = findViewById(R.id.stopButton);
        MaterialButton settings = findViewById(R.id.settingsButton);

        speak.setOnClickListener(v -> speak());
        stop.setOnClickListener(v -> {
            if (tts != null) tts.stop();
        });
        settings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Bind to THIS engine explicitly so the in-app preview uses our voice.
        status.setText(R.string.tts_loading);
        logLine("Loading engine: " + getPackageName());
        tts = new TextToSpeech(this, this::onTtsInit, getPackageName());
    }

    private void onTtsInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(LT);
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA
                    && r != TextToSpeech.LANG_NOT_SUPPORTED;
            status.setText(ttsReady ? R.string.tts_ready : R.string.tts_no_lang);
            logLine("Engine init OK, setLanguage(lit-LTU) = " + r
                    + (ttsReady ? " (available)" : " (NOT available)"));
            String engines = "";
            try {
                if (tts.getDefaultEngine() != null) engines = tts.getDefaultEngine();
            } catch (Exception ignored) {
            }
            logLine("Default engine: " + engines);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    audioChunks = 0; audioBytes = 0;
                    logLine("onStart(" + id + ")");
                }
                @Override public void onDone(String id) {
                    logLine("onDone(" + id + ") chunks=" + audioChunks + " bytes=" + audioBytes);
                }
                @Override public void onError(String id) {
                    logLine("onError(" + id + ")");
                }
                @Override public void onError(String id, int code) {
                    logLine("onError(" + id + ") code=" + code);
                }
                @Override
                public void onAudioAvailable(String id, byte[] audio) {
                    audioChunks++;
                    audioBytes += (audio == null ? 0 : audio.length);
                }
                @Override
                public void onBeginSynthesis(String id, int sampleRateInHz,
                                             int audioFormat, int channelCount) {
                    logLine("onBeginSynthesis rate=" + sampleRateInHz
                            + " fmt=" + audioFormat + " ch=" + channelCount);
                }
            });
        } else {
            ttsReady = false;
            status.setText(R.string.tts_failed);
            logLine("Engine init FAILED, status=" + statusCode);
        }
    }

    private void speak() {
        if (!ttsReady || tts == null) {
            status.setText(R.string.tts_not_ready);
            logLine("speak() ignored: engine not ready");
            return;
        }
        CharSequence text = input.getText();
        if (TextUtils.isEmpty(text)) return;
        tts.setSpeechRate(rateSlider.getValue());
        int r = tts.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null, "preview");
        logLine("speak(\"" + text + "\") returned " + r
                + (r == TextToSpeech.SUCCESS ? " (queued)" : " (FAILED)"));
    }

    private void logLine(String line) {
        ui.post(() -> {
            log.insert(0, line + "\n");
            if (log.length() > 4000) log.setLength(4000);
            if (diag != null) diag.setText(log.toString());
        });
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
