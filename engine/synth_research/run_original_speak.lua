local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do
  local f=io.open("/tmp/mods/mod_"..i..".bc","rb"); local bc=f:read("*a"); f:close()
  pre[nm]=assert(loadstring(bc,nm))
end
LANG="LT"
package.loaded["trans"]=setmetatable({init=function() end}, {__index=function() return function(s) return s end end})
package.loaded["translate"]=setmetatable({}, {__index=function() return function(s) return s end end})
local ffi=require("ffi")
local data=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local pos=1
local function u8() local b=data:byte(pos); pos=pos+1; return b end
local function u16() local lo=data:byte(pos); local hi=data:byte(pos+1); pos=pos+2; return lo+hi*256 end
local function i16() local v=u16(); if v>=32768 then v=v-65536 end; return v end
local MAP2={["S\0"]=true,["B\0"]=true,["V\0"]=true,["P\0"]=true,["N\0"]=true,["E\0"]=true,["D\0"]=true}
local voice={}
local n=#data
while pos<=n do
  local b=u8()
  if b==255 then local idx=u16(); local cnt=u16()
    local arr=ffi.new("short[?]",cnt); for k=0,cnt-1 do arr[k]=i16() end
    voice[idx]={data=arr,n=cnt}
  else
    local name=data:sub(pos,pos+b-1); pos=pos+b
    local bucket=MAP2[name:sub(1,2)] or false
    local cnt=u8()
    if bucket then
      local e={}
      for k=1,cnt do local blen=u8()
        if blen==255 then e[k]=i16() else e[k]=data:sub(pos,pos+blen-1); pos=pos+blen end end
      voice[name]=e
    else
      local e={}
      for k=1,cnt do local blen=u8(); local rec={}
        if blen==255 then rec.key=u16() else rec.key=data:sub(pos,pos+blen-1); pos=pos+blen end
        rec.count=u8()+1; rec.typ=bit.band(u8(),127); e[k]=rec end
      voice[name]=e
    end
  end
end
-- inspect P0..P8 values
for d=0,8 do local p=voice["P\0"..string.char(48+d).."\0"]
  io.write("P"..d.."={") if p then for i,v in ipairs(p) do io.write(tostring(v)..",") end end print("}") end
local VOICES={Gintaras=voice}
package.loaded["database"]={VOICES=VOICES, loaddatabase=function() return VOICES end}
local vs=require("voicesynth")
local ok,err=xpcall(function() vs.loadvoice("Gintaras", 0) end, debug.traceback)
print("loadvoice:", ok); if not ok then print(err) end
