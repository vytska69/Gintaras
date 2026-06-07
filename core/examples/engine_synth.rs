use gintaras_core::engine::{Engine, SynthParams};
use std::io::BufRead;
fn fnv(pcm:&[i16])->u64{ let mut h=0xcbf29ce484222325u64; for &s in pcm { for b in (s as u16).to_le_bytes(){h^=b as u64;h=h.wrapping_mul(0x100000001b3);} } h }
fn rd(n:&str)->Option<Vec<u8>>{ std::fs::read(format!("../ios/Resources/{}",n)).ok() }
fn main(){
    let dta=std::fs::read("../ios/Resources/Gintaras.dta").unwrap();
    let e=Engine::new(&dta, rd("ruleslit.rul"), rd("stdlit.dct"), rd("spelllit.dct"),
        [rd("punc0lit.dct"),rd("punc1lit.dct"),rd("punc2lit.dct"),rd("punc3lit.dct")]);
    let p=SynthParams{rate:100,pitch:100,punctuation_level:3,numgroup:16,use_dictionary:true,pause_word:100,pause_sentence:100};
    let f=std::fs::File::open(std::env::args().nth(1).unwrap()).unwrap();
    for line in std::io::BufReader::new(f).lines(){
        let l=line.unwrap(); let l=l.trim(); if l.is_empty(){continue;}
        let pcm=e.synthesize_text(l,&p);
        println!("{}\t{:016x}", pcm.len(), fnv(&pcm));
    }
}
