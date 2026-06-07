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
package.loaded["trans"]=setmetatable({init=function() end,conversion=function(word) local a=word:gsub("(.)%z","%1"); return CONV[a] or word end,tolower=function(s) return s end},{__index=function() return function(s) return s end end})
local database=require("database")
local dta=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
database.loaddatabase("Gintaras", dta)
package.loaded["database"]=database
package.loaded["dictionary"]=setmetatable({dictconv=function(t) return t end,DICT=setmetatable({},{__index=function() return false end}),loaddictionary=function() end,loadphrase=function() end},{__index=function() return function() end end})
local translate=require("translate")
local orig=translate.translate; local LAST
translate.translate=function(c,a,v) LAST=orig(c,a,v); return LAST end
package.loaded["translate"]=translate
local vs=require("voicesynth"); vs.loadvoice("Gintaras", nil, 0); pcall(function() vs.loadvoice("Gintaras", nil, 8) end)
local function toUTF16LE(s) local o={} for i=1,#s do o[#o+1]=string.char(s:byte(i));o[#o+1]="\0" end return table.concat(o) end
local function hx(s) local h={} for i=1,#s do h[#h+1]=string.format("%02x",s:byte(i)) end return table.concat(h) end
for _,w in ipairs(arg) do
  LAST=nil
  local co=coroutine.wrap(vs.speak); local r={co("Gintaras",toUTF16LE(w),100,100,100,100,100,100,100,100)}
  while r[1]~=nil do r={co()} end
  local keys={} for i,k in ipairs(LAST) do keys[i]=hx(k) end
  io.write(w.."\t"..table.concat(keys," ").."\n")
end
