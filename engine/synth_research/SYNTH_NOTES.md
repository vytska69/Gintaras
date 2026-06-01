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
