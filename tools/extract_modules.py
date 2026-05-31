#!/usr/bin/env python3
"""Extract the 8 embedded LuaJIT sub-modules from a decrypted main.bin bundle.
Run under a 32-bit LuaJIT 2.1 (the bundle is 32-bit bytecode). Helper for RE.

  luajit extract_modules.lua main.dec.luajit  outdir/
"""
print(__doc__)
