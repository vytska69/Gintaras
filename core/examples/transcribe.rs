use gintaras_core::transcriber::{normalise, transcribe};
use std::io::BufRead;
fn main() {
    let path = std::env::args().nth(1).unwrap();
    let f = std::fs::File::open(path).unwrap();
    for line in std::io::BufReader::new(f).lines() {
        let w = line.unwrap();
        let w = w.trim();
        if w.is_empty() { continue; }
        let cp = normalise(w);
        let ph = transcribe(&cp, cp.len());
        println!("{}\t{}", w, ph.join(" "));
    }
}
