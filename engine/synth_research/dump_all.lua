local ju=require("jit.util")
local bc=require("jit.bc")
local f=io.open("/tmp/mods/mod_7.bc","rb");local data=f:read("*a");f:close()
local chunk=loadstring(data,"voicesynth")
local seen={}
local out=io.open("/tmp/vs_full.dis","w")
local function dumpproto(p,id)
  if seen[p] then return end; seen[p]=true
  out:write("\n========== PROTO "..id.." ==========\n")
  local info=ju.funcinfo(p)
  out:write("params="..tostring(info.params).." stack="..tostring(info.stackslots)
    .." upvals="..tostring(info.upvalues).." nbc="..tostring(info.nbc).."\n")
  out:write("-- string/proto consts:\n")
  local j=-1
  while j>-220 do local k=ju.funck(p,j)
    if k~=nil then
      if type(k)=="proto" then out:write(("  k[%d]=<proto %s.%d>\n"):format(j,id,-j))
      elseif type(k)=="string" then out:write(("  k[%d]=%q\n"):format(j,k))
      elseif type(k)=="table" then local s="";for kk,vv in pairs(k)do s=s..tostring(kk).."="..tostring(vv).." "end;out:write(("  k[%d]=tab{%s}\n"):format(j,s))
      else out:write(("  k[%d]=%s\n"):format(j,tostring(k))) end
    end
    j=j-1
  end
  out:write("-- num consts:\n")
  local jn=0
  while jn<60 do local ok,k=pcall(ju.funck,p,jn); if not ok or k==nil then break end; out:write(("  num[%d]=%s\n"):format(jn,tostring(k))); jn=jn+1 end
  out:write("-- bytecode:\n")
  bc.dump(p,out,false)
end
local function walk(p,id)
  dumpproto(p,id)
  local j=-1
  while j>-220 do local k=ju.funck(p,j); if type(k)=="proto" then walk(k,id.."."..(-j)) end; j=j-1 end
end
walk(chunk,"root")
out:close()
print("done")
