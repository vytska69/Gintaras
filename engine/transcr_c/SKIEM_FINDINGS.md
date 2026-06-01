# skiem() empirical findings (from oracle differential testing)

skiem marks word/syllable boundaries by +1 on class-flag bytes. Measured behaviour
over the corpus (golden_skiem.tsv):

- **Last position** is marked when the final char is a consonant, or when the word
  ends in a vowel that forms a diphthong/long nucleus (e.g. KALBA's final A is
  marked, AI/AO both positions). Pure short final vowels in 2-letter probes differ.
- **Position 0** is marked when the first char is a vowel (ABRAKADABRA→{0,..};
  LABYRINTAS→{9} only). Consonant-initial words don't mark pos 0.
- Longer words mostly reduce to {0?, last} because internal syllable flags use a
  DIFFERENT bit than the one these probes detect — skiem sets bit0 for some marks
  and the vowel pass sets bit1; our delta test sees the net low-byte change.

## Current C port status
tr_skiem (skiem.c) implements the backward nucleus walk; measured 33.6% exact
match on ASCII probes. The remaining gap is the boundary-vs-vowel bit semantics:
need to track bit0 (syllable) and bit1 (vowel) separately, matching the original's
`tst #1`/`tst #2` tests, rather than a single increment. Next: split the flag
bits and re-measure.

The accuracy harness (test_skiem_accuracy.c) gates this: it reports exact-match %
against the oracle so each refinement is measured, not assumed.
