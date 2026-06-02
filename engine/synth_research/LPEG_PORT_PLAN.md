# Porting translate's LPeg grammar to Java (path A)

## What translate is
`translate` (mod_6, 9 protos) builds an LPeg grammar over the UTF-16LE phoneme
string and runs it to emit the demi-syllable unit-name sequence. The grammar uses:
- P, S(set), R(range), V(non-terminal), Cmt (match-time capture), Ct/Cg/Cs/Cp.
- Cmt callbacks (protos #3..#8) build a CANDIDATE unit key from
  `prefix .. sub(s, pos, pos+1) .. suffix` and test `VOICES[key] ~= nil`
  (and read `entry[1]`); they return the match position only if the unit exists.
  → selection is DATA-DRIVEN against the voice table, which is why fixed rules
    plateau (~42%).
- proto #9 = `numbers` (builds N..+..R intonation keys for digit groups).

## Decoded Cmt pattern (e.g. proto#3)
candidate = prefix .. s:sub(pos, pos+1) .. suffix    -- one phoneme window
if VOICES[candidate] then return pos+1 (advance), emit candidate
Variants (#4..#8) use windows sub(pos), sub(pos-4,pos-3), sub(pos-6,pos-3),
sub(pos-6,pos-5) and the "|" marker — i.e. they look at the previous/next phoneme
to decide left/right half-units and codas, always gated by VOICES existence.

## Port design (minimal LPeg in Java)
1. `Lpeg` core: Pattern objects with match(input, pos) → newPos or -1.
   Combinators: literal P(str), any P(n), set S(chars), range R, sequence (*),
   ordered choice (+), star (^0/^1 via repetition), Cmt (predicate+capture).
2. Recreate the exact grammar from proto#1 (the pattern wiring) and the Cmt
   callbacks #3..#8 (candidate-key + VOICES check).
3. Output: the ordered list of matched unit-name strings.
4. Validate against gold_translate_seq.tsv to 100% (same method that took the
   transcriber to 100%), then wire into DiphoneSynth.

## Status
Cmt callbacks decoded (candidate = window+affix, gated by VOICES). Next: implement
the Lpeg core + the grammar wiring from proto#1, validate vs gold.

## KEY: units = the VOICES "hits" (candidate keys that exist) — no full LPeg needed
Instrumented translate with a VOICES proxy logging which candidate keys it queries
and which EXIST. Result: the output unit sequence == exactly the keys that HIT, in
query order. So translate is: generate candidate keys per position, keep those
present in VOICES.

Candidate set per phoneme position (from labas/saule/lietuva traces), with ^=word
start, $=word end, -=unit boundary:
  ^CV, CV, ^CV-, CVc--, CV-, Vc, VcV--, Vc-, -CV, CV(next), ... -Vc$, c$, --CVc, -Vc
The HITS that form the sequence are the demi-syllable units: "Cv-", "-Cv" for each
onset+vowel, "Vc"/"-Vc" for codas, single vowels/consonants where those exist.

So the Java port does NOT need a general LPeg engine — it needs:
1. a left-to-right walk generating the same candidate keys per position, and
2. keep the ones present in the voice index, advancing appropriately.
This is tractable and exact (gated by the real voice table). Implement
CandidateSequencer in Java, validate vs gold to 100%.
