# libtranscr — clean-room C reimplementation (arm64)

Goal: replace the proprietary 32-bit-only `libtranscr.so` (Lithuanian
grapheme→phoneme front-end) with a clean C reimplementation that compiles for
**arm64-v8a** (and any other ABI), so the from-scratch engine drops all native
LuaJIT/ARM32 dependencies.

## Why this is tractable
`libtranscr.so` is 2.6 MB but only **~47 KB is code** (`.text`); the rest is
compiled linguistic tables (`.rodata` ~678 KB, `.data.rel.ro` ~1 MB). It is fully
self-contained (no external file loading) and depends only on libc + libm. So the
work is: (1) extract the tables verbatim, (2) reimplement ~30 functions whose
names are intact (`KircTranskr`, `Kirciuoti`, `ilgiai`, `tonai`, `skiem`, ...).

## Verification
`_work/oracle/transcr_oracle.py` runs the ORIGINAL libtranscr.so under a CPU
emulator and is DETERMINISTIC (unlike the database oracle). `gen_reference.py`
emits golden `word → phoneme` pairs. Every reimplemented function is regression-
tested against the oracle over a large corpus; CI fails on any mismatch.

## Public ABI (the 7 entry points the engine calls)
See `engine/reconstructed/libtranscr.h`:
  init_transcr, PradApdZod, SpellZod, KircTranskr, KircTranskr1, ilgiai, tonai

## Status (incremental)
- [x] Pipeline + simplest leaf functions (isalpha1, isdigit1, strrev) verified.
- [ ] Table extraction (.rodata/.data.rel.ro → C arrays).
- [ ] Syllabification (skiem), stress (Kirciuoti/KircTranskr).
- [ ] Durations (ilgiai), tones (tonai).
- [ ] Full KircTranskr matching the oracle over the corpus.
- [ ] CMake build for arm64-v8a + integrate into app jniLibs.
