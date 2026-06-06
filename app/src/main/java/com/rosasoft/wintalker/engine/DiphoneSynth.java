package com.rosasoft.wintalker.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Diphone-concatenation synthesiser. Turns a phoneme sequence (from the
 * transcriber) into 16-bit PCM by selecting diphone units from the voice database
 * and concatenating their pitch-period sample blocks.
 *
 * This is the first audible stage (plain concatenation, no pitch modification).
 * PSOLA pitch/duration control is layered on afterwards as the quality pass.
 *
 * Diphone keys: the engine forms boundary-aware overlapping units from the phoneme
 * stream. For "_ l aA b aA s _" the units are roughly "-l", "la", "ab", "ba",
 * "as", "s-". We try each candidate key against the index and fall back to
 * progressively simpler keys so unknown contexts still produce sound.
 */
public final class DiphoneSynth {

    public static final int SAMPLE_RATE = 22050;

    /** Engine base pitch period (prosody table P0[5] = 220 samples ≈ 100 Hz). The
     *  recorded voiced periods sit a little shorter (higher) than this; proto7
     *  steps them toward this base. */
    private static final int BASE_PERIOD = 220;

    /** P0.Prosody = P0[1]+P0[2] = 20 (voicesynth loadvoice root.43). */
    private static final int PROSODY = 20;
    /** P1.ProsodyChange = {0,50,10,20,50,160}: root.47 slews toward PC[6]=160 over
     *  the first half of a word, then PC[3]=10 over the second. */
    private static final int PC_EARLY = 160;   // PC[6]
    private static final int PC_LATE = 10;     // PC[3]

    private final VoiceDatabase db;
    private final Map<String, VoiceDatabase.Entry> index;
    private CandidateSequencer sequencer;

    public DiphoneSynth(VoiceDatabase db) {
        this.db = db;
        this.index = db.diphoneIndex();
    }

    /**
     * proto7 DSP, simplified: steady the pitch of voiced periods by linearly
     * interpolating each voiced period to the unit's mean voiced-period length;
     * leave unvoiced (consonant/noise) periods exactly as recorded. Returns plain
     * waveforms ready for concatenation.
     */
    private static List<short[]> smoothVoiced(List<VoiceDatabase.Period> ps) {
        List<short[]> out = new ArrayList<>(ps.size());
        // mean length of voiced periods in this unit
        long sum = 0; int nv = 0;
        for (VoiceDatabase.Period p : ps) if (p.voiced) { sum += p.samples.length; nv++; }
        int target = nv > 0 ? (int) (sum / nv) : 0;
        for (VoiceDatabase.Period p : ps) {
            if (p.voiced && target > 0 && p.samples.length != target) {
                out.add(lerpResample(p.samples, target));
            } else {
                out.add(p.samples);
            }
        }
        return out;
    }

    /** Smooth a unit seam in place, mirroring the original's join filter (root.45):
     *  two passes of 2-tap neighbour averaging centred on the seam, widening the
     *  window ±4 then ±8 samples. This rounds off the step discontinuity between
     *  concatenated demi-syllables without touching the unit interiors. */
    private static void smoothSeam(short[] pcm, int seam) {
        for (int pass = 1; pass <= 2; pass++) {
            int w = 4 * pass;
            int lo = Math.max(1, seam - w), hi = Math.min(pcm.length - 1, seam + w);
            for (int i = lo; i < hi; i++)
                pcm[i] = (short) ((pcm[i] + pcm[i + 1]) / 2);
        }
    }

    /** Median sample-length of the voiced periods in a sequence (0 if none). */
    private static int medianVoicedLength(List<VoiceDatabase.Period> ps) {
        List<Integer> v = new ArrayList<>();
        for (VoiceDatabase.Period p : ps) if (p.voiced) v.add(p.samples.length);
        if (v.isEmpty()) return 0;
        java.util.Collections.sort(v);
        return v.get(v.size() / 2);
    }

    /** Linear frame interpolation, exactly per voicesynth root.44: the j-th of `n`
     *  frames between data and data2 is out[i] = ((n-1-j)*data[i] + j*data2[i])/(n-1),
     *  over min(len(data),len(data2)) samples rounded down to even. j=0 -> data,
     *  j=n-1 -> data2. Used to render a count>1 voiced frame as `count` periods. */
    private static short[] interpFrame(short[] data, short[] data2, int n, int j) {
        int len = Math.min(data.length, data2.length);
        len &= ~1;                              // floor to even (root.44)
        if (len == 0) return data;
        if (n <= 1) return java.util.Arrays.copyOf(data, len);
        int w0 = (n - 1) - j, w1 = j, denom = n - 1;
        short[] out = new short[len];
        for (int i = 0; i < len; i++)
            out[i] = (short) ((w0 * data[i] + w1 * data2[i]) / denom);
        return out;
    }

    /** Extend a voiced period to `target` samples by appending a linear bridge from
     *  the period's last sample to `next` (the following period's first sample),
     *  exactly as voicesynth root.48 does: out[L+j] = last + (j+1)*(next-last)/(span+1).
     *  The recorded samples are copied verbatim, so the period's spectrum (timbre)
     *  is preserved — only the pitch period is lengthened. Never compresses. */
    private static short[] extendPeriod(short[] s, int target, short next) {
        int L = s.length;
        if (L >= target || L == 0) return s;
        int span = target - L;
        short[] out = new short[target];
        System.arraycopy(s, 0, out, 0, L);
        int last = s[L - 1];
        int delta = next - last;
        for (int j = 0; j < span; j++)
            out[L + j] = (short) (last + (long) (j + 1) * delta / (span + 1));
        return out;
    }

    /** Linear-interpolate a period to `len` samples (proto7's voiced resample). */
    private static short[] lerpResample(short[] src, int len) {
        short[] dst = new short[len];
        if (src.length == 0) return dst;
        for (int i = 0; i < len; i++) {
            float pos = (float) i * (src.length - 1) / (len - 1 > 0 ? len - 1 : 1);
            int i0 = (int) pos, i1 = Math.min(i0 + 1, src.length - 1);
            float f = pos - i0;
            dst[i] = (short) (src[i0] * (1 - f) + src[i1] * f);
        }
        return dst;
    }

    /** Look up a unit by name, trying the exact key then simpler fallbacks. */
    private VoiceDatabase.Entry lookup(String key) {
        VoiceDatabase.Entry e = index.get(key);
        return e;
    }

    /**
     * Synthesize a phoneme token list into PCM. The engine's units are
     * boundary-prefixed phoneme pairs "-XY" (e.g. "labas" = -la, -ab, -ba, -as;
     * "gintaras" = -gi, -in, -ta, -ar, -ra, -as). We form an overlapping "-XY"
     * unit for every adjacent phoneme pair and concatenate their waveforms.
     */
    public short[] synthesize(String[] phonemes) {
        // Build the conversion code-unit string with the literal trans-conversion
        // port ({@link Conversion}, == the original trans root.8 + root.37.11).
        // This carries the special-letter code units, the diphthong short-vowel
        // handling and the '|' palatal marker exactly as the original engine does
        // (validated 100% byte-for-byte over the corpus). Then select the demi-
        // syllable unit sequence with the literal translate port.
        List<String> ps = new ArrayList<>(phonemes.length);
        for (String p : phonemes) ps.add(p);
        String s = Conversion.convert(ps);

        if (sequencer == null) sequencer = new CandidateSequencer(db);
        return synthesizeUnits(sequencer.sequence(s));
    }

    // ===================== Faithful voicesynth DSP port =====================
    // Re-derived by running the ORIGINAL LuaJIT voicesynth over Gintaras.dta and
    // dumping every emitted pitch period (one yield per period). The structural
    // assembly below mirrors voicesynth root.52/51/49/48/47/45 exactly; validated
    // by cross-correlating Java PCM against the engine's reference PCM per word.
    //
    // Prosody constants from loadvoice (root.43) on the Gintaras voice:
    //   BASE_PERIOD = P0[5] = 220          (base pitch period, samples)
    //   PROSODY     = P0[1]+P0[2] = 20
    //   ProsodyChange P1 = {0,50,10,20,50,160}; PC[3]=10, PC[6]=160 (1-based)
    //   TEMPO       = P0[6]/100 = 0.62     (count-domain resample factor)
    private static final double TEMPO_FACTOR = 0.62;
    private static final int PC3 = 10, PC6 = 160;

    /** Build PCM from an explicit engine unit-name sequence. */
    public short[] synthesizeUnits(List<String> unitNames) {
        // Resolve each unit to its records (Period{samples,voiced,count}).
        List<List<VoiceDatabase.Period>> units = new ArrayList<>();
        for (String name : unitNames) {
            VoiceDatabase.Entry e = lookup(name);
            if (e == null) e = lookupRelaxed(name);
            if (e == null) continue;
            List<VoiceDatabase.Period> recs = db.unitTypedPeriods(e);
            if (!recs.isEmpty()) units.add(recs);
        }
        if (units.isEmpty()) return new short[0];

        // ---- root.52/51: count-domain tempo resampler -> flat list of frames ----
        // For each VOICED record accumulate count*TEMPO and emit floor() periods,
        // dropping the fractional remainder (decimates ~38% of periods at 0.62).
        // UNVOICED records pass through verbatim (no resampling). A voiced record
        // with count>1 expands to `count` linear-interpolated frames (root.44 /
        // root.52.5) before resampling.
        List<short[]> frames = new ArrayList<>();
        List<Boolean> voiced = new ArrayList<>();
        List<Integer> frameUnit = new ArrayList<>();   // 1-based source unit index
        double acc = 0;
        for (int ui = 0; ui < units.size(); ui++) {
            List<VoiceDatabase.Period> recs = units.get(ui);
            for (int ri = 0; ri < recs.size(); ri++) {
                VoiceDatabase.Period p = recs.get(ri);
                List<short[]> srcFrames = new ArrayList<>();
                if (p.voiced && p.count > 1) {
                    boolean nextVoiced = ri + 1 < recs.size() && recs.get(ri + 1).voiced
                            && recs.get(ri + 1).samples.length > 0;
                    short[] data2 = nextVoiced ? recs.get(ri + 1).samples : p.samples;
                    for (int j = 0; j < p.count; j++)
                        srcFrames.add(interpFrame(p.samples, data2, p.count, j));
                } else {
                    srcFrames.add(p.samples);
                }
                if (p.voiced) {
                    for (short[] sf : srcFrames) {
                        acc += TEMPO_FACTOR;
                        int emit = (int) Math.floor(acc);
                        for (int q = 0; q < emit; q++) {
                            frames.add(sf); voiced.add(Boolean.TRUE); frameUnit.add(ui + 1);
                        }
                        acc -= emit;
                    }
                } else {
                    for (short[] sf : srcFrames) {
                        frames.add(sf); voiced.add(Boolean.FALSE); frameUnit.add(ui + 1);
                    }
                }
            }
        }
        if (frames.isEmpty()) return new short[0];

        // ---- root.48 + root.47: per-period pitch regeneration ----
        // Each voiced period is rebuilt at target pitch length = BASE + accumulator,
        // the accumulator slewing per period via root.47. Recorded samples are copied
        // verbatim (timbre preserved) into the target-length buffer; if shorter, a
        // linear bridge ramps the tail toward the next period's first sample. Unvoiced
        // periods emit verbatim. root.45 smooths each period seam. Output buffers are
        // appended in order (root.46).
        // Pitch arch via root.47: the slew target is PC[3] (period falls) for the
        // first half of the word and PC[6] (recover to base) for the second half,
        // keyed by the SOURCE UNIT index (root.52 sets phonecount=#units,
        // phoneorder=unit index; root.47 fires per emitted period). This reproduces
        // the original's shallow per-word pitch arch.
        int phonecount = units.size();      // root.52: phonecount = #units

        pitchAcc = 0;
        pitchTarget = 0;
        short[] prevBuf = null;
        boolean prevVoiced = false;
        int prevPitch = 0;
        List<short[]> outPeriods = new ArrayList<>();
        for (int k = 0; k < frames.size(); k++) {
            short[] cur = frames.get(k);
            boolean curVoiced = voiced.get(k);
            int target = BASE_PERIOD;          // floor(BASE*scale), scale=1
            if (curVoiced) {
                root47(phonecount, frameUnit.get(k));
                target = BASE_PERIOD + pitchAcc;
            }
            if (prevBuf == null) {
                prevBuf = cur; prevVoiced = curVoiced; prevPitch = target;
                continue;
            }
            short[] emit;
            if (prevVoiced) {
                emit = new short[Math.max(prevPitch, 1)];
                int copy = Math.min(prevPitch, prevBuf.length);
                System.arraycopy(prevBuf, 0, emit, 0, copy);
                if (prevBuf.length < prevPitch && prevBuf.length > 0) {
                    int last = prevBuf[prevBuf.length - 1];
                    int next = cur.length > 0 ? cur[0] : last;
                    int span = prevPitch - prevBuf.length;
                    int delta = next - last;
                    for (int j = 0; j < span; j++)
                        emit[prevBuf.length + j] =
                            (short) (last + (long) (j + 1) * delta / (span + 1));
                }
            } else {
                emit = prevBuf;
            }
            root45(emit, cur);
            outPeriods.add(emit);
            prevBuf = cur; prevVoiced = curVoiced; prevPitch = target;
        }
        if (prevBuf != null) outPeriods.add(prevBuf);

        int total = 0;
        for (short[] p : outPeriods) total += p.length;
        short[] pcm = new short[total];
        int o = 0;
        for (short[] p : outPeriods) { System.arraycopy(p, 0, pcm, o, p.length); o += p.length; }
        return pcm;
    }

    // root.47 pitch accumulator state (per utterance).
    private int pitchAcc = 0;
    private int pitchTarget = 0;

    /** Pitch slew toward a per-phone target, faithful to voicesynth root.47.
     *  In playback order the first half of the word slews toward PC[3]=10
     *  (slew -18.75, period falls) and the second half toward PC[6]=160
     *  (slew 0, period recovers to base): a single shallow pitch arch matching
     *  the original. Step = (sign)*(|delta|>>4, +1 toward target); clamp +-100. */
    private void root47(int phonecount, int phoneorder) {
        if (phonecount > 0 && phoneorder > 0) {
            pitchTarget = (phonecount / 2.0 >= phoneorder) ? PC3 : PC6;
        }
        double slew = -PROSODY + pitchTarget / 8.0;
        double d = slew - pitchAcc;
        if (d != 0) {
            int step;
            if (d > 0) step = ((int) d >> 4) + 1;
            else { step = -((int) (-d) >> 4); if (step == 0) step = -1; }
            pitchAcc = pitchAcc + step;
            if (pitchAcc < -100) pitchAcc = -100;
            else if (pitchAcc > 100) pitchAcc = 100;
        }
    }

    /** Join-smoothing filter, faithful port of voicesynth root.45: two passes
     *  (window +-4 then +-8) of 2-tap averaging across the seam between period
     *  buffers b0|b1 (b1 follows b0). A 0 sample is treated as absent (the
     *  original's truthiness quirk). Modifies b0/b1 in place. */
    private static void root45(short[] b0, short[] b1) {
        // The two period buffers form one virtual stream s = b0 || b1 (b1 follows
        // b0). For the seam neighbourhood the filter writes the midpoint:
        //   s[c+1] = (s[c] + s[c+2]) / 2,  c = (n0-1)+j,  j in [-w, w]
        // (a 0 sample is treated as absent and skipped — the original's truthiness
        // quirk). Guarded so reads/writes stay inside [0, n0+n1).
        int n0 = b0.length, n1 = b1.length, n = n0 + n1;
        for (int pass = 1; pass <= 2; pass++) {
            int w = 4 * pass;
            for (int j = -w; j <= w; j++) {
                if (!(-j < n0 - 1 && j < n1 - 1)) continue;
                int c = (n0 - 1) + j;
                if (c < 0 || c + 2 >= n) continue;
                int left = streamGet(b0, b1, n0, c);
                if (left == 0) left = streamGet(b0, b1, n0, c);   // (quirk no-op)
                int right = streamGet(b0, b1, n0, c + 2);
                int mid = (left + right) / 2;
                streamSet(b0, b1, n0, c + 1, (short) mid);
            }
        }
    }

    private static int streamGet(short[] b0, short[] b1, int n0, int i) {
        return i < n0 ? b0[i] : b1[i - n0];
    }
    private static void streamSet(short[] b0, short[] b1, int n0, int i, short v) {
        if (i < n0) b0[i] = v; else b1[i - n0] = v;
    }

    /** Concatenate pitch periods with a tiny crossfade at each period boundary to
     *  suppress step discontinuities (crackle) while keeping full length. */
    private static short[] concatPeriodsSmooth(List<short[]> periods, int xf) {
        if (periods.isEmpty()) return new short[0];
        int total = 0;
        for (short[] p : periods) total += p.length;
        total -= xf * Math.max(0, periods.size() - 1);
        short[] out = new short[Math.max(total, 0)];
        int pos = 0;
        for (int k = 0; k < periods.size(); k++) {
            short[] p = periods.get(k);
            int start = 0;
            if (k > 0) {
                int n = Math.min(xf, Math.min(p.length, pos));
                int base = pos - n;
                for (int j = 0; j < n; j++) {
                    float t = (float) j / n;
                    int mixed = (int) (out[base + j] * (1 - t) + p[j] * t);
                    out[base + j] = (short) Math.max(-32768, Math.min(32767, mixed));
                }
                start = n;
            }
            for (int j = start; j < p.length && pos < out.length; j++) out[pos++] = p[j];
        }
        return java.util.Arrays.copyOf(out, pos);
    }

    /** Peak amplitude of the first/last `n` samples of a segment. */
    private static int edgePeak(short[] s, boolean start, int n) {
        int peak = 0, m = Math.min(n, s.length);
        for (int i = 0; i < m; i++) {
            int v = Math.abs(start ? s[i] : s[s.length - 1 - i]);
            if (v > peak) peak = v;
        }
        return peak;
    }

    /** Equalise loudness across joins: for each adjacent pair, ramp the tail of
     *  the left segment and the head of the right segment toward their mean edge
     *  amplitude, so the transition has no sudden loudness step. */
    private static void levelJoins(List<short[]> segs) {
        final int W = 600; // ~27 ms blend region each side of a join
        for (int k = 0; k + 1 < segs.size(); k++) {
            short[] L = segs.get(k), R = segs.get(k + 1);
            int lp = edgePeak(L, false, W), rp = edgePeak(R, true, W);
            if (lp < 200 || rp < 200) continue; // skip near-silent edges
            float target = (lp + rp) / 2f;
            scaleEdge(L, false, W, target / lp);
            scaleEdge(R, true, W, target / rp);
        }
    }

    /** Scale the first/last `n` samples of `s` by a gain that ramps from 1.0 at
     *  the interior to `g` at the very edge (so the interior is untouched). */
    private static void scaleEdge(short[] s, boolean start, int n, float g) {
        int m = Math.min(n, s.length);
        for (int i = 0; i < m; i++) {
            float t = (float) i / m;            // 0 at edge … 1 at interior end
            float gain = g * (1 - t) + 1f * t;  // edge=g, interior=1
            int idx = start ? i : s.length - 1 - i;
            s[idx] = (short) Math.max(-32768, Math.min(32767, Math.round(s[idx] * gain)));
        }
    }

    /** Tiny click guard at start (8 samples — preserves onset loudness) and a
     *  longer fade-out at the end. */
    private static void applyFades(short[] pcm, int n) {
        int head = Math.min(8, pcm.length / 2);   // just enough to avoid the click
        for (int i = 0; i < head; i++) pcm[i] = (short) (pcm[i] * ((float) i / head));
        int tail = Math.min(n, pcm.length / 2);
        for (int i = 0; i < tail; i++)
            pcm[pcm.length - 1 - i] = (short) (pcm[pcm.length - 1 - i] * ((float) i / tail));
    }

    /** Concatenate segments with a linear crossfade of `xf` samples at each join. */
    private static short[] overlapAdd(List<short[]> segs, int xf) {
        if (segs.isEmpty()) return new short[0];
        int total = 0;
        for (short[] s : segs) total += s.length;
        total -= xf * Math.max(0, segs.size() - 1);
        short[] out = new short[Math.max(total, 0)];
        int pos = 0;
        for (int k = 0; k < segs.size(); k++) {
            short[] s = segs.get(k);
            int start = 0;
            if (k > 0) {
                // crossfade the first xf samples of s into the tail of out
                int n = Math.min(xf, s.length);
                int base = pos - n;
                for (int j = 0; j < n; j++) {
                    if (base + j < 0 || base + j >= out.length) continue;
                    float t = (float) j / n;
                    int mixed = (int) (out[base + j] * (1 - t) + s[j] * t);
                    out[base + j] = (short) Math.max(-32768, Math.min(32767, mixed));
                }
                start = n;
            }
            for (int j = start; j < s.length; j++) {
                if (pos < out.length) out[pos++] = s[j];
            }
        }
        return java.util.Arrays.copyOf(out, pos);
    }

    /** Try a unit name with short↔long vowel substitutions on each vowel char. */
    private VoiceDatabase.Entry lookupRelaxed(String name) {
        char[] cs = name.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            char[] alts = vowelAlts(cs[i]);
            if (alts.length > 1) {
                char orig = cs[i];
                for (char alt : alts) {
                    if (alt == orig) continue;
                    cs[i] = alt;
                    VoiceDatabase.Entry e = lookup(new String(cs));
                    if (e != null) return e;
                }
                cs[i] = orig;
            }
        }
        return null;
    }

    /** Try "-XY" with short↔long vowel substitutions before giving up. */
    private VoiceDatabase.Entry lookupFlexible(char a, char b) {
        char[] altA = vowelAlts(a), altB = vowelAlts(b);
        for (char ca : altA) for (char cb : altB) {
            VoiceDatabase.Entry e = lookup("-" + ca + cb);
            if (e != null) return e;
        }
        return null;
    }

    /** Short/long variants of a vowel char (so -tá matches when -ta is absent). */
    private static char[] vowelAlts(char c) {
        switch (c) {
            case 'a': return new char[]{'a', (char)0xe1};
            case (char)0xe1: return new char[]{(char)0xe1, 'a'};
            case 'o': return new char[]{'o', (char)0xf3};
            case (char)0xf3: return new char[]{(char)0xf3, 'o'};
            case 'e': return new char[]{'e', (char)0xeb};
            case (char)0xeb: return new char[]{(char)0xeb, 'e'};
            default: return new char[]{c};
        }
    }
}
