local dc=dofile("decompile.lua")
local bcuv=dofile("bc_uv.lua")
local jutil=require("jit.util"); local funcinfo=jutil.funcinfo

-- 1) load voicesynth with deterministic deps (reuse run_synth setup minimally)
dofile("_lpegload.lua")
local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb");package.preload[nm]=assert(loadstring(f:read("*a"),nm));f:close() end
LANG="LT"
package.loaded["trans"]=setmetatable({init=function() end},{__index=function() return function(s) return s end end})
package.loaded["dictionary"]=setmetatable({dictconv=function(t) return t end,DICT=setmetatable({},{__index=function() return false end}),loaddictionary=function() end,loadphrase=function() end},{__index=function() return function() end end})

local target = arg[1] or "voicesynth"
local outpath = arg[2] or ("/home/user/Gintaras/engine/decompiled/"..target..".decomp.txt")

local MODNUM={javajni=1,database=2,dictionary=3,md5=4,trans=5,translate=6,voicesynth=7,main=8}
local BCLIST=bcuv("/tmp/mods/mod_"..MODNUM[target]..".bc")
local BYSIG={}
for _,e in ipairs(BCLIST) do local k=e.params..":"..e.numbc..":"..e.numuv; BYSIG[k]=BYSIG[k] or {}; table.insert(BYSIG[k], e) end
local mod = require(target)

-- 2) BFS the live closure graph from the module to map proto->closure (for UV values)
local seen={}; local proto2cl={}
local function visit(v, depth)
  if depth>6 then return end
  local t=type(v)
  if t=="function" then
    if seen[v] then return end; seen[v]=true
    local fi=funcinfo(v); if fi and fi.proto then proto2cl[fi.proto]=proto2cl[fi.proto] or v end
    -- also key by loc string (proto identity fallback)
    if fi and fi.loc then proto2cl["loc:"..fi.loc..":"..tostring(fi.linedefined)]=proto2cl["loc:"..fi.loc..":"..tostring(fi.linedefined)] or v end
    local i=1; while true do local n,uv=debug.getupvalue(v,i); if n==nil then break end; visit(uv, depth+1); i=i+1 end
  elseif t=="table" then
    if seen[v] then return end; seen[v]=true
    for k,val in pairs(v) do visit(val, depth+1) end
  end
end
visit(mod, 0)
if type(mod)=="table" then for k,v in pairs(mod) do visit(v,0) end end

local function uvvals(cl)
  if not cl then return nil end
  local t={}; local i=1
  while true do local n,v=debug.getupvalue(cl,i); if n==nil then break end
    local tv=type(v); local d
    if tv=="function" then local fi=funcinfo(v); d="function@"..tostring(fi and fi.loc).." line"..tostring(fi and fi.linedefined)
    elseif tv=="table" then local c=0; local sample={}; for kk,vv in pairs(v) do c=c+1; if #sample<5 then sample[#sample+1]=tostring(kk).."="..tostring(vv) end end; d="table{#"..c..": "..table.concat(sample,",").."}"
    elseif tv=="cdata" then d="cdata "..tostring(v)
    elseif tv=="string" then d='"'..tostring(v)..'"'
    else d=tv.." "..tostring(v) end
    t[i-1]=d; i=i+1 end
  return t
end

-- 3) walk proto tree of the module chunk and decompile each
local f=io.open("/tmp/mods/mod_"..({javajni=1,database=2,dictionary=3,md5=4,trans=5,translate=6,voicesynth=7,main=8})[target]..".bc","rb")
local chunk=loadstring(f:read("*a"),target); f:close()
local of=io.open(outpath,"w")
local function out(s) of:write(s.."\n") end
local count=0
local function walk(p, id)
  out("\n========================= PROTO "..id.." =========================")
  local fi=funcinfo(p)
  -- count bytecode of this proto
  local nbc=0; for pc=1,1e9 do if not jutil.funcbc(p,pc) then break end nbc=nbc+1 end
  local sig=tostring(fi.params)..":"..nbc..":"..tostring(fi.upvalues)
  local cand=BYSIG[sig]
  local uvtxt={}
  if cand and #cand>=1 then local e=cand[1]; if #cand==1 then BYSIG[sig]=nil else table.remove(cand,1) end
    for i=0,(fi.upvalues or 1)-1 do local b=e.uv[i]
      if b then uvtxt[i]=(b.islocal and "parent.local["..b.slot.."]" or "parent.upvalue["..b.slot.."]")..(b.immut and " (immut)" or "") else uvtxt[i]="?" end end
  end
  dc.decode(p, out, uvtxt)
  count=count+1
  local j=-1
  while j>-512 do local k=jutil.funck(p,j); if type(k)=="proto" then walk(k, id.."."..(-j)) end j=j-1 end
end
walk(chunk, "root")
of:close()
print("decompiled "..count.." protos -> "..outpath)
