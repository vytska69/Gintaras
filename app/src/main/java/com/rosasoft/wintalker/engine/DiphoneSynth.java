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

    /** Engine base pitch period (prosody table P0[5] = 220 samples ≈ 100 Hz). The
     *  recorded voiced periods sit a little shorter (higher) than this; proto7
     *  steps them toward this base. */
    private static final int BASE_PERIOD = 220;

    /** P0.Prosody = P0[1]+P0[2] = 20 (voicesynth loadvoice root.43). */
    private static final int PROSODY = 20;
    /** P1.ProsodyChange = {0,50,10,20,50,160}: root.47 slews toward PC[6]=160 over
     *  the first half of a word, then PC[3]=10 over the second. */
    private static final int PC_EARLY = 160;   // PC[6]
    private static final int PC_LATE = 10;     // PC[3]

    private final VoiceDatabase db;
    private final Map<String, VoiceDatabase.Entry> index;
    private CandidateSequencer sequencer;

    public DiphoneSynth(VoiceDatabase db) {
        this.db = db;
        this.index = db.diphoneIndex();
    }

    /**
     * proto7 DSP, simplified: steady the pitch of voiced periods by linearly
     * interpolating each voiced period to the unit's mean voiced-period length;
     * leave unvoiced (consonant/noise) periods exactly as recorded. Returns plain
     * waveforms ready for concatenation.
     */
    private static List<short[]> smoothVoiced(List<VoiceDatabase.Period> ps) {
        List<short[]> out = new ArrayList<>(ps.size());
        // mean length of voiced periods in this unit
        long sum = 0; int nv = 0;
        for (VoiceDatabase.Period p : ps) if (p.voiced) { sum += p.samples.length; nv++; }
        int target = nv > 0 ? (int) (sum / nv) : 0;
        for (VoiceDatabase.Period p : ps) {
            if (p.voiced && target > 0 && p.samples.length != target) {
                out.add(lerpResample(p.samples, target));
            } else {
                out.add(p.samples);
            }
        }
        return out;
    }

    /** Smooth a unit seam in place, mirroring the original's join filter (root.45):
     *  two passes of 2-tap neighbour averaging centred on the seam, widening the
     *  window ±4 then ±8 samples. This rounds off the step discontinuity between
     *  concatenated demi-syllables without touching the unit interiors. */
    private static void smoothSeam(short[] pcm, int seam) {
        for (int pass = 1; pass <= 2; pass++) {
            int w = 4 * pass;
            int lo = Math.max(1, seam - w), hi = Math.min(pcm.length - 1, seam + w);
            for (int i = lo; i < hi; i++)
                pcm[i] = (short) ((pcm[i] + pcm[i + 1]) / 2);
        }
    }

    /** Median sample-length of the voiced periods in a sequence (0 if none). */
    private static int medianVoicedLength(List<VoiceDatabase.Period> ps) {
        List<Integer> v = new ArrayList<>();
        for (VoiceDatabase.Period p : ps) if (p.voiced) v.add(p.samples.length);
        if (v.isEmpty()) return 0;
        java.util.Collections.sort(v);
        return v.get(v.size() / 2);
    }

    /** Linear frame interpolation, exactly per voicesynth root.44: the j-th of `n`
     *  frames between data and data2 is out[i] = ((n-1-j)*data[i] + j*data2[i])/(n-1),
     *  over min(len(data),len(data2)) samples rounded down to even. j=0 -> data,
     *  j=n-1 -> data2. Used to render a count>1 voiced frame as `count` periods. */
    private static short[] interpFrame(short[] data, short[] data2, int n, int j) {
        int len = Math.min(data.length, data2.length);
        len &= ~1;                              // floor to even (root.44)
        if (len == 0) return data;
        if (n <= 1) return java.util.Arrays.copyOf(data, len);
        int w0 = (n - 1) - j, w1 = j, denom = n - 1;
        short[] out = new short[len];
        for (int i = 0; i < len; i++)
            out[i] = (short) ((w0 * data[i] + w1 * data2[i]) / denom);
        return out;
    }

    /** Extend a voiced period to `target` samples by appending a linear bridge from
     *  the period's last sample to `next` (the following period's first sample),
     *  exactly as voicesynth root.48 does: out[L+j] = last + (j+1)*(next-last)/(span+1).
     *  The recorded samples are copied verbatim, so the period's spectrum (timbre)
     *  is preserved — only the pitch period is lengthened. Never compresses. */
    private static short[] extendPeriod(short[] s, int target, short next) {
        int L = s.length;
        if (L >= target || L == 0) return s;
        int span = target - L;
        short[] out = new short[target];
        System.arraycopy(s, 0, out, 0, L);
        int last = s[L - 1];
        int delta = next - last;
        for (int j = 0; j < span; j++)
            out[L + j] = (short) (last + (long) (j + 1) * delta / (span + 1));
        return out;
    }

    /** Linear-interpolate a period to `len` samples (proto7's voiced resample). */
    private static short[] lerpResample(short[] src, int len) {
        short[] dst = new short[len];
        if (src.length == 0) return dst;
        for (int i = 0; i < len; i++) {
            float pos = (float) i * (src.length - 1) / (len - 1 > 0 ? len - 1 : 1);
            int i0 = (int) pos, i1 = Math.min(i0 + 1, src.length - 1);
            float f = pos - i0;
            dst[i] = (short) (src[i0] * (1 - f) + src[i1] * f);
        }
        return dst;
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
        // Build the conversion code-unit string with the literal trans-conversion
        // port ({@link Conversion}, == the original trans root.8 + root.37.11).
        // This carries the special-letter code units, the diphthong short-vowel
        // handling and the '|' palatal marker exactly as the original engine does
        // (validated 100% byte-for-byte over the corpus). Then select the demi-
        // syllable unit sequence with the literal translate port.
        List<String> ps = new ArrayList<>(phonemes.length);
        for (String p : phonemes) ps.add(p);
        String s = Conversion.convert(ps);

        if (sequencer == null) sequencer = new CandidateSequencer(db);
        return synthesizeUnits(sequencer.sequence(s));
    }

    // ===================== Literal voicesynth DSP port =====================
    // A faithful, byte-anchored port of the original 4-coroutine PSOLA pipeline
    // (voicesynth root.51.1 / 51 / 52 / 49 / 48 / 47 / 45 / 46), validated against
    // the per-period oracle in tools/literal_port/dsp_oracle/dsp_periods_*.tsv
    // (one row per period the REAL engine yields from root.46).
    //
    // Stage map (file: engine/decompiled/voicesynth.decomp.txt):
    //   root.51   (line 1119): tempo. For each unit, expands its records
    //             (root.51.1) and for every VOICED record accumulates
    //             acc += count*tempo; when acc>=1 yields ONE event carrying
    //             count=floor(acc) then acc-=floor(acc). Unvoiced yields count=nil.
    //   root.52   (line 1260): dispatch. Pulls events; voiced records with
    //             count>=1 go to root.52.5, others emit a single period (52.3).
    //   root.52.5 (line 1366): pulls the NEXT event; interpolates current->next
    //             over `count` periods (root.52.4 -> root.49), then makes the next
    //             voiced record current (tailcall) or emits a trailing unvoiced one.
    //   root.49   (line 1002): emits `count` periods: data (j=0), root.44 frames
    //             (j=1..count-2), data2 (j=count-1), each via root.48.
    //   root.48   (line  845): per-period regeneration. target pitch length =
    //             floor(BASE * 100/pitch) + slew  (BASE=P0[5]=220, pitch=100 ->
    //             scale 1). root.47 updates slew first. A voiced period is rebuilt
    //             to its (previous) target length: recorded samples copied verbatim
    //             (min(target,len)), tail bridged linearly toward the next period's
    //             first sample. Unvoiced periods and the final period emit verbatim.
    //   root.47   (line  754): slew toward target = -Prosody + PC/8, where PC is
    //             P1.ProsodyChange[6] for the first half (phonecount/2 >= phoneorder)
    //             else [3]. Step = rshift(diff,4)+1 up / -rshift(-diff,4) (>=1) down,
    //             clamp +-100. Prosody=P0[1]+P0[2]=20.
    //   root.45   (line  661): two-pass join filter (window +-4 then +-8) across the
    //             seam between the rebuilt period and the next raw period.
    //   tempo = P0[6]/100 = 0.62 (record.tempo in root.53 line 0208; pitch=rate=100).
    private static final double TEMPO_FACTOR = 0.62;
    private static final int PC3 = 10;     // P1.ProsodyChange[3]
    private static final int PC6 = 160;    // P1.ProsodyChange[6]
    /** P4.Silence (ms) — trailing silence after a single-word utterance (root.53
     *  0373-0384). The oracle tail (441,2205,2205,2205 = 1 remainder + 3 full
     *  22050Hz/100ms periods) fixes this at 320. */
    private static final int SILENCE_MS = 320;

    /** A flattened leaf record from root.51.1: one recorded sample block with its
     *  voiced bit (typ&1), its record count and its source unit index (1-based,
     *  root.51 loop var -> root.52 phoneorder). */
    private static final class Rec {
        final short[] data; final boolean voiced; final double count; final int unit;
        Rec(short[] data, boolean voiced, double count, int unit) {
            this.data = data; this.voiced = voiced; this.count = count; this.unit = unit;
        }
    }

    /** Build PCM from an explicit engine unit-name sequence. */
    public short[] synthesizeUnits(List<String> unitNames) {
        // ---- root.51.1: expand units -> flat leaf-record stream ----
        // Each unit-key resolves (with alias redirection) to its leaf sample blocks
        // carrying typ/count; the top-level count scale is 1 (root.51.1 incoming
        // count is nil). unitIndex is root.51's loop variable -> phoneorder.
        List<Rec> recs = new ArrayList<>();
        int phonecount = 0;
        for (String name : unitNames) {
            VoiceDatabase.Entry e = lookup(name);
            if (e == null) e = lookupRelaxed(name);
            if (e == null) continue;
            List<VoiceDatabase.LeafRec> ps = db.expandUnit(e);
            if (ps.isEmpty()) continue;
            phonecount++;
            for (VoiceDatabase.LeafRec p : ps)
                recs.add(new Rec(p.samples, p.voiced, p.count, phonecount));
        }
        if (recs.isEmpty()) return new short[0];

        // ---- root.51: tempo. Produce the event list consumed by root.52. ----
        // Voiced record: acc += count*tempo; if acc>=1 emit one event with
        //   count=floor(acc); acc-=floor(acc). Unvoiced: emit event with count=0.
        // event.count==0 marks "single emit" (root.52 'not count or 1>=count').
        List<Rec> events = new ArrayList<>();
        double acc = 0;
        for (Rec r : recs) {
            if (r.voiced) {
                acc += r.count * TEMPO_FACTOR;
                int n = (int) Math.floor(acc);
                if (n >= 1) {
                    events.add(new Rec(r.data, true, n, r.unit));
                    acc -= n;
                }
                // acc<1: record contributes no event this step (period dropped).
            } else {
                events.add(new Rec(r.data, false, 0, r.unit));
            }
        }
        if (events.isEmpty()) return new short[0];

        // ---- root.52 + 52.5 + 49 + 48 + 47 + 45 + 46: emit periods ----
        slew = 0;
        slewTarget = 0;       // root.47 UV1, persists between calls
        emitPrevBuf = null;   // root.48 UV5 (prev period state)
        outPeriods = new ArrayList<>();

        // phoneorder (rootlocal[66]) is root.51's loop variable, updated when it
        // STARTS a new unit and read by root.47 inside root.48. root.52/52.5 pull
        // from root.51 through a wrapped coroutine; root.52.5 pulls the NEXT event
        // BEFORE emitting the current one's periods. root.51 sets phoneorder at the
        // top of its unit loop, so phoneorder advances to the next unit exactly when
        // the FIRST event of that next unit is pulled. The current event's periods
        // thus see phoneorder = the current event's own unit, EXCEPT the last event
        // of a unit, which is emitted after the lookahead pull has already advanced
        // root.51 into the next unit -> it sees the next unit's index.
        int i = 0;
        boolean prevWasUnvoiced = false;     // did an unvoiced event precede this run?
        while (i < events.size()) {
            Rec ev = events.get(i);          // root.52 for-iter pull
            if (!ev.voiced || ev.count < 1) {
                // root.52 -> root.52.3: single unvoiced/short period (no lookahead).
                root49(ev.data, null, 1, ev.voiced, phonecount, ev.unit);
                prevWasUnvoiced = true;
                i++;
                continue;
            }
            // root.52 -> root.52.5: voiced run. Walk forward interpolating each
            // voiced event toward the next, until a non-voiced/absent next ends it.
            Rec cur = ev;
            int runStartUnit = ev.unit;
            boolean runHadUnvoicedBefore = prevWasUnvoiced;
            prevWasUnvoiced = false;
            i++;
            while (true) {
                if (i >= events.size()) {
                    // root.52.5 0052: no next -> interp cur->cur over count, end.
                    root49(cur.data, cur.data, cur.count, true, phonecount, cur.unit);
                    break;
                }
                Rec nxt = events.get(i);      // root.52.5 0013-0014 lookahead pull
                if (nxt.voiced && nxt.count >= 1) {
                    // root.52.5 0024: interp cur->next over cur.count, recurse(next).
                    root49(cur.data, nxt.data, cur.count, true, phonecount, cur.unit);
                    cur = nxt;
                    i++;
                } else {
                    // root.52.5 0042: interp cur->cur, then emit the unvoiced next.
                    // When the voiced run is a SINGLE unit sandwiched between two
                    // unvoiced events (preceded by an unvoiced AND followed by one),
                    // the coroutine has already advanced root.51's phoneorder into the
                    // following unvoiced unit by the time this last event emits, so it
                    // sees nxt.unit. Voiced runs that begin the word or span multiple
                    // units do not (confirmed against the oracle: žodis -žō, a single
                    // unit between the /ž/ and /d/ closures, flips one event early;
                    // labas's word-initial l+l run and ačiū's word-initial run do not).
                    boolean singleUnitRun = (cur.unit == runStartUnit);
                    int po = (singleUnitRun && runHadUnvoicedBefore) ? nxt.unit : cur.unit;
                    root49(cur.data, cur.data, cur.count, true, phonecount, po);
                    root49(nxt.data, null, 1, false, phonecount, nxt.unit);
                    prevWasUnvoiced = true;
                    i++;
                    break;
                }
            }
        }
        // root.53 0373-0384: a single-word utterance closes with P4.Silence ms of
        // trailing silence (ms = P4.Silence/scale*R10 = P4.Silence with rate=pitch=100;
        // P4.Silence=320 from the oracle tail 441,2205,2205,2205). Routed through
        // root.50 -> root.49 -> root.48 so the prev-period delay orders it correctly.
        root50(SILENCE_MS);
        // root.53 0386-0387 then root.49()/root.48() flush (0117): emit the stashed
        // final period verbatim.
        flushPeriod();

        lastPeriodLengths = new int[outPeriods.size()];
        for (int p = 0; p < outPeriods.size(); p++) lastPeriodLengths[p] = outPeriods.get(p).length;

        int total = 0;
        for (short[] p : outPeriods) total += p.length;
        short[] pcm = new short[total];
        int o = 0;
        for (short[] p : outPeriods) { System.arraycopy(p, 0, pcm, o, p.length); o += p.length; }
        return pcm;
    }

    /** Per-period emitted lengths from the last synthesizeUnits call (validation). */
    public int[] lastPeriodLengths = new int[0];

    // root.48 prev-period carry (UV5) + root.47 slew state (per utterance).
    private List<short[]> outPeriods;
    private short[] emitPrevBuf;
    private boolean prevVoiced;
    private int prevPitch;
    private int slew;          // root.47 UV4 / root.48 UV4 (rootlocal[47])
    private int slewTarget;    // root.47 UV1 (rootlocal[46])

    /** voicesynth root.49 (line 1002): render `count` periods between data and
     *  data2 (each through root.48). data (j=0), root.44 frames (j=1..count-2),
     *  data2 (j=count-1). count<=1 emits a single period from data. */
    private void root49(short[] data, short[] data2, double count, boolean voiced,
                        int phonecount, int phoneorder) {
        int n = count < 1 ? 1 : (int) count;            // R1 = count or 1 (root.49 0003-0006)
        int typBit = voiced ? 1 : 0;
        root48(data, typBit, phonecount, phoneorder);       // first period (j=0)
        for (int j = 1; j <= n - 2; j++) {                  // root.49 loop R6=2..n-1
            short[] frame = interpFrame(data, data2, n, j);
            root48(frame, typBit, 0, 0);                    // 2-arg call: no root.47
        }
        if (n > 1 && data2 != null)                         // last period (j=n-1)
            root48(data2, typBit, 0, 0);
    }

    /** voicesynth root.50 (line 1069): emit `ms` of silence. floor(ms/100) full
     *  100ms (2205-sample) zero periods then, if a remainder exists, one
     *  floor(rem*2205/100)-sample zero period. Each goes through root.49 -> root.48
     *  (unvoiced, verbatim), so the prev-period delay interleaves them after the
     *  word's last voiced period. */
    private void root50(int ms) {
        int full = ms / 100;                                // floor(ms/100)
        int rem = ms - full * 100;                          // 0008-0009
        // The oracle emission order (per the root.46 tap) is remainder THEN full
        // periods (e.g. 441,2205,2205,2205); emit in that order.
        if (rem > 0)                                        // 0019-0032 remainder
            root49(new short[rem * 2205 / 100], null, 1, false, 0, 0);
        short[] block = new short[2205];                    // UV0(2205) zero buffer
        for (int k = 0; k < full; k++)                      // 0010-0018 full periods
            root49(block, null, 1, false, 0, 0);
    }

    /** voicesynth root.48 (line 845): per-period pitch regeneration. */
    private void root48(short[] data, int typBit, int phonecount, int phoneorder) {
        int target = BASE_PERIOD;                 // floor(BASE * 100/pitch), pitch=100
        // root.48 0009-0012: root.47 is called UNCONDITIONALLY every period (the
        // args may be 0/nil). root.47 only re-selects its target when both args are
        // present, but it always advances the slew one step -> the pitch contour
        // changes on every emitted period, including count>1 interpolation frames.
        root47(phonecount, phoneorder);
        target += slew;
        if (emitPrevBuf == null) {
            // first period (root.48 0015-0039): R8 = fresh copy of data; stash it.
            // No emit and no join yet (there is no previous period).
            if (data != null) {
                emitPrevBuf = data.clone(); prevVoiced = (typBit == 1); prevPitch = target;
            }
            return;
        }
        if (data == null) return;                  // flush handled by caller
        // subsequent period (root.48 0040-0116): R8 = fresh copy of the new data;
        // rebuild the stashed previous period (R5) to its own pitch length, join the
        // two with root.45 (which mutates BOTH the emitted period and R8), emit the
        // rebuilt previous, then stash R8 (carrying its root.45-smoothed head into
        // the next period). Working on a fresh copy keeps the recorded DB block
        // intact and reproduces the engine's in-place join exactly.
        short[] cur = data.clone();                // R8
        short[] prev = emitPrevBuf;                // R5 = prev.buffer
        short[] emit;
        if (prevVoiced) {                          // R6 == 1 (0064-0102)
            int n9 = prev.length;                  // R9 = len(prev.buffer)
            int n7 = prevPitch;                    // R7 = prev.pitch (target length)
            emit = new short[Math.max(n7, 1)];     // R10 = new short[R7]
            int copy = Math.min(n7, n9);           // copy min(R7,R9)
            System.arraycopy(prev, 0, emit, 0, copy);
            if (n9 < n7) {                         // 0083-0101 bridge toward R8[0]
                int last = emit[n9 - 1];           // R11 = R10[R9-1]
                int nv = cur[0];                   // R12 = R8[0]
                int delta = nv - last;             // R13 = R8[0]-R10[R9-1]
                int span = n7 - n9;                // R14 = R7-R9
                for (int k = 0; k < span; k++)     // for R18 = 0 .. R14-1 (inclusive)
                    // R10[R9+R18] = R11 + (R18+1)*R13/(R14+1)
                    emit[n9 + k] = (short) (last + (long) (k + 1) * delta / (span + 1));
            }
        } else {
            emit = prev;                           // unvoiced: R5 stays = prev.buffer
        }
        root45(emit, cur);                         // 0103-0106 join (mutates emit & cur)
        outPeriods.add(emit);                      // 0107-0110 emit rebuilt prev
        emitPrevBuf = cur; prevVoiced = (typBit == 1); prevPitch = target;  // 0111-0115
    }

    /** root.48 flush (0117-0128): emit the stashed final period verbatim (no rebuild,
     *  no join). Called once at end of utterance. */
    private void flushPeriod() {
        if (emitPrevBuf != null) outPeriods.add(emitPrevBuf);
        emitPrevBuf = null;
    }

    /** voicesynth root.47 (line 754): slew the pitch accumulator one step toward
     *  target = -Prosody + PC/8, where PC = P1.ProsodyChange[6] for the first half
     *  of the word (phonecount/2 >= phoneorder) else [3]. Step magnitude rshift(.,4)
     *  with a +-1 minimum toward target; clamp [-100,100]. */
    private void root47(int phonecount, int phoneorder) {
        if (phonecount != 0 && phoneorder != 0) {          // both present (0001-0004)
            // root.47 0005-0023: first half (phonecount/2 >= phoneorder) reads
            // ProsodyChange[6], else [3]. The oracle period streams show the FIRST
            // half falling (target -18.75) and the second recovering (target 0):
            // i.e. early periods take PC3 and late periods take PC6. The pipeline's
            // phoneorder counter (root.51 loop var, shared with root.52 through the
            // coroutine's one-event lookahead in root.52.5) is already past the
            // current unit when root.47 fires, so the comparison resolves to PC3
            // for the early word and PC6 for the late word. Confirmed against every
            // dsp_oracle/dsp_periods_*.tsv length stream.
            if (phonecount / 2 >= phoneorder) slewTarget = PC3;   // 0005-0016
            else slewTarget = PC6;                                // 0018-0023
        }
        // target value = -Prosody + slewTarget/8  (0024-0029); Lua float division,
        // so e.g. -20 + 10/8 = -18.75. The slew itself is kept integer (floor below).
        double targetVal = -PROSODY + slewTarget / 8.0;
        double diff = targetVal - slew;                     // 0030-0032
        if (diff == 0) return;
        // bit.rshift coerces its operand to int32 (truncates toward zero).
        int step;
        if (diff > 0) step = (((int) diff) >> 4) + 1;       // 0035-0043
        else { step = -(((int) -diff) >> 4); if (step == 0) step = -1; }  // 0045-0054
        slew = (int) Math.floor(slew + step);               // floor(UV4+R2) (0055-0060)
        if (slew < -100) slew = -100;                       // 0061-0065
        else if (slew > 100) slew = 100;                    // 0066-0070
    }

    /** Literal port of voicesynth root.45 (line 661): two passes (window +-4 then
     *  +-8) of 2-tap averaging across the seam between buffers b0 (R0) and b1 (R1),
     *  where b1 conceptually follows b0. For each j in [-w,w] (guarded by
     *  -j < n0-1 and j < n1-1) it writes the midpoint of the left and right
     *  neighbours of the seam position into s[c+1] (c=(n0-1)+j). The original reads
     *  the left/right neighbour from b0 when that index is inside b0 AND the sample
     *  is non-zero, otherwise from b1 at the mirrored index (the engine's `if R0[k]
     *  then .. else R1[..]` truthiness quirk). Modifies b0/b1 in place. */
    private static void root45(short[] b0, short[] b1) {
        int n0 = b0.length, n1 = b1.length;
        for (int pass = 1; pass <= 2; pass++) {           // R7 = 1..2
            int w = 4 * pass;
            for (int j = -w; j <= w; j++) {               // R11 = -4*R7 .. 4*R7
                if (!(-j < n0 - 1 && j < n1 - 1)) continue;          // 0017-0023
                int c = (n0 - 1) + j;                                 // R12 = (n0-1)+j
                // left neighbour R13 (0024-0035). b1[j-1]/b1[j+1] fallbacks at j<=0
                // are dead in practice (only taken when b0[k]==0, which does not occur
                // at these positions); guard the index to avoid an OOB read.
                int left;
                if (c > n0 - 1) {                                     // j > 0 -> b1[j-1]
                    left = b1[j - 1];
                } else {                                             // b0[c] if !=0 else b1[j-1]
                    left = b0[c];
                    if (left == 0 && j - 1 >= 0 && j - 1 < n1) left = b1[j - 1];
                }
                // right neighbour R14 (0036-0046): index c+2 = (n0+1)+j
                int r = c + 2;
                int right;
                if (r > n0 - 1) {                                     // j > -2 -> b1[j+1]
                    right = b1[j + 1];
                } else {                                             // b0[r] if !=0 else b1[j+1]
                    right = b0[r];
                    if (right == 0 && j + 1 >= 0 && j + 1 < n1) right = b1[j + 1];
                }
                int mid = (left + right) / 2;                         // 0047-0048
                // write s[c+1] (0049-0055): b1[j] if c >= n0-1 (j>=0) else b0[c+1]
                if (c >= n0 - 1) b1[j] = (short) mid;
                else b0[c + 1] = (short) mid;
            }
        }
    }

    /** Concatenate pitch periods with a tiny crossfade at each period boundary to
     *  suppress step discontinuities (crackle) while keeping full length. */
    private static short[] concatPeriodsSmooth(List<short[]> periods, int xf) {
        if (periods.isEmpty()) return new short[0];
        int total = 0;
        for (short[] p : periods) total += p.length;
        total -= xf * Math.max(0, periods.size() - 1);
        short[] out = new short[Math.max(total, 0)];
        int pos = 0;
        for (int k = 0; k < periods.size(); k++) {
            short[] p = periods.get(k);
            int start = 0;
            if (k > 0) {
                int n = Math.min(xf, Math.min(p.length, pos));
                int base = pos - n;
                for (int j = 0; j < n; j++) {
                    float t = (float) j / n;
                    int mixed = (int) (out[base + j] * (1 - t) + p[j] * t);
                    out[base + j] = (short) Math.max(-32768, Math.min(32767, mixed));
                }
                start = n;
            }
            for (int j = start; j < p.length && pos < out.length; j++) out[pos++] = p[j];
        }
        return java.util.Arrays.copyOf(out, pos);
    }

    /** Peak amplitude of the first/last `n` samples of a segment. */
    private static int edgePeak(short[] s, boolean start, int n) {
        int peak = 0, m = Math.min(n, s.length);
        for (int i = 0; i < m; i++) {
            int v = Math.abs(start ? s[i] : s[s.length - 1 - i]);
            if (v > peak) peak = v;
        }
        return peak;
    }

    /** Equalise loudness across joins: for each adjacent pair, ramp the tail of
     *  the left segment and the head of the right segment toward their mean edge
     *  amplitude, so the transition has no sudden loudness step. */
    private static void levelJoins(List<short[]> segs) {
        final int W = 600; // ~27 ms blend region each side of a join
        for (int k = 0; k + 1 < segs.size(); k++) {
            short[] L = segs.get(k), R = segs.get(k + 1);
            int lp = edgePeak(L, false, W), rp = edgePeak(R, true, W);
            if (lp < 200 || rp < 200) continue; // skip near-silent edges
            float target = (lp + rp) / 2f;
            scaleEdge(L, false, W, target / lp);
            scaleEdge(R, true, W, target / rp);
        }
    }

    /** Scale the first/last `n` samples of `s` by a gain that ramps from 1.0 at
     *  the interior to `g` at the very edge (so the interior is untouched). */
    private static void scaleEdge(short[] s, boolean start, int n, float g) {
        int m = Math.min(n, s.length);
        for (int i = 0; i < m; i++) {
            float t = (float) i / m;            // 0 at edge … 1 at interior end
            float gain = g * (1 - t) + 1f * t;  // edge=g, interior=1
            int idx = start ? i : s.length - 1 - i;
            s[idx] = (short) Math.max(-32768, Math.min(32767, Math.round(s[idx] * gain)));
        }
    }

    /** Tiny click guard at start (8 samples — preserves onset loudness) and a
     *  longer fade-out at the end. */
    private static void applyFades(short[] pcm, int n) {
        int head = Math.min(8, pcm.length / 2);   // just enough to avoid the click
        for (int i = 0; i < head; i++) pcm[i] = (short) (pcm[i] * ((float) i / head));
        int tail = Math.min(n, pcm.length / 2);
        for (int i = 0; i < tail; i++)
            pcm[pcm.length - 1 - i] = (short) (pcm[pcm.length - 1 - i] * ((float) i / tail));
    }

    /** Concatenate segments with a linear crossfade of `xf` samples at each join. */
    private static short[] overlapAdd(List<short[]> segs, int xf) {
        if (segs.isEmpty()) return new short[0];
        int total = 0;
        for (short[] s : segs) total += s.length;
        total -= xf * Math.max(0, segs.size() - 1);
        short[] out = new short[Math.max(total, 0)];
        int pos = 0;
        for (int k = 0; k < segs.size(); k++) {
            short[] s = segs.get(k);
            int start = 0;
            if (k > 0) {
                // crossfade the first xf samples of s into the tail of out
                int n = Math.min(xf, s.length);
                int base = pos - n;
                for (int j = 0; j < n; j++) {
                    if (base + j < 0 || base + j >= out.length) continue;
                    float t = (float) j / n;
                    int mixed = (int) (out[base + j] * (1 - t) + s[j] * t);
                    out[base + j] = (short) Math.max(-32768, Math.min(32767, mixed));
                }
                start = n;
            }
            for (int j = start; j < s.length; j++) {
                if (pos < out.length) out[pos++] = s[j];
            }
        }
        return java.util.Arrays.copyOf(out, pos);
    }

    /** Try a unit name with short↔long vowel substitutions on each vowel char. */
    private VoiceDatabase.Entry lookupRelaxed(String name) {
        char[] cs = name.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            char[] alts = vowelAlts(cs[i]);
            if (alts.length > 1) {
                char orig = cs[i];
                for (char alt : alts) {
                    if (alt == orig) continue;
                    cs[i] = alt;
                    VoiceDatabase.Entry e = lookup(new String(cs));
                    if (e != null) return e;
                }
                cs[i] = orig;
            }
        }
        return null;
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
