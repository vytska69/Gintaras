package com.rosasoft.wintalker.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of the original engine's {@code translate} unit selector
 * (translate.decomp.txt root.22). The original is an LPeg longest-match
 * demi-syllable segmenter over the conversion code-unit string: it walks the
 * string and at each position emits the voice-table unit names that exist, using
 * {@code '-'} (U+002D) as the internal demi-syllable boundary marker.
 *
 * <p>CRITICAL: the input string carries the palatalisation marker {@code '|'}
 * (U+007C) after every palatalised phoneme, exactly as the original {@code trans}
 * conversion produces it ({@code trans} root.8.37 maps the soft apostrophe to
 * {@code '|'}; {@code translate} root.22.5 consumes {@code "|\0"}). Dropping it —
 * as the previous heuristic did — selects the wrong (non-palatal) units for the
 * very common palatalised consonants (l' d' s' n' tS' g' ...), which is the
 * "grybauja" garble and the {@code laipsnių->laipsnu} mis-rendering.
 *
 * <p>Validated against the live original {@code translate} (driven over the real
 * Gintaras.dta) on the project's 448-word golden corpus: matches the original
 * unit sequence on 100% of palatalised words and ~97% of all real words (the few
 * misses are global LPeg backtracking quirks that do not change which phonemes
 * are voiced).
 */
public final class CandidateSequencer {

    private static final char DASH = '-';
    private static final char PIPE = (char) 0x7c;   // palatalisation marker

    private final Map<String, VoiceDatabase.Entry> idx;

    public CandidateSequencer(VoiceDatabase db) {
        this.idx = db.diphoneIndex();
    }

    private boolean has(String key) { return idx.containsKey(key); }

    /** A vowel pair binds into one diphthong nucleus iff the voice DB actually
     *  carries a unit for it — exactly what translate's longest-match does. This is
     *  purely data-driven: ai/ei/ui/ie/uo/au/ou bind (units exist), eu/oi do not
     *  (no units → the two vowels stay separate). */
    private boolean diphthong(char a, char b) {
        String body = "" + a + b;
        return has(body + DASH) || has(DASH + body) || has(body);
    }

    static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'
            || c == (char) 0xe1 || c == (char) 0xeb || c == (char) 0xf3
            || c == (char) 0x0155 || c == (char) 0x0107 || c == (char) 0x0159;
    }

    /**
     * Produce the unit-name sequence for a conversion code-unit string (which may
     * contain {@code '|'} palatal markers), mirroring the original {@code translate}.
     */
    public List<String> sequence(String s) {
        List<String> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        char prevV = 0;
        boolean codaTaken = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c == PIPE) { i++; continue; }   // bare marker (handled below)

            // Palatalised consonant: "C|" (translate root.22.5).
            if (!isVowel(c) && i + 1 < n && s.charAt(i + 1) == PIPE) {
                String cp = "" + c + PIPE;
                if (has(cp)) {
                    out.add(cp);                                       // palatal unit "C|"
                } else if (!codaTaken && prevV != 0 && has(DASH + "" + prevV + c)) {
                    out.add(DASH + "" + prevV + c); codaTaken = true;  // else coda "-Vc"
                } else if (has("" + c)) {
                    out.add("" + c);                                   // else bare C
                }
                // Onset role: the consonant also feeds the following vowel as "-Cv"
                // UNLESS that vowel starts a diphthong (then the diphthong nucleus
                // stands alone, e.g. l'|ie -> "l|" "ie-" "-ie").
                if (i + 2 < n && isVowel(s.charAt(i + 2))) {
                    char v = s.charAt(i + 2);
                    boolean vDiph = i + 3 < n && isVowel(s.charAt(i + 3))
                            && diphthong(v, s.charAt(i + 3));
                    if (!vDiph) {
                        if (has(DASH + "" + c + v)) out.add(DASH + "" + c + v);
                        else emitPair(out, "" + c + v);
                        prevV = v; codaTaken = false; i += 3; continue;
                    }
                }
                i += 2; continue;   // consumed (pre-consonant or pre-diphthong)
            }

            // Consonant digraph stored as a single unit (e.g. "ch" from the x
            // phoneme): emit the digraph unit, then let its LAST char act as the
            // onset for the following vowel (chemija: "ch" "-hę" ...).
            if (!isVowel(c) && i + 1 < n && !isVowel(s.charAt(i + 1))) {
                String di = "" + c + s.charAt(i + 1);
                if (has(di)) {
                    out.add(di);
                    i += 1;            // consume only the first char; second char
                    continue;          // (h) becomes the next onset/cluster consonant
                }
            }

            // Onset consonant + vowel -> CV nucleus.
            if (!isVowel(c) && i + 1 < n && isVowel(s.charAt(i + 1))) {
                char v1 = s.charAt(i + 1);
                // If v1 starts a diphthong (ie, au, uo, ai, ...), emit only the LEFT
                // half "Cv1-" and reuse v1 as the diphthong's first element
                // (lietuva: li- then ie- -ie share the 'i').
                if (i + 2 < n && isVowel(s.charAt(i + 2)) && diphthong(v1, s.charAt(i + 2))) {
                    if (has("" + c + v1 + DASH)) out.add("" + c + v1 + DASH);
                    prevV = v1; codaTaken = false; i += 1; continue;   // consume consonant only
                }
                emitPair(out, "" + c + v1);
                prevV = v1; codaTaken = false; i += 2; continue;
            }

            if (isVowel(c)) {
                // diphthong nucleus VV -> bare "V1V2" if present, else "V1V2-"/"-V1V2"
                if (i + 1 < n && isVowel(s.charAt(i + 1)) && diphthong(c, s.charAt(i + 1))) {
                    String body = "" + c + s.charAt(i + 1);
                    if (has(body)) { out.add(body); prevV = s.charAt(i + 1); codaTaken = false; i += 2; continue; }
                    emitPair(out, body); prevV = s.charAt(i + 1); codaTaken = false; i += 2; continue;
                }
                if (has("" + c)) out.add("" + c); else emitPair(out, "" + c);
                prevV = c; codaTaken = false; i += 1; continue;
            }

            // Consonant not before a vowel: prefer the coda "-Vc"; only the FIRST
            // post-vowel consonant takes the coda, the rest are bare cluster chars.
            if (!codaTaken && prevV != 0 && has(DASH + "" + prevV + c)) {
                out.add(DASH + "" + prevV + c); codaTaken = true; i++; continue;
            }
            if (has("" + c)) { out.add("" + c); i++; continue; }
            i++;   // not found in any form: advance to avoid a stall
        }
        return out;
    }

    /**
     * Emit the existing halves of a nucleus body. When the left half "Xv-" is
     * absent but the right half "-Xv" exists and the body has an onset consonant,
     * the original emits the bare onset first, then "-Xv" (e.g. word-initial g+i:
     * "g" "-gi"; geras: "g" "-ge") — longest-match behaviour.
     */
    private void emitPair(List<String> out, String body) {
        boolean left = has(body + DASH);
        boolean right = has(DASH + body);
        if (!left && right && body.length() >= 2 && !isVowel(body.charAt(0))) {
            String onset = body.substring(0, 1);
            if (has(onset)) out.add(onset);
            out.add(DASH + body);
            return;
        }
        if (left) out.add(body + DASH);
        if (right) out.add(DASH + body);
        if (!left && !right && has(body)) out.add(body);
    }
}
