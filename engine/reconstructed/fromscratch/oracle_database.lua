local f=io.open("/tmp/mods/mod_2.bc","rb"); local bc=f:read("*a"); f:close()
local mod=assert(loadstring(bc,"database"))()
local data=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local V = mod.loaddatabase(data, "Gintaras")
local function count(t) local n=0 for _ in pairs(t) do n=n+1 end return n end
local vname, entry = next(V)
-- dump first 6 records fully
for i=1,6 do
  local r=entry[i]
  if r then
    local key=r.key
    local kd = type(key)=="cdata" and ("cdata:"..tostring(key)) or (type(key)=="string" and ("str("..#key..")") or tostring(key))
    print(string.format("rec[%d]: key=%s count=%s typ=%s", i, kd, tostring(r.count), tostring(r.typ)))
  end
end
-- what is 'key' really? check a record that looked like sample data
print("---- examine record value types across all 42 ----")
local keytypes={}
for i=1,count(entry) do
  local r=entry[i]; if r and r.key~=nil then keytypes[type(r.key)]=(keytypes[type(r.key)] or 0)+1 end
end
for k,v in pairs(keytypes) do print("keytype",k,"x",v) end
