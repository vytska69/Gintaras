local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb"); pre[nm]=assert(loadstring(f:read("*a"),nm)); f:close() end
LANG="LT"
local ok,ffi=pcall(require,"ffi")
local data=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local pos=1
local function u8() local b=data:byte(pos);pos=pos+1;return b end
local function u16() local lo=data:byte(pos);local hi=data:byte(pos+1);pos=pos+2;return lo+hi*256 end
local function i16() local v=u16();if v>=32768 then v=v-65536 end;return v end
local MAP2={["S\0"]=true,["B\0"]=true,["V\0"]=true,["P\0"]=true,["N\0"]=true,["E\0"]=true,["D\0"]=true}
local voice={}; local blocks={}
local nn=#data
while pos<=nn do local b=u8()
  if b==255 then local idx=u16();local cnt=u16();local arr={};for k=1,cnt do arr[k]=i16() end;blocks[idx]=arr
  else local name=data:sub(pos,pos+b-1);pos=pos+b;local bucket=MAP2[name:sub(1,2)] or false;local cnt=u8()
    if bucket then local e={};for k=1,cnt do local blen=u8();if blen==255 then e[#e+1]=i16() else e[#e+1]=data:sub(pos,pos+blen-1);pos=pos+blen end end;voice[name]=e
    else local e={};for k=1,cnt do local blen=u8();local rec={};if blen==255 then rec.key=u16() else rec.key=data:sub(pos,pos+blen-1);pos=pos+blen end;rec.count=u8()+1;rec.typ=bit.band(u8(),127);e[k]=rec end;voice[name]=e end
  end
end
package.cpath="/home/user/Gintaras/engine/synth_research/?.so;"..package.cpath
cp_lpeg=package.loadlib("/home/user/Gintaras/engine/synth_research/lpeg32.so","luaopen_lpeg")
if cp_lpeg then package.preload["lpeg"]=cp_lpeg end
local tr=require("translate")
-- words as UTF-8 bytes? translate likely expects UTF-16LE of cp1257? Try cp1257 bytes.
local CP={["š"]=0xf0,["ž"]=0xfe,["č"]=0xe8,["ė"]=0xeb,["į"]=0xe1,["ą"]=0xe0,["ę"]=0xe6,["ų"]=0xf8,["ū"]=0xfb}
-- helper: convert a utf8 lithuanian word to cp1257 byte string
local function tocp(w)
  local o={}
  local i=1
  while i<=#w do
    local b=w:byte(i)
    if b<0x80 then o[#o+1]=string.char(b); i=i+1
    else
      -- 2-byte utf8
      local ch=w:sub(i,i+1)
      local map={["č"]=0xe8,["š"]=0xf0,["ž"]=0xfe,["ė"]=0xeb,["į"]=0xe1,["ą"]=0xe0,["ę"]=0xe6,["ų"]=0xf8,["ū"]=0xfb,["ó"]=0xf3}
      o[#o+1]=string.char(map[ch] or b); i=i+2
    end
  end
  return table.concat(o)
end
local function utf16(s) local o={} for i=1,#s do o[#o+1]=s:sub(i,i);o[#o+1]="\0" end return table.concat(o) end
local function hexseq(seq)
  local parts={}
  for _,key in ipairs(seq) do
    local h={}
    for i=1,#key,2 do local lo=key:byte(i) or 0; local hi=key:byte(i+1) or 0; h[#h+1]=string.format("%04X",lo+hi*256) end
    parts[#parts+1]=table.concat(h,".")
  end
  return table.concat(parts," ")
end
for _,w in ipairs({"šuo","žmona","čia","šeši","žalias","ąžuolas","saulė","džiaugsmas"}) do
  local cp=tocp(w)
  local ok2,seq=pcall(tr.translate, utf16(cp), nil, voice)
  if ok2 and type(seq)=="table" then
    print(w.."  ->  "..hexseq(seq))
  else
    print(w.."  -> ERROR "..tostring(seq))
  end
end
