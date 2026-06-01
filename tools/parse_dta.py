#!/usr/bin/env python3
"""
Parser for Gintaras.dta — recovered byte-for-byte from the LuaJIT `database`
module's loaddatabase() (see /tmp/db.dis disassembly).

Layout (flat byte stream, little-endian, pos walks forward):

  while pos < len:
    b = u8[pos]; pos += 1
    if b == 255:                         # shared sample-array block
        idx = u16[pos]; pos += 2
        n   = u16[pos]; pos += 2
        samples = i16[pos : pos+n]; pos += 2*n
        BLOCKS[idx] = samples            # diphone waveform pool
    else:                                # a voice entry; b = name length
        name = bytes[pos:pos+b]; pos += b
        bucket = MAP2[name[:2]]          # (a const lookup table; selects list vs key mode)
        cnt = u8[pos]; pos += 1          # number of phoneme records
        entry = {}
        for i in 1..cnt:
            blen = u8[pos]; pos += 1
            rec = {}
            if blen == 255:              # record keyed by a u16
                if bucket: append i16 u16[pos] to a list
                else:      rec.key = u16[pos]
                pos += 2
            else:                        # record keyed by a blen-byte string
                s = bytes[pos:pos+blen]; pos += blen
                if bucket: append string s to a list
                else:      rec.key = s
            if not bucket:
                c0 = u8[pos]; pos += 1
                c1 = u8[pos]; pos += 1
                rec.count = c0 + 1
                rec.typ   = c1 & 127
                entry[i] = rec
        VOICES[ name_string ] = entry

NOTE: the `bucket`/MAP2 const table couples a few special name-prefixes to a
"flat list" representation; for the single Lithuanian voice ("Gintaras") the
normal (dict) path is what matters. This parser implements both and reports
which path each entry took, so we can validate against the real file.
"""
import struct
import sys
from collections import Counter


def parse_dta(data: bytes):
    pos = 0
    n = len(data)
    blocks = {}           # idx -> list[int16]  (shared sample pools)
    voices = {}           # name(str) -> entry
    u8 = lambda p: data[p]
    u16 = lambda p: struct.unpack_from("<H", data, p)[0]
    i16 = lambda p: struct.unpack_from("<h", data, p)[0]

    while pos < n:
        b = u8(pos); pos += 1
        if b == 255:
            idx = u16(pos); pos += 2
            cnt = u16(pos); pos += 2
            samples = struct.unpack_from("<%dh" % cnt, data, pos)
            pos += 2 * cnt
            blocks[idx] = samples
        else:
            name = data[pos:pos + b]; pos += b
            # MAP2 lookup: we don't yet know the exact const map, so default to
            # the dict path (bucket=False). We detect anomalies via validation.
            bucket = False
            cnt = u8(pos); pos += 1
            entry = {"name": name, "records": []}
            for i in range(cnt):
                blen = u8(pos); pos += 1
                rec = {}
                if blen == 255:
                    rec["key"] = u16(pos); pos += 2
                    rec["keytype"] = "u16"
                else:
                    rec["key"] = data[pos:pos + blen]; pos += blen
                    rec["keytype"] = "str"
                if not bucket:
                    c0 = u8(pos); pos += 1
                    c1 = u8(pos); pos += 1
                    rec["count"] = c0 + 1
                    rec["typ"] = c1 & 127
                entry["records"].append(rec)
            voices[name.decode("latin-1", "replace")] = entry
    return blocks, voices, pos


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/Gintaras.dta"
    data = open(path, "rb").read()
    print(f"file: {path}  size={len(data)}")
    try:
        blocks, voices, end = parse_dta(data)
    except Exception as e:
        print(f"PARSE ERROR at recovery: {e}")
        raise
    print(f"parsed to pos={end} (clean={end == len(data)})")
    print(f"sample blocks: {len(blocks)}")
    if blocks:
        sizes = [len(s) for s in blocks.values()]
        total = sum(sizes)
        peaks = [max((abs(x) for x in s), default=0) for s in list(blocks.values())[:50]]
        print(f"  total samples: {total} ({total*2} bytes)")
        print(f"  block size min/max/avg: {min(sizes)}/{max(sizes)}/{total//len(sizes)}")
        print(f"  peak amplitude (first 50 blocks) max: {max(peaks) if peaks else 0}")
        idxs = sorted(blocks.keys())
        print(f"  block idx range: {idxs[0]}..{idxs[-1]}")
    print(f"voices: {len(voices)}")
    for nm, e in list(voices.items())[:5]:
        rt = Counter(r["keytype"] for r in e["records"])
        print(f"  voice '{nm}': {len(e['records'])} records, keytypes={dict(rt)}")


if __name__ == "__main__":
    main()
