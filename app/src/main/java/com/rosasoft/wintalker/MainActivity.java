package com.rosasoft.wintalker;

import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Locale;

/**
 * Launcher screen — a from-scratch in-app UI to try the Lithuanian voice.
 * Speaks the entered text through this very engine via the Android TTS API, with
 * speed and pitch (timbre) sliders.
 */
public class MainActivity extends AppCompatActivity {

    private static final Locale LT = new Locale("lit", "LTU");

    private TextToSpeech tts;
    private boolean ttsReady = false;

    private TextInputEditText input;
    private Slider rateSlider;
    private Slider pitchSlider;
    private TextView rateLabel;
    private TextView pitchLabel;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        input = findViewById(R.id.inputText);
        rateSlider = findViewById(R.id.rateSlider);
        pitchSlider = findViewById(R.id.pitchSlider);
        rateLabel = findViewById(R.id.rateLabel);
        pitchLabel = findViewById(R.id.pitchLabel);
        status = findViewById(R.id.statusText);

        // Live value labels for the speed / timbre sliders.
        rateLabel.setText(getString(R.string.speech_rate) + ": " + fmt(rateSlider.getValue()));
        pitchLabel.setText(getString(R.string.pitch) + ": " + fmt(pitchSlider.getValue()));
        rateSlider.addOnChangeListener((s, v, u) ->
                rateLabel.setText(getString(R.string.speech_rate) + ": " + fmt(v)));
        pitchSlider.addOnChangeListener((s, v, u) ->
                pitchLabel.setText(getString(R.string.pitch) + ": " + fmt(v)));

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
        tts = new TextToSpeech(this, this::onTtsInit, getPackageName());
    }

    private void onTtsInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(LT);
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA
                    && r != TextToSpeech.LANG_NOT_SUPPORTED;
            status.setText(ttsReady ? R.string.tts_ready : R.string.tts_no_lang);
        } else {
            ttsReady = false;
            status.setText(R.string.tts_failed);
        }
    }

    private void speak() {
        if (!ttsReady || tts == null) {
            status.setText(R.string.tts_not_ready);
            return;
        }
        CharSequence text = input.getText();
        if (TextUtils.isEmpty(text)) return;
        tts.setSpeechRate(rateSlider.getValue());
        tts.setPitch(pitchSlider.getValue());
        tts.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null, "preview");
    }

    /** Format a slider multiplier as e.g. "1.0×". */
    private static String fmt(float v) {
        return String.format(Locale.US, "%.1f×", v);
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
