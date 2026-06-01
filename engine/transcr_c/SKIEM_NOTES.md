# skiem() decode (@0xd0114, 776 bytes) — syllabification

Walks the word BACKWARDS placing syllable boundaries by marking bit flags in a
parallel class buffer (bit0 = boundary-ish, bit1 = vowel/marked).

Sets used (extracted):
- vowels  `AĄEĘĖIYĮOUŪŲ` (TR_SYLL_VOWELS) — syllable nucleus
- sonorants `JLMNRV` (TR_SONORANTS) — may onset the next syllable
- obstruents `BDGKPTCČFH` (TR_SYLL_OBSTR) — force a break
- digraph guards: `SŠ`/`ZŽ` combine with preceding `D`/`C` (dz, dž, dž clusters
  not split); `H` after `C` (ch) kept together.

Algorithm sketch:
1. From end, find a vowel (nucleus).
2. Walk left over sonorants; decide boundary using the obstruent/sonorant class
   of the consonants between this nucleus and the previous one (max-onset style).
3. Mark boundary bit; special-case dz/dž (Z/Ž preceded by D) and ch (H after C),
   and the `0x20` space → word boundary path.
4. A trailing table @0x1af914 (records stepped by 4, length 0x224) applies a
   secondary fixup via helper 0xc71bc (likely strncmp against suffix patterns).

Output: in-place class-flag buffer marking syllable starts; consumed by stress
(Kirciuoti) and durations (ilgiai).
