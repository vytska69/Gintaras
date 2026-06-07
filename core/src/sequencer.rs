//! Literal port of the original `translate` unit selector (Java `CandidateSequencer`,
//! translate.decomp.txt root.22 + root.22.2..6). An ordered-choice longest-match
//! over the conversion code-unit string against the voice-table unit names; the
//! first existing key at each position is emitted. `-` is the demi-syllable
//! boundary, `^`/`$` word start/end, `|` the palatal marker.

use crate::voicedb::VoiceDatabase;

const DASH: char = '-'; // 0x2d
const HAT: char = '^'; // 0x5e word start
const DOL: char = '$'; // 0x24 word end
const PIPE: char = '|'; // 0x7c palatal

/// Produce the unit-name sequence for a conversion code-unit string.
pub fn sequence(db: &VoiceDatabase, s: &str) -> Vec<String> {
    let chars: Vec<char> = s.chars().collect();
    let n = chars.len();
    let mut out: Vec<String> = Vec::new();
    let mut i = 0usize;
    while i < n {
        let consumed = step(db, &chars, n, i, &mut out);
        i += if consumed > 0 { consumed } else { 1 };
    }
    out
}

#[inline]
fn has(db: &VoiceDatabase, key: &str) -> bool {
    db.lookup(key).is_some()
}

fn step(db: &VoiceDatabase, s: &[char], n: usize, i: usize, out: &mut Vec<String>) -> usize {
    let at_start = i == 0;
    let two_avail = i + 2 <= n;
    let two: String = if two_avail {
        [s[i], s[i + 1]].iter().collect()
    } else {
        String::new()
    };

    // R30: whole-word 2-unit: "^" + 2u + "$".
    if at_start && two_avail && i + 2 == n {
        let k = format!("{}{}{}", HAT, two, DOL);
        if has(db, &k) { out.push(k); return 2; }
    }
    // R28: word-initial 2-unit: "^" + 2u.
    if at_start && two_avail {
        let k = format!("{}{}", HAT, two);
        if has(db, &k) { out.push(k); return 2; }
    }
    // R29: word-final 2-unit: 2u + "$".
    if two_avail && i + 2 == n {
        let k = format!("{}{}", two, DOL);
        if has(db, &k) { out.push(k); return 2; }
    }
    // R27: 2-unit bare.
    if two_avail && has(db, &two) { out.push(two); return 2; }

    // R26: whole-word single: "^" + u + "$".
    if at_start && i + 1 == n {
        let k = format!("{}{}{}", HAT, s[i], DOL);
        if has(db, &k) { out.push(k); return 1; }
    }
    // R11: word-initial left half: "^" + u(i) + u(i+1) + "-".
    if at_start && i + 1 < n {
        let k = format!("{}{}{}{}", HAT, s[i], s[i + 1], DASH);
        if has(db, &k) { out.push(k); return 1; }
    }
    // R10: double-dash left half: u(i) + u(i+1)u(i+2) + "--".
    if i + 3 <= n {
        let k = format!("{}{}{}{}{}", s[i], s[i + 1], s[i + 2], DASH, DASH);
        if has(db, &k) { out.push(k); return 1; }
    }
    // R9: left half: u(i) + u(i+1) + "-".
    if i + 1 < n {
        let k = format!("{}{}{}", s[i], s[i + 1], DASH);
        if has(db, &k) { out.push(k); return 1; }
    }
    // R24: word-initial single bare: "^" + u(i).
    if at_start {
        let k = format!("{}{}", HAT, s[i]);
        if has(db, &k) { out.push(k); return 1; }
    }
    // R14: word-final coda: "-" + u(i-1) + u(i) + "$".
    if i + 1 == n && i >= 1 {
        let k = format!("{}{}{}{}", DASH, s[i - 1], s[i], DOL);
        if has(db, &k) { out.push(k); return 1; }
    }
    // R25: word-final single bare: u(i) + "$".
    if i + 1 == n {
        let k = format!("{}{}", s[i], DOL);
        if has(db, &k) { out.push(k); return 1; }
    }
    // R13: double-dash right half over 2 prev, with palatal retry.
    if i >= 2 {
        let k = format!("{}{}{}{}{}", DASH, DASH, s[i - 2], s[i - 1], s[i]);
        if has(db, &k) { out.push(k); return 1; }
        if PIPE == s[i - 1] {
            let kp = format!("{}{}{}", DASH, s[i - 2], s[i]);
            if has(db, &kp) { out.push(kp); return 1; }
        }
    }
    // R12: right half / coda: "-" + u(i-1) + u(i).
    if i >= 1 {
        let k = format!("{}{}{}", DASH, s[i - 1], s[i]);
        if has(db, &k) { out.push(k); return 1; }
    }
    // R23..R17: long bare units, 9..3 code units, longest first.
    for len in (3..=9).rev() {
        if i + len <= n {
            let k: String = s[i..i + len].iter().collect();
            if has(db, &k) { out.push(k); return len; }
        }
    }
    // R16: 2-unit bare (duplicate alternative).
    if two_avail && has(db, &two) { out.push(two); return 2; }
    // R15: single bare unit.
    let single: String = s[i..i + 1].iter().collect();
    if has(db, &single) { out.push(single); return 1; }

    0 // no TERM matched: caller advances by the P(2) fallback
}
