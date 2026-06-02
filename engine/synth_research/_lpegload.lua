local f=package.loadlib("/home/user/Gintaras/engine/synth_research/lpeg32.so","luaopen_lpeg")
if f then package.preload["lpeg"]=f end
