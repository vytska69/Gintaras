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
local voice={}; local blocks={}
local nn=#data
while pos<=nn do local b=u8()
  if b==255 then local idx=u16();local cnt=u16();local arr={};for k=1,cnt do arr[k]=i16() end;blocks[idx]=arr
  else local name=data:sub(pos,pos+b-1);pos=pos+b;local bucket=MAP2[name:sub(1,2)] or false;local cnt=u8()
    if bucket then local e={};for k=1,cnt do local blen=u8();if blen==255 then e[#e+1]=i16() else e[#e+1]=data:sub(pos,pos+blen-1);pos=pos+blen end end;voice[name]=e
    else local e={};for k=1,cnt do local blen=u8();local rec={};if blen==255 then rec.key=u16() else rec.key=data:sub(pos,pos+blen-1);pos=pos+blen end;rec.count=u8()+1;rec.typ=bit.band(u8(),127);e[k]=rec end;voice[name]=e end
  end
end
local tr=require("translate")
local function utf16(s) local o={} for i=1,#s do o[#o+1]=s:sub(i,i);o[#o+1]="\0" end return table.concat(o) end
local function synthWord(w)
  local seq=tr.translate(utf16(w), nil, voice)
  local samples={}
  for _,key in ipairs(seq) do
    local e=voice[key]
    if e and type(e)=="table" then
      for _,rec in ipairs(e) do
        if type(rec)=="table" and type(rec.key)=="number" and blocks[rec.key] then
          for _,s in ipairs(blocks[rec.key]) do samples[#samples+1]=s end
        end
      end
    end
  end
  return samples
end
local pcm={}; local gap=math.floor(0.25*22050)
for _,w in ipairs({"labas","lietuva","gintaras","saule"}) do
  local s=synthWord(w); print(w..": "..#s.." samples")
  for _,v in ipairs(s) do pcm[#pcm+1]=v end
  for i=1,gap do pcm[#pcm+1]=0 end
end
-- fade ends
local function clampw(path)
  local nb=#pcm*2
  local f=io.open(path,"wb")
  local function le32(x) return string.char(x%256,math.floor(x/256)%256,math.floor(x/65536)%256,math.floor(x/16777216)%256) end
  f:write("RIFF"..le32(36+nb).."WAVEfmt "..le32(16)..string.char(1,0,1,0)..le32(22050)..le32(22050*2)..string.char(2,0,16,0).."data"..le32(nb))
  for _,v in ipairs(pcm) do local x=v;if x<0 then x=x+65536 end;f:write(string.char(x%256,math.floor(x/256)%256)) end
  f:close()
end
clampw("/tmp/real_seq.wav")
print("WAV written, total "..#pcm.." samples")
