-- Minimal LuaJIT 2.1 .bc parser: extracts per-proto (numparams,numuv,numbc,uvtable).
-- Proto order in the dump is post-order (children before parents).
local fn=arg[1]
local data=io.open(fn,"rb"):read("*a")
local p=1
local function u8() local b=data:byte(p); p=p+1; return b end
local function uleb() local b=u8(); local v=bit.band(b,0x7f); local sh=7
  while b>=0x80 do b=u8(); v=v+bit.lshift(bit.band(b,0x7f),sh); sh=sh+7 end; return v end
-- header: ESC 'L' 'J' ver, flags, [chunkname]
assert(u8()==0x1b and u8()==0x4c and u8()==0x4a, "not a LJ bc")
local ver=u8()
local flags=uleb()
local FLAG_FFI=bit.band(flags,1); local FLAG_STRIP=bit.band(flags,2)
if FLAG_STRIP==0 then local n=uleb(); p=p+n end -- chunkname
print("version="..ver.." flags="..flags.." stripped="..tostring(FLAG_STRIP~=0))
local idx=0
while true do
  local len=uleb()
  if len==0 then break end
  local start=p
  local pflags=u8()
  local numparams=u8()
  local framesize=u8()
  local numuv=u8()
  local numkgc=uleb()
  local numkn=uleb()
  local numbc=uleb()
  local dbglen=0
  if FLAG_STRIP==0 then dbglen=uleb(); if dbglen>0 then uleb(); uleb() end end
  -- bytecode: numbc instructions * 4 bytes (the first instruction is the func header, NOT counted? In LJ, numbc includes it)
  local bcbytes=numbc*4
  local bcstart=p
  p=p+bcbytes
  -- upvalues: numuv * 2 bytes
  local uvs={}
  for i=1,numuv do local lo=u8(); local hi=u8(); uvs[i]=lo+hi*256 end
  -- skip rest (kgc, kn, debug) by jumping to start+len
  p=start+len
  idx=idx+1
  -- decode uv entries
  local parts={}
  for i,uv in ipairs(uvs) do
    local islocal = bit.band(uv,0x8000)~=0
    local immut = bit.band(uv,0x4000)~=0
    local slot = bit.band(uv,0x3fff)
    parts[#parts+1]=string.format("UV%d=%s slot %d%s", i-1, islocal and "parentLOCAL" or "parentUV", slot, immut and " (immut)" or "")
  end
  print(string.format("#%d params=%d uv=%d bc=%d kgc=%d kn=%d :: %s",
    idx, numparams, numuv, numbc, numkgc, numkn, table.concat(parts," | ")))
end
