# Gintaras TTS — from-scratch rewrite roadmap

## Goal (user requirements)
1. **arm64-v8a** support (modern devices) — requires dropping the 32-bit-only
   native libs (librosasofttts.so, libtranscr.so) entirely.
2. **Full app + engine rewrite** in modern code (Kotlin) for current Android.
3. **Material You** UI (dynamic color, Android 12+).

## Why a rewrite is mandatory for arm64
The proprietary engine ships ONLY as armeabi-v7a (32-bit):
  - librosasofttts.so (34 KB) — thin LuaJIT host
  - libtranscr.so   (2.6 MB) — Lithuanian linguistic front-end
  - libluajit.so    (400 KB) — 2015 LuaJIT 2.1-alpha
Plus main.bin (LuaJIT bytecode = all engine logic).
None run on a 64-bit-only process. So arm64 ⇒ reimplement everything in Kotlin,
keeping ONLY the data: Gintaras.dta (waveform pool) + dictionaries/rules.

## Verification strategy (no guessing)
The original modules run offline under host LuaJIT and serve as an ORACLE that
produces reference inputs/outputs for each stage. Every Kotlin component is
checked against the oracle before moving on. (See oracle_database.lua.)

## Stages
- [x] **0. Data format** — Gintaras.dta parsed & validated (5,940 int16 sample
      blocks = the voice). database module runs offline as oracle.
- [x] **1. Material You** — DynamicColors in GintarasApp; M3 palette + dark.
- [ ] **2. .dta reader (Kotlin)** — port loaddatabase: sample blocks + phoneme
      dictionary (records {key,count,typ}). Validate vs oracle dump.
- [ ] **3. Grapheme→phoneme (the hard one)** — reimplement Lithuanian
      transcription currently in libtranscr.so (KircTranskr etc.) + ruleslit.rul.
      Validate vs reference_transcriptions.tsv (72 words) and the libtranscr
      oracle (transcr_oracle.py exists under _work/oracle).
- [ ] **4. Prosody** — durations/stress/tones (ilgiai/tonai in libtranscr).
- [ ] **5. Synthesis (PSOLA)** — port voicesynth: concatenate/pitch-shift sample
      blocks into 22050 Hz PCM. Validate sample-peak vs a known-good reference.
- [ ] **6. New TtsService** — pure-Kotlin onSynthesizeText using stages 2–5,
      no JNI/native libs. Enable arm64-v8a (+ armeabi-v7a) abiFilters.
- [ ] **7. UI polish** — Material You screens for preview + settings.

## Current risk notes
- Stage 3 (transcription) is the biggest unknown: 2.6 MB of compiled linguistic
  rules. Reference data + oracle make it tractable but it is the long pole.
- Until stage 6 lands, the app keeps the existing (armeabi-v7a) engine so it
  still installs; arm64 turns on only when the Kotlin engine is ready.
