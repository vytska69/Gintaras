# Stress / accentuation findings

## What KircTranskr already encodes
KircTranskr's per-word output encodes vowel LENGTH/quality directly in the phoneme
symbols: aA, eA, eE, i, iI, oO, u, uU. Our tr_transkr reproduces these 100% across
515 words (3 corpora). So citation-form transcription — what a single word maps to
— is functionally complete.

## The deeper stress layer
The phoneme TABLE contains three variants per long vowel (e.g. aa / Aa / aA) that
encode STRESS (which syllable carries the accent + rising/falling tone). In
single-word KircTranskr output only the default (aA-style) appears; the actual
per-word stress PLACEMENT comes from the runtime stress dictionary
(36-byte records: word-root + context, located earlier at .data ~0x27fef0) applied
by Kirciuoti within the full KircTranskr1 phrase pipeline.

## Decision for the engine
For intelligible Lithuanian TTS, the citation-form phonemes we already produce are
the primary signal; correct lexical stress is a quality refinement. Plan:
1. Ship the 100% grapheme→phoneme stage now (done).
2. Stress placement (Kirciuoti + the stress dictionary) is a later quality pass —
   it needs the large stress lexicon dumped from the oracle, which is a substantial
   sub-project on its own. The synthesis (PSOLA) and audio path (stages 4-6) can be
   built and made audible first using citation-form stress, then refined.
