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
require("translate")
local vs=require("voicesynth"); vs.loadvoice("Gintaras", nil, 0); pcall(function() vs.loadvoice("Gintaras", nil, 8) end)
local ffi=require("ffi")
local function toUTF16LE(s) local o={} for i=1,#s do o[#o+1]=string.char(s:byte(i));o[#o+1]="\0" end return table.concat(o) end
local function writewav(pcm,path) local f=io.open(path,"wb") local nb=#pcm*2
  local function le32(v) return string.char(v%256,math.floor(v/256)%256,math.floor(v/65536)%256,math.floor(v/16777216)%256) end
  local function le16(v) v=v%65536; return string.char(v%256,math.floor(v/256)) end
  f:write("RIFF");f:write(le32(36+nb));f:write("WAVE");f:write("fmt ");f:write(le32(16));f:write(le16(1));f:write(le16(1));f:write(le32(22050));f:write(le32(44100));f:write(le16(2));f:write(le16(16));f:write("data");f:write(le32(nb))
  for _,s in ipairs(pcm) do f:write(le16(s)) end f:close() end
for _,w in ipairs(arg) do
  local co=coroutine.wrap(vs.speak); local pcm={}
  local r={co("Gintaras",toUTF16LE(w),100,100,100,100,100,100,100,100)}
  while r[1]~=nil do local buf,n=r[1],r[2]; if type(buf)=="cdata" and n then for i=0,n-1 do pcm[#pcm+1]=buf[i] end end; r={co()} end
  writewav(pcm,"/tmp/oref_"..w..".wav")
  print(w.." samples="..#pcm)
end
