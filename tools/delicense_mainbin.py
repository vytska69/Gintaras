#!/usr/bin/env python3
"""
Remove the license gate from the engine's main.bin (open-source build needs none).

main.bin = LuaJIT-2.1 bytecode bundle XOR-0xAF. The "main" submodule reads the
Java fields imei/number/act/uptime/expiry and computes a "licensed" boolean via
  md5(imei .. salt .. "16ac"):sub(1,8) == activation_key
The boolean is returned to the synthesis caller, which otherwise speaks the
"Prašome suaktyvinti licenciją" prompt (the `expiry` field) instead of the text.

We neutralise it with a single, unique 4-byte bytecode patch in the "main"
module: the instruction that copies the license flag into the return tuple

    MOV  55, 48        (0x12 0x37 0x30 0x00)   -> r55 = computed flag
is replaced by
    KPRI 55, 2         (0x2b 0x37 0x02 0x00)   -> r55 = true (always licensed)

so synthesis always proceeds with the real text. The pattern is unique in the
whole (decrypted) bundle, so the patch is unambiguous and reversible.
"""
import sys

XOR = 0xAF
FIND    = bytes([0x12, 0x37, 0x30, 0x00])   # MOV  55, 48
REPLACE = bytes([0x2b, 0x37, 0x02, 0x00])   # KPRI 55, 2 (true)


def delicense(raw: bytes) -> bytes:
    dec = bytes(b ^ XOR for b in raw)
    n = dec.count(FIND)
    if n != 1:
        raise SystemExit(f"expected exactly one license-flag instruction, found {n}")
    dec = dec.replace(FIND, REPLACE)
    return bytes(b ^ XOR for b in dec)


if __name__ == "__main__":
    src = sys.argv[1] if len(sys.argv) > 1 else "main.bin"
    dst = sys.argv[2] if len(sys.argv) > 2 else "main.bin"
    out = delicense(open(src, "rb").read())
    open(dst, "wb").write(out)
    print(f"delicensed {src} -> {dst} ({len(out)} bytes)")
