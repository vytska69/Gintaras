# Differential testing harness (transcription)

The C transcriber is verified by DIFFERENTIAL TESTING against the deterministic
oracle, not by trusting any single decoded table:

1. `gen_reference.py libtranscr.so <wordlist>` → golden TSV (word → phonemes).
   Regenerable for ANY word list; the oracle is deterministic (verified).
2. The C `KircTranskr` runs on the same list; outputs are compared phoneme-by-
   phoneme. CI fails on any mismatch and reports accuracy %.
3. golden_big.tsv (347 words: all vowel×consonant probes + common vocabulary) is
   the committed regression target. Grow it as coverage expands.

This makes the port measurable: each implemented rule moves the accuracy number,
and we always know exactly which words differ from the original engine.
