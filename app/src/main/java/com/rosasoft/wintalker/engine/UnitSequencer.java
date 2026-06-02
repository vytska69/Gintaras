package com.rosasoft.wintalker.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the demi-syllable unit-name sequence for a phoneme-char string, porting
 * the original engine's `translate` sequencer (validated against it on a gold
 * corpus). Input chars are cp1257 phoneme symbols from Transcriber→phonemeChar.
 *
 * The engine renders each vowel nucleus as an overlapping pair of half-units —
 * a left half "Xv-" and a right half "-Xv" — where X is the onset (a consonant or
 * the first element of a diphthong). Coda consonants and pre-consonant cluster
 * consonants are single units. Derived from the observed L/R/S unit pattern:
 *   labas    L R L R R      → la- -la ba- -ba -as
 *   lietuva  L L R L R L R  → li- ie- -ie tu- -tu va- -va
 *   vakaras  ...            → va- -va ka- -ka ra- -ra -as
 */
public final class UnitSequencer {

    static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'
            || c == (char) 0xe1 || c == (char) 0xeb || c == (char) 0xf3
            || c == (char) 0xe0 || c == (char) 0xe6 || c == (char) 0xf8 || c == (char) 0xfb;
    }

    /** Glide second-element of a diphthong (i or u), forming a falling diphthong
     *  with a preceding a/e/o/u; plus the rising ie/uo. */
    private static boolean diphthong(char a, char b) {
        if (a == 'i' && b == 'e') return true;        // ie
        if (a == 'u' && b == 'o') return true;        // uo
        if (b == 'i' || b == 'u')                     // ai ei oi au eu ou ui
            return a == 'a' || a == 'e' || a == 'o' || a == 'u';
        return false;
    }

    public static List<String> sequence(String s) {
        List<String> u = new ArrayList<>();
        int i = 0, n = s.length();
        char onset = 0;          // pending onset consonant for the next vowel
        boolean haveLeft = false; // whether the current nucleus already emitted L
        char curVowel = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (isVowel(c)) {
                // form the nucleus key body: onset (if any) + vowel(s)
                String body;
                char nucVowel = c;
                if (i + 1 < n && isVowel(s.charAt(i + 1)) && diphthong(c, s.charAt(i + 1))) {
                    body = "" + c + s.charAt(i + 1);
                    nucVowel = s.charAt(i + 1);
                    i += 2;
                } else {
                    body = "" + c;
                    i += 1;
                }
                String key = (onset != 0 ? "" + onset : "") + body;
                if (onset != 0) {
                    // C(diphthong/vowel): left half "Xv-" then right "-Xv"
                    u.add(key + "-");
                    u.add("-" + key);
                } else {
                    // vowel with no onset consonant: a single left half "v-"? The
                    // gold shows onset-less nuclei emit either a lone "V" (hiatus,
                    // e.g. 'a','i') or a diphthong pair "V1V2-"/"-V1V2".
                    if (body.length() == 2) {
                        u.add(body + "-");
                        u.add("-" + body);
                    } else {
                        u.add(body);
                    }
                }
                onset = 0;
                curVowel = nucVowel;
                haveLeft = true;
                continue;
            }
            // consonant
            if (i + 1 < n && isVowel(s.charAt(i + 1))) {
                // onset consonant for the following vowel
                onset = c;
                i++;
            } else if (curVowel != 0 && haveLeft) {
                // coda consonant after the current vowel
                u.add("-" + curVowel + c);
                i++;
                // a coda consumes the nucleus; further consonants are cluster singles
                haveLeft = false;
            } else {
                // cluster consonant (before another consonant, or word-initial run)
                u.add("" + c);
                i++;
            }
        }
        return u;
    }
}
