dofile("_lpegload.lua")
local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb");pre[nm]=assert(loadstring(f:read("*a"),nm));f:close() end
LANG="LT"
-- stub trans so voicesynth loads without libtranscr.so
package.loaded["trans"]=setmetatable({init=function() end},{__index=function() return function(s) return s end end})
local db=require("database")
local dta=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local VOICES=db.loaddatabase(dta,"Gintaras")
local vs=require("voicesynth")
io.write("voicesynth exports: ");for k,v in pairs(vs) do io.write(k.."("..type(v)..") ") end;print()
local entry=VOICES["Gintaras"] or select(2,next(VOICES))
local ok,err=pcall(vs.loadvoice, entry, "Gintaras", 0)
print("loadvoice(mode0):",ok,err or "OK")
