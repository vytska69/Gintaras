local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb"); pre[nm]=assert(loadstring(f:read("*a"),nm)); f:close() end
LANG="LT"
package.loaded["trans"]=setmetatable({init=function() end},{__index=function() return function(s) return s end end})
local ffi=require("ffi")
local data=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local pos=1
local function u8() local b=data:byte(pos);pos=pos+1;return b end
local function u16() local lo=data:byte(pos);local hi=data:byte(pos+1);pos=pos+2;return lo+hi*256 end
local function i16() local v=u16();if v>=32768 then v=v-65536 end;return v end
local MAP2={["S\0"]=true,["B\0"]=true,["V\0"]=true,["P\0"]=true,["N\0"]=true,["E\0"]=true,["D\0"]=true}
local voice={}
local nn=#data
while pos<=nn do local b=u8()
  if b==255 then local idx=u16();local cnt=u16();local arr=ffi.new("short[?]",cnt);for k=0,cnt-1 do arr[k]=i16() end;voice[idx]={data=arr,n=cnt}
  else local name=data:sub(pos,pos+b-1);pos=pos+b;local bucket=MAP2[name:sub(1,2)] or false;local cnt=u8()
    if bucket then local e={};for k=1,cnt do local blen=u8();if blen==255 then e[#e+1]=i16() else e[#e+1]=data:sub(pos,pos+blen-1);pos=pos+blen end end;voice[name]=e
    else local e={};for k=1,cnt do local blen=u8();local rec={};if blen==255 then rec.key=u16() else rec.key=data:sub(pos,pos+blen-1);pos=pos+blen end;rec.count=u8()+1;rec.typ=bit.band(u8(),127);e[k]=rec end;voice[name]=e end
  end
end
local tr=require("translate")
local function u16cp(s) local o={} for i=1,#s do o[#o+1]=s:sub(i,i);o[#o+1]="\0" end return table.concat(o) end
-- ASCII test words to derive the rule (no special chars to avoid encoding noise)
for _,w in ipairs({"as","sa","at","ta","aba","kava","namas","ratas","tata","sata","aaa","ksa","tra"}) do
  local seq=tr.translate(u16cp(w), nil, voice)
  io.write(w..": ")
  for _,k in ipairs(seq) do io.write(k:gsub(".",function(c) local b=c:byte() return (b>=32 and b<127) and string.char(b) or "?" end).." ") end
  print()
end
