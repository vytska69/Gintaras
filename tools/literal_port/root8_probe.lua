-- Load the REAL trans module but stub ffi.load("transcr") with a fake whose
-- KircTranskr just copies a phoneme string we set, so we exercise the REAL root.8.
dofile("/home/user/Gintaras/engine/synth_research/_lpegload.lua")
local ffi=require("ffi")
-- Provide a fake transcr lib via ffi by intercepting ffi.load.
local realload=ffi.load
local PH=""  -- phoneme string to inject (what KircTranskr would produce)
ffi.cdef[[ void* malloc(size_t); ]]
local fakelib=setmetatable({},{__index=function(_,k)
  if k=="init_transcr" then return function() end end
  if k=="PradApdZod" then return function(inp,out,n,c)
      -- copy inp -> out (normalized = input)
      local s=ffi.string(inp)
      ffi.copy(out, s)  -- includes nul
    end end
  if k=="SpellZod" then return function(inp,out,n,c) ffi.copy(out, ffi.string(inp)) end end
  if k=="KircTranskr" then return function(inp,out,n,c)
      -- ignore inp; write our injected phoneme string PH into out
      ffi.copy(out, PH)
    end end
  return function() end
end})
ffi.load=function(name, ...) if name=="transcr" then return fakelib end return realload(name, ...) end

local f=io.open("/tmp/mods/mod_5.bc","rb"); local chunk=assert(loadstring(f:read("*a"),"trans")); f:close()
local trans=chunk()
trans.init()

-- API: read lines "ascii\tphonemeString(with \n separators replaced by ;)" maybe.
-- We'll get phonemes from argv-provided file mapping.
local function hexout(s) local h={} for i=1,#s do h[#h+1]=string.format("%02x",s:byte(i)) end return table.concat(h) end

-- read injected phonemes from /tmp/phon_in.tsv : key \t phonemeRaw(newline-joined as \n literal)
for line in io.lines("/tmp/phon_in.tsv") do
  local key,ph=line:match("^([^\t]+)\t(.*)$")
  if key then
    ph=ph:gsub("\\n","\n")
    PH=ph
    local conv=trans.conversion("dummy", false)  -- spellFlag=false -> PradApdZod path
    print(key.."\t"..hexout(conv))
  end
end
