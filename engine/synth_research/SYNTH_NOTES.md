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
