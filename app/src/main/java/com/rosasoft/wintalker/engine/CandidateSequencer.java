package com.rosasoft.wintalker.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reproduces the original engine's `translate` unit selection WITHOUT a full LPeg
 * engine. Instrumenting the real translate showed it generates candidate unit
 * names per position and keeps those that EXIST in the voice table; the output
 * sequence is exactly those "hits", in order. (Trace for "tata": candidates
 * ^ta, ta, ^ta-, tat--, ta-✓, at, ata--, at-, -ta✓, ta$, ta, ta-✓, -ta$, a$,
 * --ata, -ta✓ → hits ta- -ta ta- -ta.)
 *
 * We walk the phoneme-char string and, at each onset+vowel, take the existing
 * left half "Cv-" then right half "-Cv"; at codas take "-Vc"; standalone vowels
 * and cluster consonants take themselves — always gated by presence in the voice
 * index, exactly like translate's match-time captures.
 */
public final class CandidateSequencer {

    private final Map<String, VoiceDatabase.Entry> idx;

    public CandidateSequencer(VoiceDatabase db) {
        this.idx = db.diphoneIndex();
    }

    private boolean has(String key) { return idx.containsKey(key); }

    /** Lithuanian diphthongs that bind into one nucleus. */
    private static boolean diphthong(char a, char b) {
        if (a == 'i' && b == 'e') return true;   // ie
        if (a == 'u' && b == 'o') return true;   // uo
        if (b == 'i' || b == 'u')                // ai ei oi au eu ou ui
            return a == 'a' || a == 'e' || a == 'o' || a == 'u';
        return false;
    }

    static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'
            || c == (char) 0xe1 || c == (char) 0xeb || c == (char) 0xf3 || c == (char) 0x07
            || c == (char) 0xe0 || c == (char) 0xe6 || c == (char) 0xf8 || c == (char) 0xfb;
    }

    /**
     * Produce the unit-name sequence for a phoneme-char string by emitting, at each
     * position, the candidate units that exist in the voice table (mirroring
     * translate). Returns names that all resolve in the index.
     */
    public List<String> sequence(String s) {
        List<String> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        char prevVowel = 0;
        while (i < n) {
            char c = s.charAt(i);
            // Onset consonant (or vowel) followed by a vowel → CV nucleus.
            if (i + 1 < n && isVowel(s.charAt(i + 1)) && !isVowel(c)) {
                char v1 = s.charAt(i + 1);
                // If the vowel v1 is itself followed by a vowel v2 forming a
                // diphthong (ie, au, uo, ai, ei...), the engine emits only the
                // LEFT half "Cv1-" here, then the diphthong "v1v2-"/"-v1v2" takes
                // over (e.g. lietuva: li- ie- -ie). Otherwise emit the full pair.
                if (i + 2 < n && isVowel(s.charAt(i + 2)) && diphthong(v1, s.charAt(i + 2))) {
                    // emit only the left half "Cv1-", then advance past the
                    // CONSONANT only so v1 is reused as the diphthong's first
                    // element (lietuva: li- then ie- -ie share the 'i').
                    if (has(c + "" + v1 + "-")) out.add(c + "" + v1 + "-");
                    prevVowel = v1;
                    i += 1;          // consume only the consonant
                    continue;
                }
                emitPair(out, "" + c + v1);
                prevVowel = v1;
                i += 2;
                continue;
            }
            if (isVowel(c)) {
                // diphthong nucleus: vowel+vowel → "V1V2-"/"-V1V2"
                if (i + 1 < n && isVowel(s.charAt(i + 1)) && diphthong(c, s.charAt(i + 1))) {
                    String body = "" + c + s.charAt(i + 1);
                    // A bare diphthong unit (e.g. "au") is rendered as a single
                    // unit when it exists; otherwise emit the half-pair "X-"/"-X"
                    // (ai, uo, ie...). The bare form takes precedence.
                    if (has(body)) {
                        out.add(body);
                        prevVowel = s.charAt(i + 1);
                        i += 2;
                        continue;
                    }
                    if (has(body + "-") || has("-" + body)) {
                        emitPair(out, body);
                        prevVowel = s.charAt(i + 1);
                        i += 2;
                        continue;
                    }
                }
                // standalone vowel: take "V" if present, else its left half
                if (has("" + c)) out.add("" + c);
                else emitPair(out, "" + c);
                prevVowel = c;
                i += 1;
                continue;
            }
            // consonant not before a vowel: prefer the coda "-Vc"; if two coda
            // consonants follow the same vowel, only the FIRST takes the coda and
            // the rest are bare cluster consonants (tekstas: -ek s; mokykla: -ok k).
            boolean codaUsed = !out.isEmpty() && out.get(out.size() - 1).startsWith("-")
                    && out.get(out.size() - 1).length() == 3
                    && out.get(out.size() - 1).charAt(1) == prevVowel;
            if (!codaUsed && prevVowel != 0 && has("-" + prevVowel + c)) {
                out.add("-" + prevVowel + c);            // coda "-Vc"
                i += 1;
            } else if (has("" + c)) {
                out.add("" + c);                          // cluster consonant alone
                i += 1;
            } else {
                // not found in any form: skip (rare); advance to avoid a stall
                i += 1;
            }
        }
        return out;
    }

    /** Emit the existing halves of a nucleus body: left "X-" then right "-X". */
    private void emitPair(List<String> out, String body) {
        boolean left = has(body + "-");
        boolean right = has("-" + body);
        if (left) out.add(body + "-");
        if (right) out.add("-" + body);
        if (!left && !right) {
            if (has(body)) out.add(body);  // some nuclei stored without dashes
        }
    }
}
