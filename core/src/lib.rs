//! Gintaras Lithuanian TTS — portable engine core.
//!
//! A faithful Rust port of the validated pure-Java engine (which is itself a
//! byte-exact port of the original WinTalker engine). One source of truth shared
//! across platforms: iOS (Speech Synthesis Provider extension), Android (JNI),
//! and others via the C ABI in `ffi`.
//!
//! Pipeline: text -> normalizer -> transcriber (G2P) -> conversion -> sequencer
//! (unit selection) -> diphone synth (PSOLA DSP + prosody) -> 16-bit PCM.

pub mod voicedb;
pub mod transcriber;
pub mod conversion;
pub mod sequencer;
pub mod synth;
pub mod numbers;
pub mod normalizer;
