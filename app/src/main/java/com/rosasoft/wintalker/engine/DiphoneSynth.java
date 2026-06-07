package com.rosasoft.wintalker.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Diphone-concatenation synthesiser with the original engine's PSOLA pitch/tempo
 * control. Turns a phoneme sequence (from the transcriber) into 16-bit PCM by
 * selecting diphone units from the voice database and running the original
 * voicesynth DSP pipeline over their recorded pitch-period blocks.
 *
 * The DSP ({@link #synthesizeUnits}) is a literal, sample-accurate port of the
 * decompiled engine modules (engine/decompiled/voicesynth.decomp.txt):
 * root.51.1 (count expander) -> root.51 (tempo accumulator) -> root.52/52.5
 * (voiced/unvoiced dispatch) -> root.49 (per-period emit) -> root.48 (per-period
 * pitch regeneration) + root.47 (pitch slew) -> root.45 (join filter) -> root.50
 * (trailing silence). Validated byte-for-byte against the per-period oracle
 * (tools/literal_port/dsp_oracle/) and the reference PCM.
 *
 * Diphone keys: the engine forms boundary-aware overlapping units from the phoneme
 * stream. For "_ l aA b aA s _" the units are roughly "-l", "la", "ab", "ba",
 * "as", "s-". We try each candidate key against the index and fall back to
 * progressively simpler keys so unknown contexts still produce sound.
 */
public final class DiphoneSynth {

    public static final int SAMPLE_RATE = 22050;

    /** Base pitch period in samples: floor(P0[5] * 100/pitch) = 220 at pitch=100
     *  (P0[5]=220, voicesynth loadvoice root.43 line 0187; root.48 line 0001-0008). */
    private static final int BASE_PERIOD = 220;

    /** P0.Prosody = P0[1]+P0[2] = 20 (voicesynth loadvoice root.43 line 0035-0038). */
    private static final int PROSODY = 20;

    private final VoiceDatabase db;
    private final Map<String, VoiceDatabase.Entry> index;
    private CandidateSequencer sequencer;

    public DiphoneSynth(VoiceDatabase db) {
        this.db = db;
        this.index = db.diphoneIndex();
    }

    /** Linear frame interpolation, voicesynth root.44 (line 612): the j-th of `n`
     *  frames between data and data2 is out[i] = ((n-1-j)*data[i] + j*data2[i])/(n-1),
     *  over floor(min(len(data),len(data2))/2)*2 samples. j=0 -> data, j=n-1 -> data2.
     *  Renders the interior periods of a count>1 voiced run (root.49). */
    private static short[] interpFrame(short[] data, short[] data2, int n, int j) {
        int len = Math.min(data.length, data2.length);
        len &= ~1;                              // floor(min/2)*2 (root.44 0015-0021)
        if (len == 0) return data;
        if (n <= 1) return java.util.Arrays.copyOf(data, len);
        int w0 = (n - 1) - j, w1 = j, denom = n - 1;
        short[] out = new short[len];
        for (int i = 0; i < len; i++)
            // ((n-1-j)*data + j*data2)/(n-1): integer division truncates toward zero,
            // identical to Lua's float divide followed by the int16 store.
            out[i] = (short) ((w0 * data[i] + w1 * data2[i]) / denom);
        return out;
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
        return synthesize(phonemes, 100, 100);
    }

    /**
     * Synthesize with a speech {@code rate} and {@code pitch} (both percentages,
     * 100 = normal — exactly the values Android passes via SynthesisRequest). At
     * 100/100 this is byte-identical to the original. Per the original (root.53/51/48):
     * the per-period tempo (count multiplier) is {@code 0.62 * pitch / rate} and the
     * base pitch period is {@code round(220 * 100 / pitch)} — so higher pitch shortens
     * the period (higher voice) while keeping duration, higher rate emits fewer
     * periods (faster) keeping pitch.
     */
    public short[] synthesize(String[] phonemes, int rate, int pitch) {
        // No sentence context: a fresh prosody state, default ('\0') punctuation, not
        // flagged as the last word -> the default P1 ramp, byte-identical to before.
        return synthesize(phonemes, rate, pitch, '\0', false, new ProsodyState());
    }

    /**
     * Synthesize one word of an utterance carrying the sentence-level intonation
     * (voicesynth root.53). {@code punctuation} is the word's trailing mark (one of
     * {@code . ! ? , ; :} or {@code '\0'} for none); {@code lastWord} is true for the
     * final word of the utterance; {@code state} is the persistent accumulator/slew
     * carried across the whole utterance (create one per utterance and reuse it).
     *
     * This is the per-word body of root.53's sentence loop: select the ProsodyChange
     * ramp by punctuation (0240-0290), apply the '?' accumulator bump (0257-0263),
     * render the word with that ramp + the carried slew, then update the accumulator
     * for the next word — reset at sentence end (0297-0320), or decline by
     * P3.ProsodyDifference otherwise (0354-0360).
     */
    public short[] synthesize(String[] phonemes, int rate, int pitch,
                              char punctuation, boolean lastWord, ProsodyState state) {
        List<String> ps = new ArrayList<>(phonemes.length);
        for (String p : phonemes) ps.add(p);
        String s = Conversion.convert(ps);

        if (sequencer == null) sequencer = new CandidateSequencer(db);

        // --- root.53 0240-0290: pick this word's ProsodyChange ramp by punctuation,
        //     and apply the '?' pre-word accumulator bump (UV0 -= P8.ProsodyDifference).
        int[] ramp;
        if (isIn(".!;:", punctuation)) {
            ramp = PC_P7;                               // 0240-0253
        } else if (punctuation == '?') {
            state.accumulator -= P8_DIFF;               // 0257-0263: -(-10) = +10
            ramp = PC_P8;                               // 0264-0268
        } else if (punctuation == ',') {
            ramp = PC_P6;                               // 0270-0276
        } else if (lastWord) {
            ramp = PC_P5;                               // 0278-0284
        } else {
            ramp = PC_P1;                               // 0286-0290
        }

        short[] pcm = synthesizeUnits(sequencer.sequence(s), rate, pitch, ramp, state);

        // --- root.53 0297-0372: post-word accumulator update for the next word.
        if (isIn(".!?;:)]}", punctuation)) {
            state.accumulator = PROSODY;                // 0306-0310: reset to P0.Prosody
            // P0.Reset == true -> declination would reset too (we keep no separate
            // declination term; the accumulator IS the carried offset).
        } else if (punctuation == ',') {
            state.accumulator = P2_PROSODY;             // 0336-0340: reset to P2.Prosody
        } else {
            state.accumulator -= P3_DIFF;               // 0354-0360: decline by 4
        }
        return pcm;
    }

    private static boolean isIn(String set, char c) {
        return c != '\0' && set.indexOf(c) >= 0;
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
    /** P4.Silence (ms) — trailing silence after a single-word utterance (root.53
     *  0373-0384). The oracle tail (441,2205,2205,2205 = 1 remainder + 3 full
     *  22050Hz/100ms periods) fixes this at 320. */
    private static final int SILENCE_MS = 320;

    // ---- Sentence-level prosody (intonation) tables ----------------------------
    // The per-word ProsodyChange ramp and the accumulator increments come from the
    // P-tables P0..P8 parsed by loadvoice (root.43) from Gintaras.dta. These are
    // fixed voice DATA; the values below were read straight out of the real .dta via
    // the running original engine (each is a 1-based slice of the raw P array — see
    // the loadvoice mapping cited per field). root.47 reads ProsodyChange[6] for the
    // first half of a word and ProsodyChange[3] for the second; root.53 selects which
    // table by the word's trailing punctuation.
    //
    //   raw .dta P arrays (1-indexed):
    //     P0={10,10,0,1,220,62}  P1={0,0,0,0,0,50,10,20,50,160}
    //     P2={10,10,20,1}        P3={0,4,20,1}   P4={0,0,300}
    //     P5={0,0,0,0,0,50,10,20,50,160}  P6={0,0,0,0,0,50,10,50,100,400}
    //     P7={0,0,0,0,0,50,10,50,100,400} P8={0,-10,0,0,10,100,200,-100,100,-600}
    //
    // ProsodyChange[1..6] = raw P[5..10]; we only ever read [3] and [6] (root.47).
    /** P1.ProsodyChange[3],[6] — default word (loadvoice 0052-0064). */
    private static final int[] PC_P1 = {10, 160};
    /** P5.ProsodyChange[3],[6] — last word of an utterance (loadvoice 0110-0124). */
    private static final int[] PC_P5 = {10, 160};
    /** P6.ProsodyChange[3],[6] — word ending in ',' (loadvoice 0129-0143). */
    private static final int[] PC_P6 = {50, 400};
    /** P7.ProsodyChange[3],[6] — word ending in '.' '!' ';' ':' (loadvoice 0147-0162). */
    private static final int[] PC_P7 = {50, 400};
    /** P8.ProsodyChange[3],[6] — word ending in '?' (loadvoice 0167-0183). */
    private static final int[] PC_P8 = {200, -600};
    /** P8.ProsodyDifference = raw P8[2] = -10 (loadvoice 0168). A '?' bumps the
     *  accumulator by -(-10)=+10 before the word is rendered (root.53 0257-0263). */
    private static final int P8_DIFF = -10;
    /** P3.ProsodyDifference = raw P3[2] = 4 (loadvoice 0088). Per-word declination:
     *  a normal word drops the accumulator by 4 afterwards (root.53 0354-0360). */
    private static final int P3_DIFF = 4;
    /** P2.Prosody = P2[1]+P2[2] = 20 (loadvoice 0070-0073). Reset value after ','. */
    private static final int P2_PROSODY = 20;

    /**
     * Persistent sentence-level prosody state, threaded across the words of ONE
     * utterance (the original keeps these as voicesynth root-locals that live for the
     * whole {@code speak()} call — rootlocal[44]=accumulator, rootlocal[47]=slew,
     * rootlocal[46]=slewTarget). A fresh state reproduces the original's start of a
     * sentence: accumulator = P0.Prosody = 20, slew = 0.
     *
     * Higher accumulator / higher slew => longer pitch period => LOWER pitch. So the
     * per-word declination (accumulator -4) gradually lowers the voice, sentence-final
     * '.' raises the accumulator's effect (fall), and '?' (P8, big negative
     * ProsodyChange) shortens the periods at the end => the voice RISES.
     */
    public static final class ProsodyState {
        int accumulator = PROSODY;   // root.53 0001-0005: UV0 = P0.Prosody = 20
        int slew = 0;                // root.47 UV4 (rootlocal[47]) — persists per utterance
        int slewTarget = 0;          // root.47 UV1 (rootlocal[46])
    }

    /** A flattened leaf record from root.51.1: one recorded sample block with its
     *  voiced bit (typ&1), its record count and its source unit index (1-based,
     *  root.51 loop var -> root.52 phoneorder). */
    private static final class Rec {
        final short[] data; final boolean voiced; final double count; final int unit;
        Rec(short[] data, boolean voiced, double count, int unit) {
            this.data = data; this.voiced = voiced; this.count = count; this.unit = unit;
        }
    }

    /** Build PCM from an explicit engine unit-name sequence (rate=pitch=100). */
    public short[] synthesizeUnits(List<String> unitNames) {
        return synthesizeUnits(unitNames, 100, 100);
    }

    /** Build PCM from an explicit unit sequence at the given rate/pitch (%, 100=normal),
     *  with no sentence context (default P1 ramp, fresh prosody state). */
    public short[] synthesizeUnits(List<String> unitNames, int rate, int pitch) {
        return synthesizeUnits(unitNames, rate, pitch, PC_P1, new ProsodyState());
    }

    /** Effective per-utterance pitch period and tempo, derived from rate/pitch. */
    private int curBasePeriod = BASE_PERIOD;
    private double curTempo = TEMPO_FACTOR;

    /** The ProsodyChange ramp ([3],[6]) in force for the word being rendered (root.47). */
    private int[] curRamp = PC_P1;

    /** Clamp a rate/pitch percentage to a sane range (avoids div-by-zero / extremes). */
    private static int clampPct(int v) { return v < 25 ? 25 : (v > 400 ? 400 : v); }

    /** Build PCM with the selected ProsodyChange ramp and the carried prosody state. */
    public short[] synthesizeUnits(List<String> unitNames, int rate, int pitch,
                                   int[] ramp, ProsodyState state) {
        curRamp = ramp;
        return synthesizeUnits(unitNames, rate, pitch, state);
    }

    private short[] synthesizeUnits(List<String> unitNames, int rate, int pitch,
                                    ProsodyState state) {
        int pPct = clampPct(pitch), rPct = clampPct(rate);
        curBasePeriod = (int) Math.round(BASE_PERIOD * 100.0 / pPct); // root.48: floor(BASE*100/pitch)
        curTempo = TEMPO_FACTOR * pPct / (double) rPct;              // root.53/51: 0.62*pitch/rate
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
                acc += r.count * curTempo;
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
        // root.47's slew (rootlocal[47]) and slewTarget (rootlocal[46]) PERSIST across
        // the words of an utterance — they live in the prosody state, not reset here.
        // (The accumulator, rootlocal[44], also lives in the state.) A fresh state
        // (single-word callers) starts slew=0, accumulator=20: byte-identical to before.
        this.state = state;
        emitPrevBuf = null;   // root.48 UV5 (prev period state) — per-word
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
        // NOTE: the engine's per-word P4.Silence trailing pad (root.53 0373-0384 ->
        // root.50) is NOT emitted here. TtsService synthesizes ONE word per call and
        // adds the inter-word / sentence pauses itself (scaled by the user's pause
        // settings); appending the ~320 ms pad to every word produced a long pause
        // after each word regardless of the setting. Standalone callers that want a
        // trailing pad can add it themselves.
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

    // root.48 prev-period carry (UV5) — per-word DSP state.
    private List<short[]> outPeriods;
    private short[] emitPrevBuf;
    private boolean prevVoiced;
    private int prevPitch;
    /** Carried sentence-level prosody (accumulator + slew); set per synthesizeUnits call. */
    private ProsodyState state = new ProsodyState();

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
        int target = curBasePeriod;               // floor(BASE * 100/pitch)
        // root.48 0009-0012: root.47 is called UNCONDITIONALLY every period (the
        // args may be 0/nil). root.47 only re-selects its target when both args are
        // present, but it always advances the slew one step -> the pitch contour
        // changes on every emitted period, including count>1 interpolation frames.
        root47(phonecount, phoneorder);
        target += state.slew;
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
                    // R10[R9+R18] = R11 + (R18+1)*R13/(R14+1). Lua computes the term
                    // and sum in floating point, then the int16 store truncates the
                    // WHOLE sum toward zero (not the quotient first).
                    emit[n9 + k] = (short) (long)
                        (last + (double) (k + 1) * delta / (span + 1));
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
     *  target = -accumulator + PC/8, where PC = ProsodyChange[6] for the first half
     *  of the word (phonecount/2 >= phoneorder) else [3]. The accumulator (root.47
     *  UV3 = root.53 UV0) carries the sentence-level offset; ProsodyChange is the
     *  per-word ramp selected by punctuation. Step magnitude rshift(.,4) with a +-1
     *  minimum toward target; clamp [-100,100]. */
    private void root47(int phonecount, int phoneorder) {
        if (phonecount != 0 && phoneorder != 0) {          // both present (0001-0004)
            // root.47 0005-0023: first half (phonecount/2 >= phoneorder) reads
            // ProsodyChange[6], else [3]. The oracle period streams show the FIRST
            // half falling (target -18.75) and the second recovering (target 0):
            // i.e. early periods take ramp[index3] and late periods ramp[index6]. The
            // pipeline's phoneorder counter (root.51 loop var, shared with root.52 via
            // the coroutine's one-event lookahead in root.52.5) is already past the
            // current unit when root.47 fires, so the comparison resolves to [3] for
            // the early word and [6] for the late word. Confirmed against every
            // dsp_oracle/dsp_periods_*.tsv length stream. curRamp = {[3], [6]}.
            if (phonecount / 2 >= phoneorder) state.slewTarget = curRamp[0];  // 0005-0016
            else state.slewTarget = curRamp[1];                               // 0018-0023
        }
        // target value = -accumulator + slewTarget/8  (0024-0029); Lua float division,
        // so e.g. -20 + 10/8 = -18.75. The slew itself is kept integer (floor below).
        double targetVal = -state.accumulator + state.slewTarget / 8.0;
        double diff = targetVal - state.slew;               // 0030-0032
        if (diff == 0) return;
        // bit.rshift coerces its operand to int32 (truncates toward zero).
        int step;
        if (diff > 0) step = (((int) diff) >> 4) + 1;       // 0035-0043
        else { step = -(((int) -diff) >> 4); if (step == 0) step = -1; }  // 0045-0054
        state.slew = (int) Math.floor(state.slew + step);   // floor(UV4+R2) (0055-0060)
        if (state.slew < -100) state.slew = -100;           // 0061-0065
        else if (state.slew > 100) state.slew = 100;        // 0066-0070
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
