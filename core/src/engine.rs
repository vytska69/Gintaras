//! High-level engine: text -> 16-bit PCM, combining the normalizer, transcriber,
//! diphone synth and the pause model. Faithful port of the Android TtsService
//! orchestration (onSynthesizeText). The platform layer (iOS extension / JNI) calls
//! this; see `ffi` for the C ABI.

use crate::normalizer::{Settings, TextNormalizer, Token};
use crate::synth::{DiphoneSynth, ProsodyState, SAMPLE_RATE};
use crate::transcriber;
use crate::voicedb::VoiceDatabase;

/// Synthesis parameters (mirror the Android settings / SynthesisRequest).
pub struct SynthParams {
    pub rate: i32,              // speech rate %, 100 = normal
    pub pitch: i32,            // pitch %, 100 = normal
    pub punctuation_level: i32, // punc table index (1=off default)
    pub numgroup: i32,          // 16 = full cardinal
    pub use_dictionary: bool,
    pub pause_word: i32,        // % (Trumpa/Įprasta/Ilga = 50/100/300)
    pub pause_sentence: i32,    // % (50/100/150)
}

impl Default for SynthParams {
    fn default() -> Self {
        SynthParams {
            rate: 100,
            pitch: 100,
            punctuation_level: 1,
            numgroup: 16,
            use_dictionary: true,
            pause_word: 100,
            pause_sentence: 100,
        }
    }
}

/// Owns the voice DB and the (optional) dictionary asset bytes.
pub struct Engine {
    db: VoiceDatabase,
    rules: Option<Vec<u8>>,
    std: Option<Vec<u8>>,
    spell: Option<Vec<u8>>,
    punc: [Option<Vec<u8>>; 4],
}

impl Engine {
    pub fn new(
        dta: &[u8],
        rules: Option<Vec<u8>>,
        std: Option<Vec<u8>>,
        spell: Option<Vec<u8>>,
        punc: [Option<Vec<u8>>; 4],
    ) -> Engine {
        Engine { db: VoiceDatabase::parse(dta), rules, std, spell, punc }
    }

    pub fn sample_rate(&self) -> u32 {
        SAMPLE_RATE
    }

    /// Synthesize a whole utterance into one PCM buffer (speech + inter-word /
    /// sentence pauses). Mirrors TtsService.onSynthesizeText.
    pub fn synthesize_text(&self, text: &str, p: &SynthParams) -> Vec<i16> {
        let tn = TextNormalizer::create(
            &self.db,
            self.rules.as_deref(),
            self.std.as_deref(),
            self.spell.as_deref(),
            [
                self.punc[0].as_deref(),
                self.punc[1].as_deref(),
                self.punc[2].as_deref(),
                self.punc[3].as_deref(),
            ],
        );
        let st = Settings {
            punctuation_level: p.punctuation_level,
            numgroup: p.numgroup,
            use_dictionary: p.use_dictionary,
        };
        let tokens = tn.normalize(text, &st);
        let synth = DiphoneSynth::new(&self.db);

        let rate = if p.rate > 0 { p.rate } else { 100 };
        let pitch = if p.pitch > 0 { p.pitch } else { 100 };
        let r_scale = 100.0 / rate as f64;
        let sr = SAMPLE_RATE as f64;
        let word_pause = (0.02 * sr * (p.pause_word as f64 / 100.0) * r_scale) as usize;
        let sent_pause = (0.30 * sr * (p.pause_sentence as f64 / 100.0) * r_scale) as usize;
        let spell_pause = (0.10 * sr * (p.pause_word as f64 / 100.0) * r_scale) as usize;

        let mut out: Vec<i16> = Vec::new();
        let mut prosody = ProsodyState::new();
        let last_spoken = last_spoken_index(&tokens);
        let n = tokens.len();
        for wi in 0..n {
            let tk = &tokens[wi];
            let is_last = wi == n - 1;
            let pc = tk.punctuation;
            let sentence_end = pc != '\0' && ".!?;:".contains(pc);
            let word_punc = trailing_punc(&tokens, wi);
            let last_word = wi as i32 == last_spoken;

            if let Some(ph) = &tk.phonemes {
                let pcm = synth.synthesize_word(ph, rate, pitch, word_punc, last_word, &mut prosody);
                out.extend_from_slice(&pcm);
                push_silence(&mut out, if is_last { sent_pause } else { spell_pause });
                continue;
            }
            if tk.text.is_empty() {
                if pc != '\0' {
                    push_silence(&mut out, if sentence_end { sent_pause } else { word_pause });
                }
                continue;
            }
            let cp = transcriber::normalise(&tk.text);
            if cp.is_empty() {
                if pc != '\0' {
                    push_silence(&mut out, if sentence_end { sent_pause } else { word_pause });
                }
                continue;
            }
            let phonemes = transcriber::transcribe(&cp, cp.len());
            let pcm = synth.synthesize_word(&phonemes, rate, pitch, word_punc, last_word, &mut prosody);
            out.extend_from_slice(&pcm);
            let gap = if sentence_end || is_last {
                sent_pause
            } else if tk.spell {
                spell_pause
            } else {
                word_pause
            };
            push_silence(&mut out, gap);
        }
        out
    }
}

fn push_silence(out: &mut Vec<i16>, n: usize) {
    out.resize(out.len() + n, 0);
}

fn is_spoken(t: &Token) -> bool {
    t.phonemes.is_some() || !t.text.is_empty()
}

fn last_spoken_index(tokens: &[Token]) -> i32 {
    for i in (0..tokens.len()).rev() {
        if is_spoken(&tokens[i]) {
            return i as i32;
        }
    }
    -1
}

/// The trailing punctuation that drives token `wi`'s intonation contour.
fn trailing_punc(tokens: &[Token], wi: usize) -> char {
    let tk = &tokens[wi];
    if tk.punctuation != '\0' {
        return tk.punctuation;
    }
    for j in wi + 1..tokens.len() {
        let nx = &tokens[j];
        if nx.punctuation != '\0' {
            return nx.punctuation;
        }
        if is_spoken(nx) {
            return '\0';
        }
    }
    '\0'
}
