dofile("_lpegload.lua")
local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb");pre[nm]=assert(loadstring(f:read("*a"),nm));f:close() end
LANG="LT"
local db=require("database")
local dta=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local VOICES=db.loaddatabase(dta,"Gintaras")
local vs=require("voicesynth")
-- list exported functions
io.write("voicesynth exports: ");for k,v in pairs(vs) do io.write(k.."("..type(v)..") ") end;print()
local entry=VOICES["Gintaras"] or select(2,next(VOICES))
local ok,err=pcall(vs.loadvoice, entry, "Gintaras", 0)
print("loadvoice(mode0):",ok,err or "OK")
