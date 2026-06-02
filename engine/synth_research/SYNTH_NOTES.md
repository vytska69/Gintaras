# voicesynth (PSOLA synthesis) — research notes

## Module structure (mod_7, LuaJIT bytecode)
Exports: loadvoice, speak, seting_intonace, working_intonace.
Internal symbols: loadphrase, Prosody/ProsodyChange/ProsodyDifference, Silence,
Reset, pitch, tempo, phonecount, phoneorder, data2, dictconv, bit.rshift, buffer.

This is a diphone concatenation + PSOLA-style prosody synthesiser:
phonemes → phoneorder (diphone selection) → Prosody (pitch/tempo) → concatenate
sample blocks from Gintaras.dta → yield PCM buffers via coroutine.

## Offline harness progress
- Built lpeg.so (32-bit) against luajit-2.1 so the engine modules load on the host
  (engine/synth_research/lpeg32.so). voicesynth.loadvoice/speak are reachable.
- trans/translate stubbed (we use our own 100% C transcriber; voicesynth only
  needs trans.init present).
- BLOCKER: loadvoice(voiceEntry, name) crashes deep inside ("compare number with
  nil") because db.loaddatabase builds VOICES partly from uninitialised FFI memory
  (the dict region was shown earlier to be non-deterministic). The sample-block
  pool is solid; the per-voice phoneme INDEX that loadvoice expects needs the
  deterministic dict parse (our Java VoiceDatabase already parses it correctly).

## Path forward
Two options for PSOLA ground truth:
1. Feed voicesynth a VOICES table built by OUR deterministic parser (VoiceDatabase)
   instead of the engine's non-deterministic loaddatabase, then capture speak()'s
   yielded PCM as reference.
2. Decode loadphrase/Prosody directly from bytecode.
Option 1 is the cleaner route and reuses stage-2 work. Next session: marshal our
parsed voice entry into the Lua VOICES shape loadvoice expects, run speak, capture
PCM.

## Update: deterministic parser ported to Lua; voice structure clarified
Ported our verified .dta parser to Lua (deterministic_parser.lua): matches Java
exactly (5928 sample blocks, 1221 dict entries). Confirmed dict entry names are
UTF-16LE DIPHONE names (e.g. 2d00 e100 = "-á", 6c00 5901 = "l-soft"), with record
counts 1..31 (1 = a sample-block reference, 11-12 = diphone variant sets).

loadvoice expects ONE voice table with ~160 records of {key,count,typ}. So the
engine's loaddatabase AGGREGATES the 1221 dict entries into a per-voice phoneme
index keyed by diphone. The non-determinism we saw was only in how it read a few
trailing records from FFI memory; the deterministic file parse gives the true set.

Next: build the aggregated 160-record voice table from our deterministic entries
(group by diphone, attach sample-block arrays), feed to voicesynth.loadvoice +
speak, capture PCM as PSOLA ground truth.

## Full PSOLA pipeline mapped (voicesynth sub-protos)
Extracted each nested proto's string constants (proto_strings.lua), revealing the
complete synthesis architecture:

- **loadvoice (proto@-43)**: builds prosody tables P0..P8 + Prosody/Reset/
  ProsodyChange/ProsodyDifference/Silence from the voice; loads DICT
  (loaddictionary) and loadphrase.
- **speak (proto@-53)**: phrase driver. Lowercases, splits on punctuation
  `.!?,;:()[]{}` (sentence vs clause vs word breaks), runs dictconv/translate per
  token, applies tempo, and emits via Prosody*/Silence using P0..P8.
- **diphone selection (proto@-49)**: count, typ, data, phonecount, phoneorder,
  data2 — picks the diphone sample run for each phoneme pair (bit.band on typ).
- **pitch/buffer DSP (proto@-47,-48)**: bit.rshift, math.floor, pitch, min, buffer
  — the PSOLA resampling/overlap (pitch shifting by index stepping).
- **generator (proto@-46)**: coroutine.yield of buffer chunks + collectgarbage +
  step — streams PCM out in MAXSIZE blocks.

## Synthesis algorithm (reconstructed)
1. speak() tokenises the phrase, classifies breaks from punctuation.
2. Per token: translate → phonemes; map phoneme pairs to diphone runs via
   phoneorder/typ (proto@-49).
3. Prosody (P0..P8) sets pitch contour + durations (tempo).
4. For each diphone: read its int16 sample block, pitch-shift via index stepping
   (rshift/floor), overlap-add into the output buffer (proto@-47/-48).
5. Silence inserts inter-word/sentence pauses; coroutine yields PCM blocks.

This is enough structure to implement a diphone-concatenation synthesiser in
C/Java: we already read the sample blocks (stage 2) and produce phonemes (stage 3,
100%). The remaining work is diphone selection (phoneorder/typ) + the pitch/
overlap DSP. A first audible version can use plain concatenation (no pitch shift),
then add PSOLA prosody.

## Diphone dictionary structure (decoded)
The 1221 dict entries are the diphone/triphone UNIT inventory. Names are UTF-16LE
where each char's LOW byte is a cp1257 phoneme symbol; high byte distinguishes
variants (0x00 plain, 0x01/0x1f/0x03/0x04 = positional/stress variants):
- '-' (0x2d, 868×) marks a word/syllable boundary within the unit
- Units look like '-án', '-ko', '-og', '-id', '-ul', 'lя-', 'sя-' — boundary +
  consonant-vowel or vowel-consonant diphones, some triphones ('-ch.', '-.dz').
- ~1064 phoneme-diphone entries + ~157 prosody/intonation rules (names with
  digits/'+' like 'N10+3R', 'N3+9R').
- Phoneme alphabet (low bytes): vowels a e i o u + á(0xe1) ė(0xeb) ó(0xf3), all
  consonants, and boundary markers.

Each entry's records ({key,count,typ}, count 1..31) point at sample blocks (the
key indexes the int16 waveform pool); typ&127 selects the variant; multiple
records = pitch/stress variants of the same diphone.

## Diphone selection (phoneorder, proto@-49)
For a phoneme sequence, the synth forms overlapping diphone keys (boundary-aware,
e.g. '_ l aA b aA s _' → '-l', 'la', 'ab', 'ba', 'as', 's-'), looks each up in the
dict, and for each chooses a record by typ (stress/pitch context), then reads that
record's sample block. This is classic diphone concatenation.

## Concrete next step
Build the diphone index in Java (reuse VoiceDatabase): map unit-name → records →
sample blocks. Then a first synthesiser concatenates the selected blocks for a
phoneme sequence (no pitch shift) to produce audible PCM. PSOLA pitch/overlap
(proto@-47,-48) is the quality layer added after.

## Unit segmentation clarified (from feedback: "gyntyrys" — recognisable!)
The synth DID produce recognisable Lithuanian (user heard ~"gintaras"), confirming
the model. Vowel timbre was off because the unit segmentation/vowel-char mapping
needs correction:

- Units are DEMISYLLABLES with a mandatory boundary: '-CV' (onset+vowel, e.g.
  '-la','-gi','-te','-to') and 'VC-' (vowel+coda, e.g. 'ta-','go-','gi-'). Plain
  2-phoneme units WITHOUT a boundary mostly DON'T exist (no 'ab','ba','as','la').
- So a word C1 V1 C2 V2 ... s is covered by overlapping demisyllables joined at
  vowel midpoints: '-C1V1' + 'V1C2...' style, NOT by my current adjacent-pair
  '-l','la','ab',... lookup (which only hit by luck).
- Vowel chars: short a e i o u = 0x61 65 69 6f 75; the engine's long/stressed
  vowels use internal codes á=0xe1, ė=0xeb, ó=0xf3, plus 0x07/0x0d/0x11; soft
  variants U=0x55, Y=0x59. My mapping forced aA→á(0xe1) everywhere, over-using the
  long form (hence the odd timbre). aA should usually map to short 'a' (0x61),
  long only when truly stressed/long.

## Correct synthesis approach (next)
1. Segment phoneme stream into the engine's demisyllable units (-CV / VC-),
   splitting at vowel centres so consecutive units overlap on the shared vowel.
2. Map vowels to the SHORT cp1257 char by default; use long codes only for the
   stressed/long phoneme variants the transcriber marks.
3. Concatenate the matched unit waveforms (still no PSOLA yet) and re-listen.
This should sharply improve vowel intelligibility.

## Overlap problem identified (feedback: "intaras" — initial g lost, i doubled)
Coverage is good: gintaras = -gi,-in,(-nt miss),-ta,-ar,-ra,-as — only the C-C
pair -nt is absent (clusters aren't stored as units; the boundary just breaks
there). The g IS present (-gi = 1885 samples).

The real issue is CONCATENATION of FULL overlapping diphones. Each '-XY' unit
spans from the centre of X to the centre of Y. Adjacent units share a phoneme:
-gi (g→i) then -in (i→n) BOTH contain a full 'i', so 'i' plays twice and the
short initial 'g' is swamped → "intaras". Classic diphone synthesis requires
joining units at the SHARED phoneme's midpoint, not concatenating them whole.

## Fix (the PSOLA join, next)
For a unit sequence U1=-XY, U2=-YZ sharing phoneme Y:
1. Locate Y's region in each unit (its pitch periods).
2. Emit U1 up to the middle of its Y, then U2 from the middle of its Y onward —
   so each shared phoneme is rendered once, with a smooth overlap-add at the join.
Since each unit's records ARE the pitch periods, we can split at a period
boundary near the unit's centre. This removes the doubling and restores onsets.
Then layer pitch/duration (PSOLA proper).

## loadvoice + speak fully dumped (the path to matching the original)
Dumped the key protos (engine/synth_research/protos/): loadvoice, the pitch DSP,
loadphrase/dictconv, phoneorder.

### loadvoice (proto@-43)
Builds prosody tables P0..P8 from the bucket entries' bytes:
- P0 = {Prosody = P0bytes[1]+[2], Reset = (P0[4]==1?1:2)}
- P1 = {ProsodyChange = {P1[5..10]}}
- P2 = {Prosody = P2[1]+[2], Silence = P2[3], Reset}
- P3 = {ProsodyDifference = P3[2], Silence = P3[3], Reset}
- P4 = {Silence = P4[3]}, P5/P6/P7 = {ProsodyChange = {P[5..10]}}
- P8 = {ProsodyDifference, ProsodyChange}
- sets pitch globals: g = P0[5]; g2 = P0[6]/100
Then loaddictionary + loadphrase(name).

### speak/dictconv (proto, 390 ops)
The phrase synthesiser. Uses P0.Prosody as the base pitch period scale, divides
the pitch params by 100, matches/lowercases the input, splits into tokens, and per
diphone applies the pitch DSP.

### pitch DSP (proto@-47/-48)
For each diphone period: pitch = floor(basePitch * ratio); copies the period's
'buffer' into the output stretched/compressed to 'pitch' length (PSOLA period
resampling), keyed by 'typ'. This is where my plain concatenation differs: the
original RESAMPLES each period to a target pitch length set by the prosody tables,
giving smooth pitch + correct durations — which is why my version sounds rougher.

## Conclusion for matching the original
My phoneme→diphone selection and period data are correct (same .dta). The gap is
the PROSODY-DRIVEN period resampling: the original sets a target pitch period from
P0/P2 and resamples each stored period to it, instead of using raw period lengths.
Implement: read P0..P8, compute base pitch period, resample each unit period to the
prosody target (linear/PSOLA), concatenate. This should match the original's pitch
and remove the roughness — the principled fix vs ear-tuning.

## Critical finding: offline loaddatabase is unfixably non-deterministic
Confirmed the engine's loaddatabase returns 101/116/167-record voice tables across
runs, with record keys pointing into UNINITIALISED host memory (visible garbage in
the keys). The per-voice phoneme index is built from FFI pointers that are only
valid in the original Android process memory layout — they cannot be reproduced on
the host. So an offline ORIGINAL reference is impossible via Lua, and the ARM .so
need bionic's /system/bin/linker (can't run under qemu+glibc).

Conclusion: our deterministic file parse is actually MORE correct than the engine's
offline run. The synthesis gap is the join/prosody algorithm, which we must derive
from the bytecode + the user's ear, not from a captured reference.

## Objective envelope of our 'labas' (energy per 10ms)
la: 0-100ms full (~28k), dip to ~2.5k at 110-160ms (b closure), ba: 160-300ms full
(~32k), s: 300-400ms decay. Both a's are equally loud and the l has no distinct
weaker onset — so the ear hears 'abas' (two strong a's dominate, l/b weak). This
matches how concatenated diphones behave without formant-aware amplitude shaping.

## Honest status
Without an original reference, ear-driven tweaks are hitting diminishing returns on
single phones. The pragmatic path: accept the current intelligibility level as a
v1, wire the engine into the arm64 app so it WORKS on-device (the primary goal —
modern Android, arm64, no native libs), and refine voice quality iteratively from
there. The transcription (100%) and data layers are solid; synthesis is functional
if rough.

## Progress running the ORIGINAL speak() offline (feeding our deterministic data)
Breakthrough on the blocker: loadvoice's 2nd arg is a MODE (number 0..8), not a
name. Feeding our deterministic voice table (VOICES={Gintaras={[diphone]=records,
P0..P8={bytes}, [idx]=sampleblock}}) and stubbing database.loaddatabase to return
it, loadvoice now builds the prosody tables P0..P8 correctly (verified: P0=
{10,10,0,1,220,62} etc.) and reaches loaddictionary/loadphrase.

It still throws 'compare number with nil' (debug hook: ~line 29 of an inner proto)
even for mode 2 (loaddictionary only) — so the fault is in loaddictionary or the
g=P0[5] tail, not loadphrase. loaddictionary likely expects a DICT/dictionary
structure (stdlit.dct etc.) we haven't provided.

This is the closest we've been to running the ORIGINAL synthesis offline with
correct data. Next: provide the dictionary inputs loaddictionary needs (or stub
DICT as an empty table with __index returning ""), then call speak() and CAPTURE
its PCM — the true reference to match our Java synth against.
run_original_speak.lua holds the harness.

## Session update: loadvoice prosody works; loaddictionary is the remaining wall
Confirmed: with our deterministic voice table + loaddatabase stub, loadvoice
builds P0..P8 prosody correctly. loaddictionary/loadphrase are INTERNAL (not
exports), so they can't be stubbed — loadvoice always calls them, and
loaddictionary throws 'compare number with nil' (a raw ISGE/ISEQN bytecode
compare, not via math.min) reading the dictionary tables.

The dictionary tables are the MAP2-prefixed bucket entries (D/S/V/E/B/N/P names:
'D.', 'DE', 'N10+3R' intonation rules, etc.). loaddictionary expects a specific
structure for these that our generic bucket parse doesn't satisfy. Decoding
loaddictionary's exact expectations is the next step to run the original speak().

## Pragmatic note
The app already WORKS on-device (pure-Java v1, arm64). Capturing the original PCM
is a quality-reference effort that's proving multi-layered (loadvoice→
loaddictionary→loadphrase→speak, each needing exact data shapes). The v1 synthesis
is functional; the original-reference path continues as a parallel quality track.

## DECODED the synthesis core (p11 + p8) — the algorithm we were missing
p11 (the per-phrase synth loop):
  total = Σ period.count           -- sum of all periods' count fields
  ratio = (target / total) or 1    -- prosodic tempo/pitch scale
  for each period: play(key, typ, count * ratio)

p8 (play one period to a target length):
  n = count (≥1); voiced = typ & 1
  loop n times: emit data (first sample-block period) then data2 (subsequent),
                i.e. REPEAT the period n times to reach the target length;
  uses phonecount/phoneorder for ordering.

KEY INSIGHT: each diphone record is ONE pitch period with a `count` (how many
times to emit it) and `typ` (bit0 = voiced). The original reaches a target
duration by REPEATING each period count*ratio times — a uniform pitch/tempo. Our
Java synth instead concatenates raw periods once, so durations and pitch drift.

In Gintaras.dta all records have count=1, typ∈{0,1}. So per period we emit it
round(ratio) times, where ratio normalises total length to the prosody target
(base period 220 from P0[5]). This is exactly the PSOLA period-repetition we
should implement: pick target period = 220, emit each stored period (resampled to
220) round(count*ratio) times. This is the principled fix derived from p8/p11.

## DECODED the real pitch DSP (proto7) — linear period interpolation, voiced-only
proto7 is the per-period sample writer. Algorithm:
- pitch = floor(prevPitch * ratio)        -- target output period length
- if typ == 1 (VOICED):
    take the source period 'buffer' and write 'pitch' samples by LINEAR
    INTERPOLATION across it: for i in 0..pitch-1:
      out[i] = buffer[i] + (delta_to_next * (i+1)) / (len+1)
    (lines 83-101: SUBVV delta, FORI loop, ADDVV/MULVV/DIVVV = lerp)
- if typ == 0 (UNVOICED): copy the buffer through unchanged (noise — must NOT be
  pitch-interpolated, or it sounds artificial).
- the result is appended to the output buffer (coroutine yields blocks).

KEY: only VOICED periods are resampled to the target pitch; unvoiced (consonants/
noise) are passed through as-is. My PSOLA failure resampled EVERYTHING with a Hann
window → monotone 'voice-changer'. The original uses simple per-period linear
interpolation, voiced-only, at a pitch that tracks prevPitch*ratio (not a fixed
constant). This is the precise DSP to port.

## .dta deep-dive + intonation system (per user suggestion to disassemble .dta)
Sample-block lengths (= pitch periods) span 178..230+, concentrated ~200-210
(≈105-124 Hz). Within a vowel they JITTER non-monotonically (e.g. -la: 205,218,
220,217,216,197,215,199,212,205,204,210) — NOT a smooth contour. typ=0x01 for all
voiced periods (just the voiced bit; no extra pitch metadata in the records).

So the natural pitch is NOT in the stored period order; the engine IMPOSES a pitch
via the intonation system:
- loadvoice sets seting_intonace = P0[5] = 220 (base pitch period), working_intonace
  = P0[6]/100 = 0.62 (pitch change rate).
- proto7: pitch = floor(working_intonace_pitch * ratio); each VOICED period is
  linearly resampled to that pitch; UNVOICED passed through.
- P1..P8 hold ProsodyChange/ProsodyDifference ramps applied per phrase position,
  so the pitch RISES/FALLS over the utterance (intonation contour), starting from
  base 220.

KEY: the roughness in our output is the raw period-length JITTER (178..230) played
back unmodified. The original resamples every voiced period to a smoothly-varying
target pitch (base 220 ± intonation), eliminating jitter while keeping a natural
contour. Our smoothVoiced→mean is a crude version; the principled fix is to
resample voiced periods to 220 with a gentle ProsodyChange ramp from P1/P5/P6 —
NOT a flat 220 (that was the monotone 'voice changer'); the ramp is what makes it
sound natural.

## CRITICAL DIAGNOSIS: units are NOT uniform diphones (full debug of all units)
Dumped every unit's records/blocks/typ/energy. Two DISTINCT kinds of '-XY' unit:

1. VOICED multi-period units (typ=1): e.g. -la = 12 periods ~200 samples each,
   energy rising then falling (a full voiced segment ending in the vowel). -ba,
   -vi, -du, -na, -in, -ta, -ra similar. These ARE proper CV/voiced diphones.

2. UNVOICED single-block units (typ=0): -as = ONE 2800-sample block, energy ~590
   = the 's' fricative noise; -ab = one 2200-block, energy 1538 = the 'b' closure;
   -ar = one 1378-block. These are NOT 'a+consonant' diphones — they are just the
   CONSONANT segment (fricative/stop), the vowel is NOT in them.

So my model 'every adjacent pair -XY is a diphone covering X→Y' is WRONG. The vowel
lives only in the voiced CV units; the VC-named units (-as,-ab,-ar) carry only the
trailing consonant. Concatenating -la + -ab + -ba + -as therefore plays
l-a (full) + b + b-a (full) + s — the vowels are right but consonants -ab/-ba
DOUBLE the b, and -as is just s. That mismatch is why words sound 'weird'.

## Implication for correct synthesis
The right rule is likely: for a phoneme string C1 V1 C2 V2 ... use CV units for
onset+vowel (-C V) and bare-consonant/coda units for the rest, NOT a -XY for every
pair. Need to determine the engine's actual unit-selection (phoneorder) rule:
which unit covers which phoneme span. This is the real fix — current selection
doubles/mismatches segments. Next: decode phoneorder's unit-picking from the
phoneme stream rather than guessing pairs.

## SOLVED: unit-selection rule (CV syllable units + coda units)
Classified all units: '-CV' (consonant+vowel) are voiced multi-period (the syllable
onset+nucleus); '-Vc' where c is an obstruent are C-only single blocks (the coda
consonant after that vowel). The correct selection walks the phoneme string:
  - at a consonant followed by a vowel → emit CV unit '-'+C+V (consumes both)
  - at a coda consonant (after a vowel, not before one) → emit '-'+prevVowel+C
    (the C-only coda unit), consumes the consonant only
  - vowels are carried by their CV unit
This covers each phoneme ONCE — no doubling:
  labas    = -la + -ba + -as(coda)        → l a b a s ✓
  gintaras = -gi + -in(coda) + -ta + -ra + -as(coda) → g i n t a r a s ✓
  du       = -du ✓
(remaining: diphthong glides like saule's u, and vowel-initial words — minor.)

This is the real fix for the 'weird' speech: previous pairwise '-XY' selection
doubled consonants/mismatched segments. Implement this CV+coda walker in
DiphoneSynth, joining CV→coda and CV→CV at the shared vowel with a short crossfade.

## BREAKTHROUGH: real unit keys are positional N…+…R (translate module), not -XY
The user's suspicion was right: my whole -XY / CV+coda diphone model is wrong.
The REAL unit selection is in the `translate` module (which I had been stubbing
all along!). translate.translate builds unit keys of the form:
   "N" + sub(input, a, b) + "+" + n + "R"   and   "N" + ... + "+" + n
i.e. the units named "N10+3R", "N3+8R" etc. seen in the .dta are POSITIONAL units
keyed by (phoneme-substring, position-offset, R-flag) — NOT simple phoneme pairs.

translate exports: translate(), numbers(). proto8 is the unit-key builder: it
walks the phoneme string in steps of 2, takes sub(s, lo, hi) windows, and
concatenates with "N"…"+"…"R" to form the dictionary key. So the diphone dict is
indexed by a positional/context scheme the engine computes in translate, then
voicesynth.loadphrase looks those keys up in VOICES.

IMPLICATION: to match the original I must run/port translate.translate to generate
the correct unit keys, instead of guessing -XY. translate.translate currently
errors ('index upvalue nil') because it needs initialisation (VOICES/context set by
loadvoice/loadphrase). Next: initialise translate's upvalues (or port proto8's
key-building) and feed our phonemes to get the REAL unit sequence.

## SOLVED for real: the unit scheme is DEMI-DIPHONE (half-units), from translate
Ran the REAL translate.translate(utf16(word), nil, voiceTable) with lpeg — it works
and returns the true unit sequence. The scheme is DEMI-DIPHONE (each phone split
into a left half 'X-' and right half '-X'), with vowels/onsets handled specially:

  labas    = la- -la  ba- -ba  -as
  du       = du- -du
  saule    = sa- au  le- -le
  gintaras = gi- -gi  -in  ta- -ta  ra- -ra  -as
  akis     = a  ki- -ki  -is
  vienas   = vi- ie- -ie  na- -na  -as
  mama     = ma- -ma  ma- -ma
  sruoga   = s  r  u-  uo- -uo  ga- -ga

Pattern (observed): a CV syllable "C V" emits  Cv-  -Cv  (left+right halves of the
CV unit). A coda/initial vowel emits a single '-Vc' or bare 'V'. Consonant clusters
emit bare consonants. All these units EXIST in .dta (la-,-la,ba-,-ba,-as,du-,-du,
sa-,au,le-,-le all found). So my '-XY pair' and 'CV+coda' models were both wrong;
the engine uses overlapping half-units.

PLAN: port translate's unit-sequencing (it's lpeg-based UTF-16 pattern matching).
Either (a) reimplement the demi-diphone splitter from these observed patterns, or
(b) run the actual translate offline to dump sequences for a wordlist. (a) is the
on-device path. The units exist; correct sequencing is the remaining work and is
now grounded in the real engine output, not guesses.

## Verified: full offline synthesis with the REAL translate sequence
Built the complete offline pipeline (gen_real_sequence.lua): run the actual
translate.translate(utf16(word), nil, voiceTable) → it returns an ordered list of
UNIT-NAME STRINGS that are EXACT keys into the voice table, e.g. labas →
{6c0061002d00='la-', 2d006c006100='-la', 620061002d00='ba-', 2d006200='-ba',
2d00610073='-as'}. Each key's records point at sample blocks; concatenating them
gives the audio. (My earlier 0-sample attempt mis-built the key; the element IS
the key directly.)

Result: labas 11587, lietuva 14012, gintaras 16982, saule 9500 samples; clean WAV
(3.36s, peak 32737). This is the FIRST synthesis using the authoritative unit
sequence rather than my guessed selection. Sent real_seq.wav for evaluation.

If correct, the on-device path is to PORT translate's sequencing. Since translate
is lpeg-based, the cleanest port may be a direct demi-diphone rule reproducing its
output, validated against translate over a wordlist (like we did 100% for the
grapheme→phoneme transcriber).

## translate sequencing RULE fully derived (for Java port)
Ran translate on many words; the rule is clean demi-syllable splitting:
  - C V (consonant+vowel) → emit "Cv-" (left half) then "-Cv" (right half)
      ta → ta- -ta ;  sa → sa- -sa ;  kava → ka- -ka va- -va
  - word-initial / hiatus vowel V → emit "V" alone
      as → a -as ;  aaa → a a a
  - coda consonant after a vowel → emit "-Vc"
      namas → na- -na ma- -ma -as
  - consonant in a cluster before a consonant → emit "C" alone
      ksa → k sa- -sa ;  tra → t ra- -ra
So each CV is rendered by TWO overlapping half-units (left "Cv-", right "-Cv");
vowels alone and codas/cluster-consonants are single units. The ė loss earlier was
ONLY because my test words were ASCII ('saule' not 'saulė'); with cp1257 ė=0xeb the
correct lė- /-lė units are selected.

This is portable to Java and validatable against translate over a wordlist (as the
transcriber hit 100%). PLAN: implement this demi-syllable sequencer in DiphoneSynth
(replacing the wrong CV+coda single-unit selection), keep direct period concat.

## saulė diagnosis: sequence is CORRECT; missing piece is PROSODY
saulė → sa- + au + lė- + -lė covers s,a,u,l,ė exactly; ė present (0xeb), -lė is 31
voiced periods (a long final ė, not an alias/'ai'). So the 'ai' at the end and the
missing stress on 'a' are NOT sequence errors — they are the lack of PROSODY:
- raw periods play at their stored pitch (207-241 samples ≈ 91-106 Hz, slightly
  low/uneven), with no stress contour, so the 'au' diphthong is ambiguous and
  'sáulė' has no accent.
- the original applies intonation (proto7: resample voiced periods to a target
  pitch from P0[5]=220 modulated by P1/P5/P6 ProsodyChange ramps; stress raises
  pitch+amplitude on the accented syllable).

So the remaining work is the PROSODY layer on top of the now-correct unit sequence:
1. resample voiced periods to the base pitch (220) — fixes the slightly-low pitch.
2. apply a stress contour (raise pitch/amplitude on the stressed syllable).
This is the last layer; the hard part (correct units) is solved.

## Full chain established + cp1257 encoding pinned (the ė/č fix)
The complete pipeline is: text → our Transcriber (100%) → phonemes →
phonemeChar() → cp1257 diphone-char string → translate's demi-syllable sequencer →
unit keys → sample blocks → PCM (+ prosody).

translate operates on the PHONEME/letter chars (č→'c', ė→0xeb etc.), not raw
diacritics: 'aciu' works ('a ci- -ci u') while raw 'ačiū' with 0xe8 doesn't —
because the phoneme for č is 'c'(ts) in the diphone alphabet. So feeding the
transcriber's phonemeChar output is correct. Earlier ė loss was an ASCII test-word
bug; correct cp1257 (ė=0xeb) selects lė-/-lė.

Correct cp1257 codes: ą e0 č e8 ę e6 ė eb į e1 š f0 ų f8 ū fb ž fe.

Gold corpus saved (gold_translate_seq.tsv, 48 words) as the validation target for
the Java port of the demi-syllable sequencer. The rule (verified across 48 words):
CV→'Cv-' '-Cv'; initial/hiatus V→'V'; coda C→'-Vc'; cluster C→'C'; diphthongs
(ie,au,uo,ei,ai) handled as VV pairs. Next: port the sequencer to Java, validate
vs gold, wire into DiphoneSynth, then add prosody (base pitch 220 + stress).
