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

    /** Base pitch period in samples (≈100 Hz), from the voice prosody table
     *  P0[5]=220. PSOLA places every period at this constant interval. */
    private static final int PITCH_PERIOD = 220;

    private final VoiceDatabase db;
    private final Map<String, VoiceDatabase.Entry> index;

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
            case "eE": case "Ee": case "ee": return 'e';
            case "eA": case "Ea": case "ea": return 'e';
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

    /** Look up a unit by name, trying the exact key then simpler fallbacks. */
    private VoiceDatabase.Entry lookup(String key) {
        VoiceDatabase.Entry e = index.get(key);
        return e;
    }

    /**
     * Synthesize a phoneme token list into PCM using TD-PSOLA.
     *
     * Decoded from the original engine (protos p8/p11): each diphone unit is a
     * sequence of pitch-period sample blocks; the synth lays them down at a
     * CONSTANT pitch period (base 220 ≈ 100 Hz from prosody P0[5]) by overlap-
     * adding Hann-windowed periods spaced one pitch period apart. Constant
     * spacing = steady pitch (no roughness); overlap-add of windowed periods =
     * no clicks/crackle (continuous waveform); using every period once at fixed
     * spacing = natural duration. This replaces the earlier ad-hoc concatenation.
     */
    public short[] synthesize(String[] phonemes) {
        StringBuilder seq = new StringBuilder();
        for (String p : phonemes) {
            if (p.equals("_")) continue;
            seq.append(phonemeChar(p));
        }
        String s = seq.toString();

        // Collect the ordered pitch periods for the whole word: for each adjacent
        // phoneme pair form the "-XY" unit; drop the small overlap that repeats
        // the shared phoneme so each phoneme is voiced once.
        List<short[]> periods = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i + 1 < s.length(); i++) {
            VoiceDatabase.Entry e = lookup("-" + s.substring(i, i + 2));
            if (e == null) e = lookupFlexible(s.charAt(i), s.charAt(i + 1));
            if (e == null) continue;
            List<short[]> up = db.unitPeriods(e);
            if (up.isEmpty()) continue;
            int start = first ? 0 : Math.min(2, up.size() - 1);
            for (int k = start; k < up.size(); k++) periods.add(up.get(k));
            // hold the final vowel a little longer for natural duration
            if (i + 1 == s.length() - 1 && isVowelChar(s.charAt(i + 1))) {
                short[] last = up.get(up.size() - 1);
                periods.add(last); periods.add(last);
            }
            first = false;
        }
        if (periods.isEmpty()) return new short[0];

        return psola(periods, PITCH_PERIOD);
    }

    /**
     * Time-domain PSOLA: place each pitch period at a constant pitch interval P,
     * windowed with a Hann window of width 2P and overlap-added. This yields a
     * steady fundamental and a click-free, continuous waveform regardless of the
     * stored periods' varying lengths.
     */
    private static short[] psola(List<short[]> periods, int P) {
        int out = periods.size() * P + 2 * P;
        float[] acc = new float[out];
        int center = P; // first period centered one P in (room for left half-window)
        for (short[] period : periods) {
            int L = period.length;
            // window width spans 2P; resample the stored period across it so the
            // period's energy maps onto a 2P Hann grain centered at `center`.
            int half = P;
            for (int j = -half; j < half; j++) {
                int idx = center + j;
                if (idx < 0 || idx >= out) continue;
                // sample the source period proportionally to its own length
                float srcPos = ((j + half) / (float) (2 * half)) * (L - 1);
                int s0 = (int) srcPos;
                int s1 = Math.min(s0 + 1, L - 1);
                float frac = srcPos - s0;
                float sample = period[s0] * (1 - frac) + period[s1] * frac;
                // Hann window
                float w = 0.5f - 0.5f * (float) Math.cos(Math.PI * (j + half) / half);
                acc[idx] += sample * w;
            }
            center += P;
        }
        // normalize and convert
        short[] pcm = new short[out];
        for (int i = 0; i < out; i++) {
            int v = Math.round(acc[i]);
            pcm[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
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
