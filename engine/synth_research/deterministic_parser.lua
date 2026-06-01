local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do
  local f=io.open("/tmp/mods/mod_"..i..".bc","rb"); local bc=f:read("*a"); f:close()
  pre[nm]=assert(loadstring(bc,nm))
end
LANG="LT"
package.loaded["trans"]=setmetatable({init=function() end}, {__index=function() return function(s) return s end end})
package.loaded["translate"]=setmetatable({}, {__index=function() return function(s) return s end end})

-- Deterministic .dta parser (port of our verified Java VoiceDatabase)
local ffi=require("ffi")
local data=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local pos=1
local function u8() local b=data:byte(pos); pos=pos+1; return b end
local function u16() local lo=data:byte(pos); local hi=data:byte(pos+1); pos=pos+2; return lo+hi*256 end
local function i16() local v=u16(); if v>=32768 then v=v-65536 end; return v end
local MAP2={["S\0"]=true,["B\0"]=true,["V\0"]=true,["P\0"]=true,["N\0"]=true,["E\0"]=true,["D\0"]=true}
local blocks={}  -- idx -> array of int16
local entries={} -- list of {name=, bucket=, records={}}
local n=#data
while pos<=n do
  local b=u8()
  if b==255 then
    local idx=u16(); local cnt=u16()
    local arr={}
    for k=1,cnt do arr[k]=i16() end
    blocks[idx]=arr
  else
    local name=data:sub(pos,pos+b-1); pos=pos+b
    local bucket = MAP2[name:sub(1,2)] or false
    local cnt=u8()
    local recs={}
    for k=1,cnt do
      local blen=u8()
      local rec={}
      if blen==255 then rec.key=u16(); rec.numeric=true
      else rec.key=data:sub(pos,pos+blen-1); pos=pos+blen; rec.numeric=false end
      if not bucket then rec.count=u8()+1; rec.typ=bit.band(u8(),127) end
      recs[k]=rec
    end
    entries[#entries+1]={name=name,bucket=bucket,records=recs}
  end
end
print("parsed: blocks="..(function() local c=0 for _ in pairs(blocks) do c=c+1 end return c end)().." entries="..#entries)

-- Build the voice entry loadvoice wants: the first non-bucket entry is the voice
-- (name "Gintaras..."). Find it.
local voice
for _,e in ipairs(entries) do
  if e.name:find("intaras") then voice=e; break end
end
print("voice entry found:", voice~=nil, voice and #voice.records or 0, "records")

-- Show entries with many records (the voice has ~160) and their names
print("=== entries with >=100 records ===")
for i,e in ipairs(entries) do
  if #e.records>=100 then
    print(string.format("  entry[%d] name=%q (%d bytes) records=%d bucket=%s",
      i, e.name:gsub("%z","\\0"):sub(1,20), #e.name, #e.records, tostring(e.bucket)))
  end
end

print("=== record-count distribution ===")
local hist={}
for _,e in ipairs(entries) do
  local b=#e.records
  hist[b]=(hist[b] or 0)+1
end
local maxrec=0
for _,e in ipairs(entries) do if #e.records>maxrec then maxrec=#e.records end end
print("max records in any entry:", maxrec)
print("=== first 10 entry names + record counts ===")
for i=1,10 do
  local e=entries[i]
  local nm=e.name:gsub(".", function(c) return string.format("%02x",c:byte()) end)
  print(string.format("  [%d] hex=%s records=%d", i, nm:sub(1,24), #e.records))
end
print("=== how the engine's VOICES looked: 1 key, 160 records of {key,count,typ} ===")
print("=== our parse: 1221 entries. The engine AGGREGATES them under one voice. ===")
