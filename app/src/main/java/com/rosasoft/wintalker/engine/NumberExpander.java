package com.rosasoft.wintalker.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Expands Arabic numerals in text into spoken Lithuanian words.
 *
 * <p>This is a data-driven port of the original engine's number reader
 * (translate.decomp.txt root.24 / root.24.1). The spoken word fragments are NOT
 * hard-coded here: they are read from the voice database's "N..." bucket entries
 * (see {@link VoiceDatabase#numberBuckets()}), which the original Lua builds keys
 * into and concatenates. Each entry is named {@code N<value>+<scale>[R]} where
 * {@code scale} is the digit position counted from the right (0 = units, 1 = tens,
 * 2 = hundreds, 3 = thousands, 6 = millions, 9 = billions) and {@code value} is the
 * number spoken at that group. A bare {@code N<d>} entry reads a single-digit
 * number. Examples recovered from Gintaras.dta:
 * <pre>
 *   N0      -> "nulis"          N1+0  -> "vienas"
 *   N10+3R  -> "dešimt tūkstančių"   N1+3R -> "vienas tūkstantis"
 *   N0+3R   -> "tūkstančių"     N1+6R -> "vienas milijonas"
 * </pre>
 *
 * <h3>root.24.1 key construction (ported)</h3>
 * The original slides a window over the digit string trying, for each position,
 * a two-digit "teen" key first (10..19 fused at the units position of the triple)
 * then a one-digit key, marking consumed positions, and finally emitting the
 * group scale word ({@code N0+<3k>R} = tūkstančių/milijonų/...) when a triple's
 * units digit is 0 but the triple as a whole is non-zero. We reproduce exactly
 * that behaviour below.
 *
 * <h3>numgroup modes</h3>
 * The "numgroup" setting (arrays.xml numValues) chooses how a run of digits is
 * read: 1/2/3 = read the digits in groups of that many (digit-by-digit, pairs,
 * triples), 16 = the synthesizer's default full-cardinal expansion above. A
 * leading '-' becomes "minus"; a decimal separator (',' or '.') between digit
 * runs becomes "kablelis" followed by the fractional digits read one-by-one.
 *
 * <h3>Limitation</h3>
 * Lithuanian full case/gender agreement is not modelled: the fragments are emitted
 * in the nominative forms the data provides (the same limitation the original
 * data-driven reader has — it ships a single set of forms per N-entry).
 */
public final class NumberExpander {

    /** Spoken digit names for the digit-by-digit / pairs / triples grouping modes
     *  and the decimal fraction; index 0 = "nulis". Sourced once from the bare
     *  {@code N0..N9} bucket entries so they stay in sync with the voice data. */
    private final String[] digitWords = new String[10];
    private final Map<String, List<String>> buckets;

    public NumberExpander(VoiceDatabase db) {
        this.buckets = db.numberBuckets();
        for (int d = 0; d < 10; d++) {
            List<String> w = buckets.get("N" + d);
            digitWords[d] = (w != null && !w.isEmpty()) ? w.get(0) : "";
        }
    }

    /** numgroup pref values (arrays.xml): full cardinal expansion. */
    public static final int NUMGROUP_FULL = 16;

    /**
     * Replace every numeric token in {@code text} with its spoken Lithuanian
     * words, using the given numgroup mode. Non-digit characters are copied
     * through unchanged so the caller's tokenizer / punctuation handling is
     * unaffected.
     */
    public String expand(String text, int numgroup) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (isDigit(c)) {
                int j = i;
                while (j < n && isDigit(text.charAt(j))) j++;
                // optional decimal part: <digits>[.,]<digits>
                int intStart = i, intEnd = j;
                int fracStart = -1, fracEnd = -1;
                char sep = 0;
                if (j < n && (text.charAt(j) == ',' || text.charAt(j) == '.')
                        && j + 1 < n && isDigit(text.charAt(j + 1))) {
                    sep = text.charAt(j);
                    fracStart = j + 1;
                    fracEnd = fracStart;
                    while (fracEnd < n && isDigit(text.charAt(fracEnd))) fracEnd++;
                }
                // a leading '-' immediately before the number reads as "minus"
                boolean neg = (intStart > 0 && text.charAt(intStart - 1) == '-')
                        && (intStart == 1 || !Character.isLetterOrDigit(text.charAt(intStart - 2)));
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                if (neg) {
                    // drop the '-' we already emitted into out, then say "minus"
                    trimTrailingMinus(out);
                    out.append("minus ");
                }
                out.append(String.join(" ",
                        readDigits(text.substring(intStart, intEnd), numgroup)));
                if (fracStart >= 0) {
                    out.append(' ').append("kablelis"); // ',' or '.' -> "kablelis"
                    for (int k = fracStart; k < fracEnd; k++)
                        out.append(' ').append(digitWords[text.charAt(k) - '0']);
                    j = fracEnd;
                }
                out.append(' ');
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString().trim();
    }

    /** Backwards-compatible default: full cardinal expansion. */
    public String expand(String text) {
        return expand(text, NUMGROUP_FULL);
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private static void trimTrailingMinus(StringBuilder sb) {
        int e = sb.length();
        while (e > 0 && sb.charAt(e - 1) == ' ') e--;
        if (e > 0 && sb.charAt(e - 1) == '-') sb.delete(e - 1, sb.length());
    }

    /** Read a run of digits according to the numgroup mode. */
    List<String> readDigits(String digits, int numgroup) {
        if (numgroup == 1 || numgroup == 2 || numgroup == 3) {
            return readGrouped(digits, numgroup);
        }
        return readCardinal(digits); // NUMGROUP_FULL (16) and any other value
    }

    /** Digit-by-digit (1), pairs (2) or triples (3): each chunk read as a small
     *  cardinal, left to right. e.g. numgroup=1 "0123" -> nulis vienas du trys. */
    private List<String> readGrouped(String digits, int size) {
        List<String> out = new ArrayList<>();
        for (int p = 0; p < digits.length(); p += size) {
            String chunk = digits.substring(p, Math.min(p + size, digits.length()));
            if (size == 1) {
                out.add(digitWords[chunk.charAt(0) - '0']);
            } else {
                out.addAll(readCardinal(chunk));
            }
        }
        return out;
    }

    /**
     * Full cardinal expansion — the faithful port of root.24.1's key construction.
     * Returns the ordered spoken word fragments for the whole integer.
     */
    List<String> readCardinal(String raw) {
        // strip leading zeros (but keep a single "0")
        int s = 0;
        while (s < raw.length() - 1 && raw.charAt(s) == '0') s++;
        String digits = raw.substring(s);
        int len = digits.length();
        List<String> out = new ArrayList<>();
        if (len == 1) {
            List<String> w = buckets.get("N" + digits);
            if (w != null) out.addAll(w);
            return out;
        }
        int i = 0;
        while (i < len) {
            int scale = len - 1 - i;     // position from the right
            int d = digits.charAt(i) - '0';
            // teen fusion: a '1' in the tens position of a triple (scale%3==1)
            // combines with the following units digit into 10..19.
            if (scale % 3 == 1 && d == 1 && i + 1 < len) {
                int dd = (digits.charAt(i) - '0') * 10 + (digits.charAt(i + 1) - '0');
                List<String> w = lookup(dd, scale - 1);
                if (w != null) { out.addAll(w); i += 2; continue; }
            }
            if (d != 0) {
                List<String> w = lookup(d, scale);
                if (w != null) out.addAll(w);
                else {
                    // No scale word for this position (tens/hundreds of billions and
                    // beyond — the .dta tops out at scale 9). The original
                    // (translate root.24.1 lines 0122-0145) fills any unmatched digit
                    // with its bare single-digit name N{d}, so no digit is dropped.
                    List<String> bare = buckets.get("N" + d);
                    if (bare != null) out.addAll(bare);
                }
            } else if (scale % 3 == 0 && scale > 0 && tripleNonZero(digits, len, scale)) {
                // group scale word (N0+<3k>R = tūkstančių / milijonų / ...)
                List<String> w = lookup(0, scale);
                if (w != null) out.addAll(w);
            }
            i++;
        }
        return out;
    }

    /** Is the triple covering scales {@code base, base+1, base+2} non-zero? */
    private static boolean tripleNonZero(String digits, int len, int base) {
        for (int sc = base; sc < base + 3; sc++) {
            int idx = len - 1 - sc;
            if (idx >= 0 && idx < len && digits.charAt(idx) != '0') return true;
        }
        return false;
    }

    /** Try {@code N<value>+<scale>R} then {@code N<value>+<scale>}. */
    private List<String> lookup(int value, int scale) {
        List<String> w = buckets.get("N" + value + "+" + scale + "R");
        if (w == null) w = buckets.get("N" + value + "+" + scale);
        return w;
    }
}
