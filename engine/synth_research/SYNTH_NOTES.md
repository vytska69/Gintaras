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
