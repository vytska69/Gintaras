# PradApdZod() decode (@0xd0f58, 1676 bytes) — word preprocessing

The front-end's first stage (called before KircTranskr). It segments and expands
the input, building the normalised buffer the rest of the pipeline consumes.

## Responsibilities (from disassembly)
- Calls **FrazesPabaiga** (phrase-end handling) first.
- Walks the input grouping runs of letters (isalpha1) vs non-letters.
- **Numbers**: digit runs (isdigit1) up to 12 digits, with ',' and '%' handling,
  expanded via **VisasSkaicius** (number→words) into the output.
- **Symbols/punctuation**: non-letter chars expanded via **SimbPavad** (symbol
  name) — e.g. '=', '-', '+' get spoken names.
- **Single letters / abbreviations**: words of length 1, or all-consonant runs,
  spelled out via SimbPavad; the vowel test uses table
  `ĄąĘęĖėĮįŲų... AaEeIiYyUu` (@0xa07e4) to decide if a short token is a word.
- Applies per-char substitutions via **Keisti1**.
- Inserts '+' (0x2b) phrase/stress markers and ' ' (0x20) separators; collapses
  trailing spaces; ensures a leading '+' marker.

## Tables (extracted)
- Vowel set with case pairs `ĄąĘęĖėĮįŲųŪūAaEeIiYyOoUu` (@0xa09e8).
- Short-word vowel test set `ĄąĘęĖėĮįŪūAaEeIiYyUu` (@0xa07e4, note: no O).
- Marker chars: '+', ' ', '-', '=', ','.

## Implication
PradApdZod is text normalisation (numbers, symbols, abbreviations, phrase markers)
— substantial but mechanical. The per-character class FLAGS the later stages read
are set here via Keisti1 + the '+'/' ' markers. Reimplement after the core g2p so
plain alphabetic words work first; number/symbol expansion can follow.
