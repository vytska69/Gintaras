//! Text-normalization ("reading") layer — faithful Rust port of the Java
//! `TextNormalizer` (voicesynth root.53 + dictionary): transliteration (ruleslit),
//! tokenization keeping punctuation, std dictionary, number expansion, punctuation
//! tables, and the spell path (lone letters / acronyms / vowelless tokens) using
//! the original's baked SpellZod phonemes.

use crate::numbers::{NumberExpander, NUMGROUP_FULL};
use crate::voicedb::VoiceDatabase;
use std::collections::HashMap;

/// Punctuation characters kept as separate tokens (root.53 K[-13]).
const PUNCT: &str = ".!?,;:()[]{}";

/// One normalized output token.
pub struct Token {
    pub text: String,
    pub punctuation: char,
    pub spell: bool,
    /// Pre-resolved phonemes (spelled letters); else None.
    pub phonemes: Option<Vec<String>>,
}

/// Settings that change reading behaviour.
pub struct Settings {
    pub punctuation_level: i32,
    pub numgroup: i32,
    pub use_dictionary: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Settings { punctuation_level: 1, numgroup: NUMGROUP_FULL, use_dictionary: true }
    }
}

pub struct TextNormalizer<'a> {
    rules: HashMap<char, String>,
    std: Vec<(String, String)>, // (stem, replacement); longest-prefix wins
    spell: HashMap<String, String>,
    punc: [HashMap<char, String>; 4],
    numbers: NumberExpander<'a>,
}

impl<'a> TextNormalizer<'a> {
    /// Build from the asset bytes (any may be None -> that feature is skipped).
    pub fn create(
        db: &'a VoiceDatabase,
        rules: Option<&[u8]>,
        std: Option<&[u8]>,
        spell: Option<&[u8]>,
        punc: [Option<&[u8]>; 4],
    ) -> TextNormalizer<'a> {
        TextNormalizer {
            rules: parse_rules(rules),
            std: parse_std_vec(std),
            spell: parse_std_map(spell),
            punc: [parse_punc(punc[0]), parse_punc(punc[1]), parse_punc(punc[2]), parse_punc(punc[3])],
            numbers: NumberExpander::new(db),
        }
    }

    /// Apply the ruleslit per-character transliteration (with lower-case fallback).
    pub fn transliterate(&self, text: &str) -> String {
        if self.rules.is_empty() {
            return text.to_string();
        }
        let mut sb = String::with_capacity(text.len());
        for c in text.chars() {
            let mut r = self.rules.get(&c);
            if r.is_none() {
                if let Some(lc) = c.to_lowercase().next() {
                    if lc != c {
                        r = self.rules.get(&lc);
                    }
                }
            }
            match r {
                Some(s) => sb.push_str(s),
                None => sb.push(c),
            }
        }
        sb
    }

    /// Normalize raw input into the ordered spoken tokens.
    pub fn normalize(&self, raw: &str, st: &Settings) -> Vec<Token> {
        let mut out: Vec<Token> = Vec::new();
        let text = self.transliterate(raw);
        let toks = tokenize(&text);
        let whole_is_single = toks.len() == 1;
        for tok in &toks {
            if tok.is_empty() {
                continue;
            }
            let tch: Vec<char> = tok.chars().collect();
            if tch.len() == 1 && PUNCT.contains(tch[0]) {
                let ch = tch[0];
                let table = &self.punc[clamp_level(st.punctuation_level)];
                let word = match table.get(&ch) {
                    Some(s) => strip_symbol(s, ch),
                    None => String::new(),
                };
                out.push(Token { text: word, punctuation: ch, spell: false, phonemes: None });
                continue;
            }
            let lone_letter = whole_is_single && tch.len() == 1 && tch[0].is_alphabetic();
            let acronym = tch.len() > 1 && is_all_upper(tok) && has_no_digit(tok);
            if lone_letter || acronym {
                self.emit_spelled(&mut out, tok);
                continue;
            }
            let expanded = self.numbers.expand(&sub_alien(tok), st.numgroup);
            for w in expanded.split_whitespace() {
                if w.is_empty() {
                    continue;
                }
                if is_alpha_word(w) && !has_vowel(w) {
                    self.emit_spelled(&mut out, w);
                } else {
                    let spoken = if st.use_dictionary { self.apply_std(w) } else { w.to_string() };
                    out.push(Token { text: spoken, punctuation: '\0', spell: false, phonemes: None });
                }
            }
        }
        out
    }

    /// Emit one spell Token per character: original SpellZod phonemes else name.
    fn emit_spelled(&self, out: &mut Vec<Token>, tok: &str) {
        for ch in tok.chars() {
            let key: String = ch.to_lowercase().collect();
            if let Some(ph) = letter_phonemes(&key) {
                out.push(Token {
                    text: String::new(),
                    punctuation: '\0',
                    spell: true,
                    phonemes: Some(ph.split(' ').map(|s| s.to_string()).collect()),
                });
            } else {
                let name = self
                    .spell
                    .get(&key)
                    .map(|s| s.as_str())
                    .or_else(|| letter_name(&key));
                out.push(Token {
                    text: name.map(|s| s.to_string()).unwrap_or_else(|| ch.to_string()),
                    punctuation: '\0',
                    spell: true,
                    phonemes: None,
                });
            }
        }
    }

    /// std dictionary: longest stem prefix wins; replacement concatenated with the
    /// inflectional tail (no separator).
    fn apply_std(&self, word: &str) -> String {
        if self.std.is_empty() {
            return word.to_string();
        }
        let lower = word.to_lowercase();
        let mut best: Option<&(String, String)> = None;
        for e in &self.std {
            if lower.starts_with(&e.0)
                && (best.is_none() || e.0.len() > best.unwrap().0.len())
            {
                best = Some(e);
            }
        }
        match best {
            None => word.to_string(),
            Some((stem, repl)) => {
                // keep the original-cased tail after the matched stem length (in chars)
                let tail: String = word.chars().skip(stem.chars().count()).collect();
                format!("{}{}", repl, tail)
            }
        }
    }
}

// ----- helpers ---------------------------------------------------------------

fn clamp_level(lvl: i32) -> usize {
    if lvl < 0 || lvl > 3 { 0 } else { lvl as usize }
}

fn strip_symbol(value: &str, ch: char) -> String {
    let mut v = value.to_string();
    if v.ends_with(ch) {
        v.pop();
    }
    v.trim().to_string()
}

fn tokenize(text: &str) -> Vec<String> {
    let mut toks = Vec::new();
    let mut cur = String::new();
    for c in text.chars() {
        if c.is_whitespace() {
            if !cur.is_empty() {
                toks.push(std::mem::take(&mut cur));
            }
        } else if PUNCT.contains(c) {
            if !cur.is_empty() {
                toks.push(std::mem::take(&mut cur));
            }
            toks.push(c.to_string());
        } else {
            cur.push(c);
        }
    }
    if !cur.is_empty() {
        toks.push(cur);
    }
    toks
}

fn is_all_upper(tok: &str) -> bool {
    for c in tok.chars() {
        if c.is_alphabetic() && !c.is_uppercase() {
            return false;
        }
    }
    true
}

fn has_no_digit(s: &str) -> bool {
    !s.chars().any(|c| c.is_numeric())
}

fn is_alpha_word(w: &str) -> bool {
    let mut any = false;
    for c in w.chars() {
        if c.is_numeric() {
            return false;
        }
        if c.is_alphabetic() {
            any = true;
        }
    }
    any
}

fn has_vowel(w: &str) -> bool {
    let lower = w.to_lowercase();
    lower.chars().any(|c| "aeiouyąęėįųū".contains(c))
}

/// Substitute non-Lithuanian x/q/w with their phonetic reading inside a word.
fn sub_alien(w: &str) -> String {
    if !w.chars().any(|c| matches!(c, 'x' | 'X' | 'q' | 'Q' | 'w' | 'W')) {
        return w.to_string();
    }
    let mut b = String::with_capacity(w.len() + 2);
    for c in w.chars() {
        match c {
            'x' => b.push_str("ks"),
            'X' => b.push_str("KS"),
            'q' => b.push('k'),
            'Q' => b.push('K'),
            'w' => b.push('v'),
            'W' => b.push('V'),
            _ => b.push(c),
        }
    }
    b
}

// ----- file parsing ----------------------------------------------------------

fn slurp(b: Option<&[u8]>) -> Option<String> {
    let b = b?;
    if b.len() >= 2 && b[0] == 0xFF && b[1] == 0xFE {
        let u: Vec<u16> = b[2..]
            .chunks_exact(2)
            .map(|c| u16::from_le_bytes([c[0], c[1]]))
            .collect();
        return Some(String::from_utf16_lossy(&u));
    }
    if b.len() >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF {
        return Some(String::from_utf8_lossy(&b[3..]).into_owned());
    }
    Some(String::from_utf8_lossy(b).into_owned())
}

fn strip_cr(s: &str) -> &str {
    s.strip_suffix('\r').unwrap_or(s)
}

fn parse_rules(b: Option<&[u8]>) -> HashMap<char, String> {
    let mut m = HashMap::new();
    let txt = match slurp(b) {
        Some(t) => t,
        None => return m,
    };
    for line in txt.split('\n') {
        let line = strip_cr(line);
        let ch: Vec<char> = line.chars().collect();
        if ch.len() < 3 || ch[0] != 'D' {
            continue;
        }
        let src = ch[1];
        // indexOf(' ', 2) in chars
        let sp = ch.iter().enumerate().skip(2).find(|(_, &c)| c == ' ').map(|(i, _)| i);
        let sp = match sp {
            Some(p) => p,
            None => continue,
        };
        let dst: String = ch[sp + 1..].iter().collect();
        m.insert(src, dst);
    }
    m
}

/// stdlit/spelllit: lines "stem* replacement" -> (lowercased stem, replacement).
fn parse_std_entries(b: Option<&[u8]>) -> Vec<(String, String)> {
    let mut v = Vec::new();
    let txt = match slurp(b) {
        Some(t) => t,
        None => return v,
    };
    for line in txt.split('\n') {
        let line = strip_cr(line);
        let sp = match line.find(' ') {
            Some(p) if p > 0 => p,
            _ => continue,
        };
        let mut key = &line[..sp];
        let val = &line[sp + 1..];
        if key.ends_with('*') {
            key = &key[..key.len() - 1];
        }
        if key.is_empty() {
            continue;
        }
        v.push((key.to_lowercase(), val.to_string()));
    }
    v
}

fn parse_std_vec(b: Option<&[u8]>) -> Vec<(String, String)> {
    parse_std_entries(b)
}

fn parse_std_map(b: Option<&[u8]>) -> HashMap<String, String> {
    parse_std_entries(b).into_iter().collect()
}

fn parse_punc(b: Option<&[u8]>) -> HashMap<char, String> {
    let mut m = HashMap::new();
    let txt = match slurp(b) {
        Some(t) => t,
        None => return m,
    };
    for line in txt.split('\n') {
        let line = strip_cr(line);
        let ch: Vec<char> = line.chars().collect();
        if ch.len() < 3 || ch[0] != '*' {
            continue;
        }
        let sym = ch[1];
        let close = ch.iter().enumerate().skip(2).find(|(_, &c)| c == '*').map(|(i, _)| i);
        let close = match close {
            Some(p) => p,
            None => continue,
        };
        let mut rest: String = if close + 1 < ch.len() { ch[close + 1..].iter().collect() } else { String::new() };
        if rest.starts_with(' ') {
            rest.remove(0);
        }
        m.insert(sym, rest);
    }
    m
}

// ----- letter tables (verbatim from the original SpellZod) --------------------

fn letter_name(key: &str) -> Option<&'static str> {
    Some(match key {
        "a" => "a", "ą" => "ą nosinė", "b" => "bė", "c" => "cė", "č" => "čė", "d" => "dė",
        "e" => "e", "ę" => "ę nosinė", "ė" => "ė", "f" => "ef", "g" => "gė", "h" => "ha",
        "i" => "i", "į" => "y nosinė", "y" => "y ilgoji", "j" => "jot", "k" => "ka",
        "l" => "el", "m" => "em", "n" => "en", "o" => "o", "p" => "pė", "r" => "er",
        "s" => "es", "š" => "eš", "t" => "tė", "u" => "u", "ų" => "ū nosinė",
        "ū" => "ū ilgoji", "v" => "vė", "z" => "zė", "ž" => "žė",
        "w" => "dablvė", "x" => "iks", "q" => "kū",
        _ => return None,
    })
}

fn letter_phonemes(key: &str) -> Option<&'static str> {
    Some(match key {
        "a" => "_ aA _ _",
        "ą" => "_ aA n oO s' i n' eE _ _",
        "b" => "_ b' eE _ _",
        "c" => "_ ts' eE _ _",
        "č" => "_ tS' eE _ _",
        "d" => "_ d' eE _ _",
        "e" => "_ eA _ _",
        "ę" => "_ eA n oO s' i n' eE _ _",
        "ė" => "_ eE _ _",
        "f" => "_ E f _ _",
        "g" => "_ g' eE _ _",
        "h" => "_ h aA _ _",
        "i" => "_ i _ _",
        "į" => "_ iI n oO s' i n' eE _ _",
        "y" => "_ iI i L g Oo j' i _ _",
        "j" => "_ j' O t _ _",
        "k" => "_ k aA _ _",
        "l" => "_ E L _ _",
        "m" => "_ E M _ _",
        "n" => "_ E N _ _",
        "o" => "_ oO _ _",
        "p" => "_ p' eE _ _",
        "q" => "_ k uU _ _",
        "r" => "_ E R _ _",
        "s" => "_ E s _ _",
        "š" => "_ E S _ _",
        "t" => "_ t' eE _ _",
        "u" => "_ U _ _",
        "ų" => "_ uU n oO s' i n' eE _ _",
        "ū" => "_ uU i L g Oo j' i _ _",
        "v" => "_ v' eE _ _",
        "w" => "_ d aA b' L' v' eE _ _",
        "x" => "_ i k s _ _",
        "z" => "_ z' eE _ _",
        "ž" => "_ Z' eE _ _",
        _ => return None,
    })
}
