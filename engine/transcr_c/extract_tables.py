#!/usr/bin/env python3
"""
Extract libtranscr's compiled linguistic tables from .rodata into C source.

libtranscr.so is 2.6 MB but only ~47 KB is code; the bulk is these tables. The
grapheme/phoneme classification sets and the phoneme-output string table live in
.rodata around 0xd2df4..0xd2fa0; morphological ending tables follow. Pulling them
out verbatim is the high-value, low-risk part of the clean-room port: the C
reimplementation reuses the EXACT data, so only the ~30 algorithms must be
rewritten (and checked against the deterministic oracle).

Usage: extract_tables.py <libtranscr.so> > transcr_tables.c
"""
import sys
from elftools.elf.elffile import ELFFile


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "../../_work/oracle/libtranscr.so"
    elf = ELFFile(open(path, "rb"))
    ro = elf.get_section_by_name(".rodata")
    rod = ro.data()
    rbase = ro["sh_addr"]

    def cstr(va):
        o = va - rbase
        e = rod.index(b"\x00", o)
        return rod[o:e]

    # Classification sets used by transkr (cp1257 bytes), recovered from the
    # literal-pool references in the transkr disassembly.
    SETS = {
        "TR_VOWELS":        0xd2df4,  # a e i o u A E I O U ė Ė
        "TR_VOICED_CONS":   0xd2e04,  # b d g z ž j l m n r v w h
        "TR_VOWELS_BOUND":  0xd2e14,  # vowels + '_' word boundary
        "TR_UNVOICED_CONS": 0xd3044,  # B D G K P T C Č F H (assimilation set)
    }

    print("/* Auto-extracted from libtranscr.so .rodata — do not edit by hand. */")
    print('#include "transcr_tables.h"')
    print()
    for name, va in SETS.items():
        b = cstr(va)
        arr = ", ".join("0x%02x" % c for c in b) + ", 0x00"
        print(f"const unsigned char {name}[] = {{ {arr} }};  /* {b!r} */")
    print()
    print("/* TODO: phoneme-output string table (0xd2e3c..0xd2fa0) and the")
    print("   morphological ending tables (0xd3050+) as the algorithms that index")
    print("   them are reimplemented and validated against the oracle. */")


if __name__ == "__main__":
    main()
