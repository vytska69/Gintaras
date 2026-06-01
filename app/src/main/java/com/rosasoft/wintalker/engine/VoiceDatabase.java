package com.rosasoft.wintalker.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java reader for {@code Gintaras.dta} — the voice database of the Lithuanian
 * synthesizer. Recovered byte-for-byte from the original LuaJIT {@code database}
 * module's {@code loaddatabase()} and validated against the real 3.6 MB file: the
 * parse consumes every byte exactly (5,928 sample blocks + 1,221 dictionary
 * entries = 6,627 records, end == file length).
 *
 * This replaces the proprietary native engine's data layer with no JNI / native
 * code, a prerequisite for the arm64-v8a, modern-Android rewrite.
 *
 * <h3>Binary layout (little-endian)</h3>
 * A flat byte stream walked front to back:
 * <pre>
 *   while pos &lt; len:
 *     b = u8                       // tag
 *     if b == 0xFF:                // shared waveform block
 *         idx = u16                // block id
 *         n   = u16                // sample count
 *         samples = n × i16        // 16-bit PCM
 *     else:                        // dictionary entry; b = UTF-16LE name length
 *         name = b bytes (UTF-16LE)
 *         bucket = MAP2.contains(name[0..2])   // first UTF-16 char in {S,B,V,P,N,E,D}
 *         cnt = u8
 *         repeat cnt:
 *             blen = u8
 *             if blen == 0xFF: key = u16        // numeric key
 *             else:            key = blen bytes // string key
 *             if !bucket:  count = u8 + 1; typ = u8 &amp; 0x7F
 * </pre>
 */
public final class VoiceDatabase {

    /** Names whose first UTF-16 char is one of these take the "flat list" path
     *  (no per-record count/typ bytes). Recovered from the module's const table. */
    private static final String MAP2_CHARS = "SBVPNED";

    /** A shared 16-bit PCM waveform block, referenced by dictionary records. */
    public static final class SampleBlock {
        public final int idx;
        public final short[] samples;
        SampleBlock(int idx, short[] samples) {
            this.idx = idx;
            this.samples = samples;
        }
    }

    /** One dictionary record: a key (numeric or string) plus optional count/typ. */
    public static final class Record {
        public final int numKey;       // valid when stringKey == null
        public final byte[] stringKey; // valid when non-null
        public final int count;        // 0 when bucket entry (flat list)
        public final int typ;          // 0 when bucket entry
        Record(int numKey, byte[] stringKey, int count, int typ) {
            this.numKey = numKey;
            this.stringKey = stringKey;
            this.count = count;
            this.typ = typ;
        }
        public boolean isNumeric() { return stringKey == null; }
    }

    /** A dictionary entry: a UTF-16LE name and its records. */
    public static final class Entry {
        public final byte[] name;
        public final boolean bucket;
        public final List<Record> records;
        Entry(byte[] name, boolean bucket, List<Record> records) {
            this.name = name;
            this.bucket = bucket;
            this.records = records;
        }
    }

    public final Map<Integer, SampleBlock> blocks = new HashMap<>();
    public final List<Entry> entries = new ArrayList<>();
    /** Byte offset reached after parsing; equals the file length on a clean parse. */
    public int bytesConsumed;

    private final byte[] d;
    private int pos;

    private VoiceDatabase(byte[] data) {
        this.d = data;
    }

    public static VoiceDatabase parse(byte[] data) {
        VoiceDatabase db = new VoiceDatabase(data);
        db.run();
        return db;
    }

    public static VoiceDatabase parse(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(in.available(), 1 << 20));
        byte[] chunk = new byte[1 << 16];
        int n;
        while ((n = in.read(chunk)) != -1) out.write(chunk, 0, n);
        return parse(out.toByteArray());
    }

    private int u8(int p) { return d[p] & 0xFF; }
    private int u16(int p) { return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8); }
    private short i16(int p) { return (short) u16(p); }

    /**
     * The low byte of each UTF-16LE code unit in an entry name is the cp1257
     * phoneme symbol; returns the decoded unit name (e.g. "-la", "ab").
     */
    public static String unitName(byte[] name) {
        StringBuilder sb = new StringBuilder(name.length / 2);
        for (int i = 0; i + 1 < name.length; i += 2)
            sb.append((char) (name[i] & 0xFF));
        return sb.toString();
    }

    /**
     * Builds a diphone index: unit-name → entry. Entry names whose first code
     * unit's low byte is '-' or that contain only phoneme chars are concatenation
     * units; their numeric records are consecutive sample-block indices = the
     * pitch periods of that diphone.
     */
    private Map<String, Entry> indexCache;

    public Map<String, Entry> diphoneIndex() {
        if (indexCache == null) {
            indexCache = new HashMap<>();
            for (Entry e : entries) indexCache.putIfAbsent(unitName(e.name), e);
        }
        return indexCache;
    }

    /**
     * Concatenate all pitch-period sample blocks referenced by a diphone entry.
     * Records with a STRING key are ALIASES: the key (UTF-16LE) names another unit
     * to use instead (e.g. "-et" → "-at", "-uv" → "-ov"). We resolve aliases via
     * the diphone index so aliased units produce sound instead of silence.
     */
    public short[] unitWaveform(Entry e) {
        return unitWaveform(e, 0);
    }

    private short[] unitWaveform(Entry e, int depth) {
        if (e == null || depth > 4) return new short[0];
        // alias: a single string-key record points at another unit by name
        if (e.records.size() == 1 && !e.records.get(0).isNumeric()) {
            String target = unitName(e.records.get(0).stringKey);
            Map<String, Entry> idx = diphoneIndex();
            Entry t = idx.get(target);
            if (t != null && t != e) return unitWaveform(t, depth + 1);
            return new short[0];
        }
        int total = 0;
        for (Record r : e.records)
            if (r.isNumeric()) {
                SampleBlock b = blocks.get(r.numKey);
                if (b != null) total += b.samples.length;
            }
        short[] out = new short[total];
        int o = 0;
        for (Record r : e.records)
            if (r.isNumeric()) {
                SampleBlock b = blocks.get(r.numKey);
                if (b != null) {
                    System.arraycopy(b.samples, 0, out, o, b.samples.length);
                    o += b.samples.length;
                }
            }
        return out;
    }

    private void run() {
        final int len = d.length;
        pos = 0;
        while (pos < len) {
            int b = u8(pos); pos++;
            if (b == 0xFF) {
                int idx = u16(pos); pos += 2;
                int cnt = u16(pos); pos += 2;
                short[] s = new short[cnt];
                for (int i = 0; i < cnt; i++) { s[i] = i16(pos); pos += 2; }
                blocks.put(idx, new SampleBlock(idx, s));
            } else {
                byte[] name = new byte[b];
                System.arraycopy(d, pos, name, 0, b); pos += b;
                boolean bucket = isBucket(name);
                int cnt = u8(pos); pos++;
                List<Record> recs = new ArrayList<>(cnt);
                for (int i = 0; i < cnt; i++) {
                    int blen = u8(pos); pos++;
                    int numKey = 0;
                    byte[] strKey = null;
                    if (blen == 0xFF) {
                        numKey = u16(pos); pos += 2;
                    } else {
                        strKey = new byte[blen];
                        System.arraycopy(d, pos, strKey, 0, blen); pos += blen;
                    }
                    int count = 0, typ = 0;
                    if (!bucket) {
                        count = u8(pos) + 1; pos++;
                        typ = u8(pos) & 0x7F; pos++;
                    }
                    recs.add(new Record(numKey, strKey, count, typ));
                }
                entries.add(new Entry(name, bucket, recs));
            }
        }
        bytesConsumed = pos;
    }

    /** Bucket if the name's first UTF-16LE char (low byte) is in MAP2 and high byte 0. */
    private static boolean isBucket(byte[] name) {
        if (name.length < 2) return false;
        if (name[1] != 0) return false;
        char c = (char) (name[0] & 0xFF);
        return MAP2_CHARS.indexOf(c) >= 0;
    }
}
