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

## Rule-matching engine (d0800–d0978) — DECODED structure

transkr's emission is a CONTEXT-SENSITIVE REWRITE engine. Per input position it
scans a rule table (base `sb`, stride 28 bytes/record) testing context predicates,
and on first match emits the rule's phoneme string and advances.

### 28-byte rule record fields (offsets within record)
- +0x00: char-set ptr — left-context (strchr test on prev char)
- +0x08: char-set ptr — current/right test
- +0x0c: char-set ptr — right+1 context
- +0x10: byte mask (AND against a per-position class byte) + record advance base
- +0x14: ptr — OUTPUT phoneme string (what gets appended)
- +0x18: advance (input positions consumed on match)
- +0x19: skip (positions to jump when class-limit 0x9b/155 exceeded)
- +1,+2,+3: additional flag/mask bytes (3-bit class checks, `&7`, `&4`)

### KEY FINDING: the rule table is BUILT AT RUNTIME, not static .rodata
`sb` resolves to `*(0x27ff94)` = **0x288c84 (.bss)**. So init_transcr (via the
`auto_rules` source array @0xa020c and auto_rules_function, which works on 0xa4=164
byte structures) CONSTRUCTS the rule table in memory at startup.

### Implication for the port
Two options to get the EXACT rules:
1. Dump the constructed table from the oracle's memory after init_transcr
   (uc.mem_read at 0x288c84, walk 28-byte records) — gives ground-truth rules
   with no further instruction decoding. PREFERRED.
2. Decode init_transcr + auto_rules_function to rebuild it (more work).

Next session: implement option 1 — extend transcr_oracle to dump the rule table,
emit it as transcr_rules.c, then code the 28-byte matcher loop in C and validate
emitted phonemes vs gen_reference.py word-by-word.
