package com.rosasoft.wintalker;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeechService;

import com.rosasoft.wintalker.engine.DiphoneSynth;
import com.rosasoft.wintalker.engine.NumberExpander;
import com.rosasoft.wintalker.engine.TextNormalizer;
import com.rosasoft.wintalker.engine.Transcriber;
import com.rosasoft.wintalker.engine.VoiceDatabase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java Lithuanian TTS engine. Replaces the legacy native pipeline
 * (librosasofttts.so + libluajit.so + main.bin, armeabi-v7a only) with a
 * from-scratch implementation that runs on any ABI including arm64-v8a:
 *
 *   text → Transcriber (grapheme→phoneme, 100% vs the original over 515 words)
 *        → DiphoneSynth (diphone concatenation from Gintaras.dta) → 16-bit PCM
 *
 * No JNI, no native libraries, no LuaJIT. Only the voice DATA (Gintaras.dta) is
 * reused.
 */
public class TtsService extends TextToSpeechService {

    private static final int SAMPLE_RATE = DiphoneSynth.SAMPLE_RATE; // 22050
    private static final String LANG = "lit", COUNTRY = "LTU";

    private VoiceDatabase voiceDb;
    private DiphoneSynth synth;
    private TextNormalizer normalizer;
    private volatile boolean stop;
    private String[] currentLanguage = {LANG, COUNTRY, ""};

    @Override
    public void onCreate() {
        super.onCreate();
        loadVoice();
    }

    private synchronized void loadVoice() {
        if (synth != null) return;
        try (InputStream in = getAssets().open("Gintaras.dta")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 22);
            byte[] chunk = new byte[1 << 16];
            int r;
            while ((r = in.read(chunk)) != -1) out.write(chunk, 0, r);
            voiceDb = VoiceDatabase.parse(out.toByteArray());
            synth = new DiphoneSynth(voiceDb);
            normalizer = loadNormalizer(voiceDb);
        } catch (IOException e) {
            voiceDb = null;
            synth = null;
            normalizer = null;
        }
    }

    /** Build the text-normalization layer from the bundled dictionary assets. The
     *  punc tables are indexed by level 0..3 (punc0lit.dct .. punc3lit.dct); a
     *  missing asset degrades gracefully (feature skipped). */
    private TextNormalizer loadNormalizer(VoiceDatabase db) throws IOException {
        InputStream[] punc = new InputStream[4];
        for (int i = 0; i < 4; i++) punc[i] = openAsset("punc" + i + "lit.dct");
        // create() reads each stream fully (it slurps the bytes), so the asset
        // streams are consumed here and need no further use.
        return TextNormalizer.create(db,
                openAsset("ruleslit.rul"),
                openAsset("stdlit.dct"),
                openAsset("spelllit.dct"),
                punc);
    }

    /** Open an asset, returning null when it is absent so optional dictionaries
     *  do not break loading. */
    private InputStream openAsset(String name) {
        try {
            return getAssets().open(name);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected String[] onGetLanguage() {
        return currentLanguage;
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (lang != null && lang.toLowerCase(Locale.ROOT).startsWith("lit")) {
            return 0; // LANG_COUNTRY_AVAILABLE
        }
        return -2; // LANG_NOT_SUPPORTED
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        int available = onIsLanguageAvailable(lang, country, variant);
        if (available != -2) currentLanguage = new String[]{LANG, COUNTRY, ""};
        return available;
    }

    @Override
    protected void onStop() {
        stop = true;
    }

    @Override
    protected synchronized void onSynthesizeText(SynthesisRequest request,
                                                 SynthesisCallback callback) {
        if (synth == null) loadVoice();
        String text = request.getCharSequenceText() != null
                ? request.getCharSequenceText().toString() : request.getText();
        if (text == null || text.isEmpty() || synth == null) {
            callback.done();
            return;
        }
        stop = false;
        callback.start(SAMPLE_RATE, android.media.AudioFormat.ENCODING_PCM_16BIT, 1);

        // Speech rate (speed) and pitch (timbre) as Android passes them: percentages
        // where 100 = normal (set by the user in TTS/accessibility settings, TalkBack
        // or the in-app slider). The engine applies them per the original (root.53/48):
        // tempo = 0.62*pitch/rate, base period = round(220*100/pitch).
        int rate = request.getSpeechRate() > 0 ? request.getSpeechRate() : 100;
        int sysPitch = request.getPitch() > 0 ? request.getPitch() : 100;
        // Combine the system/per-request pitch with the app's "Tembras" setting
        // (default 100 = no change), so the in-app/Settings timbre control works too.
        int pitch = sysPitch * appPitchPref() / 100;

        // Text-normalization ("reading") layer, ported from voicesynth root.53:
        // transliterate (ruleslit), tokenize keeping punctuation, then per token
        // apply the std dictionary, number expansion (numgroup) and the selected
        // punctuation table / spell path. The settings drive behaviour.
        TextNormalizer.Settings st = readSettings();
        List<TextNormalizer.Token> tokens = (normalizer != null)
                ? normalizer.normalize(text, st)
                : fallbackTokens(text);

        // Pause model (voicesynth root.53 + root.50): a small inter-word/clause gap
        // and a longer one at sentence end (. ! ? ; :). Both scale with the user's
        // "Pauzė tarp žodžių" / "Pauzė tarp sakinių" settings (percent, 100=normal:
        // Trumpa/Įprasta/Ilga) and with the speech rate (faster speech = shorter
        // pauses). The per-word trailing pad is NOT added by the synth anymore.
        SharedPreferences pp = PreferenceManager.getDefaultSharedPreferences(this);
        int pauseWord = parseIntPref(pp, "pause_word", 100);
        int pauseSent = parseIntPref(pp, "pause_sentence", 100);
        double rScale = 100.0 / rate;
        short[] wordPause = new short[(int) (0.02 * SAMPLE_RATE * (pauseWord / 100.0) * rScale)];
        short[] sentPause = new short[(int) (0.30 * SAMPLE_RATE * (pauseSent / 100.0) * rScale)];
        // Spell tokens (letters) get a small extra gap between them, scaled likewise.
        short[] spellPause = new short[(int) (0.10 * SAMPLE_RATE * (pauseWord / 100.0) * rScale)];
        for (int wi = 0; wi < tokens.size(); wi++) {
            if (stop) break;
            TextNormalizer.Token tk = tokens.get(wi);
            boolean isLast = wi == tokens.size() - 1;
            // A pure punctuation token has no spoken word but still drives the
            // pause: sentence-final marks (. ! ? ; :) → long pause, else tiny gap.
            char pc = tk.punctuation;
            boolean sentenceEnd = pc != 0 && ".!?;:".indexOf(pc) >= 0;
            if (tk.text == null || tk.text.isEmpty()) {
                if (pc != 0 && !writePcm(callback, sentenceEnd ? sentPause : wordPause)) break;
                continue;
            }
            int[] w = Transcriber.normalise(tk.text);
            if (w.length == 0) {
                if (pc != 0 && !writePcm(callback, sentenceEnd ? sentPause : wordPause)) break;
                continue;
            }
            List<String> phonemes = Transcriber.transcribe(w, w.length);
            short[] pcm = synth.synthesize(phonemes.toArray(new String[0]), rate, pitch);
            if (!writePcm(callback, pcm)) break;
            short[] gap = sentenceEnd || isLast ? sentPause : (tk.spell ? spellPause : wordPause);
            if (!writePcm(callback, gap)) break;
        }
        callback.done();
    }

    /** Read the SharedPreferences that affect reading. Keys match root_preferences
     *  / arrays.xml: punctuation (puncValues → punc file index), numgroup
     *  (numValues; 16 = full cardinal), use_dictionary. */
    private TextNormalizer.Settings readSettings() {
        TextNormalizer.Settings st = new TextNormalizer.Settings();
        try {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
            // Default "Išjungta" (=1): punctuation is NOT spoken aloud, it only
            // drives pauses (arrays.xml puncValues: Išjungta=1, Kai kurie=2,
            // Dauguma=0, Visi=3). Reading mark names is opt-in via Settings.
            st.punctuationLevel = parseIntPref(p, "punctuation", 1);
            st.numgroup = parseIntPref(p, "numgroup", NumberExpander.NUMGROUP_FULL);
            st.useDictionary = p.getBoolean("use_dictionary", true);
        } catch (Exception e) {
            // keep defaults
        }
        return st;
    }

    /** The app's "Tembras" (pitch) setting as a percentage (default 100 = normal). */
    private int appPitchPref() {
        try {
            return parseIntPref(PreferenceManager.getDefaultSharedPreferences(this), "pitch", 100);
        } catch (Exception e) {
            return 100;
        }
    }

    /** ListPreference values are stored as strings; parse to int with a default. */
    private static int parseIntPref(SharedPreferences p, String key, int def) {
        String v = p.getString(key, null);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Minimal tokenization if the normalizer failed to load (no dictionaries):
     *  whitespace split, no transliteration / punctuation tables. */
    private List<TextNormalizer.Token> fallbackTokens(String text) {
        java.util.List<TextNormalizer.Token> out = new java.util.ArrayList<>();
        for (String w : text.split("\\s+")) {
            if (w.isEmpty()) continue;
            char last = w.charAt(w.length() - 1);
            out.add(new TextNormalizer.Token(w, ".!?;:".indexOf(last) >= 0 ? last : (char) 0, false));
        }
        return out;
    }

    /** Write 16-bit PCM to the callback in maxBufferSize chunks (little-endian).
     *  Returns false if synthesis was stopped or the sink reported an error. */
    private boolean writePcm(SynthesisCallback callback, short[] pcm) {
        if (pcm.length == 0) return true;
        int maxBytes = Math.max(2, callback.getMaxBufferSize());
        byte[] buf = new byte[Math.min(maxBytes, pcm.length * 2)];
        int i = 0;
        while (i < pcm.length) {
            if (stop) return false;
            int n = Math.min(pcm.length - i, buf.length / 2);
            for (int j = 0; j < n; j++) {
                short s = pcm[i + j];
                buf[j * 2] = (byte) (s & 0xff);
                buf[j * 2 + 1] = (byte) ((s >> 8) & 0xff);
            }
            if (callback.audioAvailable(buf, 0, n * 2)
                    != android.speech.tts.TextToSpeech.SUCCESS) {
                return false;
            }
            i += n;
        }
        return true;
    }
}
