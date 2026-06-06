# Gintaras — Lithuanian Text-to-Speech for modern devices

A from-scratch, **pure-Java** Lithuanian TTS engine for the *Gintaras* voice,
running on any modern Android device (**arm64-v8a** included) with **no native
libraries**. Reverse-engineered from the 2015 *WinTalker.Voice* product, it
reuses only the voice *data* — every line of synthesis, transcription and text
normalisation is reimplemented and validated byte-for-byte against the original.

The original engine went silent on recent Android: it is a 32-bit-only stack
(`librosasofttts.so` + a 2015 LuaJIT `libluajit.so` + `main.bin` bytecode) that
breaks under modern Android's W^X/JIT and ABI rules. Rather than patch the old
binary, this project **reimplements it in Java**, keeping only `Gintaras.dta`.

> **Ground truth, not guesswork.** The original synthesiser (LuaJIT `voicesynth`
> over `Gintaras.dta`) can be run under emulation to produce reference PCM, and
> the transcriber (`libtranscr.so`) runs under a Unicorn ARM oracle. Every stage
> of the Java port is diffed against that real output.

## What works today

| Capability | Status |
|---|---|
| Installs & runs as an Android system TTS engine | ✅ on device |
| arm64-v8a (and all ABIs) | ✅ pure Java, no `.so` |
| Text → phonemes (grapheme-to-phoneme) | ✅ bit-identical to original `libtranscr` over 515 words |
| Voice data reader (`Gintaras.dta`) | ✅ byte-exact (5 928 sample blocks + dictionaries) |
| Diphone synthesis → audible speech | ✅ matches the original engine's own audio at 0.92–0.99 correlation for most words |
| Palatalisation (soft `l' d' s' n' č' …`) | ✅ unit sequence matches original (was the main garble cause) |
| Number reading | ✅ data-driven from the voice's own number tables (+ digit-group / decimal / negative modes) |
| Abbreviations / foreign words (`wifi→vaifai`, `google→gūgll`) | ✅ `stdlit.dct` |
| Punctuation reading + spoken pauses | ✅ four verbosity levels (`punc0–3lit.dct`) |
| Cyrillic / Greek transliteration | ✅ `ruleslit.rul` |
| Acronym letter-by-letter spelling | ✅ `spelllit.dct` path |
| Material You UI | ✅ dynamic colour on Android 12+ |

## How it works

```
text
 └─ TextNormalizer  ── transliterate (ruleslit) · numbers · dictionary · punctuation · spelling
      └─ Transcriber ── Lithuanian grapheme → phoneme (G2P)
           └─ DiphoneSynth ── unit selection (CandidateSequencer) · pitch-period DSP
                └─ 16-bit PCM → Android TTS callback
                        ↑
                  Gintaras.dta  (diphone sample blocks, number tables, prosody P0..P8)
```

1. **TextNormalizer** (`engine/TextNormalizer.java`) — the "reading" layer, a port
   of the original `voicesynth` speak loop + `dictionary` module. Applies the
   Cyrillic/Greek transliteration rules, expands numbers, substitutes the standard
   dictionary, voices/strips punctuation per the chosen level, and spells acronyms.
2. **Transcriber** (`engine/Transcriber.java`) — Lithuanian grapheme→phoneme rules
   (palatalisation, voicing assimilation, diphthong glides, digraphs `ch/dž`,
   long/short vowels). Matches the original `libtranscr.so` exactly on 515 words.
3. **VoiceDatabase** (`engine/VoiceDatabase.java`) — parses `Gintaras.dta`: int16
   pitch-period blocks, the diphone dictionary (UTF-16 code-unit names — the high
   byte distinguishes the special Lithuanian letters), the number-word tables, and
   the prosody tables `P0..P8`.
4. **CandidateSequencer** (`engine/CandidateSequencer.java`) — the demi-syllable
   unit selector, a faithful port of the original `translate` (root.22), including
   the `|` palatalisation marker that picks the soft units.
5. **DiphoneSynth** (`engine/DiphoneSynth.java`) — assembles the selected units'
   pitch periods with the original's count/tempo resampler and join filter
   (`voicesynth` root.44–52), and emits PCM.
6. **TtsService** — pure-Java `TextToSpeechService`; no JNI, no LuaJIT.

## Roadmap & project future

This is active work. Near-term, in rough priority:

- **🪟 Older voice from the Windows version (soon).** We are disassembling an
  earlier build of the *Gintaras* voice from the **Windows** edition of WinTalker.
  That voice/engine is expected to **sound better** than the 2015 Android data, and
  will be offered as an alternative (and likely default) voice once integrated.
- **🍎 iOS / macOS port (soon).** A native Apple version is planned. The engine is
  deliberately dependency-free (pure data + portable DSP), so the synthesis core is
  intended to be shared across Android and Apple platforms.
- **🔊 Synthesis polish.** Most words already match the original's own audio
  (0.92–0.99). Remaining work: a few unit-selection edge cases (e.g. word-initial
  long-`ō` and the `uo` diphthong in *žodis* / *obuolys*) where `CandidateSequencer`
  still diverges from the original `translate`.
- **🎵 Prosody.** The shipped voice is near-monotone by design (its native
  intonation/stress generators were dormant in the original product). Faithful,
  optional intonation (the P-table contour: gentle declination, question rise) is
  prototyped and may be re-enabled behind a setting.

## Repository layout

```
app/                          Android app (pure-Java engine, Material You UI)
  src/main/java/.../engine/   TextNormalizer, Transcriber, CandidateSequencer,
                              VoiceDatabase, DiphoneSynth, NumberExpander
  src/main/assets/            Gintaras.dta + dictionaries (stdlit/spelllit/punc*/ruleslit)
engine/
  decompiled/                 human-readable decompilation of the original modules
  transcr_c/                  verified C transcriber + golden corpora (oracle ref)
  synth_research/             reference harnesses, decoded notes, bytecode protos
  extracted/                  original APK artefacts (engine .so, main.bin) — reference only
tools/                        .dta parser, validators, synthesis test harnesses
.github/workflows/            CI: builds & releases the debug APK (all ABIs)
```

## Building

CI builds and publishes the debug APK on every push to `main` (the rolling
`latest` GitHub release). Locally: `./gradlew assembleDebug`.

The APK contains **no `.so` files** and declares no `abiFilters`, so it is
architecture-independent — Android's ART compiles the dex to native code for the
device, running arm64 natively on 64-bit phones.

## Provenance & licensing

Reverse-engineered from the *WinTalker.Voice* product for interoperability and
preservation of the Lithuanian *Gintaras* voice on modern platforms. The engine
(transcription, normalisation, synthesis) is a clean reimplementation; only the
voice data files are reused. The original libraries were confirmed by disassembly
to contain no hidden DSP (`librosasofttts.so` is LuaJIT + LPEG + JNI glue;
`libtranscr.so` is the grapheme-to-phoneme front-end) — the complete synthesis
algorithm lives in the decompiled Lua under `engine/decompiled/`.
