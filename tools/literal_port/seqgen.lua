-- Authoritative unit-sequence generator: REAL database + REAL translate.
-- Conversion strings injected from /tmp/conv_real.tsv (produced by the REAL root.8).
dofile("/home/user/Gintaras/engine/synth_research/_lpegload.lua")
local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb");pre[nm]=assert(loadstring(f:read("*a"),nm));f:close() end
LANG="LT"
-- stub trans (not needed; we inject conv strings directly)
package.loaded["trans"]=setmetatable({init=function() end},{__index=function() return function(s) return s end end})

local database=require("database")
local dta=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local voice=database.loaddatabase("Gintaras", dta)

local translate=require("translate")

-- load conv strings (hex of UTF-16LE) keyed by word
local function fromhex(h) local b={} for x in h:gmatch("%x%x") do b[#b+1]=string.char(tonumber(x,16)) end return table.concat(b) end
local function hex(s) local h={} for i=1,#s do h[#h+1]=string.format("%02x",s:byte(i)) end return table.concat(h) end

local out=io.open("/tmp/seq_real.tsv","w")
for line in io.lines("/tmp/conv_real.tsv") do
  local w,h=line:match("^([^\t]+)\t(.*)$")
  if w then
    local conv=fromhex(h)
    local seq=translate.translate(conv, nil, voice)
    local keys={}
    if type(seq)=="table" then
      for _,k in ipairs(seq) do
        if type(k)=="string" then keys[#keys+1]=hex(k) end
      end
    end
    out:write(w.."\t"..h.."\t"..table.concat(keys," ").."\n")
  end
end
out:close()
print("done")
