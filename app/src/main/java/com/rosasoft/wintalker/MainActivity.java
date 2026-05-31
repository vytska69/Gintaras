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
    private int audioPeak;       // max |sample| seen — tells silence vs real audio
    private long audioNonZero;   // count of non-zero samples

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

        MaterialButton exportLog = findViewById(R.id.exportLogButton);
        exportLog.setOnClickListener(v -> exportLog());

        // Bind to THIS engine explicitly so the in-app preview uses our voice.
        status.setText(R.string.tts_loading);
        logLine("Loading engine: " + getPackageName());
        logLine("== BUILD: 2015 libluajit (reverted) + instrumented (GINTDBG) ==");
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
                    audioChunks = 0; audioBytes = 0; audioPeak = 0; audioNonZero = 0;
                    logLine("onStart(" + id + ")");
                }
                @Override public void onDone(String id) {
                    logLine("onDone(" + id + ") chunks=" + audioChunks + " bytes=" + audioBytes
                            + " peak=" + audioPeak + " nonzero=" + audioNonZero
                            + (audioPeak == 0 ? " <- ALL SILENCE" : ""));
                    dumpEngineLog();
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
                    if (audio == null) return;
                    audioBytes += audio.length;
                    // Interpret as little-endian 16-bit PCM; track peak amplitude.
                    for (int i = 0; i + 1 < audio.length; i += 2) {
                        int s = (short) ((audio[i] & 0xff) | (audio[i + 1] << 8));
                        if (s < 0) s = -s;
                        if (s > audioPeak) audioPeak = s;
                        if (s != 0) audioNonZero++;
                    }
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

    /**
     * Dumps this app's own recent logcat and surfaces engine lines. The native
     * librosasofttts logs "Error00/01/02/03 %s" with the Lua error text whenever
     * a lua_pcall fails — that message tells us exactly why synthesis yields
     * silent PCM. Apps can read their own (same-UID) log entries without any
     * special permission.
     */
    private void dumpEngineLog() {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                Process p = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-d", "-v", "brief", "-t", "400"});
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    String l = line.toLowerCase();
                    if (l.contains("gintdbg") || l.contains("error0") || l.contains("rosasoft") || l.contains("ttsservice")
                            || l.contains("luajit") || l.contains("lua") || l.contains("jni")
                            || l.contains("transcr") || l.contains("restrict")
                            || l.contains("execmem") || l.contains("avc:")) {
                        sb.append(line).append('\n');
                        if (sb.length() > 2500) break;
                    }
                }
                r.close();
            } catch (Exception e) {
                sb.append("logcat read failed: ").append(e);
            }
            String out = sb.length() > 0 ? sb.toString() : "(no engine log lines)";
            logLine("--- ENGINE LOGCAT ---\n" + out);
        }).start();
    }

    /**
     * Writes the on-screen diagnostics plus a full logcat dump to the public
     * Downloads folder as a timestamped .txt. Uses MediaStore on Android 10+
     * (no permission needed) and a direct file on older versions.
     */
    private void exportLog() {
        new Thread(() -> {
            String name = "gintaras-log-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                            .format(new java.util.Date()) + ".txt";
            String content = buildExportContent();
            String result;
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                    cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    android.net.Uri uri = getContentResolver().insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    result = "Įrašyta: Download/" + name;
                } else {
                    java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    java.io.File f = new java.io.File(dir, name);
                    try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                        os.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    result = "Įrašyta: " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                result = "Eksportas nepavyko: " + e;
            }
            logLine(result);
        }).start();
    }

    /** Full diagnostics: on-screen log (newest-first reversed to chronological)
     *  followed by a complete logcat dump for this app. */
    private String buildExportContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Gintaras TTS diagnostic log ===\n");
        sb.append("time: ").append(new java.util.Date()).append('\n');
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append(" / Android ")
                .append(android.os.Build.VERSION.RELEASE).append(" (API ")
                .append(android.os.Build.VERSION.SDK_INT).append(")\n\n");
        sb.append("=== ON-SCREEN LOG (chronological) ===\n");
        String[] lines = log.toString().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) sb.append(lines[i]).append('\n');
        sb.append("\n=== FULL LOGCAT (this app) ===\n");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
        } catch (Exception e) {
            sb.append("logcat read failed: ").append(e).append('\n');
        }
        return sb.toString();
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
