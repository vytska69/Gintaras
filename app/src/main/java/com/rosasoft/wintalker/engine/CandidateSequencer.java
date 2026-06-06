package com.rosasoft.wintalker.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Literal port of the original engine's {@code translate} unit selector
 * (engine/decompiled/translate.decomp.txt root.22 and its Cmt callbacks
 * root.22.2 … root.22.6).
 *
 * <p>The original is an LPeg grammar matched over the conversion code-unit string.
 * It is an ordered-choice repetition: {@code Ct( (TERMS + P(2))^0 )}. At each
 * position it tries an ordered list of grammar TERMS; each TERM is a
 * {@code Cmt(<capture pattern>, <callback>)} whose callback builds a candidate
 * demi-syllable unit name and succeeds iff that name exists in the voice table.
 * The first succeeding TERM fires: its captured key is collected and its
 * {@code C()} capture is consumed (the {@code B()} look-behind, {@code #} look-
 * ahead and {@code Cc} constant captures do not consume input). If no TERM
 * matches, one code unit is consumed silently ({@code P(2)} = 2 bytes = 1 UTF-16
 * code unit). The collected keys, in order, are the unit sequence.
 *
 * <p>This replaces the previous heuristic reconstruction. Because selection is
 * gated entirely on voice-table membership and the key formulas + the choice
 * order are taken verbatim from the decompiled callbacks/grammar, the output is
 * byte-identical to the live original (validated against the running translate;
 * see tools/literal_port/SeqVal.java).
 *
 * <h3>Cmt callback key formulas</h3>
 * In LPeg, {@code Cmt(C(P(2)), fn)} consumes one code unit FIRST, captures it as
 * {@code cap1}, then calls {@code fn} with the position AFTER the capture; so the
 * callback's {@code sub(pos, …)} windows read the units FOLLOWING the capture and
 * {@code sub(pos-k, …)} read PRECEDING units. {@code u(p)} = the code unit at
 * 0-based position p (the captured unit is at the position {@code i} the term
 * fires from). {@code cap1 = u(i)} for the 1-unit terms.
 * <pre>
 *   root.22.2 (lines 0001-0031): cap1 .. u(i+1) .. "-"                  [needs i+1]
 *   root.22.3 (lines 0001-0033): cap1 .. u(i+1)u(i+2) .. "--"           [needs i+2]
 *   root.22.4 (lines 0001-0031): "-" .. u(i-1) .. cap1                  [needs i-1]
 *   root.22.5 (lines 0001-0057): "--" .. u(i-2)u(i-1) .. cap1           [needs i-2]
 *                                 palatal retry: if u(i-1) == "|" then
 *                                 "-" .. u(i-2) .. cap1
 *   root.22.6 (lines 0001-0028): (cap2 or "") .. cap1 .. (cap3 or "")   [bare]
 * </pre>
 * The constant captures {@code Cc("^")} (word start) and {@code Cc("$")} (word
 * end) supply cap2/cap3 to the 22.6 terms; the structural gates {@code (C - B)}
 * (word start) and {@code P(-P2)} (word end) restrict where a term may fire.
 */
public final class CandidateSequencer {

    private static final char DASH = (char) 0x2d; // "-\0"
    private static final char HAT  = (char) 0x5e; // "^\0" word-start marker
    private static final char DOL  = (char) 0x24; // "$\0" word-end marker
    private static final char PIPE = (char) 0x7c; // "|\0" palatal marker

    private final Map<String, VoiceDatabase.Entry> idx;

    public CandidateSequencer(VoiceDatabase db) {
        this.idx = db.diphoneIndex();
    }

    /** vtbl membership test (translate's {@code VOICES[key] ~= nil}). */
    private boolean has(String key) { return idx.containsKey(key); }

    /** One code unit at code-unit position {@code p}. Returns null if out of range
     *  (an out-of-range {@code string.sub} in Lua yields "", which makes the
     *  candidate key fail the lookup, equivalent to "no such term"). */
    private static String u(String s, int p) {
        if (p < 0 || p >= s.length()) return null;
        return String.valueOf(s.charAt(p));
    }

    /**
     * Produce the unit-name sequence for a conversion code-unit string, mirroring
     * the original {@code translate} grammar exactly. {@code s} is the output of
     * {@link Conversion} (code units, with any {@code '|'} palatal markers).
     */
    public List<String> sequence(String s) {
        List<String> out = new ArrayList<>();
        int n = s.length();   // length in code units
        int i = 0;            // current code-unit position (0-based)
        while (i < n) {
            int consumed = step(s, n, i, out);
            i += (consumed > 0) ? consumed : 1; // (TERMS + P(2)): fall back to 1
        }
        return out;
    }

    /**
     * Try the ordered-choice TERMS at position {@code i}. On the first hit, append
     * the key and return the number of code units consumed (the {@code C()} size).
     * Return 0 if no TERM matches (caller consumes one unit via the {@code P(2)}
     * fallback).
     *
     * <p>Choice order is the verbatim grammar sum (root.22 lines 0339-0359):
     * R30, R28, R29, R27, R26, R11, R10, R9, R24, R14, R25, R13, R12,
     * R23, R22, R21, R20, R19, R18, R17, R16, R15.
     */
    private int step(String s, int n, int i, List<String> out) {
        boolean atStart = (i == 0);
        boolean twoAvail = (i + 2 <= n);
        String two = twoAvail ? (u(s, i) + u(s, i + 1)) : null;

        // R30: whole-word 2-unit (22.6, (C2-B1)*P(-P2)*Cc("^")*Cc("$")): "^"+2u+"$".
        if (atStart && twoAvail && i + 2 == n) {
            String k = HAT + two + DOL;
            if (has(k)) { out.add(k); return 2; }
        }
        // R28: word-initial 2-unit bare (22.6, (C2-B1)*Cc("^")): "^" + 2u.
        if (atStart && twoAvail) {
            String k = HAT + two;
            if (has(k)) { out.add(k); return 2; }
        }
        // R29: word-final 2-unit bare (22.6, C2*P(-P2)*Cc("")*Cc("$")): 2u + "$".
        if (twoAvail && i + 2 == n) {
            String k = two + DOL;
            if (has(k)) { out.add(k); return 2; }
        }
        // R27: 2-unit bare (22.6, C2).
        if (twoAvail && has(two)) { out.add(two); return 2; }

        // R26: whole-word single unit (22.6, (C-B)*P(-P2)*Cc("^")*Cc("$")): "^"+u+"$".
        if (atStart && i + 1 == n) {
            String k = HAT + u(s, i) + DOL;
            if (has(k)) { out.add(k); return 1; }
        }
        // R11: word-initial left half (22.2 + Cc("^")): "^" + u(i) + u(i+1) + "-".
        if (atStart && i + 1 < n) {
            String k = HAT + u(s, i) + u(s, i + 1) + DASH;
            if (has(k)) { out.add(k); return 1; }
        }
        // R10: double-dash left half (22.3): u(i) + u(i+1)u(i+2) + "--".
        if (i + 3 <= n) {
            String k = u(s, i) + u(s, i + 1) + u(s, i + 2) + DASH + DASH;
            if (has(k)) { out.add(k); return 1; }
        }
        // R9: left half (22.2): u(i) + u(i+1) + "-".
        if (i + 1 < n) {
            String k = u(s, i) + u(s, i + 1) + DASH;
            if (has(k)) { out.add(k); return 1; }
        }
        // R24: word-initial single bare (22.6 + Cc("^")): "^" + u(i).
        if (atStart) {
            String k = HAT + u(s, i);
            if (has(k)) { out.add(k); return 1; }
        }
        // R14: word-final coda (22.4 + Cc("$")): "-" + u(i-1) + u(i) + "$".
        if (i + 1 == n && i - 1 >= 0) {
            String k = DASH + u(s, i - 1) + u(s, i) + DOL;
            if (has(k)) { out.add(k); return 1; }
        }
        // R25: word-final single bare (22.6 + Cc(""),Cc("$")): u(i) + "$".
        if (i + 1 == n) {
            String k = u(s, i) + DOL;
            if (has(k)) { out.add(k); return 1; }
        }
        // R13: double-dash right half over 2 prev, with palatal retry (22.5).
        if (i - 2 >= 0) {
            String k = DASH + "" + DASH + u(s, i - 2) + u(s, i - 1) + u(s, i);
            if (has(k)) { out.add(k); return 1; }
            // palatal retry: if the immediately previous unit is "|", skip it.
            if (PIPE == s.charAt(i - 1)) {
                String kp = DASH + u(s, i - 2) + u(s, i);
                if (has(kp)) { out.add(kp); return 1; }
            }
        }
        // R12: right half / coda (22.4): "-" + u(i-1) + u(i).
        if (i - 1 >= 0) {
            String k = DASH + u(s, i - 1) + u(s, i);
            if (has(k)) { out.add(k); return 1; }
        }
        // R23..R17: long bare units (22.6), 9..3 code units, longest first.
        for (int len = 9; len >= 3; len--) {
            if (i + len <= n) {
                String k = s.substring(i, i + len);
                if (has(k)) { out.add(k); return len; }
            }
        }
        // R16: 2-unit bare (22.6) — duplicate alternative of R27 in the grammar.
        if (twoAvail && has(two)) { out.add(two); return 2; }
        // R15: single bare unit (22.6): u(i).
        String single = u(s, i);
        if (single != null && has(single)) { out.add(single); return 1; }

        return 0; // no TERM matched: caller advances by the P(2) fallback
    }
}
