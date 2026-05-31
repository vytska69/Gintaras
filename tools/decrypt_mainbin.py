#!/usr/bin/env python3
"""WinTalker/Gintaras TTS: main.bin <-> LuaJIT bytecode codec.
main.bin is LuaJIT 2.0 bytecode XOR-ed byte-wise with 0xAF.
Usage: decrypt_mainbin.py <in> <out>   (symmetric; same op both ways)
"""
import sys
def xor(data): return bytes(b ^ 0xAF for b in data)
if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    open(dst, "wb").write(xor(open(src, "rb").read()))
    print(f"wrote {dst}")
