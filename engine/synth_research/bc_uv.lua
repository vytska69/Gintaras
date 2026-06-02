-- returns a function: given modfile, returns list of {params,numuv,numbc,numkgc,numkn, uv={...}} in .bc order
return function(fn)
  local data=io.open(fn,"rb"):read("*a"); local p=1
  local function u8() local b=data:byte(p); p=p+1; return b end
  local function uleb() local b=u8(); local v=bit.band(b,0x7f); local sh=7
    while b>=0x80 do b=u8(); v=v+bit.lshift(bit.band(b,0x7f),sh); sh=sh+7 end; return v end
  assert(u8()==0x1b and u8()==0x4c and u8()==0x4a)
  local ver=u8(); local flags=uleb(); local STRIP=bit.band(flags,2)
  if STRIP==0 then local n=uleb(); p=p+n end
  local list={}
  while true do
    local len=uleb(); if len==0 then break end
    local start=p
    local pflags=u8(); local numparams=u8(); local framesize=u8(); local numuv=u8()
    local numkgc=uleb(); local numkn=uleb(); local numbc=uleb()
    if STRIP==0 then local dl=uleb(); if dl>0 then uleb(); uleb() end end
    p=p+numbc*4
    local uv={}
    for i=1,numuv do local lo=u8(); local hi=u8(); local w=lo+hi*256
      uv[i-1]={islocal=bit.band(w,0x8000)~=0, immut=bit.band(w,0x4000)~=0, slot=bit.band(w,0x3fff)} end
    p=start+len
    list[#list+1]={params=numparams, numuv=numuv, numbc=numbc, numkgc=numkgc, numkn=numkn, uv=uv}
  end
  return list
end
