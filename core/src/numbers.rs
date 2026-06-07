//! Data-driven number reader — faithful Rust port of the Java `NumberExpander`
//! (translate root.24/24.1). Word fragments come from the voice DB's "N..." bucket
//! entries; never hard-coded.

use crate::voicedb::VoiceDatabase;

pub const NUMGROUP_FULL: i32 = 16;

pub struct NumberExpander<'a> {
    db: &'a VoiceDatabase,
    digit_words: [String; 10],
}

impl<'a> NumberExpander<'a> {
    pub fn new(db: &'a VoiceDatabase) -> NumberExpander<'a> {
        let mut dw: [String; 10] = Default::default();
        for d in 0..10 {
            if let Some(w) = db.number_bucket(&format!("N{}", d)) {
                if let Some(first) = w.first() {
                    dw[d] = first.clone();
                }
            }
        }
        NumberExpander { db, digit_words: dw }
    }

    /// Replace every numeric token in `text` with its spoken Lithuanian words.
    pub fn expand(&self, text: &str, numgroup: i32) -> String {
        let chars: Vec<char> = text.chars().collect();
        let n = chars.len();
        let mut out = String::with_capacity(text.len() + 16);
        let mut i = 0usize;
        while i < n {
            let c = chars[i];
            if c.is_ascii_digit() {
                let mut j = i;
                while j < n && chars[j].is_ascii_digit() {
                    j += 1;
                }
                let (int_start, int_end) = (i, j);
                let mut frac_start: i64 = -1;
                let mut frac_end = 0usize;
                if j < n && (chars[j] == ',' || chars[j] == '.') && j + 1 < n && chars[j + 1].is_ascii_digit() {
                    frac_start = (j + 1) as i64;
                    frac_end = j + 1;
                    while frac_end < n && chars[frac_end].is_ascii_digit() {
                        frac_end += 1;
                    }
                }
                let neg = int_start > 0
                    && chars[int_start - 1] == '-'
                    && (int_start == 1 || !chars[int_start - 2].is_alphanumeric());
                if !out.is_empty() && !out.ends_with(' ') {
                    out.push(' ');
                }
                if neg {
                    trim_trailing_minus(&mut out);
                    out.push_str("minus ");
                }
                let int_str: String = chars[int_start..int_end].iter().collect();
                out.push_str(&self.read_digits(&int_str, numgroup).join(" "));
                if frac_start >= 0 {
                    out.push_str(" kablelis");
                    for k in (frac_start as usize)..frac_end {
                        out.push(' ');
                        out.push_str(&self.digit_words[(chars[k] as u8 - b'0') as usize]);
                    }
                    j = frac_end;
                }
                out.push(' ');
                i = j;
            } else {
                out.push(c);
                i += 1;
            }
        }
        out.trim().to_string()
    }

    fn read_digits(&self, digits: &str, numgroup: i32) -> Vec<String> {
        if numgroup == 1 || numgroup == 2 || numgroup == 3 {
            self.read_grouped(digits, numgroup as usize)
        } else {
            self.read_cardinal(digits)
        }
    }

    fn read_grouped(&self, digits: &str, size: usize) -> Vec<String> {
        let ch: Vec<char> = digits.chars().collect();
        let mut out = Vec::new();
        let mut p = 0;
        while p < ch.len() {
            let end = (p + size).min(ch.len());
            if size == 1 {
                out.push(self.digit_words[(ch[p] as u8 - b'0') as usize].clone());
            } else {
                let chunk: String = ch[p..end].iter().collect();
                out.extend(self.read_cardinal(&chunk));
            }
            p += size;
        }
        out
    }

    fn read_cardinal(&self, raw: &str) -> Vec<String> {
        let rc: Vec<char> = raw.chars().collect();
        let mut s = 0;
        while s < rc.len() - 1 && rc[s] == '0' {
            s += 1;
        }
        let digits = &rc[s..];
        let len = digits.len();
        let mut out: Vec<String> = Vec::new();
        if len == 1 {
            if let Some(w) = self.db.number_bucket(&format!("N{}", digits[0])) {
                out.extend(w.iter().cloned());
            }
            return out;
        }
        let mut i = 0;
        while i < len {
            let scale = (len - 1 - i) as i32;
            let d = (digits[i] as u8 - b'0') as i32;
            if scale % 3 == 1 && d == 1 && i + 1 < len {
                let dd = d * 10 + (digits[i + 1] as u8 - b'0') as i32;
                if let Some(w) = self.lookup(dd, scale - 1) {
                    out.extend(w.iter().cloned());
                    i += 2;
                    continue;
                }
            }
            if d != 0 {
                if let Some(w) = self.lookup(d, scale) {
                    out.extend(w.iter().cloned());
                } else if let Some(bare) = self.db.number_bucket(&format!("N{}", d)) {
                    out.extend(bare.iter().cloned());
                }
            } else if scale % 3 == 0 && scale > 0 && triple_non_zero(digits, len, scale) {
                if let Some(w) = self.lookup(0, scale) {
                    out.extend(w.iter().cloned());
                }
            }
            i += 1;
        }
        out
    }

    fn lookup(&self, value: i32, scale: i32) -> Option<&Vec<String>> {
        self.db
            .number_bucket(&format!("N{}+{}R", value, scale))
            .or_else(|| self.db.number_bucket(&format!("N{}+{}", value, scale)))
    }
}

fn triple_non_zero(digits: &[char], len: usize, base: i32) -> bool {
    for sc in base..base + 3 {
        let idx = len as i32 - 1 - sc;
        if idx >= 0 && (idx as usize) < len && digits[idx as usize] != '0' {
            return true;
        }
    }
    false
}

fn trim_trailing_minus(s: &mut String) {
    let trimmed = s.trim_end_matches(' ');
    if trimmed.ends_with('-') {
        let new_len = trimmed.len() - 1;
        s.truncate(new_len);
    }
}
