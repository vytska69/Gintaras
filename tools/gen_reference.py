#!/usr/bin/env python3
"""Generate golden (word -> phoneme transcription) pairs from the original
libtranscr.so via the Unicorn oracle. These pairs are the regression target for
the clean-room arm64 reimplementation.

Usage: gen_reference.py <libtranscr.so> [wordlist.txt] > reference.tsv
"""
import sys
from transcr_oracle import TranscrOracle

# A representative seed set covering Lithuanian graphemes, diacritics, clusters,
# palatalization, nasal assimilation, diphthongs and stress patterns.
SEED = """
labas lietuva ačiū šuo žąsis penki ąžuolas motina sudie gintaras kompiuteris
namas vanduo saulė mėnulis žmogus vaikas mokykla knyga draugas meilė
laisvė tėvynė kalba sintezė balsas garsas tekstas skaičius vienas du trys
keturi šeši septyni aštuoni devyni dešimt šimtas tūkstantis pirmas antras
darbas miestas kaimas kelias upė jūra dangus debesis lietus sniegas
ryškus tamsus didelis mažas geras blogas naujas senas greitas lėtas
ąsotis ęglė įsakymas ųdra ūkas česnakas šaukštas žvaigždė chemija
""".split()


def main():
    lib = sys.argv[1] if len(sys.argv) > 1 else "libtranscr.so"
    words = SEED
    if len(sys.argv) > 2:
        words = [w.strip() for w in open(sys.argv[2], encoding="utf-8") if w.strip()]

    o = TranscrOracle(lib)
    o.call_named("init_transcr")

    print("# word\tnormalized\tnorm_cp1257_hex\tphonemes(space-separated)\treturn")
    for w in words:
        norm, raw, n = o.transcribe(w, enc="cp1257")
        phon = " ".join(t for t in raw.split("\n") if t)
        norm_hex = norm.encode("cp1257", "replace").hex()
        print(f"{w}\t{norm}\t{norm_hex}\t{phon}\t{n}")


if __name__ == "__main__":
    main()
