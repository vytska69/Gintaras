# init_transcr() decode (@0xd0a18, 604 bytes)

NOT the rule-table builder. init_transcr reads an optional config file and sets
three global double coefficients + one int:

- Opens **"LTKONFIG.TXT"** (fopen mode "r"), reads `"%lf %lf %lf %d"` →
  (d0, d1, d2, n).
- Clamps n to [0, 100] (0x64); stores into globals at *(0x27ff9c+{-0x4,-0x14,
  -0x10,-0xc}) i.e. the .data pointer slots.
- Per double: if <0 set 0.0; the int default path sets n=75 (0x4b).
- **Defaults when file absent** (d0bac branch): the doubles are set to
  0.05 (0x3fa999999999999a), 0.1, 0.2-ish (0x3fc0a3d7..., 0x3fc99999...) —
  prosody/pause/tone scaling coefficients.

Implication: these are PROSODY tuning constants, not transcription rules. The C
port can hardcode the defaults (no LTKONFIG.TXT on Android) and expose them if
needed. The grapheme→phoneme RULE table is built elsewhere (a constructor or on
first KircTranskr call) — to be located next.
