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
            // Vowels map to the SHORT cp1257 char by default; the diphone lookup
            // falls back to the long variant (á/ó/ė) when a short unit is absent.
            case "aA": case "Aa": case "aa": return 'a';
            case "oO": case "Oo": case "oo": return 'o';
            case "eE": case "Ee": case "ee": return 'e';
            case "eA": case "Ea": case "ea": return 'e';
            case "iI": case "ii": return 'i';
            case "uU": case "uu": return 'u';
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

        List<short[]> parts = new ArrayList<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            // primary: boundary-prefixed pair "-XY"
            String key = "-" + s.substring(i, i + 2);
            VoiceDatabase.Entry e = lookup(key);
            if (e == null) {
                // fallbacks: long/short vowel swap on either char, then single
                e = lookupFlexible(s.charAt(i), s.charAt(i + 1));
            }
            if (e != null) parts.add(db.unitWaveform(e));
        }
        if (parts.isEmpty() && s.length() == 1) {
            VoiceDatabase.Entry e = lookup("-" + s);
            if (e != null) parts.add(db.unitWaveform(e));
        }

        int total = 0;
        for (short[] p : parts) total += p.length;
        short[] out = new short[total];
        int o = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
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
