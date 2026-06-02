dofile("_lpegload.lua")
local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb");pre[nm]=assert(loadstring(f:read("*a"),nm));f:close() end
LANG="LT"
package.loaded["trans"]=setmetatable({init=function() end},{__index=function() return function(s) return s end end})
local ffi=require("ffi")

-- deterministic .dta parse into the voice table shape voicesynth expects
local data=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local pos=1
local function u8() local b=data:byte(pos);pos=pos+1;return b end
local function u16() local lo=data:byte(pos);local hi=data:byte(pos+1);pos=pos+2;return lo+hi*256 end
local function i16() local v=u16();if v>=32768 then v=v-65536 end;return v end
local MAP2={["S\0"]=true,["B\0"]=true,["V\0"]=true,["P\0"]=true,["N\0"]=true,["E\0"]=true,["D\0"]=true}
local voice={}
local nn=#data
while pos<=nn do local b=u8()
  if b==255 then local idx=u16();local cnt=u16()
    local arr=ffi.new("short[?]",cnt); for k=0,cnt-1 do arr[k]=i16() end
    voice[idx]={data=arr,n=cnt}
  else local name=data:sub(pos,pos+b-1);pos=pos+b;local bucket=MAP2[name:sub(1,2)] or false;local cnt=u8()
    if bucket then local e={};for k=1,cnt do local blen=u8();if blen==255 then e[#e+1]=i16() else e[#e+1]=data:sub(pos,pos+blen-1);pos=pos+blen end end;voice[name]=e
    else local e={};for k=1,cnt do local blen=u8();local rec={};if blen==255 then rec.key=u16() else rec.key=data:sub(pos,pos+blen-1);pos=pos+blen end;rec.count=u8()+1;rec.typ=bit.band(u8(),127);e[k]=rec end;voice[name]=e end
  end
end

-- stub database so loadvoice uses OUR deterministic voice (no FFI loaddatabase)
local db=package.loaded["database"] or require("database")
db.VOICES = { Gintaras = voice }
db.loaddatabase = function(name) db.VOICES = db.VOICES or {}; db.VOICES[name]=voice; return db.VOICES end
package.loaded["database"]=db

-- stub dictionary so plain words flow straight to translate (no .dct needed)
package.loaded["dictionary"]=setmetatable({
  dictconv=function(t) return t end,
  DICT=setmetatable({},{__index=function() return false end}),
  loaddictionary=function() end, loadphrase=function() end,
},{__index=function() return function() end end})

local vs=require("voicesynth")
local ok,err=xpcall(function() return vs.loadvoice("Gintaras", nil, 0) end, debug.traceback)
print("loadvoice(mode0):", ok); if not ok then print(err) end

-- load dictionary mode so DICT exists (loaddictionary). Try mode 8 then 2.
pcall(function() vs.loadvoice("Gintaras", nil, 8) end)

-- drive speak as a coroutine, capture yielded (buffer,len)
local function trySpeak(args, label)
  local co=coroutine.wrap(vs.speak)
  local pcm={}
  local ok,err=pcall(function()
    local r={co(unpack(args))}
    local guard=0
    while true do
      guard=guard+1; if guard>100000 then break end
      -- a yield returns buffer + length
      local buf=r[1]; local n=r[2]
      if buf==nil then break end
      if type(buf)=="cdata" and n then for i=0,n-1 do pcm[#pcm+1]=buf[i] end end
      r={co()}
    end
  end)
  print(label.." ok="..tostring(ok).." samples="..#pcm.." err="..tostring(err))
  return pcm,ok
end

-- try a few arg orders
trySpeak({"Gintaras","mama",100}, "A name,text,tempo")
trySpeak({"mama","Gintaras",100}, "B text,name,tempo")

print("--- trying full 10-arg combos ---")
trySpeak({"Gintaras","mama",100,100,100,100,100,100,100,100}, "C all100")
trySpeak({"Gintaras","mama",100,0,0,0,0,0,0,0}, "D zeros")
trySpeak({"Gintaras","mama",1,1,1,1,1,1,1,1}, "E ones")
