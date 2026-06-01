# skiem() / syllabification findings (corrected via proper pipeline context)

## Key methodology correction
skiem must be tested AFTER PradApdZod (word preprocessing), not on a raw word.
PradApdZod normalises, appends a trailing space (0x20), AND sets class-flag bits
on characters. skiem's disassembly does `tst #1; addne #1` — it increments a byte
ONLY if bit0 is already set. So in the real pipeline skiem's net change is small;
the actual syllable/flag information is largely established by PradApdZod.

## Measured behaviour (after PradApdZod)
- Vowel-initial words: pos 0 gets +1.
- Consonant-initial: pos 0 marked for first char in {C,G,K,M,S}, not for
  {B,D,F,H,J,L,N,P,R,T,V,Z}. This reflects which chars PradApdZod pre-flagged
  (bit0), confirming the signal originates in PradApdZod.

## Implication
The transcription flag state is built primarily by **PradApdZod**, then refined by
skiem and the rule matcher. Next: decode PradApdZod (@0xd0f58) to recover how it
sets the class-flag bits, since that is the true source of the per-char flags the
whole pipeline depends on. The differential harness already supports measuring
each stage against the oracle.
