use gintaras_core::{voicedb::VoiceDatabase, normalizer::{TextNormalizer, Settings}};
use std::io::BufRead;
fn vis(s:&str)->String{ s.chars().map(|c| if (c as u32)<128 {c.to_string()} else {format!("{{{:x}}}",c as u32)}).collect() }
fn main(){
    let dta=std::env::args().nth(1).unwrap();
    let asset=|n:&str| std::fs::read(format!("app/src/main/assets/{}",n)).ok();
    let data=std::fs::read(&dta).unwrap();
    let db=VoiceDatabase::parse(&data);
    let rules=asset("ruleslit.rul"); let std_=asset("stdlit.dct"); let spell=asset("spelllit.dct");
    let p:[Option<Vec<u8>>;4]=[asset("punc0lit.dct"),asset("punc1lit.dct"),asset("punc2lit.dct"),asset("punc3lit.dct")];
    let tn=TextNormalizer::create(&db, rules.as_deref(), std_.as_deref(), spell.as_deref(),
        [p[0].as_deref(),p[1].as_deref(),p[2].as_deref(),p[3].as_deref()]);
    let st=Settings{punctuation_level:3, numgroup:16, use_dictionary:true};
    let f=std::fs::File::open(std::env::args().nth(2).unwrap()).unwrap();
    for line in std::io::BufReader::new(f).lines(){
        let l=line.unwrap(); let l=l.trim(); if l.is_empty(){continue;}
        let toks=tn.normalize(l,&st);
        let parts:Vec<String>=toks.iter().map(|t|{
            if let Some(ph)=&t.phonemes { format!("P[{}]", vis(&ph.join(" "))) }
            else { format!("T[{}|{}]", vis(&t.text), if t.punctuation=='\0'{String::new()}else{vis(&t.punctuation.to_string())}) }
        }).collect();
        println!("{}", parts.join(" "));
    }
}
