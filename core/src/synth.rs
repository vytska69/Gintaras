//! Literal Rust port of the Java `DiphoneSynth` — the original voicesynth PSOLA
//! pipeline (root.51.1/51/52/49/48/47/45) plus the sentence-level intonation
//! (root.53). Produces 16-bit PCM byte-identical to the validated Java engine.

use crate::conversion;
use crate::sequencer;
use crate::voicedb::VoiceDatabase;

pub const SAMPLE_RATE: u32 = 22050;

const BASE_PERIOD: i32 = 220; // P0[5]
const PROSODY: i32 = 20; // P0.Prosody
const TEMPO_FACTOR: f64 = 0.62; // P0[6]/100

// Per-word ProsodyChange ramps [ [3], [6] ] selected by punctuation (root.53).
const PC_P1: [i32; 2] = [10, 160]; // default word
const PC_P5: [i32; 2] = [10, 160]; // last word
const PC_P6: [i32; 2] = [50, 400]; // ','
const PC_P7: [i32; 2] = [50, 400]; // . ! ; :
const PC_P8: [i32; 2] = [200, -600]; // ?
const P8_DIFF: i32 = -10;
const P3_DIFF: i32 = 4;
const P2_PROSODY: i32 = 20;

/// Persistent sentence-level prosody state, threaded across the words of one
/// utterance. Fresh = accumulator 20, slew 0 (start of sentence).
pub struct ProsodyState {
    pub accumulator: i32,
    pub slew: i32,
    pub slew_target: i32,
}

impl ProsodyState {
    pub fn new() -> ProsodyState {
        ProsodyState { accumulator: PROSODY, slew: 0, slew_target: 0 }
    }
}

impl Default for ProsodyState {
    fn default() -> Self {
        Self::new()
    }
}

struct Rec {
    data: Vec<i16>,
    voiced: bool,
    count: f64,
    unit: i32,
}

/// Per-call DSP context (the Java instance's per-word DSP fields).
struct Ctx {
    cbp: i32,       // current base period
    ramp: [i32; 2], // current ProsodyChange ramp
    out: Vec<Vec<i16>>,
    emit_prev: Option<Vec<i16>>,
    prev_voiced: bool,
    prev_pitch: i32,
}

pub struct DiphoneSynth<'a> {
    db: &'a VoiceDatabase,
}

#[inline]
fn is_in(set: &str, c: char) -> bool {
    c != '\0' && set.contains(c)
}

#[inline]
fn clamp_pct(v: i32) -> i32 {
    if v < 25 { 25 } else if v > 400 { 400 } else { v }
}

impl<'a> DiphoneSynth<'a> {
    pub fn new(db: &'a VoiceDatabase) -> DiphoneSynth<'a> {
        DiphoneSynth { db }
    }

    /// Synthesize one word with no sentence context (rate/pitch %, 100 = normal).
    pub fn synthesize(&self, phonemes: &[String], rate: i32, pitch: i32) -> Vec<i16> {
        let mut st = ProsodyState::new();
        self.synthesize_word(phonemes, rate, pitch, '\0', false, &mut st)
    }

    /// Synthesize one word of an utterance carrying sentence-level intonation
    /// (root.53). `punctuation` is the word's trailing mark; `last_word` true for
    /// the final word; `state` persists across the utterance.
    pub fn synthesize_word(
        &self,
        phonemes: &[String],
        rate: i32,
        pitch: i32,
        punctuation: char,
        last_word: bool,
        state: &mut ProsodyState,
    ) -> Vec<i16> {
        let s = conversion::convert_tokens(phonemes);
        let units = sequencer::sequence(self.db, &s);

        // root.53 0240-0290: select this word's ProsodyChange ramp by punctuation.
        let ramp = if is_in(".!;:", punctuation) {
            PC_P7
        } else if punctuation == '?' {
            state.accumulator -= P8_DIFF; // -(-10) = +10
            PC_P8
        } else if punctuation == ',' {
            PC_P6
        } else if last_word {
            PC_P5
        } else {
            PC_P1
        };

        let pcm = self.synthesize_units(&units, rate, pitch, ramp, state);

        // root.53 0297-0372: post-word accumulator update.
        if is_in(".!?;:)]}", punctuation) {
            state.accumulator = PROSODY;
        } else if punctuation == ',' {
            state.accumulator = P2_PROSODY;
        } else {
            state.accumulator -= P3_DIFF;
        }
        pcm
    }

    /// Build PCM from an explicit unit sequence (default P1 ramp, fresh state).
    pub fn synthesize_units_simple(&self, units: &[String], rate: i32, pitch: i32) -> Vec<i16> {
        let mut st = ProsodyState::new();
        self.synthesize_units(units, rate, pitch, PC_P1, &mut st)
    }

    fn synthesize_units(
        &self,
        unit_names: &[String],
        rate: i32,
        pitch: i32,
        ramp: [i32; 2],
        ps: &mut ProsodyState,
    ) -> Vec<i16> {
        let p_pct = clamp_pct(pitch);
        let r_pct = clamp_pct(rate);
        let cbp = (BASE_PERIOD as f64 * 100.0 / p_pct as f64).round() as i32;
        let tempo = TEMPO_FACTOR * p_pct as f64 / r_pct as f64;

        // root.51.1: expand units -> flat leaf-record stream.
        let mut recs: Vec<Rec> = Vec::new();
        let mut phonecount = 0i32;
        for name in unit_names {
            let entry = self.lookup(name);
            let entry = match entry {
                Some(e) => e,
                None => continue,
            };
            let leaves = self.db.expand_unit(entry);
            if leaves.is_empty() {
                continue;
            }
            phonecount += 1;
            for l in leaves {
                recs.push(Rec { data: l.samples.to_vec(), voiced: l.voiced, count: l.count, unit: phonecount });
            }
        }
        if recs.is_empty() {
            return Vec::new();
        }

        // root.51: tempo -> event list.
        let mut events: Vec<Rec> = Vec::new();
        let mut acc = 0.0f64;
        for r in &recs {
            if r.voiced {
                acc += r.count * tempo;
                let n = acc.floor() as i32;
                if n >= 1 {
                    events.push(Rec { data: r.data.clone(), voiced: true, count: n as f64, unit: r.unit });
                    acc -= n as f64;
                }
            } else {
                events.push(Rec { data: r.data.clone(), voiced: false, count: 0.0, unit: r.unit });
            }
        }
        if events.is_empty() {
            return Vec::new();
        }

        let mut ctx = Ctx { cbp, ramp, out: Vec::new(), emit_prev: None, prev_voiced: false, prev_pitch: 0 };

        // root.52 + 52.5 dispatch.
        let mut i = 0usize;
        let mut prev_was_unvoiced = false;
        while i < events.len() {
            if !events[i].voiced || events[i].count < 1.0 {
                let (d, v, u) = (events[i].data.clone(), events[i].voiced, events[i].unit);
                ctx.root49(ps, &d, None, 1.0, v, phonecount, u);
                prev_was_unvoiced = true;
                i += 1;
                continue;
            }
            let mut cur = i;
            let run_start_unit = events[i].unit;
            let run_had_unvoiced_before = prev_was_unvoiced;
            prev_was_unvoiced = false;
            i += 1;
            loop {
                if i >= events.len() {
                    let d = events[cur].data.clone();
                    let (c, u) = (events[cur].count, events[cur].unit);
                    ctx.root49(ps, &d, Some(&d), c, true, phonecount, u);
                    break;
                }
                if events[i].voiced && events[i].count >= 1.0 {
                    let cd = events[cur].data.clone();
                    let nd = events[i].data.clone();
                    let (c, u) = (events[cur].count, events[cur].unit);
                    ctx.root49(ps, &cd, Some(&nd), c, true, phonecount, u);
                    cur = i;
                    i += 1;
                } else {
                    let single_unit_run = events[cur].unit == run_start_unit;
                    let po = if single_unit_run && run_had_unvoiced_before {
                        events[i].unit
                    } else {
                        events[cur].unit
                    };
                    let cd = events[cur].data.clone();
                    let c = events[cur].count;
                    ctx.root49(ps, &cd, Some(&cd), c, true, phonecount, po);
                    let nd = events[i].data.clone();
                    let nu = events[i].unit;
                    ctx.root49(ps, &nd, None, 1.0, false, phonecount, nu);
                    prev_was_unvoiced = true;
                    i += 1;
                    break;
                }
            }
        }
        ctx.flush();

        let total: usize = ctx.out.iter().map(|p| p.len()).sum();
        let mut pcm = Vec::with_capacity(total);
        for p in &ctx.out {
            pcm.extend_from_slice(p);
        }
        pcm
    }

    /// Index lookup with short<->long vowel relaxation (so -tá matches when -ta is
    /// absent), mirroring Java lookup + lookupRelaxed.
    fn lookup(&self, name: &str) -> Option<usize> {
        if let Some(e) = self.db.lookup(name) {
            return Some(e);
        }
        let mut cs: Vec<char> = name.chars().collect();
        for i in 0..cs.len() {
            let orig = cs[i];
            let alts = vowel_alts(orig);
            if alts.len() > 1 {
                for &alt in &alts {
                    if alt == orig {
                        continue;
                    }
                    cs[i] = alt;
                    let cand: String = cs.iter().collect();
                    if let Some(e) = self.db.lookup(&cand) {
                        return Some(e);
                    }
                }
                cs[i] = orig;
            }
        }
        None
    }
}

fn vowel_alts(c: char) -> Vec<char> {
    match c as u32 {
        0x61 => vec!['a', '\u{00e1}'],
        0x00e1 => vec!['\u{00e1}', 'a'],
        0x6f => vec!['o', '\u{00f3}'],
        0x00f3 => vec!['\u{00f3}', 'o'],
        0x65 => vec!['e', '\u{00eb}'],
        0x00eb => vec!['\u{00eb}', 'e'],
        _ => vec![c],
    }
}

impl Ctx {
    /// root.49: render `count` periods between data and data2 (each via root.48).
    fn root49(
        &mut self,
        ps: &mut ProsodyState,
        data: &[i16],
        data2: Option<&[i16]>,
        count: f64,
        voiced: bool,
        phonecount: i32,
        phoneorder: i32,
    ) {
        let n = if count < 1.0 { 1 } else { count as i32 };
        let typ_bit = if voiced { 1 } else { 0 };
        self.root48(ps, data, typ_bit, phonecount, phoneorder); // j=0
        let mut j = 1;
        while j <= n - 2 {
            let frame = interp_frame(data, data2.unwrap(), n, j);
            self.root48(ps, &frame, typ_bit, 0, 0);
            j += 1;
        }
        if n > 1 {
            if let Some(d2) = data2 {
                self.root48(ps, d2, typ_bit, 0, 0); // j=n-1
            }
        }
    }

    /// root.48: per-period pitch regeneration.
    fn root48(&mut self, ps: &mut ProsodyState, data: &[i16], typ_bit: i32, phonecount: i32, phoneorder: i32) {
        let mut target = self.cbp;
        self.root47(ps, phonecount, phoneorder);
        target += ps.slew;
        if self.emit_prev.is_none() {
            self.emit_prev = Some(data.to_vec());
            self.prev_voiced = typ_bit == 1;
            self.prev_pitch = target;
            return;
        }
        let cur = data.to_vec(); // R8
        let prev = self.emit_prev.take().unwrap(); // R5
        let mut emit: Vec<i16> = if self.prev_voiced {
            let n9 = prev.len();
            let n7 = self.prev_pitch.max(1) as usize;
            let mut e = vec![0i16; n7];
            let copy = n7.min(n9);
            e[..copy].copy_from_slice(&prev[..copy]);
            if n9 < n7 {
                let last = e[n9 - 1] as f64;
                let delta = (cur[0] as i32 - e[n9 - 1] as i32) as f64;
                let span = (n7 - n9) as f64;
                for k in 0..(n7 - n9) {
                    let term = last + (k as f64 + 1.0) * delta / (span + 1.0);
                    e[n9 + k] = term as i64 as i16; // (short)(long)double: trunc toward zero
                }
            }
            e
        } else {
            prev
        };
        let mut cur = cur;
        root45(&mut emit, &mut cur); // join (mutates both)
        self.out.push(emit);
        self.emit_prev = Some(cur);
        self.prev_voiced = typ_bit == 1;
        self.prev_pitch = target;
    }

    /// root.48 flush: emit the stashed final period verbatim.
    fn flush(&mut self) {
        if let Some(p) = self.emit_prev.take() {
            self.out.push(p);
        }
    }

    /// root.47: slew one step toward target = -accumulator + ProsodyChange/8.
    fn root47(&mut self, ps: &mut ProsodyState, phonecount: i32, phoneorder: i32) {
        if phonecount != 0 && phoneorder != 0 {
            ps.slew_target = if phonecount / 2 >= phoneorder { self.ramp[0] } else { self.ramp[1] };
        }
        let target_val = -(ps.accumulator as f64) + ps.slew_target as f64 / 8.0;
        let diff = target_val - ps.slew as f64;
        if diff == 0.0 {
            return;
        }
        let step = if diff > 0.0 {
            ((diff as i32) >> 4) + 1
        } else {
            let s = -(((-diff) as i32) >> 4);
            if s == 0 { -1 } else { s }
        };
        ps.slew += step;
        if ps.slew < -100 {
            ps.slew = -100;
        } else if ps.slew > 100 {
            ps.slew = 100;
        }
    }
}

/// root.44 frame interpolation: out[i] = ((n-1-j)*data[i] + j*data2[i])/(n-1),
/// over floor(min(len)/2)*2 samples.
fn interp_frame(data: &[i16], data2: &[i16], n: i32, j: i32) -> Vec<i16> {
    let mut len = data.len().min(data2.len());
    len &= !1usize;
    if len == 0 {
        return data.to_vec();
    }
    if n <= 1 {
        return data[..len].to_vec();
    }
    let w0 = (n - 1 - j) as i32;
    let w1 = j;
    let denom = n - 1;
    let mut out = vec![0i16; len];
    for i in 0..len {
        out[i] = ((w0 * data[i] as i32 + w1 * data2[i] as i32) / denom) as i16;
    }
    out
}

/// root.45: two-pass 2-tap join filter across the seam between b0 and b1.
fn root45(b0: &mut [i16], b1: &mut [i16]) {
    let n0 = b0.len() as i32;
    let n1 = b1.len() as i32;
    for pass in 1..=2 {
        let w = 4 * pass;
        let mut j = -w;
        while j <= w {
            if !(-j < n0 - 1 && j < n1 - 1) {
                j += 1;
                continue;
            }
            let c = (n0 - 1) + j;
            // left neighbour
            let left: i32 = if c > n0 - 1 {
                b1[(j - 1) as usize] as i32
            } else {
                let l = b0[c as usize] as i32;
                if l == 0 && j - 1 >= 0 && j - 1 < n1 {
                    b1[(j - 1) as usize] as i32
                } else {
                    l
                }
            };
            // right neighbour, index c+2
            let r = c + 2;
            let right: i32 = if r > n0 - 1 {
                b1[(j + 1) as usize] as i32
            } else {
                let rr = b0[r as usize] as i32;
                if rr == 0 && j + 1 >= 0 && j + 1 < n1 {
                    b1[(j + 1) as usize] as i32
                } else {
                    rr
                }
            };
            let mid = ((left + right) / 2) as i16;
            if c >= n0 - 1 {
                b1[j as usize] = mid;
            } else {
                b0[(c + 1) as usize] = mid;
            }
            j += 1;
        }
    }
}
