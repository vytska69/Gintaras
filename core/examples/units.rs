use gintaras_core::{voicedb::VoiceDatabase, transcriber, conversion, sequencer};
use std::io::BufRead;
fn main() {
    let dta = std::env::args().nth(1).unwrap();
    let words = std::env::args().nth(2).unwrap();
    let db = VoiceDatabase::parse(&std::fs::read(dta).unwrap());
    let f = std::fs::File::open(words).unwrap();
    for line in std::io::BufReader::new(f).lines() {
        let w = line.unwrap(); let w = w.trim();
        if w.is_empty() { continue; }
        let cp = transcriber::normalise(w);
        let ph = transcriber::transcribe(&cp, cp.len());
        let s = conversion::convert_tokens(&ph);
        let units = sequencer::sequence(&db, &s);
        // print units with non-ASCII as {hex} so terminal encoding can't corrupt the compare
        let vis: Vec<String> = units.iter().map(|u| u.chars().map(|c|
            if (c as u32) < 128 { c.to_string() } else { format!("{{{:x}}}", c as u32) }).collect()).collect();
        println!("{}", vis.join(" "));
    }
}
