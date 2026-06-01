local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do
  local f=io.open("/tmp/mods/mod_"..i..".bc","rb"); local bc=f:read("*a"); f:close()
  pre[nm]=assert(loadstring(bc,nm))
end
LANG="LT"
package.loaded["trans"]=setmetatable({init=function() end}, {__index=function() return function(s) return s end end})
package.loaded["translate"]=setmetatable({}, {__index=function() return function(s) return s end end})
local db=require("database")
local dta=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local VOICES=db.loaddatabase(dta,"Gintaras")
local vs=require("voicesynth")
-- loadvoice(voiceEntry, name)
local entry=VOICES and (VOICES["Gintaras"] or select(2,next(VOICES)))
print("entry type:", type(entry))
local ok,err=pcall(vs.loadvoice, entry, "Gintaras")
print("loadvoice:", ok, err or "OK")
