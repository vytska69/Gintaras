local ffi=require("ffi")
local data=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local pos=1
local function u8() local b=data:byte(pos); pos=pos+1; return b end
local function u16() local lo=data:byte(pos); local hi=data:byte(pos+1); pos=pos+2; return lo+hi*256 end
local function i16() local v=u16(); if v>=32768 then v=v-65536 end; return v end
local MAP2={["S\0"]=true,["B\0"]=true,["V\0"]=true,["P\0"]=true,["N\0"]=true,["E\0"]=true,["D\0"]=true}
local entries={}
local n=#data
while pos<=n do
  local b=u8()
  if b==255 then local idx=u16(); local cnt=u16(); pos=pos+2*cnt
  else
    local name=data:sub(pos,pos+b-1); pos=pos+b
    local bucket=MAP2[name:sub(1,2)] or false
    local cnt=u8(); local recs={}
    for k=1,cnt do
      local blen=u8(); local rec={}
      if blen==255 then rec.key=u16(); rec.numeric=true
      else rec.key=data:sub(pos,pos+blen-1); pos=pos+blen; rec.numeric=false end
      if not bucket then rec.count=u8()+1; rec.typ=bit.band(u8(),127) end
      recs[k]=rec
    end
    entries[#entries+1]={name=name,bucket=bucket,records=recs}
  end
end
-- decode entry names as UTF-16LE
local function u16name(s)
  local out={}
  for i=1,#s,2 do
    local lo=s:byte(i); local hi=s:byte(i+1) or 0
    out[#out+1]=string.char(lo)  -- low byte is the phoneme char (cp1257)
  end
  return table.concat(out)
end
print("=== diphone dictionary entries (name decoded) ===")
for i=1,30 do
  local e=entries[i]
  if e then
    print(string.format("[%d] name='%s' (hex %s) recs=%d", i,
      u16name(e.name):gsub("[^%g ]","."), e.name:gsub(".",function(c) return string.format("%02x",c:byte()) end):sub(1,16), #e.records))
  end
end
-- collect all low-byte chars used in entry names
local chars={}
for _,e in ipairs(entries) do
  for i=1,#e.name,2 do chars[e.name:byte(i)]=(chars[e.name:byte(i)] or 0)+1 end
end
print("=== phoneme chars in diphone names (cp1257 low byte: count) ===")
local ks={} for c in pairs(chars) do ks[#ks+1]=c end table.sort(ks)
for _,c in ipairs(ks) do
  io.write(string.format("%s(%d) ", (c>=32 and c<127) and string.char(c) or ("\\x"..string.format("%02x",c)), chars[c]))
end
print()
print("total entries:", #entries)
-- count entries that are pure phoneme diphones (contain '-' = 0x2d) vs prosody (contain digits/+)
local diph,pros=0,0
for _,e in ipairs(entries) do
  local nm=e.name
  if nm:find("\43") or nm:match("%d") then pros=pros+1 else diph=diph+1 end
end
print("phoneme-diphone entries (approx):", diph, " prosody/other:", pros)
