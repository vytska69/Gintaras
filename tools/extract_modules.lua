-- Usage: LUA_PATH=./src/?.lua luajit extract_modules.lua <main.dec> <outdir>
local infile, outdir = arg[1], arg[2] or "modules"
local realload = load
local order = {}
local function cap(s) order[#order+1] = s; return function() end end
loadstring = cap
load = function(s, ...) if type(s) == "string" then return cap(s) end return realload(s, ...) end
local data = assert(io.open(infile, "rb")):read("*a")
pcall(assert(realload(data, "main")))
local names = {}
for name, fn in pairs(package.preload) do names[#names+1] = name end
os.execute("mkdir -p " .. outdir)
for i, src in ipairs(order) do
  local f = io.open(outdir .. "/module_" .. i .. ".luajit", "wb")
  f:write(src); f:close()
  print(("module #%d  %d bytes  %s"):format(i, #src, src:byte(1)==27 and "bytecode" or "source"))
end
