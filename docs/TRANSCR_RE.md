# libtranscr — reverse engineering & clean-room arm64 plan

`libtranscr.so` is the only proprietary, ARM32-only blocker for an arm64-native
build. This document records how we drive the original as a golden oracle and the
plan to reimplement it from scratch in portable C.

## The oracle (`tools/transcr_oracle.py`)

We execute the original 32-bit library under the **Unicorn** CPU emulator on the
dev host — purely as a reverse-engineering reference, never shipped:

1. Map its two `PT_LOAD` segments at a chosen base.
2. Apply all **101 366 `R_ARM_RELATIVE`** relocations (`*(B+off) += B`).
3. Route the **22 `R_ARM_JUMP_SLOT`** libc imports to Python shims (string/mem/
   math/`sscanf`/`sprintf`; `fopen` returns NULL — no runtime file I/O is hit).
4. Call exported functions with an AAPCS trampoline.

`init_transcr()` runs cleanly; the front-end pipeline produces correct Lithuanian
phonetics. Golden pairs: `engine/reconstructed/testdata/reference_transcriptions.tsv`
(regenerate with `tools/gen_reference.py`).

## Front-end pipeline (recovered)

```
word (cp1257 / ISO-8859-13, 8-bit Baltic)
  → PradApdZod(in, out, cap, 0)      # normalize: uppercase + trailing space
  → KircTranskr(norm, out, cap, 0)   # grapheme→phoneme + stress; returns #bytes
  → ilgiai(...)                      # segment durations   (next)
  → tonai/tonai1(...)                # pitch / tone contour (next)
```

Input encoding is **8-bit Baltic (cp1257)** — *not* UTF-16. The Java/Lua layer
lowercases UTF-16 text; the Lua converts to the Baltic code page before the FFI
call. `SpellZod` spells a word letter-by-letter; `KircTranskr1` is the 5-arg
variant (`...,int,int,char`).

## Phoneme notation (observed)

Output is a `\n`-separated token list bracketed by `_` (word boundary/silence).

| Token | Meaning | Example |
|---|---|---|
| `_` | word boundary / pause | every word |
| `l b s t u i m n r k p g v z ...` | base phoneme | `labas → l aA b aA s` |
| `X'` (apostrophe) | palatalized (minkštas) consonant | `lietuva → l' i ...` |
| `aA eE iI oO uU` | long/stressed vowel | `aA` in `labas` |
| `eA oO` | stressed mid vowels | `sudie → ... i eA` |
| `S Z` | š, ž | `šuo → S u oO` |
| `tS' dZ` | č, dž (affricates) | `ačiū → aA tS' uU` |
| `N N'` | nasal assimilation before velar/palatal | `gintaras → g' i N t ...` |
| `W J` | glide offsets of diphthongs | `saulė → s aA W l' eE`, `vaikas → v aA J k ...` |

The transcriptions are linguistically correct (palatalization, nasal
assimilation, vowel length/stress, diphthongs), confirming the oracle drives the
real algorithm.

## Clean-room reimplementation plan (arm64-native C)

Internals are ~47 KB code + ~1.7 MB tables (101 k pointers). Strategy:

1. **Behavioural spec from the oracle** — expand the golden corpus to tens of
   thousands of words (dictionaries + generated forms); capture word→phonemes,
   durations and tones. This is the regression contract.
2. **Recover the rule tables** — `ruleslit.rul`/`*.dct` assets plus the binary
   `.rodata`/`.data.rel.ro` tables (traced via the oracle's memory hooks) define
   the grapheme→phoneme and stress rules. Extract them into a portable,
   pointer-free data format (indices, not 32-bit pointers).
3. **Reimplement the algorithm in C** — `PradApdZod`, `KircTranskr`, `ilgiai`,
   `tonai` against the recovered tables; compile for `arm64-v8a` (and any ABI).
4. **Differential test** — every build is diffed against the oracle corpus until
   parity, then wired into the engine via the same FFI ABI (`libtranscr.h`).

Step 1–2 are the bulk of the effort; the oracle makes them tractable and
verifiable rather than guesswork.
