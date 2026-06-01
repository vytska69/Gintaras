package com.rosasoft.wintalker;

import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeechService;

import com.rosasoft.wintalker.engine.DiphoneSynth;
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
        } catch (IOException e) {
            voiceDb = null;
            synth = null;
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

        // Synthesize each whitespace-separated word, with a short pause between.
        short[] pause = new short[(int) (0.18 * SAMPLE_RATE)];
        for (String word : text.split("\\s+")) {
            if (stop) break;
            if (word.isEmpty()) continue;
            int[] w = Transcriber.normalise(word);
            if (w.length == 0) continue;
            List<String> phonemes = Transcriber.transcribe(w, w.length);
            short[] pcm = synth.synthesize(phonemes.toArray(new String[0]));
            if (!writePcm(callback, pcm)) break;
            writePcm(callback, pause);
        }
        callback.done();
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
