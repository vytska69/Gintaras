# Gintaras — Lithuanian Text-to-Speech for modern Android

A from-scratch, **pure-Java** Lithuanian TTS engine for the *Gintaras* voice,
running on any modern Android device (**arm64-v8a** included) with **no native
libraries**.

The original *WinTalker.Voice* (2015) engine went silent on recent Android: it is
a 32-bit-only stack (`librosasofttts.so` + a 2015 LuaJIT `libluajit.so` +
`main.bin` bytecode) that breaks under modern Android's W^X/JIT and ABI rules.
Rather than patch the old engine, this project **reimplements it from scratch in
Java**, reusing only the voice *data* (`Gintaras.dta`).

## What works

| Capability | Status |
|---|---|
| Installs & runs as an Android TTS engine | ✅ on device |
| arm64-v8a (and all ABIs) | ✅ pure Java, no `.so` |
| Text → phonemes (grapheme-to-phoneme) | ✅ 100% match vs original over 515 words |
| Voice data reader (`Gintaras.dta`) | ✅ byte-exact (5928 sample blocks) |
| Diphone synthesis → audible speech | ✅ functional (intelligible; quality WIP) |
| Material You UI | ✅ dynamic colour on Android 12+ |

## How it works

```
text → Transcriber → phonemes → DiphoneSynth → 16-bit PCM → Android TTS callback
                                      ↑
                                Gintaras.dta (diphone sample blocks)
```

1. **Transcriber** (`app/.../engine/Transcriber.java`) — Lithuanian
   grapheme→phoneme rules (palatalisation, voicing assimilation, diphthong
   glides, digraphs `ch/dž`, sonorant variants). A pure-Java port of the verified
   C reimplementation; matches the original `libtranscr.so` exactly on 515 words.
2. **VoiceDatabase** (`engine/VoiceDatabase.java`) — parses `Gintaras.dta`: 5928
   int16 pitch-period sample blocks + a diphone dictionary (`-XY` units) + the
   prosody tables `P0..P8`.
3. **DiphoneSynth** (`engine/DiphoneSynth.java`) — selects boundary-prefixed
   diphone units for each phoneme pair, concatenates their pitch periods, steadies
   voiced periods (per the decoded `proto7` DSP), and emits PCM.
4. **TtsService** — pure-Java `TextToSpeechService`; no JNI, no LuaJIT.

## Repository layout

```
app/                          Android app (pure-Java engine, Material You UI)
  src/main/java/.../engine/   Transcriber, VoiceDatabase, DiphoneSynth
  src/main/assets/Gintaras.dta    the voice data (only retained binary asset)
engine/
  transcr_c/                  verified C transcriber + golden corpora (oracle ref)
  synth_research/             decoded synthesis notes + bytecode protos
  extracted/                  original APK artefacts (engine .so, main.bin) — reference only
tools/                        .dta parser, validators, build helpers
.github/workflows/            CI: builds & releases the debug APK (all ABIs)
```

## Building

CI builds and publishes the debug APK on every push to `main` (the rolling
`latest` GitHub release). Locally: `./gradlew assembleDebug`.

The APK contains **no `.so` files** and declares no `abiFilters`, so it is
architecture-independent — Android's ART compiles the dex to native code for the
device, running arm64 natively on 64-bit phones.

## Provenance & licensing

Reverse-engineered from the 2015 *WinTalker.Voice* APK for interoperability and
preservation of the Lithuanian *Gintaras* voice on modern Android. The synthesis
code is a clean reimplementation; only the voice data file is reused. See
`engine/synth_research/` for how the original algorithm was decoded.
