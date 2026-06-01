local bc=io.open("/tmp/mods/mod_7.bc","rb"):read("*a")
local chunk=loadstring(bc,"voicesynth")
local bclib=require("jit.bc")
-- jit.bc.dump on chunk dumps only top-level; iterate sub-protos via jit.util
local jutil=require("jit.util")
local function dumpfn(f, name)
  local out=io.open("/tmp/proto_"..name..".dis","w")
  bclib.dump(f, out, true)
  out:close()
end
-- We can reach nested protos via funcinfo's 'children'? Simpler: dump top, then
-- the loadvoice/speak are stored as constants (functions) in the module proto.
-- Use jit.util.funck to read function constants.
local i=-1
while true do
  local k=jutil.funck(chunk,i)
  if k==nil and i<-200 then break end
  if type(k)=="proto" then
    dumpfn(k, tostring(-i))
  end
  i=i-1
  if i<-50 then break end
end
print("done")
