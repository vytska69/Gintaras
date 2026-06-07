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

## DSP (voicesynth root.43–53) — SAMPLE-ACCURATE (byte-identical)

`DiphoneSynth.synthesizeUnits` is a literal, sample-accurate port of the original
4-coroutine PSOLA pipeline. Given the exact unit sequence the engine selects, the
Java PCM is **byte-identical** to the running original.

Per-period length vs `dsp_oracle/dsp_periods_*.tsv` (root.46 yields): **EXACT**,
354/354 + sunus, for mama, labas, saulė, žodis, ačiū, obuolys, laipsnių, lietuva,
sūnus. Full-PCM correlation vs clean single-word reference: **1.000000 at lag 0;
0 sample differences (maxDiff=0)** for all of them.

Pipeline (file refs in `engine/decompiled/voicesynth.decomp.txt`):
- **root.51.1** (1193) count expander → `VoiceDatabase.expandUnit`: resolve a unit
  key to its leaf sample blocks, propagating the record count through alias
  redirects with scale = count/Σcount. An aliasing record (e.g. `-žō`, one record
  count=23 → an 11-block list) plays the 11 blocks at count 23/11 each (float).
- **root.51** (1119) tempo: per VOICED record `acc += count*tempo` (tempo =
  P0[6]/100 = 0.62 at rate=pitch=100); when `acc≥1` emit ONE event carrying
  count=floor(acc), `acc -= floor(acc)`. Unvoiced → count=0 (single emit).
- **root.52 / 52.5** (1260/1366) dispatch: voiced runs interpolate current→next
  over `count` periods (root.52.4→root.49); the run ends at an unvoiced/absent next.
- **root.49** (1002): emit `count` periods — data (j=0), root.44 frames
  (j=1..count-2), data2 (j=count-1) — each via root.48.
- **root.48** (845): per-period regeneration. target length = floor(BASE·100/pitch)
  + slew (BASE=P0[5]=220). The stashed previous period is rebuilt to its own target
  length (recorded prefix verbatim, tail bridged toward the next period's first
  sample with denominator span+1), joined with root.45, and emitted; the current
  period is stashed as a fresh copy. root.47 runs EVERY period.
- **root.47** (754): slew toward target = −Prosody + PC/8 (float −18.75 / 0;
  Prosody=20), PC = P1.ProsodyChange[6] first half / [3] second half by
  phonecount/2 ≷ phoneorder; step rshift(diff,4)+1 up / −rshift(−diff,4) (≥1) down;
  int slew clamped ±100.
- **root.45** (661): two-pass (±4, ±8) seam join filter, mutating both the emitted
  period and the carried copy.
- **root.50** (1069): trailing P4.Silence=320 ms (one 441-sample remainder period
  then three 2205-sample zero periods), routed through root.49→root.48.

Two subtleties anchored to the oracle (documented in the code):
- root.47's phoneorder leads emission by ONE event when a single-unit voiced run is
  sandwiched between two unvoiced events (e.g. žodis `-žō` flips one period before
  the /d/ closure); word-initial and multi-unit runs do not.
- Lua does the bridge/interp math in floating point and the int16 store truncates
  the whole sum toward zero; the Java port matches that rounding exactly.

### Validating
- `KeyVal`-style harness: feed the oracle's exact unit keys
  (`/tmp/oracle_keys.tsv`, regenerated by `keydump.lua`) to `synthesizeUnits`,
  diff `DiphoneSynth.lastPeriodLengths` against `dsp_oracle/dsp_periods_*.tsv`.
- Reference PCM MUST be rendered one word per `speak` invocation (see
  `oraclewav.lua`): the original carries prosody/slew state across words within a
  single `speak` run, so multi-word renders are not a valid per-word ground truth.

## A/B WAVs

`ab_wavs/ref_<word>.wav` (running original, one word per `speak`) vs
`ab_wavs/port_<word>.wav` (this Java port) for mama, saulė, žodis, obuolys, ačiū,
laipsnių, labas, sūnus, lietuva. The pairs are byte-identical.
