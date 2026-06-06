-- Capture the original's per-PERIOD emit stream for a word, as DSP oracle.
-- We tap coroutine.yield inside speak: speak yields (buffer_cdata, nsamples)
-- per emitted PCM chunk (root.46). To get period-level granularity we instead
-- record every yielded buffer in order with its sample count + first samples.
dofile("/home/user/Gintaras/engine/synth_research/_lpegload.lua")
local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb");pre[nm]=assert(loadstring(f:read("*a"),nm));f:close() end
LANG="LT"
local CONV={}
do local f=io.open("/tmp/conv_table.tsv","r")
  for line in f:lines() do local w,hex=line:match("^([^\t]+)\t([^\t]*)")
    if w and hex then local b={} for bb in hex:gmatch("%x%x") do b[#b+1]=string.char(tonumber(bb,16)) end CONV[w]=table.concat(b) end end
  f:close() end
package.loaded["trans"]=setmetatable({init=function() end,
  conversion=function(word) local a=word:gsub("(.)%z","%1"); return CONV[a] or word end,
  tolower=function(s) return s end},{__index=function() return function(s) return s end end})
local database=require("database")
local dta=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local voice=database.loaddatabase("Gintaras", dta)
package.loaded["database"]=database
package.loaded["dictionary"]=setmetatable({dictconv=function(t) return t end,DICT=setmetatable({},{__index=function() return false end}),loaddictionary=function() end,loadphrase=function() end},{__index=function() return function() end end})
require("translate")
local vs=require("voicesynth")
vs.loadvoice("Gintaras", nil, 0)
pcall(function() vs.loadvoice("Gintaras", nil, 8) end)
local ffi=require("ffi")
local function toUTF16LE(s) local o={} for i=1,#s do o[#o+1]=string.char(s:byte(i));o[#o+1]="\0" end return table.concat(o) end
local w=arg[1] or "mama"
local co=coroutine.wrap(vs.speak)
local out=io.open("/tmp/dsp_periods_"..w..".tsv","w")
local r={co("Gintaras",toUTF16LE(w),100,100,100,100,100,100,100,100)}
local g=0
while true do g=g+1; if g>2000000 then break end
  local buf=r[1]; local n=r[2]
  if buf==nil then break end
  if type(buf)=="cdata" and n then
    local first={}
    for i=0,math.min(n-1,5) do first[#first+1]=tostring(buf[i]) end
    out:write(n.."\t"..table.concat(first,",").."\n")
  end
  r={co()}
end
out:close()
print(w.." period-chunks captured")
