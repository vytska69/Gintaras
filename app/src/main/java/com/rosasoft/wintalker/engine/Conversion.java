package com.rosasoft.wintalker.engine;

import java.util.List;

/**
 * Literal port of the original engine's {@code trans} conversion pipeline:
 * phoneme tokens (from {@link Transcriber}, == libtranscr KircTranskr output)
 * → the UTF-16LE unit-name code-unit string consumed by {@code translate}.
 *
 * <p>Source: engine/decompiled/trans.decomp.txt. The exported {@code conversion}
 * is {@code root.37}; its heavy lifting is {@code root.8}; the final code-unit
 * mapping is {@code root.37.11}.
 *
 * <p>{@code conversion} (root.37, lines 0034-0053): run KircTranskr to get a
 * newline-joined phoneme string, then {@code R5 = root.8(phonemeString)}, then
 * {@code return R5:gsub(".", root.37.11)} which maps each result byte to a
 * UTF-16 code unit.
 *
 * <p>root.8 (lines 0127-0217) operates on the phoneme string:
 * <ol>
 *   <li>{@code gsub("[_0123456789]", "")} — drop word-boundary '_' and digits.</li>
 *   <li>{@code gsub("\n", " ")} — newline token separators become spaces.</li>
 *   <li>substitution pass 1: Cs( Cmt(2,mem) + Cmt(1,mem) + 1 )^0 — at each
 *       position try a 2-char phoneme key in the SUBST map (root.8 R3), else a
 *       1-char key, else pass the char through. A matched key is replaced by its
 *       SUBST value (root.8.29 returns true + value).</li>
 *   <li>{@code gsub(".", R1)} — lowercase single uppercase letters (LOWER map).</li>
 *   <li>substitution pass 2: same Cs pattern again (so e.g. "eE"→lower→"ee"→235).</li>
 *   <li>palatal pass: Cs( C("' ") * C(vowel (.)? " ")^-1 / root.8.37 + 1 )^0 —
 *       move the soft-apostrophe marker to AFTER the vowel that follows the
 *       palatalised consonant, emitting it as '|'. (root.8.37, lines 0001-0020.)</li>
 *   <li>{@code gsub(" ", "")} — remove all spaces.</li>
 * </ol>
 * The byte values below are the literal {@code string.char(N)} constants from the
 * decompiled source; the final UTF-16 mapping (root.37.11 via root R5, lines
 * 0105-0141) converts the six special bytes to their U+01xx code units and every
 * other byte {@code b} to {@code U+00bb} (low byte = b, high byte = 0).
 */
public final class Conversion {

    private Conversion() {}

    /**
     * SUBST map (trans root.8 R3, lines 0036-0126): phoneme key → cp1257-ish
     * byte sequence. Two-char keys are tried before one-char keys at each
     * position (Cmt(2) before Cmt(1)).
     */
    private static String subst(String key) {
        switch (key) {
            // two-char keys (lines 0041-0096)
            case "tS": return str(232);
            case "ts": return str(99);             // 'c'
            case "uu": return str(248);
            case "aa": return str(224);
            case "ii": return str(225);
            case "oo": return str(243);
            case "ee": return str(235);
            case "eA": return str(230);
            case "Ea": return str(230);
            case "ea": return str(230);
            case "dZ": return str(100) + str(254); // 'd' + byte 254
            // one-char keys (lines 0101-0126)
            case "S":  return str(240);
            case "Z":  return str(254);
            case "w":  return str(117);            // 'u'
            case "W":  return str(248);
            case "x":  return str(99) + str(104);  // "ch"
            default:   return null;
        }
    }

    /** LOWER map (trans root.8 K[-1], line 0206): uppercase ASCII → lowercase. */
    private static char lower(char c) {
        if (c >= 'A' && c <= 'Z') return Character.toLowerCase(c);
        return c;
    }

    /** Vowel set (trans root.8 R2, lines 0004-0035): "aeiou" + bytes
     *  248,224,225,243,235,230. Used by the palatal pass to find the vowel that
     *  follows a palatalised consonant. */
    private static boolean isVowelByte(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'
            || c == 248 || c == 224 || c == 225 || c == 243 || c == 235 || c == 230;
    }

    private static String str(int b) { return String.valueOf((char) b); }

    /**
     * Convert a phoneme token list (e.g. ["_","l'","i","eA",...,"_"]) into the
     * UTF-16LE conversion string. The token list is exactly KircTranskr's output
     * (newline-joined); we join with "\n" to reproduce the original input to
     * root.8.
     */
    public static String convert(List<String> phonemeTokens) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < phonemeTokens.size(); i++) {
            if (i > 0) joined.append('\n');
            joined.append(phonemeTokens.get(i));
        }
        return convert(joined.toString());
    }

    /** Core port of root.8 + the root.37.11 final mapping. Input is the raw
     *  newline-joined phoneme string. Output is the UTF-16LE code-unit string. */
    public static String convert(String phonemeString) {
        // root.8 line 0131: gsub("[_0123456789]", "")
        StringBuilder b1 = new StringBuilder();
        for (int i = 0; i < phonemeString.length(); i++) {
            char c = phonemeString.charAt(i);
            if (c == '_' || (c >= '0' && c <= '9')) continue;
            b1.append(c);
        }
        // root.8 line 0137: gsub("\n", " ")
        String s = b1.toString().replace('\n', ' ');

        // root.8 lines 0156-0160: substitution pass 1
        s = substPass(s);
        // root.8 lines 0162-0166: gsub(".", LOWER)
        StringBuilder bl = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) bl.append(lower(s.charAt(i)));
        s = bl.toString();
        // root.8 lines 0168-0171: substitution pass 2
        s = substPass(s);
        // root.8 lines 0173-0209: palatal reorder pass
        s = palatalPass(s);
        // root.8 lines 0211-0214: gsub(" ", "")
        s = s.replace(" ", "");

        // root.37.11 (final gsub of root.37): map each byte to a code unit.
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            out.append(finalMap(s.charAt(i)));
        }
        return out.toString();
    }

    /**
     * root.8 substitution Cs pattern (lines 0143-0160): at each position, try the
     * 2-char window as a SUBST key first, else the 1-char window, else pass one
     * char through. A key match is replaced by its SUBST value.
     */
    private static String substPass(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            if (i + 2 <= n) {
                String two = s.substring(i, i + 2);
                String r = subst(two);
                if (r != null) { out.append(r); i += 2; continue; }
            }
            String one = s.substring(i, i + 1);
            String r = subst(one);
            if (r != null) { out.append(r); i += 1; continue; }
            out.append(s.charAt(i));
            i += 1;
        }
        return out.toString();
    }

    /**
     * root.8 palatal Cs pattern (lines 0173-0209) + root.8.37 (lines 0001-0020).
     * Matches the literal sequence "' " (apostrophe + space). Optionally also
     * grabs the FOLLOWING token if it begins with a vowel byte: C( S(vowel) .
     * (1-P(" "))^-1 . " " ) — i.e. [vowel, optional one non-space char, space].
     *
     * root.8.37(cap1="' ", cap2):
     *   if cap2:  return cap1:gsub("'"," ")  ..  cap2:gsub(" ","|")
     *             → "' " becomes "  " (two spaces); cap2's trailing space → "|".
     *   else:     return cap1:gsub("'","|")  → "| " (apostrophe → '|').
     * The trailing gsub(" ","") in root.8 then drops all the spaces, leaving the
     * '|' marker positioned after the vowel that follows the palatal consonant.
     */
    private static String palatalPass(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            // try to match "' " at position i
            if (s.charAt(i) == '\'' && i + 1 < n && s.charAt(i + 1) == ' ') {
                // cap1 = "' "; try optional cap2 = vowel (.)? " " starting at i+2
                int j = i + 2;
                String cap2 = matchVowelToken(s, j);
                if (cap2 != null) {
                    // cap1:gsub("'"," ") = "  "; cap2:gsub(" ","|")
                    out.append("  ");
                    out.append(cap2.replace(" ", "|"));
                    i = j + cap2.length();
                } else {
                    // cap1:gsub("'","|") = "| "
                    out.append("| ");
                    i += 2;
                }
                continue;
            }
            out.append(s.charAt(i));
            i += 1;
        }
        return out.toString();
    }

    /**
     * Match the optional cap2 capture: S(vowel) . (1 - P(" "))^-1 . " "
     * (a vowel byte, then at most one non-space char, then a space). Returns the
     * matched substring (including its trailing space) or null if no match.
     */
    private static String matchVowelToken(String s, int start) {
        int n = s.length();
        if (start >= n) return null;
        if (!isVowelByte(s.charAt(start))) return null;
        int p = start + 1;
        // (1 - P(" "))^-1 : optionally consume one non-space char
        if (p < n && s.charAt(p) != ' ') p++;
        // P(" ") : require a space
        if (p < n && s.charAt(p) == ' ') {
            return s.substring(start, p + 1);
        }
        return null;
    }

    /**
     * root.37.11 / root R5 final byte→code-unit map (root lines 0105-0141):
     * the six special bytes become U+01xx unit-name code units; everything else
     * becomes U+00bb (the byte as the low half, high byte 0).
     */
    private static char finalMap(char b) {
        switch (b) {
            case 232: return (char) 0x010D; // č  (root line 0110: char(232) -> "\013\001")
            case 248: return (char) 0x0159; // ū  (line 0116: char(248) -> "Y\001")
            case 224: return (char) 0x0155; // ā  (line 0122: char(224) -> "U\001")
            case 230: return (char) 0x0107; // ę  (line 0128: char(230) -> "\007\001")
            case 240: return (char) 0x0111; // š  (line 0134: char(240) -> "\017\001")
            case 254: return (char) 0x0163; // ž  (line 0140: char(254) -> "c\001")
            default:  return (char) (b & 0xFF);
        }
    }
}
