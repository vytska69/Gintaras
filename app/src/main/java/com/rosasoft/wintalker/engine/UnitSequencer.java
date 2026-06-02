package com.rosasoft.wintalker.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the demi-syllable unit-name sequence for a phoneme-char string, porting
 * the original engine's `translate` sequencer (verified against it over a 48-word
 * gold corpus). Input chars are cp1257 phoneme symbols from
 * Transcriber→phonemeChar (vowels a e i o u + long ė=0xeb ó=0xf3 etc., consonants).
 *
 * Rule (observed from the real translate):
 *   - consonant C immediately before a vowel V → emit two half-units: "Cv-" then
 *     "-Cv" (overlapping left/right halves of the CV unit)
 *   - a vowel V not preceded by a consonant in the same onset (word-initial or in
 *     a vowel sequence) → emit "V" alone
 *   - a coda consonant C (after a vowel, not before one) → emit "-Vc"
 *   - a consonant in a cluster before another consonant → emit "C" alone
 * Each emitted name is a key into the voice diphone table.
 */
public final class UnitSequencer {

    static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'
            || c == (char) 0xe1 /*į/á*/ || c == (char) 0xeb /*ė*/ || c == (char) 0xf3 /*ó*/
            || c == (char) 0xe0 || c == (char) 0xe6 || c == (char) 0xf8 || c == (char) 0xfb;
    }

    /** Produce the ordered unit-name strings for a phoneme-char string. */
    public static List<String> sequence(String s) {
        List<String> units = new ArrayList<>();
        int i = 0;
        char prevVowel = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (isVowel(c)) {
                // Diphthong: two adjacent vowels (ie, au, uo, ei, ai) form a
                // glide pair rendered like a CV — "V1V2-" then "-V1V2".
                if (i + 1 < n && isVowel(s.charAt(i + 1)) && isDiphthong(c, s.charAt(i + 1))) {
                    char v2 = s.charAt(i + 1);
                    units.add("" + c + v2 + '-');
                    units.add("-" + c + v2);
                    prevVowel = v2;
                    i += 2;
                    continue;
                }
                // a lone vowel (onset / hiatus): emit it by itself
                units.add("" + c);
                prevVowel = c;
                i++;
                continue;
            }
            // consonant
            if (i + 1 < n && isVowel(s.charAt(i + 1))) {
                // CV onset: two half-units
                char v = s.charAt(i + 1);
                units.add("" + c + v + '-');   // left half  "Cv-"
                units.add("-" + c + v);        // right half "-Cv"
                prevVowel = v;
                i += 2;
            } else if (prevVowel != 0) {
                // coda consonant after a vowel
                units.add("-" + prevVowel + c);
                i++;
            } else {
                // cluster consonant before another consonant (or word start)
                units.add("" + c);
                i++;
            }
        }
        return units;
    }

    /** Lithuanian rising/falling diphthongs that pair into one glide unit. */
    private static boolean isDiphthong(char a, char b) {
        // ie, uo, au, ai, ei, ui, oi, и т.д. — vowel followed by i/u glide, or
        // the ie/uo rising diphthongs.
        if ((a == 'i' && b == 'e') || (a == 'u' && b == 'o')) return true;      // ie, uo
        if (b == 'i' || b == 'u') return a == 'a' || a == 'e' || a == 'o' || a == 'u'; // ai,ei,oi,au,eu,ou
        return false;
    }
}
