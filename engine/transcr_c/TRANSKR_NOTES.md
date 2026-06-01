# transkr() decode notes (@0xd041c, 1532 bytes, 377 insns)

Grapheme→phoneme core. Signature (from KircTranskr1 call):
  transkr(char *out, int outflag, const char *in, ..., len-ish, flag@sp)

## Phases decoded
1. **Boundary + length** (d0428–d0474): appends "_\n"; gets strlen(in); if <=1 bail.
2. **Vowel-class pass** (d04a8–d04fc): scans input BACKWARDS (`ldrb [r5,#-1]!`),
   uses vowel set `AĄEĘĖIYĮOUŪŲ` (0xd2fac); marks each char vowel(1)/cons(2) into a
   side buffer; special-cases 'J'(0x4a) and palatalisation context.
3. **Voicing assimilation** (d0500–d05a4): obstruent set `BDGPTKSŠZŽCČ` (0x171910)
   and voiced subset `BDGZŽ` (0x171920); regressive assimilation walking backwards,
   toggling voiced/unvoiced state (the `r6`/`r7` 2-state machine).
4. **Emit with boundaries** (d05a4–d0738): writes '_' (0x5f) boundaries; per char,
   reads class nibble (`>>4` = count, `&8` = flag) and appends mapped phoneme(s)
   via memcpy-like helper 0xc71a4/strcat 0xc7198.
5. **Trailing boundary** (d0738–d0774): ensures final "_"; returns out length+1.

## Tables (extracted)
- Vowels `AĄEĘĖIYĮOUŪŲ` @0xd2fac; voiced cons `BDGZŽ`; obstruents `BDGPTKSŠZŽCČ`.
- Phoneme-output strings @0xd2e3c..0xd2fa0 (i,e,a,o,u, diphthongs, all consonants
  + palatalised ' variants) — see extract_tables.py TODO.
- cp1257 case-fold pairs @0x17196c (TR_CASEFOLD) — extracted.

## Still to pin
- The exact per-grapheme record layout that yields the class nibble + which
  phoneme-output string index. Indexed with a stride near the 0x9b(155) limit.
  Validate emitted phonemes vs the oracle word-by-word once coded.
