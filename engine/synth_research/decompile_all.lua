local dc=dofile("decompile.lua")
local bcuv=dofile("bc_uv.lua")
local jutil=require("jit.util"); local funcinfo=jutil.funcinfo
local MODNUM={javajni=1,database=2,dictionary=3,md5=4,trans=5,translate=6,voicesynth=7,main=8}
local function do_module(target)
  local mf="/tmp/mods/mod_"..MODNUM[target]..".bc"
  local BCLIST=bcuv(mf)
  local BYSIG={}
  for _,e in ipairs(BCLIST) do local k=e.params..":"..e.numbc..":"..e.numuv; BYSIG[k]=BYSIG[k] or {}; table.insert(BYSIG[k], e) end
  local chunk=loadstring(io.open(mf,"rb"):read("*a"), target)
  local outpath="/home/user/Gintaras/engine/decompiled/"..target..".decomp.txt"
  local of=io.open(outpath,"w"); local function out(s) of:write(s.."\n") end
  out("== Faithful decompilation of module '"..target.."' (LuaJIT 2.1 bytecode) ==")
  out("Format: per proto, semantic register-machine transliteration. UV bindings")
  out("are resolved structurally from the .bc upvalue tables (parent local/upvalue")
  out("slot). Proto ids: root = module body; root.N = child via FNEW K[-N].")
  local count=0
  local function walk(p,id,parentid)
    out("\n========================= PROTO "..id.." (parent: "..(parentid or "-")..") =========================")
    local fi=funcinfo(p)
    local nbc=0; for pc=1,1e9 do if not jutil.funcbc(p,pc) then break end nbc=nbc+1 end
    local sig=tostring(fi.params)..":"..nbc..":"..tostring(fi.upvalues)
    local cand=BYSIG[sig]; local uvtxt={}
    if cand and #cand>=1 then local e=cand[1]; if #cand==1 then BYSIG[sig]=nil else table.remove(cand,1) end
      for i=0,(fi.upvalues or 1)-1 do local b=e.uv[i]
        if b then uvtxt[i]=(b.islocal and (parentid.."local["..b.slot.."]") or (parentid.."upvalue["..b.slot.."]"))..(b.immut and " immut" or "") else uvtxt[i]="?" end end
    end
    dc.decode(p, out, uvtxt); count=count+1
    local j=-1
    while j>-512 do local k=jutil.funck(p,j); if type(k)=="proto" then walk(k, id.."."..(-j), id) end j=j-1 end
  end
  walk(chunk,"root",nil)
  of:close()
  print(target..": "..count.." protos -> "..outpath)
end
for _,m in ipairs({"voicesynth","translate","trans","database","dictionary","main","javajni","md5"}) do
  local ok,err=pcall(do_module,m); if not ok then print(m..": ERROR "..tostring(err)) end
end
