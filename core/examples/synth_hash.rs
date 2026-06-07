use gintaras_core::{voicedb::VoiceDatabase, transcriber, synth::DiphoneSynth};
use std::io::BufRead;
fn fnv(pcm:&[i16])->u64{ let mut h=0xcbf29ce484222325u64;
  for &s in pcm { for b in (s as u16).to_le_bytes() { h^=b as u64; h=h.wrapping_mul(0x100000001b3); } } h }
fn main(){
    let dta=std::env::args().nth(1).unwrap();
    let words=std::env::args().nth(2).unwrap();
    let db=VoiceDatabase::parse(&std::fs::read(dta).unwrap());
    let synth=DiphoneSynth::new(&db);
    let f=std::fs::File::open(words).unwrap();
    for line in std::io::BufReader::new(f).lines(){
        let w=line.unwrap(); let w=w.trim(); if w.is_empty(){continue;}
        let cp=transcriber::normalise(w);
        let ph=transcriber::transcribe(&cp,cp.len());
        let pcm=synth.synthesize(&ph,100,100);
        println!("{}\t{:016x}", pcm.len(), fnv(&pcm));
    }
}
