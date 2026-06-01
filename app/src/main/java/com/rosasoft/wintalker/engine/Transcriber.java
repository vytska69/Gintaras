package com.rosasoft.wintalker.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Lithuanian grapheme→phoneme transcriber — pure Java port of the verified C
 * reimplementation (engine/transcr_c), which matches the original libtranscr.so
 * 100% over 515 words. No native code, so it runs on any ABI incl. arm64-v8a.
 *
 * Input is a cp1257-uppercased word (see normalise()); output is the phoneme
 * token list the synthesiser consumes, with '_' word boundaries.
 */
public final class Transcriber {

    // cp1257 Lithuanian letters used as constants
    private static final int A_OG = 0xc0, C_HK = 0xc8, E_OG = 0xc6, E_DOT = 0xcb,
            I_OG = 0xc1, S_HK = 0xd0, U_OG = 0xd8, U_MAC = 0xdb, Z_HK = 0xde;

    private static boolean isFrontVowel(int c) {
        return c == 'I' || c == 'Y' || c == I_OG || c == 'E' || c == E_DOT || c == E_OG;
    }

    private static String consVoiced(int c) {
        switch (c) {
            case 'B': return "b"; case 'C': return "ts"; case 'D': return "d";
            case 'F': return "f"; case 'G': return "g"; case 'H': return "h";
            case 'K': return "k"; case 'L': return "l"; case 'M': return "m";
            case 'N': return "n"; case 'P': return "p"; case 'R': return "r";
            case 'S': return "s"; case 'T': return "t"; case 'V': return "v";
            case 'Z': return "z"; case 'J': return "j'";
            case C_HK: return "tS"; case S_HK: return "S"; case Z_HK: return "Z";
            default: return null;
        }
    }

    private static String consDevoiced(int c) {
        switch (c) {
            case 'B': return "p"; case 'D': return "t"; case 'G': return "k";
            case 'Z': return "s"; case Z_HK: return "S"; case S_HK: return "S";
            case 'C': return "ts"; case C_HK: return "tS";
            default: return consVoiced(c);
        }
    }

    private static String vowelPhoneme(int c) {
        switch (c) {
            case 'A': case A_OG: return "aA";
            case 'E': case E_OG: return "eA";
            case E_DOT: return "eE";
            case 'I': return "i"; case I_OG: return "iI"; case 'Y': return "iI";
            case 'O': return "oO";
            case 'U': return "u"; case U_MAC: return "uU"; case U_OG: return "uU";
            default: return null;
        }
    }

    /** Regressive palatalisation through palatalised consonant chains. */
    private static boolean isPalatalised(int[] w, int n, int i) {
        if (i + 1 >= n) return false;
        int nx = w[i + 1];
        if (isFrontVowel(nx) || nx == 'I' || nx == 'J') return true;
        if (vowelPhoneme(nx) != null) return false;
        if (consVoiced(nx) != null) return isPalatalised(w, n, i + 1);
        return false;
    }

    /** Transcribe a cp1257-uppercased word into phoneme tokens incl. '_' bounds. */
    public static List<String> transcribe(int[] w, int n) {
        List<String> out = new ArrayList<>();
        out.add("_");
        for (int i = 0; i < n; i++) {
            int c = w[i];

            // CH → x
            if (c == 'C' && i + 1 < n && w[i + 1] == 'H') {
                out.add(isPalatalised(w, n, i + 1) ? "x'" : "x");
                i++; continue;
            }
            // DŽ → dZ, DZ → dz
            if (c == 'D' && i + 1 < n && (w[i + 1] == Z_HK || w[i + 1] == 'Z')) {
                String base = (w[i + 1] == Z_HK) ? "dZ" : "dz";
                out.add(isPalatalised(w, n, i + 1) ? base + "'" : base);
                i++; continue;
            }
            // softness 'i' before a back vowel is absorbed
            if (c == 'I' && !(i > 0 && vowelPhoneme(w[i - 1]) != null) && i + 1 < n) {
                int nx = w[i + 1];
                boolean back = (nx == 'A' || nx == A_OG || nx == 'O' || nx == 'U'
                        || nx == U_OG || nx == U_MAC);
                if (back) continue;
            }
            // diphthong glides after short vowels
            if (i > 0) {
                int pv = w[i - 1];
                boolean shortV = (pv == 'A' || pv == 'E' || pv == 'O' || pv == 'U');
                if (shortV) {
                    if (c == 'I') { out.add("J"); continue; }
                    if (c == 'U' && pv != 'U') { out.add("W"); continue; }
                }
            }

            String vp = vowelPhoneme(c);
            if (vp != null) {
                if ((c == 'A' || c == A_OG) && i > 0
                        && ((w[i - 1] == 'I' && !(i >= 2 && vowelPhoneme(w[i - 2]) != null))
                            || w[i - 1] == 'J')) {
                    out.add("eA"); continue;
                }
                out.add(vp); continue;
            }

            // sonorant uppercase positional variants
            {
                boolean nextIsCons = (i + 1 >= n) || (vowelPhoneme(w[i + 1]) == null && w[i + 1] != 'I');
                boolean prevIsCons = (i > 0) && (vowelPhoneme(w[i - 1]) == null);
                boolean afterVowel = (i > 0) && vowelPhoneme(w[i - 1]) != null;
                boolean coda = afterVowel && nextIsCons;
                boolean prevObstruent = prevIsCons
                        && !(w[i-1]=='L'||w[i-1]=='M'||w[i-1]=='N'||w[i-1]=='R'||w[i-1]=='J'||w[i-1]=='V');
                boolean pal = isPalatalised(w, n, i);
                boolean sonUp = coda || prevObstruent;
                boolean rUp = coda || prevIsCons || (nextIsCons && i + 1 < n && consVoiced(w[i+1]) != null);
                String up = null;
                if (c == 'L' && sonUp) up = "L";
                else if (c == 'M' && sonUp) up = "M";
                else if (c == 'N' && sonUp) up = "N";
                else if (c == 'R' && rUp) up = "R";
                if (up != null) { out.add(pal ? up + "'" : up); continue; }
            }

            String cp = consVoiced(c);
            if (cp == null) continue;
            boolean isFinal = (i == n - 1);
            String use = cp;
            if (isFinal) {
                use = consDevoiced(c);
            } else {
                int nx = w[i + 1];
                boolean nxUnvoiced = (nx=='P'||nx=='T'||nx=='K'||nx=='S'||nx==S_HK||nx=='C'||nx==C_HK||nx=='F'||nx=='H');
                boolean nxVoiced = (nx=='B'||nx=='D'||nx=='G'||nx=='Z'||nx==Z_HK);
                if (nxUnvoiced) {
                    use = consDevoiced(c);
                } else if (nxVoiced) {
                    switch (c) {
                        case 'S': use = "z"; break;
                        case S_HK: use = "Z"; break;
                        case 'K': use = "g"; break;
                        case 'P': use = "b"; break;
                        case 'T': use = "d"; break;
                        case 'C': use = "dz"; break;
                        case C_HK: use = "dZ"; break;
                        default: use = cp; break;
                    }
                }
            }
            boolean palatal = isPalatalised(w, n, i);
            if (palatal && !use.endsWith("'")) use = use + "'";
            out.add(use);
        }
        out.add("_");
        return out;
    }

    /** Uppercase a Unicode Lithuanian string to a cp1257 code array for transcribe(). */
    public static int[] normalise(String text) {
        int[] w = new int[text.length()];
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = Character.toUpperCase(text.charAt(i));
            int cp;
            switch (ch) {
                case 'Ą': cp = A_OG; break;  // Ą
                case 'Č': cp = C_HK; break;  // Č
                case 'Ę': cp = E_OG; break;  // Ę
                case 'Ė': cp = E_DOT; break; // Ė
                case 'Į': cp = I_OG; break;  // Į
                case 'Š': cp = S_HK; break;  // Š
                case 'Ų': cp = U_OG; break;  // Ų
                case 'Ū': cp = U_MAC; break; // Ū
                case 'Ž': cp = Z_HK; break;  // Ž
                default:
                    if (ch >= 'A' && ch <= 'Z') cp = ch;
                    else if (ch == ' ' || ch == '\t') continue;
                    else cp = -1;
            }
            if (cp > 0) w[n++] = cp;
        }
        int[] r = new int[n];
        System.arraycopy(w, 0, r, 0, n);
        return r;
    }
}
