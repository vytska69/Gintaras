# Gintaras — Lithuanian Text-to-Speech for iOS / macOS

A native Apple port of the *Gintaras* Lithuanian voice, built on a **shared Rust
core** — a from-scratch, byte‑exact reimplementation of the 2015
*WinTalker.Voice* engine. It reuses only the voice *data* (`Gintaras.dta` + the
dictionaries); every line of synthesis, transcription and text normalisation is
reimplemented and validated byte‑for‑byte against the original.

> **Ground truth, not guesswork.** Every stage of the port is diffed against the
> real output of the original synthesiser (LuaJIT `voicesynth` over
> `Gintaras.dta`) and transcriber (`libtranscr.so`). The Rust core is in turn
> validated byte‑identical to that reference across the full corpus.

On iOS the engine ships two ways:

1. **Host app** — a SwiftUI app to preview the voice and configure reading
   settings (punctuation, number grouping, pitch, pauses, dictionary), stored in
   a shared App Group.
2. **System voice** — an `AVSpeechSynthesisProviderAudioUnit` *Speech Synthesis
   Provider* extension (iOS 16+), so "Gintaras" appears as a Lithuanian voice
   everywhere: VoiceOver, Spoken Content, and any app using `AVSpeechSynthesizer`.

## How it works

```
text
 └─ TextNormalizer  ── transliterate (ruleslit) · numbers · dictionary · punctuation · spelling
      └─ Transcriber ── Lithuanian grapheme → phoneme (G2P)
           └─ Sequencer ── demi-syllable unit selection (palatalisation marker `|`)
                └─ DiphoneSynth ── pitch-period PSOLA DSP · join filter · prosody
                     └─ 16-bit PCM → AVAudioPCMBuffer → AVSpeechSynthesisProvider
                              ↑
                        Gintaras.dta  (diphone sample blocks, number tables, prosody P0..P8)
```

The synthesis core is dependency‑free portable Rust (`core/`, crate
`gintaras-core`), exposed over a C ABI and packaged as an `xcframework` for
Swift. The same core powers other platforms; this branch is the Apple port.

## Repository layout

```
core/        shared Rust engine (gintaras-core): voicedb, transcriber,
             conversion, sequencer, synth, numbers, normalizer, engine, ffi
ios/         the Apple port — SwiftUI host app + system voice extension
  Resources/ Gintaras.dta + dictionaries (stdlit/spelllit/punc*/ruleslit)
  Sources/   GintarasKit (Swift wrapper) · GintarasApp · GintarasVoice
  project.yml / build-rust.sh / README.md
docs/        notes
```

## Building

See [`ios/README.md`](ios/README.md). In short (macOS + Xcode + Rust + XcodeGen):

```sh
cd ios
./build-rust.sh        # Rust core → GintarasCoreFFI.xcframework
xcodegen generate
open Gintaras.xcodeproj
```

## Provenance & licensing

Reverse‑engineered from the *WinTalker.Voice* product for interoperability and
preservation of the Lithuanian *Gintaras* voice on modern platforms. The engine
(transcription, normalisation, synthesis) is a clean reimplementation; only the
voice data files are reused. The original libraries were confirmed by disassembly
to contain no hidden DSP (`librosasofttts.so` is LuaJIT + LPEG + JNI glue;
`libtranscr.so` is the grapheme‑to‑phoneme front‑end).
