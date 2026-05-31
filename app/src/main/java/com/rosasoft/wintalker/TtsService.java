package com.rosasoft.wintalker;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeechService;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Lithuanian TTS service backed by the WinTalker/Gintaras native engine.
 *
 * IMPORTANT — native ABI contract:
 * The native library {@code librosasofttts.so} accesses the INSTANCE FIELDS and
 * native methods of this exact class ({@code com.rosasoft.wintalker.TtsService})
 * by name/signature through JNI. Field names, field types and the four native
 * method signatures below MUST NOT change, otherwise the engine breaks. See
 * {@code proguard-rules.pro} which prevents R8 from renaming them.
 *
 * Reconstructed from the original APK and modernized for current Android
 * (API 21..34). Behavioural differences vs the 2015 original are limited to
 * defensive guards around the (now restricted) telephony APIs.
 */
public class TtsService extends TextToSpeechService {

    private static final int MAXSIZE = 16384;
    private static final int SAMPLING_RATE_HZ = 22050;

    private static boolean Update = false;

    private String COUNTRY;
    private volatile String LANG;
    private String expirytext;
    private volatile String[] mCurrentLanguage;
    volatile String voice = "";
    private volatile byte[] buffer = null;
    private volatile byte[] textbuffer = null;
    private volatile int uptime = 1800;
    private volatile byte[] expiry = null;
    private volatile int buffersize = 0;
    private volatile int lastbuffer = 0;
    private volatile int rate = 0;
    private volatile int pitch = 0;
    private volatile boolean stop = false;
    private volatile String imei = "";
    private volatile String act = "";
    private volatile String number = "";
    private volatile int punc = 0;
    private volatile int numgroup = 16;
    private volatile int pitchrel = 100;
    private volatile int pause_word = 100;
    private volatile int pause_sentence = 100;
    private volatile boolean use_dictionary = true;
    private volatile int numcalls = 0;
    private boolean lua = false;

    // --- Native engine entry points (librosasofttts.so) -------------------
    private native void DeInitJNI();
    private native int InitJNI(byte[] luaBytecode);
    private native int LoadJNI(byte[] data, byte[] name, int kind);
    private native void RunJNI(int chunkIndex);

    static {
        System.loadLibrary("luajit");
        System.loadLibrary("rosasofttts");
        System.loadLibrary("transcr");
    }

    // --- IO helpers --------------------------------------------------------
    private byte[] readFile(String filename) {
        try (FileInputStream inputStream = openFileInput(filename)) {
            int size = inputStream.available();
            byte[] buf = new byte[size];
            inputStream.read(buf);
            return buf;
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] readAsset(String asset) {
        try (InputStream stream = getAssets().open(asset)) {
            int size = stream.available();
            byte[] buf = new byte[size];
            stream.read(buf);
            return buf;
        } catch (IOException e) {
            return null;
        }
    }

    // --- Engine lifecycle --------------------------------------------------
    protected void initLua() {
        byte[] luabuffer = readAsset("main.bin");
        if (luabuffer != null) {
            InitJNI(luabuffer);
            this.lua = true;
        }
    }

    protected void setVoice(String lang) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String tempvoice = prefs.getString(getString(R.string.voice_lang), getString(R.string.default_voice));
        if ((tempvoice != null && !tempvoice.equals(this.voice)) || Update) {
            if (!this.lua) {
                initLua();
            }
            this.voice = tempvoice;

            byte[] dta = readAsset(this.voice + ".dta");
            if (dta != null) {
                LoadJNI(dta, this.voice.getBytes(), 0);
            }
            byte[] std = readFile("stdlit.dct");
            if (std == null) {
                std = readAsset("stdlit.dct");
            }
            int flag = Update ? 8 : 1;
            if (std != null) {
                LoadJNI(std, "stdlit".getBytes(), flag);
            }
            loadAssetInto("spelllit.dct", "spelllit", 2);
            loadAssetInto("ruleslit.rul", "ruleslit", 3);
            loadAssetInto("punc0lit.dct", "punc0lit", 4);
            loadAssetInto("punc1lit.dct", "punc1lit", 5);
            loadAssetInto("punc2lit.dct", "punc2lit", 6);
            loadAssetInto("punc3lit.dct", "punc3lit", 7);
            Update = false;
        }
    }

    private void loadAssetInto(String asset, String name, int kind) {
        byte[] data = readAsset(asset);
        if (data != null) {
            LoadJNI(data, name.getBytes(), kind);
        }
    }

    protected void create() {
        this.buffer = new byte[MAXSIZE];
        if (!this.lua) {
            initLua();
        }
    }

    protected synchronized void destroy() {
        this.textbuffer = "".getBytes();
        if (this.lua) {
            this.lua = false;
            this.voice = "";
            DeInitJNI();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        this.LANG = getString(R.string.lang);
        this.COUNTRY = getString(R.string.country);
        this.mCurrentLanguage = new String[]{this.LANG, this.COUNTRY, ""};
        Resources res = getResources();
        this.uptime = res.getInteger(R.integer.uptime);
        // Licensing dropped: no activation message, no device-bound expiry token.
        this.expirytext = "";
        this.expiry = new byte[0];
        create();
    }

    @Override
    public void onDestroy() {
        destroy();
        super.onDestroy();
    }

    @Override
    protected String[] onGetLanguage() {
        return this.mCurrentLanguage;
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        // 0 == LANG_COUNTRY_AVAILABLE for the single supported language (lit-LTU).
        if (lang != null && lang.toLowerCase().startsWith("lit")) {
            return 0;
        }
        return -2; // LANG_NOT_SUPPORTED
    }

    @Override
    protected synchronized int onLoadLanguage(String lang, String country, String variant) {
        int available = onIsLanguageAvailable(lang, country, variant);
        if (available != -2) {
            String loadCountry = (available == 0) ? this.COUNTRY : country;
            setVoice(lang);
            if (this.mCurrentLanguage == null
                    || !this.mCurrentLanguage[0].equals(lang)
                    || !this.mCurrentLanguage[1].equals(country)) {
                this.mCurrentLanguage = new String[]{lang, loadCountry, ""};
            }
        }
        return available;
    }

    @Override
    protected void onStop() {
        this.stop = true;
    }

    @Override
    protected synchronized void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String text = request.getText();
        if (text == null || text.isEmpty() || !this.lua) {
            callback.done();
            return;
        }
        int load = onLoadLanguage(request.getLanguage(), request.getCountry(), request.getVariant());
        if (load == -2) {
            callback.error();
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        this.rate = request.getSpeechRate();
        this.pitch = request.getPitch();
        this.punc = parseInt(prefs.getString("punctuation", "0"), 0);
        this.numgroup = parseInt(prefs.getString("numgroup", "16"), 16);
        this.pitchrel = parseInt(prefs.getString("pitch", "100"), 100);
        this.pitch = (this.pitch * this.pitchrel) / 100;
        this.pause_word = parseInt(prefs.getString("pause_word", "100"), 100);
        this.pause_sentence = parseInt(prefs.getString("pause_sentence", "100"), 100);
        this.use_dictionary = prefs.getBoolean("use_dictionary", true);

        // Licensing dropped: no IMEI / SIM / activation collection. These ABI
        // fields are kept (the native layer may look them up) but stay empty.
        this.act = "";
        this.imei = "";
        this.number = "";

        this.textbuffer = text.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_16);

        callback.start(SAMPLING_RATE_HZ, 2, 1);
        int maxBufferSize = callback.getMaxBufferSize();
        this.buffersize = 0;
        this.stop = false;
        boolean out = false;
        int count = 0;
        this.lastbuffer = 0;
        do {
            RunJNI(count);
            count++;
            if (this.buffersize == 0) {
                break;
            }
            int offset = 0;
            while (offset < this.buffersize) {
                int bytesToWrite = Math.min(maxBufferSize, this.buffersize - offset);
                out = callback.audioAvailable(this.buffer, offset, bytesToWrite) != 0;
                if (out) {
                    break;
                }
                offset += bytesToWrite;
            }
            if (out || this.stop) {
                break;
            }
        } while (this.lastbuffer != 1);
        callback.done();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String value = intent.getStringExtra("Service.data");
            Update = "UPDATE".equals(value);
        }
        return START_NOT_STICKY;
    }
}
