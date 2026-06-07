//! C ABI for the Gintaras core — the integration point for the iOS Speech
//! Synthesis Provider extension (and Android via JNI). All strings are UTF-8;
//! asset buffers may be null (the feature is then skipped). PCM is 16-bit mono at
//! `gintaras_sample_rate()` Hz; free it with `gintaras_free_pcm`.

use crate::engine::{Engine, SynthParams};
use std::slice;

/// Synthesis parameters (percentages; 100 = normal). `use_dictionary` is 0/1.
#[repr(C)]
pub struct GintarasParams {
    pub rate: i32,
    pub pitch: i32,
    pub punctuation_level: i32,
    pub numgroup: i32,
    pub use_dictionary: i32,
    pub pause_word: i32,
    pub pause_sentence: i32,
}

unsafe fn opt(ptr: *const u8, len: usize) -> Option<Vec<u8>> {
    if ptr.is_null() || len == 0 {
        None
    } else {
        Some(slice::from_raw_parts(ptr, len).to_vec())
    }
}

/// Create an engine from the voice .dta + optional dictionary asset buffers.
/// Returns null on failure. Free with `gintaras_engine_destroy`.
#[no_mangle]
pub unsafe extern "C" fn gintaras_engine_create(
    dta: *const u8,
    dta_len: usize,
    rules: *const u8,
    rules_len: usize,
    std_: *const u8,
    std_len: usize,
    spell: *const u8,
    spell_len: usize,
    punc0: *const u8,
    punc0_len: usize,
    punc1: *const u8,
    punc1_len: usize,
    punc2: *const u8,
    punc2_len: usize,
    punc3: *const u8,
    punc3_len: usize,
) -> *mut Engine {
    if dta.is_null() || dta_len == 0 {
        return std::ptr::null_mut();
    }
    let dta_slice = slice::from_raw_parts(dta, dta_len);
    let punc = [
        opt(punc0, punc0_len),
        opt(punc1, punc1_len),
        opt(punc2, punc2_len),
        opt(punc3, punc3_len),
    ];
    let e = Engine::new(
        dta_slice,
        opt(rules, rules_len),
        opt(std_, std_len),
        opt(spell, spell_len),
        punc,
    );
    Box::into_raw(Box::new(e))
}

/// Output sample rate (Hz).
#[no_mangle]
pub unsafe extern "C" fn gintaras_sample_rate(engine: *const Engine) -> u32 {
    if engine.is_null() {
        22050
    } else {
        (*engine).sample_rate()
    }
}

/// Synthesize UTF-8 `text` into a freshly-allocated 16-bit PCM buffer. Writes the
/// sample count to `out_len`. Returns null (and *out_len=0) on error. Free the
/// returned buffer with `gintaras_free_pcm(ptr, *out_len)`.
#[no_mangle]
pub unsafe extern "C" fn gintaras_synthesize(
    engine: *const Engine,
    text: *const u8,
    text_len: usize,
    params: *const GintarasParams,
    out_len: *mut usize,
) -> *mut i16 {
    if !out_len.is_null() {
        *out_len = 0;
    }
    if engine.is_null() || text.is_null() || out_len.is_null() {
        return std::ptr::null_mut();
    }
    let txt = match std::str::from_utf8(slice::from_raw_parts(text, text_len)) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let p = if params.is_null() {
        SynthParams::default()
    } else {
        let g = &*params;
        SynthParams {
            rate: g.rate,
            pitch: g.pitch,
            punctuation_level: g.punctuation_level,
            numgroup: g.numgroup,
            use_dictionary: g.use_dictionary != 0,
            pause_word: g.pause_word,
            pause_sentence: g.pause_sentence,
        }
    };
    let pcm = (*engine).synthesize_text(txt, &p);
    let boxed = pcm.into_boxed_slice();
    let len = boxed.len();
    *out_len = len;
    Box::into_raw(boxed) as *mut i16
}

/// Free a PCM buffer returned by `gintaras_synthesize`.
#[no_mangle]
pub unsafe extern "C" fn gintaras_free_pcm(ptr: *mut i16, len: usize) {
    if !ptr.is_null() && len > 0 {
        drop(Box::from_raw(slice::from_raw_parts_mut(ptr, len)));
    }
}

/// Destroy an engine created by `gintaras_engine_create`.
#[no_mangle]
pub unsafe extern "C" fn gintaras_engine_destroy(engine: *mut Engine) {
    if !engine.is_null() {
        drop(Box::from_raw(engine));
    }
}
