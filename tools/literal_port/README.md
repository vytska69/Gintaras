# Literal port of the Lithuanian TTS engine — status & harnesses

This directory holds the oracle harnesses, ground-truth artifacts and validators
used to port the original engine modules LITERALLY (from `engine/decompiled/`)
and validate every stage byte-for-byte against the RUNNING original
(`_work/luajit-2.1/src/luajit` + `engine/synth_research/lpeg32.so`).

## Ported modules (in `app/src/main/java/com/rosasoft/wintalker/engine/`)

| Module | Java class | Source (decompiled) | Validation | Result |
|--------|-----------|---------------------|-----------|--------|
| trans conversion | `Conversion.java` | trans root.37 / root.8 / root.8.37 / root.37.11 | `ConvVal.java` vs live root.8 | **401/401 = 100%** |
| translate unit selector | `CandidateSequencer.java` | translate root.22 + root.22.2..6 | `SeqVal.java` / `PipeVal.java` vs live translate | **401/401 = 100%** |
| voicesynth DSP | `DiphoneSynth.java` | voicesynth root.43–53 | `WavGen.java` vs reference PCM | partial (see below) |

The transcriber (`Transcriber.java`, G2P) was already verified bit-identical to
libtranscr and is unchanged.

## How the oracle is driven (do not trust prior heuristic gold)

1. `PhonDump.java` — dumps verified-Transcriber phonemes for every corpus word
   (== libtranscr KircTranskr output; confirmed via the ARM oracle).
2. `root8_probe.lua` — injects those phonemes through the REAL `trans` module's
   KircTranskr stub so the REAL `root.8` runs (real lpeg32.so). Output:
   `conv_real.tsv` (word → true UTF-16LE conversion hex). This is the conversion
   oracle.
3. `seqgen.lua` — feeds `conv_real.tsv` into the REAL `translate` over the real
   `Gintaras.dta`. Output: `seq_real.tsv` (word → conversion → unit-key sequence).
   This is the unit-selection oracle. (The old `gold_translate_seq.tsv` col-1 was
   a heuristic conversion — wrong for word-initial long-ō, ī-acute, palatals.)
4. `dsp_oracle.lua` — taps the speak coroutine; `root.46` yields one PCM chunk
   PER PITCH PERIOD. Output: `dsp_oracle/dsp_periods_<word>.tsv` (per-period
   lengths + first samples) — the byte-level DSP oracle.

Regenerate the conversion table for reference WAVs with the python in the parent
prompt (`/tmp/gentable.py`) or from `conv_real.tsv`; `conv_table.tsv` here is the
real-conversion table consumed by `refgen3.lua` to render reference PCM.

## Conversion (trans root.8) — 100%

`Conversion.convert(phonemeTokens)` reproduces the two substitution passes, the
lowercase pass, the palatal-marker reorder pass (root.8.37: `' ` → move `|` after
the following vowel) and the six-byte special→U+01xx final map (root.37.11).
Exact byte match on 401/401 words incl. žodis, obuolys, laipsnių.

## Unit selection (translate root.22) — 100%

`CandidateSequencer.sequence(conv)` is a verbatim port of translate's LPeg
ordered-choice grammar: at each position it tries the grammar TERMS in the exact
sum order (R30,R28,R29,R27,R26,R11,R10,R9,R24,R14,R25,R13,R12,R23..R15); each
TERM builds its candidate unit-name via the literal Cmt-callback formula
(root.22.2..6), gated on voice-table membership; consumes the `C()` capture width;
falls back to one code unit (`P(2)`). Includes the root.22.5 palatal `|` retry.
Exact match on 401/401 incl. žodis (`žō- -žō di- i| -is`), obuolys
(`ō bu- uō- -uō lí- -lí -ís`), laipsnių.

KEY DECODE NOTES (for future reference):
- LuaJIT `concat(R4..R7)` is a CAT over the register RANGE R4,R5,R6,R7.
- `Cmt(C(P(2)), fn)` consumes 1 code unit FIRST, captures it, then calls `fn`
  with the position AFTER the capture — so `sub(pos,…)` reads FOLLOWING units and
  `sub(pos-k,…)` reads PRECEDING units. Byte position p (1-indexed) maps to
  code-unit index (p-1)/2; a unit consumed at index i leaves pos = 2(i+1)+1.
- All of root.22.6 callbacks (R15..R30) are the bare-concat callback; the earlier
  heuristic mis-attributed R27..R30 to the coda callback, which stole position 0.

## DSP (voicesynth root.43–53) — partial, divergence characterized

`DiphoneSynth` is a structural port (root.52/51 tempo, root.48 PSOLA pitch
regeneration, root.45 join, root.46 emit) but NOT yet byte-exact.

Measured vs reference PCM (head-aligned correlation over the voiced part):
- mama 0.9989 (first samples are byte-identical to the reference)
- obuolys 0.62, žodis 0.56, ačiū 0.55, saulė 0.35, laipsnių 0.29, labas 0.05

WHY it diverges (precise, not a guess):
- The original DSP is a 4-coroutine pipeline: root.51.1 (count expander) →
  root.51 (tempo: per voiced record `acc += count*tempo`, yield `floor(acc)`,
  `acc -= floor(acc)`; GLOBAL accumulator) → root.52 (dispatch 52.3/52.4/52.5) →
  root.49 (period emit) → root.48 (PSOLA: rebuild each voiced period to a target
  pitch length and linearly interpolate toward the next period) → root.45 (join)
  → root.46 (yield one period). The per-period target length is `floor(base*scale)
  + slew`, where `slew` is updated per period by root.47 toward a ProsodyChange
  target (P1[3]=10 first half, P1[6]=160 second half), stepping `rshift(d,4)±1`,
  clamped ±100. The oracle period streams confirm this: a clean linear pitch
  ramp (e.g. labas 219→201 then a 2200-sample stop closure then recovery).
- The current Java approximates the tempo accumulator and the pitch slew with a
  different (PC3/PC6, >>4) formula and does not rebuild every period to the exact
  target length, so for multi-syllable words the per-period COUNT and LENGTHS
  drift; by mid-word Java and the reference are out of phase and a sample-level
  correlation collapses even though the timbre/units are correct. mama (one
  repeated syllable whose units need no length change) stays aligned → 0.9989.
- The exact contour needs the runtime-set prosody state (root locals 44–48,
  set during loadvoice/speak from P1.ProsodyChange) that cannot be resolved
  statically; the `dsp_oracle/*.tsv` period-length streams are the anchor to
  finish the port.

NEXT STEP to finish the DSP: port root.51.1/51/52/49/48/47/45/50 as a literal
per-period pipeline driven by the prosody arrays already extracted
(P0={10,10,0,1,220,62}, P1.ProsodyChange={0,50,10,20,50,160}); validate each
word's emitted per-period length stream against `dsp_oracle/dsp_periods_*.tsv`
(must match exactly), then the concatenated PCM against `ref_*.wav`.

## A/B WAVs

`ab_wavs/ref_<word>.wav` (running original) vs `ab_wavs/port_<word>.wav` (this
Java port) for žodis, obuolys, mama, saulė, ačiū, laipsnių. Units now match the
original exactly; the audible difference is the DSP pitch/tempo contour above.
