# voicesynth (PSOLA synthesis) — research notes

## Module structure (mod_7, LuaJIT bytecode)
Exports: loadvoice, speak, seting_intonace, working_intonace.
Internal symbols: loadphrase, Prosody/ProsodyChange/ProsodyDifference, Silence,
Reset, pitch, tempo, phonecount, phoneorder, data2, dictconv, bit.rshift, buffer.

This is a diphone concatenation + PSOLA-style prosody synthesiser:
phonemes → phoneorder (diphone selection) → Prosody (pitch/tempo) → concatenate
sample blocks from Gintaras.dta → yield PCM buffers via coroutine.

## Offline harness progress
- Built lpeg.so (32-bit) against luajit-2.1 so the engine modules load on the host
  (engine/synth_research/lpeg32.so). voicesynth.loadvoice/speak are reachable.
- trans/translate stubbed (we use our own 100% C transcriber; voicesynth only
  needs trans.init present).
- BLOCKER: loadvoice(voiceEntry, name) crashes deep inside ("compare number with
  nil") because db.loaddatabase builds VOICES partly from uninitialised FFI memory
  (the dict region was shown earlier to be non-deterministic). The sample-block
  pool is solid; the per-voice phoneme INDEX that loadvoice expects needs the
  deterministic dict parse (our Java VoiceDatabase already parses it correctly).

## Path forward
Two options for PSOLA ground truth:
1. Feed voicesynth a VOICES table built by OUR deterministic parser (VoiceDatabase)
   instead of the engine's non-deterministic loaddatabase, then capture speak()'s
   yielded PCM as reference.
2. Decode loadphrase/Prosody directly from bytecode.
Option 1 is the cleaner route and reuses stage-2 work. Next session: marshal our
parsed voice entry into the Lua VOICES shape loadvoice expects, run speak, capture
PCM.

## Update: deterministic parser ported to Lua; voice structure clarified
Ported our verified .dta parser to Lua (deterministic_parser.lua): matches Java
exactly (5928 sample blocks, 1221 dict entries). Confirmed dict entry names are
UTF-16LE DIPHONE names (e.g. 2d00 e100 = "-á", 6c00 5901 = "l-soft"), with record
counts 1..31 (1 = a sample-block reference, 11-12 = diphone variant sets).

loadvoice expects ONE voice table with ~160 records of {key,count,typ}. So the
engine's loaddatabase AGGREGATES the 1221 dict entries into a per-voice phoneme
index keyed by diphone. The non-determinism we saw was only in how it read a few
trailing records from FFI memory; the deterministic file parse gives the true set.

Next: build the aggregated 160-record voice table from our deterministic entries
(group by diphone, attach sample-block arrays), feed to voicesynth.loadvoice +
speak, capture PCM as PSOLA ground truth.

## Full PSOLA pipeline mapped (voicesynth sub-protos)
Extracted each nested proto's string constants (proto_strings.lua), revealing the
complete synthesis architecture:

- **loadvoice (proto@-43)**: builds prosody tables P0..P8 + Prosody/Reset/
  ProsodyChange/ProsodyDifference/Silence from the voice; loads DICT
  (loaddictionary) and loadphrase.
- **speak (proto@-53)**: phrase driver. Lowercases, splits on punctuation
  `.!?,;:()[]{}` (sentence vs clause vs word breaks), runs dictconv/translate per
  token, applies tempo, and emits via Prosody*/Silence using P0..P8.
- **diphone selection (proto@-49)**: count, typ, data, phonecount, phoneorder,
  data2 — picks the diphone sample run for each phoneme pair (bit.band on typ).
- **pitch/buffer DSP (proto@-47,-48)**: bit.rshift, math.floor, pitch, min, buffer
  — the PSOLA resampling/overlap (pitch shifting by index stepping).
- **generator (proto@-46)**: coroutine.yield of buffer chunks + collectgarbage +
  step — streams PCM out in MAXSIZE blocks.

## Synthesis algorithm (reconstructed)
1. speak() tokenises the phrase, classifies breaks from punctuation.
2. Per token: translate → phonemes; map phoneme pairs to diphone runs via
   phoneorder/typ (proto@-49).
3. Prosody (P0..P8) sets pitch contour + durations (tempo).
4. For each diphone: read its int16 sample block, pitch-shift via index stepping
   (rshift/floor), overlap-add into the output buffer (proto@-47/-48).
5. Silence inserts inter-word/sentence pauses; coroutine yields PCM blocks.

This is enough structure to implement a diphone-concatenation synthesiser in
C/Java: we already read the sample blocks (stage 2) and produce phonemes (stage 3,
100%). The remaining work is diphone selection (phoneorder/typ) + the pitch/
overlap DSP. A first audible version can use plain concatenation (no pitch shift),
then add PSOLA prosody.
