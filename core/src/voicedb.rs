//! Pure-Rust reader for `Gintaras.dta` — a faithful port of the Java
//! `VoiceDatabase` (itself recovered byte-for-byte from the original LuaJIT
//! `database` module). Parses the flat little-endian byte stream into shared PCM
//! sample blocks + dictionary entries, and provides the diphone index, the
//! root.51.1 count-expander (`expand_unit`) and the number-word buckets.

use std::collections::HashMap;

/// Names whose first UTF-16 char (low byte, high byte 0) is one of these take the
/// "flat list" path (no per-record count/typ bytes).
const MAP2_CHARS: &[u8] = b"SBVPNED";

/// A shared 16-bit PCM waveform block, referenced by dictionary records.
pub struct SampleBlock {
    pub samples: Vec<i16>,
}

/// One dictionary record: a key (numeric or string) plus optional count/typ.
pub struct Record {
    pub num_key: u16,
    pub str_key: Option<Vec<u8>>, // Some = string key (alias / bucket word)
    pub count: i32,               // 0 for bucket entries (flat list)
    pub typ: u8,                  // 0 for bucket entries; bit0 = voiced
}

impl Record {
    #[inline]
    pub fn is_numeric(&self) -> bool {
        self.str_key.is_none()
    }
}

/// A dictionary entry: a UTF-16LE name and its records.
pub struct Entry {
    pub name: Vec<u8>,
    pub bucket: bool,
    pub records: Vec<Record>,
}

/// A leaf sample block expanded from a unit by root.51.1, with the (possibly
/// fractional) record count and the voiced bit it should be played at.
pub struct LeafRec<'a> {
    pub samples: &'a [i16],
    pub voiced: bool,
    pub count: f64,
}

pub struct VoiceDatabase {
    pub blocks: HashMap<u16, SampleBlock>,
    pub entries: Vec<Entry>,
    /// unit-name (UTF-16 code units as a String) -> entry index (first wins).
    index: HashMap<String, usize>,
    number_buckets: HashMap<String, Vec<String>>,
}

#[inline]
fn u16le(d: &[u8], p: usize) -> u16 {
    (d[p] as u16) | ((d[p + 1] as u16) << 8)
}

impl VoiceDatabase {
    /// Parse the raw `.dta` bytes.
    pub fn parse(d: &[u8]) -> VoiceDatabase {
        let mut blocks: HashMap<u16, SampleBlock> = HashMap::new();
        let mut entries: Vec<Entry> = Vec::new();
        let len = d.len();
        let mut pos = 0usize;
        while pos < len {
            let b = d[pos] as usize;
            pos += 1;
            if b == 0xFF {
                let idx = u16le(d, pos);
                pos += 2;
                let cnt = u16le(d, pos) as usize;
                pos += 2;
                let mut s = Vec::with_capacity(cnt);
                for _ in 0..cnt {
                    s.push(u16le(d, pos) as i16);
                    pos += 2;
                }
                blocks.insert(idx, SampleBlock { samples: s });
            } else {
                let name = d[pos..pos + b].to_vec();
                pos += b;
                let bucket = is_bucket(&name);
                let cnt = d[pos] as usize;
                pos += 1;
                let mut recs = Vec::with_capacity(cnt);
                for _ in 0..cnt {
                    let blen = d[pos] as usize;
                    pos += 1;
                    let mut num_key = 0u16;
                    let mut str_key = None;
                    if blen == 0xFF {
                        num_key = u16le(d, pos);
                        pos += 2;
                    } else {
                        str_key = Some(d[pos..pos + blen].to_vec());
                        pos += blen;
                    }
                    let (mut count, mut typ) = (0i32, 0u8);
                    if !bucket {
                        count = d[pos] as i32 + 1;
                        pos += 1;
                        typ = d[pos] & 0x7F;
                        pos += 1;
                    }
                    recs.push(Record { num_key, str_key, count, typ });
                }
                entries.push(Entry { name, bucket, records: recs });
            }
        }
        let mut db = VoiceDatabase {
            blocks,
            entries,
            index: HashMap::new(),
            number_buckets: HashMap::new(),
        };
        db.build_index();
        db.build_number_buckets();
        db
    }

    /// Decode an entry/key name from its UTF-16LE code units, keeping the FULL code
    /// unit (the high byte distinguishes č=U+010D, š=U+0111, ž=U+0163, ā=U+0155,
    /// ę=U+0107, ū=U+0159 from the ascii/cp1257 chars that share a low byte).
    pub fn unit_name(name: &[u8]) -> String {
        let mut s = String::with_capacity(name.len() / 2);
        let mut i = 0;
        while i + 1 < name.len() {
            let cu = (name[i] as u32) | ((name[i + 1] as u32) << 8);
            // All names use BMP scalars (<= 0x0163, no surrogates).
            if let Some(c) = char::from_u32(cu) {
                s.push(c);
            }
            i += 2;
        }
        s
    }

    fn build_index(&mut self) {
        for (i, e) in self.entries.iter().enumerate() {
            let key = Self::unit_name(&e.name);
            self.index.entry(key).or_insert(i); // putIfAbsent: first wins
        }
    }

    /// Entry index for a unit name, if present.
    pub fn lookup(&self, name: &str) -> Option<usize> {
        self.index.get(name).copied()
    }

    /// Faithful port of voicesynth root.51.1 (count expander): resolve a unit entry
    /// to its leaf sample blocks, propagating the record count with per-level
    /// scaling. Aliasing records (single string key) redirect to another entry.
    pub fn expand_unit(&self, entry: usize) -> Vec<LeafRec<'_>> {
        let mut out = Vec::new();
        self.expand_entry(entry, 0.0, false, 0, &mut out);
        out
    }

    fn expand_entry<'a>(
        &'a self,
        entry: usize,
        in_count: f64,
        have_count: bool,
        depth: i32,
        out: &mut Vec<LeafRec<'a>>,
    ) {
        if depth > 8 {
            return;
        }
        let e = &self.entries[entry];
        let mut total = 0.0f64;
        for r in &e.records {
            total += r.count as f64;
        }
        let scale = if have_count && in_count != 1.0 && total != 0.0 {
            in_count / total
        } else {
            1.0
        };
        for r in &e.records {
            let c = r.count as f64 * scale;
            if r.is_numeric() {
                if let Some(b) = self.blocks.get(&r.num_key) {
                    out.push(LeafRec {
                        samples: &b.samples,
                        voiced: (r.typ & 1) != 0,
                        count: c,
                    });
                }
            } else if let Some(t) = self.lookup(&Self::unit_name(r.str_key.as_ref().unwrap())) {
                if t != entry {
                    self.expand_entry(t, c, true, depth + 1, out);
                }
            }
        }
    }

    fn build_number_buckets(&mut self) {
        let mut m: HashMap<String, Vec<String>> = HashMap::new();
        for e in &self.entries {
            let nm = Self::unit_name(&e.name);
            if !nm.starts_with('N') {
                continue;
            }
            let mut words = Vec::new();
            for r in &e.records {
                if let Some(k) = &r.str_key {
                    words.push(number_word(k));
                }
            }
            if !words.is_empty() || e.bucket {
                m.insert(nm, words);
            }
        }
        self.number_buckets = m;
    }

    /// The "N..." number-word fragments for a key (e.g. "N1+3R" -> ["vienas","tūkstantis"]).
    pub fn number_bucket(&self, key: &str) -> Option<&Vec<String>> {
        self.number_buckets.get(key)
    }
}

/// Bucket if the name's first UTF-16LE char (low byte) is in MAP2 and high byte 0.
fn is_bucket(name: &[u8]) -> bool {
    if name.len() < 2 || name[1] != 0 {
        return false;
    }
    MAP2_CHARS.contains(&name[0])
}

/// Decode a dta-stored word into canonical Lithuanian: the legacy code units
/// U+0111->š, U+0159->ų, U+0171->ū (U+010D 'č' is already canonical).
fn number_word(key: &[u8]) -> String {
    let mut s = String::with_capacity(key.len() / 2);
    let mut i = 0;
    while i + 1 < key.len() {
        let cu = (key[i] as u32) | ((key[i + 1] as u32) << 8);
        let c = match cu {
            0x0111 => 'š',
            0x0159 => 'ų',
            0x0171 => 'ū',
            _ => char::from_u32(cu).unwrap_or('\u{FFFD}'),
        };
        s.push(c);
        i += 2;
    }
    s
}
