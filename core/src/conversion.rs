//! Literal port of the original `trans` conversion (Java `Conversion`): phoneme
//! tokens (KircTranskr output) -> the UTF-16 unit-name code-unit string consumed
//! by the sequencer. Source: engine/decompiled/trans.decomp.txt (root.37 / root.8
//! / root.8.37 / root.37.11). Works on `char` units to mirror the Java code.

/// SUBST map (trans root.8 R3): phoneme key -> cp1257-ish byte sequence. Two-char
/// keys are tried before one-char keys.
fn subst(key: &str) -> Option<Vec<char>> {
    let v: &[u32] = match key {
        // two-char keys
        "tS" => &[232],
        "ts" => &[99],
        "uu" => &[248],
        "aa" => &[224],
        "ii" => &[225],
        "oo" => &[243],
        "ee" => &[235],
        "eA" => &[230],
        "Ea" => &[230],
        "ea" => &[230],
        "dZ" => &[100, 254],
        // one-char keys
        "S" => &[240],
        "Z" => &[254],
        "w" => &[117],
        "W" => &[248],
        "x" => &[99, 104],
        _ => return None,
    };
    Some(v.iter().map(|&b| char::from_u32(b).unwrap()).collect())
}

/// LOWER map (trans root.8 K[-1]): uppercase ASCII -> lowercase.
fn lower(c: char) -> char {
    if c.is_ascii_uppercase() {
        c.to_ascii_lowercase()
    } else {
        c
    }
}

/// Vowel set (trans root.8 R2): "aeiou" + bytes 248,224,225,243,235,230.
fn is_vowel_byte(c: char) -> bool {
    matches!(c, 'a' | 'e' | 'i' | 'o' | 'u')
        || matches!(c as u32, 248 | 224 | 225 | 243 | 235 | 230)
}

/// Convert a phoneme token list into the UTF-16 conversion string.
pub fn convert_tokens(tokens: &[String]) -> String {
    convert_str(&tokens.join("\n"))
}

/// Core port of root.8 + the root.37.11 final mapping. Input is the raw
/// newline-joined phoneme string; output is the code-unit string.
pub fn convert_str(phoneme_string: &str) -> String {
    // root.8: gsub("[_0123456789]", "")
    let mut v: Vec<char> = Vec::with_capacity(phoneme_string.len());
    for c in phoneme_string.chars() {
        if c == '_' || c.is_ascii_digit() {
            continue;
        }
        v.push(c);
    }
    // gsub("\n", " ")
    for c in v.iter_mut() {
        if *c == '\n' {
            *c = ' ';
        }
    }
    // substitution pass 1
    v = subst_pass(&v);
    // gsub(".", LOWER)
    for c in v.iter_mut() {
        *c = lower(*c);
    }
    // substitution pass 2
    v = subst_pass(&v);
    // palatal reorder pass
    v = palatal_pass(&v);
    // gsub(" ", "")
    v.retain(|&c| c != ' ');
    // root.37.11 final byte -> code-unit map
    v.iter().map(|&c| final_map(c)).collect()
}

/// root.8 substitution Cs pattern: 2-char key first, else 1-char, else pass through.
fn subst_pass(s: &[char]) -> Vec<char> {
    let mut out: Vec<char> = Vec::with_capacity(s.len());
    let n = s.len();
    let mut i = 0;
    while i < n {
        if i + 2 <= n {
            let two: String = s[i..i + 2].iter().collect();
            if let Some(r) = subst(&two) {
                out.extend(r);
                i += 2;
                continue;
            }
        }
        let one: String = s[i..i + 1].iter().collect();
        if let Some(r) = subst(&one) {
            out.extend(r);
            i += 1;
            continue;
        }
        out.push(s[i]);
        i += 1;
    }
    out
}

/// root.8 palatal Cs pattern + root.8.37: match "' ", optionally grab the following
/// vowel token, and re-emit the soft marker as '|' after that vowel.
fn palatal_pass(s: &[char]) -> Vec<char> {
    let mut out: Vec<char> = Vec::with_capacity(s.len());
    let n = s.len();
    let mut i = 0;
    while i < n {
        if s[i] == '\'' && i + 1 < n && s[i + 1] == ' ' {
            let j = i + 2;
            if let Some(len) = match_vowel_token(s, j) {
                // cap1:gsub("'"," ") = "  "; cap2:gsub(" ","|")
                out.push(' ');
                out.push(' ');
                for k in j..j + len {
                    out.push(if s[k] == ' ' { '|' } else { s[k] });
                }
                i = j + len;
            } else {
                // cap1:gsub("'","|") = "| "
                out.push('|');
                out.push(' ');
                i += 2;
            }
            continue;
        }
        out.push(s[i]);
        i += 1;
    }
    out
}

/// Match cap2 = S(vowel) . (1 - P(" "))^-1 . " " ; return its length (incl. trailing
/// space) or None.
fn match_vowel_token(s: &[char], start: usize) -> Option<usize> {
    let n = s.len();
    if start >= n || !is_vowel_byte(s[start]) {
        return None;
    }
    let mut p = start + 1;
    if p < n && s[p] != ' ' {
        p += 1;
    }
    if p < n && s[p] == ' ' {
        Some(p + 1 - start)
    } else {
        None
    }
}

/// root.37.11 final byte -> code-unit map: the six special bytes become U+01xx;
/// everything else becomes U+00bb (byte as low half).
fn final_map(b: char) -> char {
    match b as u32 {
        232 => '\u{010D}', // č
        248 => '\u{0159}', // ū
        224 => '\u{0155}', // ā
        230 => '\u{0107}', // ę
        240 => '\u{0111}', // š
        254 => '\u{0163}', // ž
        x => char::from_u32(x & 0xFF).unwrap(),
    }
}
