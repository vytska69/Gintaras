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
     * Map a transcriber phoneme token (e.g. "aA", "l'", "S", "N") to the single
     * cp1257 phoneme char used in diphone names. The diphone alphabet uses one
     * char per phoneme: the base letter, lowercase for plain, with long/stressed
     * vowels using their accented cp1257 forms.
     */
    private static char phonemeChar(String ph) {
        // strip palatalisation marker for the unit-name lookup
        String base = ph.endsWith("'") ? ph.substring(0, ph.length() - 1) : ph;
        switch (base) {
            // Vowels map to the SHORT cp1257 char by default; the diphone lookup
            // falls back to the long variant (á/ó/ė) when a short unit is absent.
            case "aA": case "Aa": case "aa": return 'a';
            case "oO": case "Oo": case "oo": return 'o';
            case "eE": case "Ee": return (char) 0xeb;  // ė (long/close e)
            case "eA": case "Ea": case "ea": case "ee": return 'e';
            case "iI": case "ii": return 'i';
            case "uU": case "uu": return 'u';
            // glides and uppercase sonorant variants → their base letter
            // glides: J is the i-glide (au→au, ai→ai), W is the u-glide. In the
            // diphone alphabet they reuse the vowel chars i/u (saulė = -sa,-au,
            // -ul,-le; vaikas = -va,-ai,-ik,-ka), NOT j/v.
            case "J": return 'i';
            case "W": return 'u';
            case "L": return 'l';
            case "M": return 'm';
            case "N": return 'n';
            case "R": return 'r';
            // affricates / digraphs keep first char (approximation for v1)
            case "ts": case "tS": return 'c';
            case "dz": case "dZ": return 'z';
            case "S": return (char) 0xf0; // š lowercase cp1257
            case "Z": return (char) 0xfe; // ž lowercase cp1257
            case "x": return 'h';
            default:
                if (base.length() >= 1) {
                    char c = base.charAt(0);
                    return Character.toLowerCase(c);
                }
                return '.';
        }
    }

    /** Whether a diphone-name char is a vowel (short a e i o u or long á ė ó). */
    private static boolean isVowelChar(char c) {
        return c=='a'||c=='e'||c=='i'||c=='o'||c=='u'
            || c==(char)0xe1 || c==(char)0xeb || c==(char)0xf3;
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
        // phoneme chars (skip the '_' boundary tokens; the '-' is added per unit)
        StringBuilder seq = new StringBuilder();
        for (String p : phonemes) {
            if (p.equals("_")) continue;
            seq.append(phonemeChar(p));
        }
        String s = seq.toString();

        // Select the unit-name sequence with CandidateSequencer — the data-driven
        // demi-syllable selector ported from the engine's `translate` (matches it
        // ~92% on the gold corpus; remaining cases mirror the engine's own quirks).
        // Collect each unit's pitch periods WITH their voiced flag so we can apply
        // the original proto7 pitch step (voiced periods only) below.
        if (sequencer == null) sequencer = new CandidateSequencer(db);
        List<VoiceDatabase.Period> segs = new ArrayList<>();
        // Mark, in period units, where each demi-syllable unit ends — these are the
        // seams the original smooths across (root.45 join filter), as opposed to the
        // joins *inside* a unit (consecutive recorded periods, already continuous).
        List<Integer> unitEndPeriod = new ArrayList<>();
        for (String name : sequencer.sequence(s)) {
            VoiceDatabase.Entry e = lookup(name);
            if (e == null) e = lookupRelaxed(name);
            if (e != null) {
                segs.addAll(db.unitTypedPeriods(e));
                unitEndPeriod.add(segs.size());
            }
        }
        if (segs.isEmpty()) return new short[0];

        // Pitch contour — ported EXACTLY from the original voicesynth pitch
        // accumulator (root.47) + DSP (root.48). Per voiced period the offset slews
        // toward `slewTarget = -Prosody + PC/8`, where PC is the ProsodyChange entry
        // PC[6]=160 over the first half of the word and PC[3]=10 over the second
        // (P1 ramp {0,50,10,20,50,160}); Prosody = P0.Prosody = 20. The slew step is
        // (diff>>4)+1 when rising, -((-diff)>>4) (min -1) when falling, then floored
        // and clamped to +-100. Target period = BASE_PERIOD + offset (root.48). This
        // gives a subtle ~100-110 Hz contour so repeated syllables differ — the cure
        // for the robotic 'mama'/'namas' — without the earlier exaggerated swing.
        int nV = 0;
        for (VoiceDatabase.Period p : segs) if (p.voiced) nV++;
        int[] targetLen = new int[segs.size()];
        double offset = 0; int iv = 0;
        for (int k = 0; k < segs.size(); k++) {
            VoiceDatabase.Period p = segs.get(k);
            if (!p.voiced) { targetLen[k] = p.samples.length; continue; }
            int pc = (iv * 2 < nV) ? PC_EARLY : PC_LATE;       // PC[6] early, PC[3] late
            double slewTarget = -PROSODY + pc / 8.0;           // 0.0 early, -18.75 late
            double diff = slewTarget - offset;
            int step;
            if (diff == 0) step = 0;
            else if (diff > 0) step = ((int) diff >> 4) + 1;   // rshift truncates to int
            else { step = -((int) (-diff) >> 4); if (step == 0) step = -1; }
            offset = Math.floor(offset + step);
            if (offset > 100) offset = 100; else if (offset < -100) offset = -100;
            int t = (int) (BASE_PERIOD + offset);
            // root.48 is EXTEND-ONLY: it never compresses a recorded period, only
            // pads it out to the target pitch. So the recorded length is the floor.
            targetLen[k] = Math.max(p.samples.length, t);
            iv++;
        }

        int total = 0;
        for (int len : targetLen) total += len;
        short[] pcm = new short[total];
        int o = 0;
        int seamIdx = 0;               // which entry of unitEndPeriod comes next
        List<Integer> seamSamples = new ArrayList<>();
        for (int k = 0; k < segs.size(); k++) {
            VoiceDatabase.Period p = segs.get(k);
            if (p.voiced && targetLen[k] > p.samples.length) {
                // next period's first sample is the bridge target (root.48 src[0])
                short next = (k + 1 < segs.size() && segs.get(k + 1).samples.length > 0)
                        ? segs.get(k + 1).samples[0]
                        : (p.samples.length > 0 ? p.samples[p.samples.length - 1] : 0);
                short[] r = extendPeriod(p.samples, targetLen[k], next);
                System.arraycopy(r, 0, pcm, o, r.length); o += r.length;
            } else {
                System.arraycopy(p.samples, 0, pcm, o, p.samples.length); o += p.samples.length;
            }
            // record the sample offset at every unit seam (not the final end)
            while (seamIdx < unitEndPeriod.size() && unitEndPeriod.get(seamIdx) == k + 1) {
                if (k + 1 < segs.size()) seamSamples.add(o);
                seamIdx++;
            }
        }
        // root.45 join smoothing: two passes of 2-tap neighbour averaging across each
        // demi-syllable seam, widening the window (±4 then ±8), to remove the step
        // discontinuity (buzz/click) the original avoids at unit joins.
        for (int seam : seamSamples) smoothSeam(pcm, seam);
        applyFades(pcm, 48);
        return pcm;
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
