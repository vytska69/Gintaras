package com.rosasoft.wintalker.engine;

/**
 * Expands Arabic numerals in text into spoken Lithuanian words, so the
 * transcriber (which only handles letters) can voice numbers. Mirrors the role of
 * the original engine's VisasSkaicius/PradApdZod number handling.
 *
 * Handles cardinal integers 0..999,999,999 (and each digit beyond as a fallback).
 * Uses nominative masculine forms — adequate for intelligible read-out; full case
 * agreement is a future refinement.
 */
public final class NumberExpander {

    private static final String[] ONES = {
        "", "vienas", "du", "trys", "keturi", "penki", "šeši", "septyni",
        "aštuoni", "devyni"
    };
    private static final String[] TEENS = {
        "dešimt", "vienuolika", "dvylika", "trylika", "keturiolika", "penkiolika",
        "šešiolika", "septyniolika", "aštuoniolika", "devyniolika"
    };
    private static final String[] TENS = {
        "", "dešimt", "dvidešimt", "trisdešimt", "keturiasdešimt", "penkiasdešimt",
        "šešiasdešimt", "septyniasdešimt", "aštuoniasdešimt", "devyniasdešimt"
    };

    /** Replace every run of digits in `text` with its Lithuanian words. */
    public static String expand(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                int j = i;
                while (j < n && text.charAt(j) >= '0' && text.charAt(j) <= '9') j++;
                String digits = text.substring(i, j);
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                out.append(numberToWords(digits));
                out.append(' ');
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString().trim();
    }

    /** Convert a digit string to Lithuanian words (long numbers fall back to
     *  digit-by-digit if they exceed the supported range). */
    static String numberToWords(String digits) {
        // strip leading zeros but keep a single "nulis" for all-zero
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return spellDigits(digits);
        }
        if (value == 0) return "nulis";
        if (value > 999_999_999L) return spellDigits(digits);
        return below1e9(value).trim();
    }

    private static String spellDigits(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < digits.length(); k++) {
            int d = digits.charAt(k) - '0';
            if (sb.length() > 0) sb.append(' ');
            sb.append(d == 0 ? "nulis" : ONES[d]);
        }
        return sb.toString();
    }

    private static String below1e9(long v) {
        StringBuilder sb = new StringBuilder();
        long millions = v / 1_000_000;
        long thousands = (v / 1000) % 1000;
        long rest = v % 1000;
        if (millions > 0) {
            sb.append(below1000(millions)).append(' ')
              .append(unitWord(millions, "milijonas", "milijonai", "milijonų")).append(' ');
        }
        if (thousands > 0) {
            // "1000" is just "tūkstantis", not "vienas tūkstantis"
            if (thousands != 1) sb.append(below1000(thousands)).append(' ');
            sb.append(unitWord(thousands, "tūkstantis", "tūkstančiai", "tūkstančių")).append(' ');
        }
        if (rest > 0) sb.append(below1000(rest));
        return sb.toString();
    }

    private static String below1000(long v) {
        StringBuilder sb = new StringBuilder();
        long h = v / 100;
        long rem = v % 100;
        if (h > 0) {
            // "100" is just "šimtas", not "vienas šimtas"
            if (h > 1) sb.append(ONES[(int) h]).append(' ');
            sb.append(unitWord(h, "šimtas", "šimtai", "šimtų")).append(' ');
        }
        if (rem >= 10 && rem < 20) {
            sb.append(TEENS[(int) (rem - 10)]);
        } else {
            long t = rem / 10, o = rem % 10;
            if (t > 0) sb.append(TENS[(int) t]);
            if (o > 0) { if (t > 0) sb.append(' '); sb.append(ONES[(int) o]); }
        }
        return sb.toString().trim();
    }

    /** Lithuanian unit declension by the last digit(s): 1→sing, 2-9→plural,
     *  0 and 11-19→genitive plural. */
    private static String unitWord(long count, String one, String few, String many) {
        long mod100 = count % 100, mod10 = count % 10;
        if (mod10 == 1 && mod100 != 11) return one;
        if (mod10 >= 2 && mod10 <= 9 && !(mod100 >= 11 && mod100 <= 19)) return few;
        return many;
    }
}
