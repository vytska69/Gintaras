dofile("_lpegload.lua")
local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb");pre[nm]=assert(loadstring(f:read("*a"),nm));f:close() end
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
local db=require("database"); db.VOICES={Gintaras=voice}; db.loaddatabase=function(n) db.VOICES[n]=voice; return db.VOICES end
package.loaded["dictionary"]=setmetatable({dictconv=function(t) return t end,DICT=setmetatable({},{__index=function() return false end}),loaddictionary=function() end,loadphrase=function() end},{__index=function() return function() end end})
local vs=require("voicesynth")
vs.loadvoice("Gintaras",nil,0); pcall(function() vs.loadvoice("Gintaras",nil,8) end)
local function cpmap(w)
  local m={["\xc4\x8d"]=0xe8,["\xc5\xa1"]=0xf0,["\xc5\xbe"]=0xfe,["\xc4\x97"]=0xeb,["\xc4\xaf"]=0xe1,["\xc4\x85"]=0xe0,["\xc4\x99"]=0xe6,["\xc5\xb3"]=0xf8,["\xc5\xab"]=0xfb,["\xc3\xb3"]=0xf3}
  local o={}; local i=1
  while i<=#w do local b=w:byte(i)
    if b<0x80 then o[#o+1]=string.char(b); i=i+1
    else local ch=w:sub(i,i+1); o[#o+1]=string.char(m[ch] or b); i=i+2 end
  end
  return table.concat(o)
end
local function utf16(s) local o={} for i=1,#s do o[#o+1]=s:sub(i,i);o[#o+1]="\0" end return table.concat(o) end
local function synth(word,args)
  local co=coroutine.wrap(vs.speak); local pcm={}
  local a={"Gintaras",utf16(cpmap(word))}; for _,v in ipairs(args) do a[#a+1]=v end
  local r={co(unpack(a))}; local g=0
  while true do g=g+1; if g>200000 then break end local buf,n=r[1],r[2]; if buf==nil then break end
    if type(buf)=="cdata" and n then for i=0,n-1 do pcm[#pcm+1]=buf[i] end end; r={co()} end
  return pcm
end
local function writewav(path,pcm)
  local nb=#pcm*2; local f=io.open(path,"wb")
  local function le32(x) x=x%4294967296; return string.char(x%256,math.floor(x/256)%256,math.floor(x/65536)%256,math.floor(x/16777216)%256) end
  f:write("RIFF"..le32(36+nb).."WAVEfmt "..le32(16)..string.char(1,0,1,0)..le32(22050)..le32(44100)..string.char(2,0,16,0).."data"..le32(nb))
  for _,v in ipairs(pcm) do v=math.floor(v); if v<0 then v=v+65536 end; v=v%65536; f:write(string.char(v%256,math.floor(v/256))) end
  f:close()
end
local words={"mama","namas","labas","saule","lietuva","gintaras","vakaras","sesuo"}
local all={}
for _,w in ipairs(words) do
  local p=synth(w,{100,100,100,100,100,100,100,100})
  print(w..": "..#p.." samples")
  for _,v in ipairs(p) do all[#all+1]=v end
  for i=1,math.floor(0.18*22050) do all[#all+1]=0 end
end
writewav("/tmp/ORIG_ref.wav", all)
print("wrote /tmp/ORIG_ref.wav total "..#all)
