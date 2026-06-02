dofile("_lpegload.lua")
local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb");pre[nm]=assert(loadstring(f:read("*a"),nm));f:close() end
LANG="LT"
package.loaded["trans"]=setmetatable({init=function() end},{__index=function() return function(s) return s end end})
local db=require("database")
local dta=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local VOICES=db.loaddatabase(dta,"Gintaras")
print("VOICES keys:")
local cnt=0
for k,v in pairs(VOICES) do cnt=cnt+1; if cnt<=8 then print("  ",(type(k)=="string" and #k<12) and k or ("<"..type(k)..">"), type(v)) end end
print("  total keys:",cnt)
local g=VOICES["Gintaras"]
print("VOICES.Gintaras type:",type(g))
if type(g)=="table" then local c=0; for k,v in pairs(g) do c=c+1; if c<=12 then local kk=(type(k)=="string")and ("["..#k.."]"..k:gsub("%z","."))or tostring(k); print("    field",kk,type(v)) end end; print("    Gintaras fields:",c) end
local vs=require("voicesynth")
local ok,err=xpcall(function() return vs.loadvoice(g,"Gintaras",0) end, debug.traceback)
print("loadvoice:",ok); if not ok then print(err) end
