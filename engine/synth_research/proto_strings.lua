local bc=io.open("/tmp/mods/mod_7.bc","rb"):read("*a")
local chunk=loadstring(bc,"voicesynth")
local ju=require("jit.util")
-- find the proto matching the 258-line dump by re-walking and printing string consts
local i=-1
while i>-60 do
  local k=ju.funck(chunk,i)
  if type(k)=="proto" then
    -- print its string constants
    local strs={}
    local j=-1
    while j>-80 do
      local kk=ju.funck(k,j)
      if type(kk)=="string" then strs[#strs+1]=kk end
      j=j-1
    end
    if #strs>3 then
      print("proto@"..i.." strings: "..table.concat(strs," | "))
    end
  end
  i=i-1
end
