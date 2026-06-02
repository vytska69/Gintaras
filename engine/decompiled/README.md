# Original Gintaras engine — faithful bytecode decompilation

This directory contains a **bit-for-bit** extraction of the original 2015
WinTalker.Voice / Gintaras TTS engine, decompiled from its LuaJIT 2.1 bytecode
modules (`main.bin` → `mod_1..8.bc`). The goal is a lossless, re-implementable
record of the original algorithm (vowels, timbre, joins, PSOLA, prosody) so the
new pure-Java engine can be rewritten to match it exactly, without guesswork.

## How it was produced

A custom decompiler (`engine/synth_research/decompile.lua` + `bc_uv.lua` +
`decompile_all.lua`, run under the in-repo LuaJIT 2.1 at `_work/luajit-2.1`):

1. Reads each proto's bytecode via `jit.util.funcbc`, decoding every instruction
   to a semantic register-machine statement (operand modes mirror `jit/bc.lua`).
2. Resolves all constants (`funck`): strings (with `\0` bytes preserved),
   numbers, child protos, template tables.
3. Parses the raw `.bc` container (`bc_uv.lua`) to recover each proto's
   **upvalue binding table** — every `UVn` is annotated with the parent local
   slot (or parent upvalue) it captures, plus the immutable flag.

The transliteration was validated against hand-decodes (e.g. `loadvoice`'s
`Prosody = P0[1]+P0[2]`, `Reset = (P0[4]==1)`) — they match exactly.

## Files

- `*.decomp.txt` — one file per module (voicesynth, translate, trans, database,
  dictionary, main, javajni, md5). Each lists every proto.
- `MANIFEST.txt` — every proto with its signature (params/uv/bc) and string
  constants (role hint), across all modules.

## Reading a proto

```
PROTO root.48 (parent: root)          <- id; root = module body, root.N = child via FNEW K[-N]
-- params=4 framesize=22 upvalues=11
  K[-n]=...                            <- GC constants (strings/protos/templates)
  KNUM[n]=...                          <- number constants
  UVn -> rootlocal[42] immut           <- upvalue binding: captures parent local slot 42
-- code:
0001  R4 = _G["math"]                  <- one statement per bytecode instruction
...
```

Registers are `R0..Rframesize`. `R0..R(params-1)` are the arguments. Comparison
ops are written `if Ra <cmp> X then -> (next JMP)`: the **following** `JMP`
line gives the target, and the jump is taken when the condition is **true**
(literal opcode condition, e.g. `ISGE` jumps if `>=`).

## Synthesis pipeline (voicesynth = mod_7)

| proto | role |
|-------|------|
| `root` | module body: builds the lpeg grammar, helper closures, exports |
| `root.43` | **loadvoice** — builds Prosody/Reset/ProsodyChange/ProsodyDifference/Silence from the voice's P0..P8 tables; base pitch period = `P0[5]`, scale = `P0[6]/100` |
| `root.53` | **speak** — phrase driver: tokenises on `.!?,;:()[]{}`, runs dictconv/translate per token, applies the prosody contour (P1/P5/P6/P7/P8 by punctuation; per-word declination via P3.ProsodyDifference; resets at sentence end) and Silence pauses |
| `root.51` / `root.51.1` | per-token period emitter (applies `tempo`, emits {key,typ,count}) |
| `root.52` / `root.52.3/4/5` | join coordinator: selects single-buffer (`data`) vs overlapping (`data`+`data2`) demi-syllable join by `typ` bits |
| `root.49` | **phoneorder** — emits the pitch-period run for a unit from `data`/`data2` |
| `root.47` | **pitch accumulator** — slews the pitch offset toward a ProsodyChange target by `rshift(diff,4)` (min 1), clamped to ±100 |
| `root.48` | **pitch DSP** — for each VOICED period: EXTEND-ONLY. Keeps the recorded samples verbatim and appends a linear bridge to the next period's first sample to reach the target pitch (`out[L+j] = last + (j+1)*(next-last)/(span+1)`); never compresses (skips when curLen >= pitch). Unvoiced periods pass through. |
| `root.44` | frame interpolation: weighted blend of two period buffers |
| `root.45` | **join smoothing** — two passes of 2-tap neighbour averaging across a demi-syllable seam (window ±4 then ±8) |
| `root.50` | **Silence** — emits 2205-sample (0.1 s) silence blocks scaled by tempo |
| `root.46` | generator — `coroutine.yield(buffer, #buffer/2)` streams PCM out |

## Deterministic prosody parameters (decoded from loadvoice + the live P-tables)

```
P0={10,10,0,1,220,62}    -> Prosody=20, Reset=true, base period=220 (~100 Hz), scale=0.62
P1={...,0,50,10,20,50,160}  -> ProsodyChange (default word ramp)
P2={10,10,20,1}          -> Prosody=20, Silence=20
P3={0,4,20,1}            -> ProsodyDifference=4 (per-word declination), Silence=20
P4={0,0,300}             -> Silence=300
P5={...,0,50,10,20,50,160}
P6={...,0,50,10,50,100,400}  P7 = same
P8={0,-10,...,10,100,200,-100,100,-600} -> ProsodyDifference=-10 (question)
```

## Caveat for re-implementation

The original can be run offline through `translate` and the full DSP (see
`engine/synth_research/run_synth.lua`), and it reads the correct sample blocks,
but the final PCM mix is copied into the output buffer via an `ffi.copy` that
no-ops in pure Lua — so clean reference audio can't be captured offline (only
`root.50`'s silence blocks reach the output). The decompilation here is the
authoritative source for the exact algorithm; re-implement from it directly.
