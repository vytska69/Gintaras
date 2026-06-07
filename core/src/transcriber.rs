//! Lithuanian grapheme→phoneme transcriber — faithful Rust port of the Java
//! `Transcriber` (which matches the original libtranscr.so 100% over 515 words).
//! Input is a cp1257-uppercased word (see `normalise`); output is the phoneme
//! token list with `_` word boundaries.

// cp1257 Lithuanian letters used as constants
const A_OG: i32 = 0xc0;
const C_HK: i32 = 0xc8;
const E_OG: i32 = 0xc6;
const E_DOT: i32 = 0xcb;
const I_OG: i32 = 0xc1;
const S_HK: i32 = 0xd0;
const U_OG: i32 = 0xd8;
const U_MAC: i32 = 0xdb;
const Z_HK: i32 = 0xde;

const A: i32 = b'A' as i32;
const Z: i32 = b'Z' as i32;

#[inline]
fn is_front_vowel(c: i32) -> bool {
    c == b'I' as i32 || c == b'Y' as i32 || c == I_OG || c == b'E' as i32 || c == E_DOT || c == E_OG
}

fn cons_voiced(c: i32) -> Option<&'static str> {
    Some(match c as u8 as char {
        'B' => "b", 'C' => "ts", 'D' => "d", 'F' => "f", 'G' => "g", 'H' => "h",
        'K' => "k", 'L' => "l", 'M' => "m", 'N' => "n", 'P' => "p", 'R' => "r",
        'S' => "s", 'T' => "t", 'V' => "v", 'Z' => "z", 'J' => "j'",
        _ => match c {
            C_HK => "tS",
            S_HK => "S",
            Z_HK => "Z",
            _ => return None,
        },
    })
}

fn cons_devoiced(c: i32) -> Option<&'static str> {
    match c as u8 as char {
        'B' => Some("p"), 'D' => Some("t"), 'G' => Some("k"), 'Z' => Some("s"),
        'C' => Some("ts"),
        _ => match c {
            Z_HK => Some("S"),
            S_HK => Some("S"),
            C_HK => Some("tS"),
            _ => cons_voiced(c),
        },
    }
}

fn vowel_phoneme(c: i32) -> Option<&'static str> {
    match c as u8 as char {
        'A' => Some("aA"),
        'E' => Some("eA"),
        'I' => Some("i"),
        'Y' => Some("iI"),
        'O' => Some("oO"),
        'U' => Some("u"),
        _ => match c {
            A_OG => Some("aA"),
            E_OG => Some("eA"),
            E_DOT => Some("eE"),
            I_OG => Some("iI"),
            U_MAC => Some("uU"),
            U_OG => Some("uU"),
            _ => None,
        },
    }
}

/// Regressive palatalisation through palatalised consonant chains.
fn is_palatalised(w: &[i32], n: usize, i: usize) -> bool {
    if i + 1 >= n {
        return false;
    }
    let nx = w[i + 1];
    if is_front_vowel(nx) || nx == b'I' as i32 || nx == b'J' as i32 {
        return true;
    }
    if vowel_phoneme(nx).is_some() {
        return false;
    }
    if cons_voiced(nx).is_some() {
        return is_palatalised(w, n, i + 1);
    }
    false
}

/// Transcribe a cp1257-uppercased word into phoneme tokens incl. `_` bounds.
pub fn transcribe(w: &[i32], n: usize) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    out.push("_".to_string());
    let mut i = 0usize;
    while i < n {
        let c = w[i];

        // CH -> x
        if c == b'C' as i32 && i + 1 < n && w[i + 1] == b'H' as i32 {
            out.push(if is_palatalised(w, n, i + 1) { "x'" } else { "x" }.to_string());
            i += 2;
            continue;
        }
        // DŽ -> dZ, DZ -> dz
        if c == b'D' as i32 && i + 1 < n && (w[i + 1] == Z_HK || w[i + 1] == b'Z' as i32) {
            let base = if w[i + 1] == Z_HK { "dZ" } else { "dz" };
            out.push(if is_palatalised(w, n, i + 1) { format!("{}'", base) } else { base.to_string() });
            i += 2;
            continue;
        }
        // softness 'i' before a back vowel is absorbed
        if c == b'I' as i32
            && !(i > 0 && vowel_phoneme(w[i - 1]).is_some())
            && i + 1 < n
        {
            let nx = w[i + 1];
            let back = nx == b'A' as i32 || nx == A_OG || nx == b'O' as i32 || nx == b'U' as i32
                || nx == U_OG || nx == U_MAC;
            if back {
                i += 1;
                continue;
            }
        }
        // diphthong glides after short vowels
        if i > 0 {
            let pv = w[i - 1];
            let short_v = pv == b'A' as i32 || pv == b'E' as i32 || pv == b'O' as i32 || pv == b'U' as i32;
            if short_v {
                if c == b'I' as i32 {
                    out.push("J".to_string());
                    i += 1;
                    continue;
                }
                if c == b'U' as i32 && pv != b'U' as i32 {
                    out.push("W".to_string());
                    i += 1;
                    continue;
                }
            }
        }

        if let Some(vp) = vowel_phoneme(c) {
            if (c == b'A' as i32 || c == A_OG)
                && i > 0
                && ((w[i - 1] == b'I' as i32 && !(i >= 2 && vowel_phoneme(w[i - 2]).is_some()))
                    || w[i - 1] == b'J' as i32)
            {
                out.push("eA".to_string());
                i += 1;
                continue;
            }
            out.push(vp.to_string());
            i += 1;
            continue;
        }

        // sonorant uppercase positional variants
        {
            let next_is_cons = (i + 1 >= n) || (vowel_phoneme(w[i + 1]).is_none() && w[i + 1] != b'I' as i32);
            let prev_is_cons = i > 0 && vowel_phoneme(w[i - 1]).is_none();
            let after_vowel = i > 0 && vowel_phoneme(w[i - 1]).is_some();
            let coda = after_vowel && next_is_cons;
            let prev_obstruent = prev_is_cons
                && !(w[i - 1] == b'L' as i32 || w[i - 1] == b'M' as i32 || w[i - 1] == b'N' as i32
                    || w[i - 1] == b'R' as i32 || w[i - 1] == b'J' as i32 || w[i - 1] == b'V' as i32);
            let pal = is_palatalised(w, n, i);
            let son_up = coda || prev_obstruent;
            let r_up = coda || prev_is_cons
                || (next_is_cons && i + 1 < n && cons_voiced(w[i + 1]).is_some());
            let up = if c == b'L' as i32 && son_up {
                Some("L")
            } else if c == b'M' as i32 && son_up {
                Some("M")
            } else if c == b'N' as i32 && son_up {
                Some("N")
            } else if c == b'R' as i32 && r_up {
                Some("R")
            } else {
                None
            };
            if let Some(up) = up {
                out.push(if pal { format!("{}'", up) } else { up.to_string() });
                i += 1;
                continue;
            }
        }

        let cp = match cons_voiced(c) {
            Some(s) => s,
            None => {
                i += 1;
                continue;
            }
        };
        let is_final = i == n - 1;
        let mut use_: String = cp.to_string();
        if is_final {
            use_ = cons_devoiced(c).unwrap_or(cp).to_string();
        } else {
            let nx = w[i + 1];
            let nx_unvoiced = nx == b'P' as i32 || nx == b'T' as i32 || nx == b'K' as i32
                || nx == b'S' as i32 || nx == S_HK || nx == b'C' as i32 || nx == C_HK
                || nx == b'F' as i32 || nx == b'H' as i32;
            let nx_voiced = nx == b'B' as i32 || nx == b'D' as i32 || nx == b'G' as i32
                || nx == b'Z' as i32 || nx == Z_HK;
            if nx_unvoiced {
                use_ = cons_devoiced(c).unwrap_or(cp).to_string();
            } else if nx_voiced {
                use_ = match c as u8 as char {
                    'S' => "z".to_string(),
                    'K' => "g".to_string(),
                    'P' => "b".to_string(),
                    'T' => "d".to_string(),
                    'C' => "dz".to_string(),
                    _ => match c {
                        S_HK => "Z".to_string(),
                        C_HK => "dZ".to_string(),
                        _ => cp.to_string(),
                    },
                };
            }
        }
        let palatal = is_palatalised(w, n, i);
        if palatal && !use_.ends_with('\'') {
            use_.push('\'');
        }
        out.push(use_);
        i += 1;
    }
    out.push("_".to_string());
    out
}

/// Uppercase a Unicode Lithuanian string to a cp1257 code array for `transcribe`.
pub fn normalise(text: &str) -> Vec<i32> {
    let mut w: Vec<i32> = Vec::with_capacity(text.len());
    for ch0 in text.chars() {
        // Uppercase (Lithuanian ą->Ą, č->Č, ... are 1:1 in Unicode).
        let ch = ch0.to_uppercase().next().unwrap_or(ch0);
        let cp: i32 = match ch {
            'Ą' => A_OG,
            'Č' => C_HK,
            'Ę' => E_OG,
            'Ė' => E_DOT,
            'Į' => I_OG,
            'Š' => S_HK,
            'Ų' => U_OG,
            'Ū' => U_MAC,
            'Ž' => Z_HK,
            ' ' | '\t' => continue,
            _ => {
                let u = ch as i32;
                if u >= A && u <= Z {
                    u
                } else {
                    -1
                }
            }
        };
        if cp > 0 {
            w.push(cp);
        }
    }
    w
}
