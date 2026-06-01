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

    /** Base pitch period in samples, from the voice's prosody table P0[5]=220
     *  (22050/220 ≈ 100 Hz). The original resamples every period to this so the
     *  pitch is steady; using raw varying periods sounds rough. */
    private static final int TARGET_PERIOD = 220;

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

        // Resolve each adjacent-pair "-XY" unit to its list of pitch periods.
        List<List<short[]>> units = new ArrayList<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            VoiceDatabase.Entry e = lookup("-" + s.substring(i, i + 2));
            if (e == null) e = lookupFlexible(s.charAt(i), s.charAt(i + 1));
            units.add(e != null ? db.unitPeriods(e) : null);
        }

        // Build per-unit waveforms by concatenating that unit's periods DIRECTLY
        // (periods within a unit are consecutive recordings → already phase-
        // continuous; overlapping them would shorten/distort and raise pitch).
        // Each unit "-X Y" shares phoneme Y with the next; drop each following
        // unit's first-half periods (the repeated shared phoneme).
        // Each '-XY' unit spans X→Y. Adjacent units '-XY' and '-YZ' share phoneme
        // Y: '-XY' ends in Y's onset, '-YZ' begins in Y's tail. To render Y once
        // without truncating vowels, drop only a SMALL overlap (a couple of
        // periods) from the front of each following unit, not its whole first
        // half — that earlier halving made vowels too short.
        // Concatenate each unit's stored periods DIRECTLY (no resampling — the
        // stored periods already carry the correct waveform incl. consonants,
        // which are NOT pitch-periodic and must not be stretched). Drop each
        // following unit's repeated onset (~2 periods) so the shared phoneme
        // isn't doubled.
        List<short[]> segs = new ArrayList<>();
        boolean first = true;
        for (int ui = 0; ui < units.size(); ui++) {
            List<short[]> u = units.get(ui);
            if (u == null || u.isEmpty()) continue;
            List<short[]> use;
            if (first) {
                use = new ArrayList<>(u);
                // Strengthen/lengthen the initial consonant: the first unit "-CV"
                // packs the onset consonant into its first ~2 periods, too short
                // and quiet (heard as a weak/absent 'l'). Repeat the very first
                // period so the onset is held and clearly audible.
                if (s.length() >= 1 && !isVowelChar(s.charAt(0)) && !u.isEmpty()) {
                    use.add(0, u.get(0));
                }
                first = false;
            } else {
                int drop = Math.min(2, u.size() - 1);
                use = new ArrayList<>(u.subList(drop, u.size()));
            }
            // Lengthen vowels: a unit "-XY" ending in a vowel Y carries that vowel
            // in its final periods. Real speech holds vowels longer than the short
            // recorded diphone, so repeat the last (steady vowel) period a few
            // times to give the vowel natural duration. The last period before a
            // word end gets held longest.
            char endChar = s.charAt(ui + 1);
            if (isVowelChar(endChar) && !use.isEmpty()) {
                short[] lastP = use.get(use.size() - 1);
                int repeats = (ui + 1 == s.length() - 1) ? 3 : 2; // hold final vowel longer
                for (int r = 0; r < repeats; r++) use.add(lastP);
            }
            int len = 0; for (short[] p : use) len += p.length;
            short[] seg = new short[len];
            int o = 0; for (short[] p : use) { System.arraycopy(p, 0, seg, o, p.length); o += p.length; }
            segs.add(seg);
        }

        // Crossfade ONLY at unit joins (~5 ms) so the shared-phoneme handoff is
        // smooth, without touching the phase-continuous interior of each unit.
        short[] pcm = overlapAdd(segs, 110);

        // Fade in/out the very start and end. The first sample block jumps from
        // silence straight to full amplitude, which clicks — heard as a spurious
        // plosive 'p' onset on every word (pietuva/pintaras/paue). A short ramp
        // removes the click without audibly softening the real onset.
        applyFades(pcm, 48);
        return pcm;
    }

    /** Linearly resample one pitch period (src) to `len` samples into dst[off..]. */
    private static void resamplePeriod(short[] src, short[] dst, int off, int len) {
        if (src.length == 0) { for (int i = 0; i < len; i++) dst[off + i] = 0; return; }
        for (int i = 0; i < len; i++) {
            float srcPos = (float) i * src.length / len;
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, src.length - 1);
            float frac = srcPos - i0;
            dst[off + i] = (short) (src[i0] * (1 - frac) + src[i1] * frac);
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
