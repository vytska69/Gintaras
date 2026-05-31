#!/usr/bin/env python3
"""
Rebuild main.bin with the 30-minute trial gate removed (and license flag forced).

main.bin is a LuaJIT 2.1 bytecode bundle (XOR 0xAF) of 8 sub-modules. The
synthesis callback in the `main` sub-module contains a trial gate:

    uptime = sysinfo().uptime
    if uptime >= 1800 (30 min):  MOV 7,12   # swap real text -> trial message

On Android 6 this lets it speak for 30 min after each reboot; on newer Android
sysinfo() behaves differently and the gate trips immediately -> permanent
silence. We neutralise it by turning the swap into a no-op:

    MOV 7,12  (12 07 0c 00)  ->  MOV 7,7  (12 07 07 00)

We also keep the earlier license-flag patch (MOV 55,48 -> KPRI 55,2).

Because the bundle stores each module via table.concat of fragments, the gate
instruction straddles fragment boundaries and cannot be patched in place. So we:
  1. extract the 8 sub-modules (run the bundle, capture loadstring args),
  2. patch the `main` sub-module bytecode (2 unique instructions),
  3. emit a fresh bootstrap that loadstrings the 8 modules and runs main.start,
  4. compile it stripped with a matching LuaJIT 2.1, XOR-encrypt.

Usage:
    rebuild_mainbin.py <luajit-2.1-bin> <orig main.bin> <out main.bin> [workdir]

The luajit binary MUST be a 2.1 build (it is used both to extract and to compile;
it has been verified to load the device's original 2.1.0-alpha bytecode, so the
emitted bytecode is accepted by the device's libluajit/librosasofttts).
"""
import os, subprocess, sys, tempfile

XOR = 0xAF
# main sub-module bytecode patches (LuaJIT 2.1 opcodes: MOV=0x12, KPRI=0x2b)
GATE_FIND    = bytes([0x12, 7, 12, 0])   # MOV 7,12  (trial: swap text->message)
GATE_REPLACE = bytes([0x12, 7,  7, 0])   # MOV 7,7   (no-op)
LIC_FIND     = bytes([0x12, 0x37, 0x30, 0])  # MOV 55,48 (license flag)
LIC_REPLACE  = bytes([0x2b, 0x37, 0x02, 0])  # KPRI 55,2 (true)

MODULES = ["javajni", "database", "dictionary", "md5",
           "trans", "translate", "voicesynth", "main"]

EXTRACT_LUA = r'''
local realload = load
local order = {}
loadstring = function(s) order[#order+1] = s; return function() end end
load = function(s, ...) if type(s)=="string" then order[#order+1]=s; return function() end else return realload(s, ...) end end
pcall(assert(realload(io.open(arg[1],"rb"):read("*a"), "m")))
for i = 1, #order do
  local f = io.open(arg[2].."/mod_"..i..".bc", "wb"); f:write(order[i]); f:close()
end
io.write(#order)
'''


def _env(luajit):
    """Make jit/*.lua (needed by `luajit -b`) resolvable from the source tree."""
    env = dict(os.environ)
    srcdir = os.path.dirname(os.path.abspath(luajit))
    paths = [os.path.join(srcdir, "?.lua"), os.path.join(srcdir, "jit", "?.lua")]
    env["LUA_PATH"] = ";".join(paths) + ";" + env.get("LUA_PATH", "")
    return env


def run_lj(luajit, script, *args):
    with tempfile.NamedTemporaryFile("w", suffix=".lua", delete=False) as t:
        t.write(script); path = t.name
    try:
        return subprocess.run([luajit, path, *args], capture_output=True,
                              text=True, env=_env(luajit))
    finally:
        os.unlink(path)


def lua_escape(b: bytes) -> str:
    return '"' + ''.join('\\%d' % c for c in b) + '"'


def main():
    luajit, orig, out = sys.argv[1], sys.argv[2], sys.argv[3]
    work = sys.argv[4] if len(sys.argv) > 4 else tempfile.mkdtemp()
    os.makedirs(work, exist_ok=True)

    dec = bytes(b ^ XOR for b in open(orig, "rb").read())
    decp = os.path.join(work, "bundle.dec")
    open(decp, "wb").write(dec)

    r = run_lj(luajit, EXTRACT_LUA, decp, work)
    n = int((r.stdout or "0").strip() or 0)
    if n != len(MODULES):
        sys.exit(f"expected {len(MODULES)} modules, extracted {n}: {r.stderr}")

    mods = {MODULES[i]: open(os.path.join(work, f"mod_{i+1}.bc"), "rb").read()
            for i in range(n)}

    m = bytearray(mods["main"])
    if m.count(GATE_FIND) != 1:
        sys.exit(f"trial-gate pattern count = {m.count(GATE_FIND)} (expected 1)")
    m = m.replace(GATE_FIND, GATE_REPLACE)
    if m.count(LIC_FIND) == 1:
        m = m.replace(LIC_FIND, LIC_REPLACE)
    mods["main"] = bytes(m)

    boot = ["local pl = package.preload"]
    for nm in MODULES:
        boot.append(f'pl["{nm}"] = assert(loadstring({lua_escape(mods[nm])}, "{nm}"))')
    boot.append('LANG = "LT"')
    boot.append('return require("main").start(...)')
    bootp = os.path.join(work, "bootstrap.lua")
    open(bootp, "w").write("\n".join(boot))

    bcp = os.path.join(work, "main.rebuilt.bc")
    c = subprocess.run([luajit, "-b", "-s", bootp, bcp], capture_output=True,
                       text=True, env=_env(luajit))
    if c.returncode != 0 or not os.path.exists(bcp):
        sys.exit(f"luajit -b failed: {c.stderr}")

    rebuilt = open(bcp, "rb").read()
    if rebuilt[:3] != b"\x1bLJ":
        sys.exit("rebuilt output is not LuaJIT bytecode")
    open(out, "wb").write(bytes(b ^ XOR for b in rebuilt))
    print(f"rebuilt {out}: {len(rebuilt)} bytes, header {rebuilt[:6].hex()} "
          f"(ver={rebuilt[3]} flags={rebuilt[4]})")


if __name__ == "__main__":
    main()
