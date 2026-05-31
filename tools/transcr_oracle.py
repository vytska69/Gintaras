#!/usr/bin/env python3
"""
transcr_oracle — execute the original 32-bit ARM libtranscr.so under the Unicorn
CPU emulator on the dev host, to use it as a GOLDEN REFERENCE ("oracle") while
clean-room reimplementing it for arm64.

It loads the ELF by hand (2 PT_LOAD segments), applies the 101366 R_ARM_RELATIVE
relocations and the 22 R_ARM_JUMP_SLOT libc imports (routed to Python shims),
then lets you call the exported functions (init_transcr, KircTranskr, ...).

This is a REVERSE-ENGINEERING TOOL ONLY. The shipped Android engine is a native
clean-room C reimplementation; nothing here is bundled into the app.
"""
import sys, struct, math
from elftools.elf.elffile import ELFFile
from unicorn import *
from unicorn.arm_const import *

BASE   = 0x00100000        # load base for the library image
TRAMP  = 0x40000000        # libc import trampolines (1 page)
RETMARK= 0x50000000        # return sentinel (LR target)
STACK  = 0x60000000        # stack region
STKSZ  = 0x00200000
SCRATCH= 0x70000000        # caller-owned scratch buffers
SCRSZ  = 0x01000000

def align_down(x, a=0x1000): return x & ~(a-1)
def align_up(x, a=0x1000):   return (x + a-1) & ~(a-1)

class TranscrOracle:
    def __init__(self, path):
        self.path = path
        self.f = open(path, 'rb')
        self.elf = ELFFile(self.f)
        self.uc = Uc(UC_ARCH_ARM, UC_MODE_ARM)
        self.syms = {}          # exported name -> vaddr
        self.imports = []       # index -> name (libc)
        self.scr_ptr = SCRATCH
        self.strtok_state = 0
        self._map_segments()
        self._load_symbols()
        self._relocate()
        self._setup_runtime()

    # --- memory image --------------------------------------------------
    def _map_segments(self):
        for seg in self.elf.iter_segments():
            if seg['p_type'] != 'PT_LOAD':
                continue
            va = seg['p_vaddr']; memsz = seg['p_memsz']
            start = align_down(BASE + va)
            end   = align_up(BASE + va + memsz)
            self.uc.mem_map(start, end - start, UC_PROT_ALL)
            self.uc.mem_write(BASE + va, seg.data())

    def _load_symbols(self):
        dynsym = self.elf.get_section_by_name('.dynsym')
        for s in dynsym.iter_symbols():
            if s.name and s['st_value']:
                self.syms[s.name] = s['st_value']

    # --- relocations ---------------------------------------------------
    def _r32(self, va):
        return struct.unpack('<I', self.uc.mem_read(va, 4))[0]
    def _w32(self, va, val):
        self.uc.mem_write(va, struct.pack('<I', val & 0xffffffff))

    def _relocate(self):
        # R_ARM_RELATIVE (type 23): *(B+off) = B + addend(in place)
        reld = self.elf.get_section_by_name('.rel.dyn')
        n = 0
        for r in reld.iter_relocations():
            off = BASE + r['r_offset']
            self._w32(off, BASE + self._r32(off))
            n += 1
        # R_ARM_JUMP_SLOT (type 22): point GOT at our trampolines
        relp = self.elf.get_section_by_name('.rel.plt')
        dynsym = self.elf.get_section_by_name('.dynsym')
        self.uc.mem_map(TRAMP, 0x1000, UC_PROT_ALL)
        for i, r in enumerate(relp.iter_relocations()):
            name = dynsym.get_symbol(r['r_info_sym']).name
            self.imports.append(name)
            self._w32(BASE + r['r_offset'], TRAMP + i * 4)
        self.reloc_count = n

    # --- runtime / hooks ----------------------------------------------
    def _setup_runtime(self):
        self.uc.mem_map(RETMARK, 0x1000, UC_PROT_ALL)
        self.uc.mem_map(STACK, STKSZ, UC_PROT_ALL)
        self.uc.mem_map(SCRATCH, SCRSZ, UC_PROT_ALL)
        self.uc.hook_add(UC_HOOK_CODE, self._on_code, begin=TRAMP, end=TRAMP + 0x1000)

    def _on_code(self, uc, addr, size, user):
        idx = (addr - TRAMP) // 4
        if idx < 0 or idx >= len(self.imports):
            return
        name = self.imports[idx]
        fn = getattr(self, 'libc_' + name, None)
        if fn is None:
            raise RuntimeError("unimplemented libc import: " + name)
        lr = uc.reg_read(UC_ARM_REG_LR)
        fn(uc)
        uc.reg_write(UC_ARM_REG_PC, lr)

    # --- helpers -------------------------------------------------------
    def cstr(self, va, maxlen=4096):
        out = bytearray()
        while len(out) < maxlen:
            b = self.uc.mem_read(va, 1)[0]
            if b == 0: break
            out.append(b); va += 1
        return bytes(out)

    def push_bytes(self, data):
        p = self.scr_ptr
        self.uc.mem_write(p, data)
        self.scr_ptr = align_up(p + len(data) + 1, 16)
        return p

    def arg(self, uc, i):
        if i < 4:
            return uc.reg_read([UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3][i])
        sp = uc.reg_read(UC_ARM_REG_SP)
        return struct.unpack('<I', uc.mem_read(sp + (i - 4) * 4, 4))[0]

    def ret(self, uc, val):
        uc.reg_write(UC_ARM_REG_R0, val & 0xffffffff)

    # --- libc shims ----------------------------------------------------
    def libc_strlen(self, uc): self.ret(uc, len(self.cstr(self.arg(uc, 0))))
    def libc_strcmp(self, uc):
        a = self.cstr(self.arg(uc, 0)); b = self.cstr(self.arg(uc, 1))
        self.ret(uc, 0 if a == b else (1 if a > b else 0xffffffff))
    def libc_strncmp(self, uc):
        n = self.arg(uc, 2)
        a = self.cstr(self.arg(uc, 0))[:n]; b = self.cstr(self.arg(uc, 1))[:n]
        self.ret(uc, 0 if a == b else (1 if a > b else 0xffffffff))
    def libc_strcpy(self, uc):
        d = self.arg(uc, 0); s = self.cstr(self.arg(uc, 1))
        uc.mem_write(d, s + b'\0'); self.ret(uc, d)
    def libc_strncpy(self, uc):
        d = self.arg(uc, 0); s = self.cstr(self.arg(uc, 1)); n = self.arg(uc, 2)
        buf = s[:n] + b'\0' * (n - min(len(s), n)); uc.mem_write(d, buf[:n]); self.ret(uc, d)
    def libc_strcat(self, uc):
        d = self.arg(uc, 0); base = self.cstr(d); s = self.cstr(self.arg(uc, 1))
        uc.mem_write(d, base + s + b'\0'); self.ret(uc, d)
    def libc_strncat(self, uc):
        d = self.arg(uc, 0); base = self.cstr(d); s = self.cstr(self.arg(uc, 1)); n = self.arg(uc, 2)
        uc.mem_write(d, base + s[:n] + b'\0'); self.ret(uc, d)
    def libc_strchr(self, uc):
        s0 = self.arg(uc, 0); s = self.cstr(s0); c = self.arg(uc, 1) & 0xff
        i = s.find(bytes([c])); self.ret(uc, (s0 + i) if i >= 0 else 0)
    def libc_strstr(self, uc):
        s0 = self.arg(uc, 0); s = self.cstr(s0); n = self.cstr(self.arg(uc, 1))
        i = s.find(n); self.ret(uc, (s0 + i) if i >= 0 else 0)
    def libc_memcpy(self, uc):
        d = self.arg(uc, 0); s = self.arg(uc, 1); n = self.arg(uc, 2)
        uc.mem_write(d, bytes(uc.mem_read(s, n))); self.ret(uc, d)
    def libc_toupper(self, uc):
        c = self.arg(uc, 0)
        self.ret(uc, ord(chr(c).upper()) if c < 128 else c)
    def libc_strtok(self, uc):
        s = self.arg(uc, 0); delim = self.cstr(self.arg(uc, 1))
        if s == 0: s = self.strtok_state
        data = self.cstr(s)
        i = 0
        while i < len(data) and data[i] in delim: i += 1
        if i >= len(data): self.ret(uc, 0); return
        start = s + i; j = i
        while j < len(data) and data[j] not in delim: j += 1
        if j < len(data):
            uc.mem_write(s + j, b'\0'); self.strtok_state = s + j + 1
        else:
            self.strtok_state = s + j
        self.ret(uc, start)
    def libc_exp(self, uc): self._math1(uc, math.exp)
    def libc_cos(self, uc): self._math1(uc, math.cos)
    def libc_log(self, uc): self._math1(uc, math.log)
    def _math1(self, uc, fn):
        lo = uc.reg_read(UC_ARM_REG_R0); hi = uc.reg_read(UC_ARM_REG_R1)
        x = struct.unpack('<d', struct.pack('<II', lo, hi))[0]
        try: y = fn(x)
        except Exception: y = 0.0
        b = struct.pack('<d', y); rlo, rhi = struct.unpack('<II', b)
        uc.reg_write(UC_ARM_REG_R0, rlo); uc.reg_write(UC_ARM_REG_R1, rhi)
    def libc___cxa_atexit(self, uc): self.ret(uc, 0)
    def libc___cxa_finalize(self, uc): self.ret(uc, 0)
    def libc_fopen(self, uc): self.ret(uc, 0)       # no file -> NULL
    def libc_fclose(self, uc): self.ret(uc, 0)
    def libc_fscanf(self, uc): self.ret(uc, 0xffffffff)  # EOF
    def libc_sprintf(self, uc):
        dst = self.arg(uc, 0); fmt = self.cstr(self.arg(uc, 1)).decode('latin1')
        s = self._format(uc, fmt, argbase=2)
        uc.mem_write(dst, s.encode('latin1') + b'\0'); self.ret(uc, len(s))
    def libc_sscanf(self, uc):
        # minimal: handle %d and %s sequentially
        src = self.cstr(self.arg(uc, 0)).decode('latin1'); fmt = self.cstr(self.arg(uc, 1)).decode('latin1')
        import re
        toks = src.split(); ti = 0; ai = 2; got = 0
        for m in re.finditer(r'%[a-z]', fmt):
            if ti >= len(toks): break
            conv = m.group()[-1]; ptr = self.arg(uc, ai); ai += 1
            if conv == 'd':
                try: self._w32(ptr, int(toks[ti]) & 0xffffffff); got += 1
                except: pass
            elif conv == 's':
                uc.mem_write(ptr, toks[ti].encode('latin1') + b'\0'); got += 1
            ti += 1
        self.ret(uc, got)

    def _format(self, uc, fmt, argbase=2):
        import re
        out = []; ai = argbase; i = 0
        for m in re.finditer(r'%[-+ #0-9.]*[diouxXeEfgGcsp%]', fmt):
            out.append(fmt[i:m.start()]); spec = m.group(); conv = spec[-1]; i = m.end()
            if conv == '%': out.append('%'); continue
            if conv in 'eEfgG':
                lo = self.arg(uc, ai); hi = self.arg(uc, ai + 1); ai += 2
                val = struct.unpack('<d', struct.pack('<II', lo, hi))[0]
                out.append(spec % val); continue
            v = self.arg(uc, ai); ai += 1
            if conv == 's': out.append(self.cstr(v).decode('latin1'))
            elif conv == 'c': out.append(chr(v & 0xff))
            else:
                if conv in 'di' and v >= 0x80000000: v -= 0x100000000
                out.append(spec % v)
        out.append(fmt[i:])
        return ''.join(out)

    # --- call mechanism ------------------------------------------------
    def call(self, vaddr, args=(), timeout=0):
        uc = self.uc
        uc.reg_write(UC_ARM_REG_SP, STACK + STKSZ - 64)
        regs = [UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3]
        for i in range(4):
            uc.reg_write(regs[i], args[i] if i < len(args) else 0)
        if len(args) > 4:
            sp = STACK + STKSZ - 64
            for k, a in enumerate(args[4:]):
                uc.mem_write(sp + k * 4, struct.pack('<I', a & 0xffffffff))
        uc.reg_write(UC_ARM_REG_LR, RETMARK)
        uc.emu_start(BASE + (vaddr & ~1), RETMARK, timeout=timeout)
        return uc.reg_read(UC_ARM_REG_R0)

    def call_named(self, name, args=(), **kw):
        return self.call(self.syms[name], args, **kw)

    # --- high level: full word -> phoneme transcription ----------------
    def reset_scratch(self):
        self.scr_ptr = SCRATCH

    def _buf(self, data=b'', cap=4096):
        p = self.scr_ptr
        self.uc.mem_write(p, (data + b'\x00' * cap)[:cap])
        self.scr_ptr = align_up(p + cap + 16, 16)
        return p

    def transcribe(self, word, enc='cp1257'):
        """Run the documented front-end pipeline for a single word and return
        the newline-separated phoneme string produced by KircTranskr."""
        self.reset_scratch()
        inp = self._buf(word.encode(enc, 'replace') + b'\0')
        norm = self._buf()
        self.call_named('PradApdZod', (inp, norm, 4096, 0))
        out = self._buf()
        n = self.call_named('KircTranskr', (norm, out, 4096, 0))
        raw = self.cstr(out, 4096)
        return self.cstr(norm).decode(enc, 'replace'), raw.decode('latin1'), n


if __name__ == '__main__':
    o = TranscrOracle(sys.argv[1] if len(sys.argv) > 1 else 'libtranscr.so')
    print("loaded: %d relative relocs, imports=%s" % (o.reloc_count, o.imports))
    print("exports sample:", [k for k in ('init_transcr','KircTranskr','Kirciuoti','SpellZod') if k in o.syms])
    o.call_named('init_transcr')
    print("init_transcr() OK")
