local jutil=require("jit.util"); local funcinfo=jutil.funcinfo
local MODNUM={javajni=1,database=2,dictionary=3,md5=4,trans=5,translate=6,voicesynth=7,main=8}
local of=io.open("/home/user/Gintaras/engine/decompiled/MANIFEST.txt","w")
of:write("PROTO MANIFEST — every function across the original engine modules.\n")
of:write("id = proto path; params/uv/bc = signature; strings = string constants (role hint).\n\n")
local function strs(p) local t={} local j=-1 while j>-120 do local k=jutil.funck(p,j) if type(k)=="string" and #k>0 and #k<26 then t[#t+1]=k end j=j-1 end return table.concat(t,",") end
for _,target in ipairs({"voicesynth","translate","trans","database","dictionary","main","javajni","md5"}) do
  of:write("\n##### MODULE "..target.." #####\n")
  local chunk=loadstring(io.open("/tmp/mods/mod_"..MODNUM[target]..".bc","rb"):read("*a"),target)
  local function walk(p,id) local fi=funcinfo(p)
    local nbc=0; for pc=1,1e9 do if not jutil.funcbc(p,pc) then break end nbc=nbc+1 end
    of:write(string.format("%-14s params=%d uv=%-2d bc=%-4d :: %s\n", id, fi.params, fi.upvalues, nbc, strs(p):sub(1,140)))
    local j=-1 while j>-512 do local k=jutil.funck(p,j) if type(k)=="proto" then walk(k,id.."."..(-j)) end j=j-1 end end
  walk(chunk,"root")
end
of:close(); print("manifest written")
