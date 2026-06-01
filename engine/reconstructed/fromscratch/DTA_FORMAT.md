# Gintaras.dta format — recovered & partially verified

Source of truth: LuaJIT `database` module `loaddatabase()` disassembly.
File: 3,646,191 bytes, little-endian.

## Verified (parses cleanly)
- **5,940 sample blocks** (the actual voice waveform pool, ~3.6 MB) read
  byte-for-byte without desync. Each block:
  - `0xFF` marker (u8)
  - `idx` (u16)  — block id, observed range 0..6812
  - `n`   (u16)  — sample count
  - `n` × int16  — PCM samples (signed 16-bit)
- After the sample blocks (~offset 3,600,068) comes the **phoneme/diphone
  dictionary**: entries keyed by UTF-16LE phoneme names (e.g. '-án', 'lř-')
  whose records reference sample blocks by u16 index, with (count, typ&127).

## Not yet pinned
- The `MAP2`/bucket const table that selects, per name-prefix, between a flat
  i16/string list vs the (key,count,typ) dict record layout. A handful of tail
  entries desync under the plain dict assumption. This is the remaining ~35
  bytes / few entries at end of file; does NOT affect the waveform pool.

## Why this matters for the from-scratch engine
The waveform pool (the hard, irreplaceable data) is fully readable in pure
code — no LuaJIT, no native libs. A Kotlin reimplementation can load these
blocks directly. Remaining work: (1) finish the small phoneme-dict bucket
logic, (2) grapheme->phoneme (today still in libtranscr.so), (3) the PSOLA
synthesis that concatenates/pitches blocks into PCM (voicesynth module).
