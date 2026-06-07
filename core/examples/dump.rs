use gintaras_core::voicedb::VoiceDatabase;
use std::collections::HashSet;
fn main() {
    let path = std::env::args().nth(1).unwrap_or("../ios/Resources/Gintaras.dta".into());
    let data = std::fs::read(&path).expect("read dta");
    let db = VoiceDatabase::parse(&data);
    let mut names: HashSet<String> = HashSet::new();
    for e in &db.entries { names.insert(VoiceDatabase::unit_name(&e.name)); }
    println!("blocks={}", db.blocks.len());
    println!("entries={}", db.entries.len());
    println!("distinct_unit_names={}", names.len());
    // number bucket sample
    if let Some(w) = db.number_bucket("N1+3R") { println!("N1+3R={:?}", w); }
    if let Some(w) = db.number_bucket("N0+3R") { println!("N0+3R={:?}", w); }
    // expand a known diphone unit "ma-" : leaf count + first 6 samples of first leaf
    if let Some(i) = db.lookup("ma-") {
        let leaves = db.expand_unit(i);
        println!("ma- leaves={} firstVoiced={} firstCount={}", leaves.len(),
            leaves.first().map(|l| l.voiced).unwrap_or(false),
            leaves.first().map(|l| l.count).unwrap_or(0.0));
        if let Some(l) = leaves.first() {
            let n = l.samples.len().min(6);
            println!("ma- leaf0 len={} head={:?}", l.samples.len(), &l.samples[..n]);
        }
    } else { println!("ma- NOT FOUND"); }
}
