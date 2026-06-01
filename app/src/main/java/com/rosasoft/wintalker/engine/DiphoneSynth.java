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
            // long/stressed vowels → accented cp1257 (á=0xe1, ó=0xf3, ė=0xeb)
            case "aA": case "Aa": return (char) 0xe1;
            case "oO": case "Oo": return (char) 0xf3;
            case "eE": case "Ee": return (char) 0xeb;
            case "eA": case "Ea": return 'e';
            case "iI": return 'i';
            case "uU": return 'u';
            // glides and uppercase sonorant variants → their base letter
            case "J": return 'j';
            case "W": return 'v';
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

    /** Look up a unit by name, trying the exact key then simpler fallbacks. */
    private VoiceDatabase.Entry lookup(String key) {
        VoiceDatabase.Entry e = index.get(key);
        return e;
    }

    /**
     * Synthesize a phoneme token list (space-separated tokens incl. '_' bounds)
     * into PCM. Forms overlapping 2-phoneme diphone units with boundary markers.
     */
    public short[] synthesize(String[] phonemes) {
        // Build the phoneme-char string with '-' for the '_' boundaries.
        StringBuilder seq = new StringBuilder();
        for (String p : phonemes) {
            if (p.equals("_")) seq.append('-');
            else seq.append(phonemeChar(p));
        }
        String s = seq.toString();

        List<short[]> parts = new ArrayList<>();
        // overlapping diphones: for each adjacent pair, look up "ab"; if missing
        // try boundary forms and single phonemes.
        for (int i = 0; i + 1 < s.length(); i++) {
            String di = s.substring(i, i + 2);
            VoiceDatabase.Entry e = lookup(di);
            if (e == null && i + 2 < s.length()) {
                // try triphone with following boundary/char
                e = lookup(s.substring(i, i + 3));
            }
            if (e == null) {
                // single-phoneme fallback
                e = lookup(s.substring(i, i + 1));
            }
            if (e != null) parts.add(db.unitWaveform(e));
        }

        int total = 0;
        for (short[] p : parts) total += p.length;
        short[] out = new short[total];
        int o = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }
}
