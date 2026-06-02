-- Instrument the original speak() and log every yielded PCM block (size in samples).
-- Offline: voiced PCM is silent (ffi.copy no-op) but root.50 silence blocks DO reach output.
dofile("/home/user/Gintaras/engine/synth_research/_lpegload.lua")
local mods={"javajni","database","dictionary","md5","trans","translate","voicesynth","main"}
local pre=package.preload
for i,nm in ipairs(mods) do local f=io.open("/tmp/mods/mod_"..i..".bc","rb");pre[nm]=assert(loadstring(f:read("*a"),nm));f:close() end
LANG="LT"
package.loaded["trans"]=setmetatable({init=function() end},{__index=function() return function(s) return s end end})
-- use the REAL translate module (returns the unit table speak indexes); do not stub.
local ffi=require("ffi")

-- deterministic .dta parse
local data=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local pos=1
local function u8() local b=data:byte(pos);pos=pos+1;return b end
local function u16() local lo=data:byte(pos);local hi=data:byte(pos+1);pos=pos+2;return lo+hi*256 end
local function i16() local v=u16();if v>=32768 then v=v-65536 end;return v end
local MAP2={["S\0"]=true,["B\0"]=true,["V\0"]=true,["P\0"]=true,["N\0"]=true,["E\0"]=true,["D\0"]=true}
local voice={}
local nn=#data
while pos<=nn do local b=u8()
  if b==255 then local idx=u16();local cnt=u16()
    local arr=ffi.new("short[?]",cnt); for k=0,cnt-1 do arr[k]=i16() end
    voice[idx]={data=arr,n=cnt}
  else local name=data:sub(pos,pos+b-1);pos=pos+b;local bucket=MAP2[name:sub(1,2)] or false;local cnt=u8()
    if bucket then local e={};for k=1,cnt do local blen=u8();if blen==255 then e[#e+1]=i16() else e[#e+1]=data:sub(pos,pos+blen-1);pos=pos+blen end end;voice[name]=e
    else local e={};for k=1,cnt do local blen=u8();local rec={};if blen==255 then rec.key=u16() else rec.key=data:sub(pos,pos+blen-1);pos=pos+blen end;rec.count=u8()+1;rec.typ=bit.band(u8(),127);e[k]=rec end;voice[name]=e end
  end
end
local VOICES={Gintaras=voice}
local db=package.loaded["database"] or require("database")
db.VOICES=VOICES
db.loaddatabase=function(name) db.VOICES=db.VOICES or {}; db.VOICES[name]=voice; return db.VOICES end
package.loaded["database"]=db
package.loaded["dictionary"]=setmetatable({
  dictconv=function(t) return t end,
  DICT=setmetatable({},{__index=function() return false end}),
  loaddictionary=function() end, loadphrase=function() end,
},{__index=function() return function() end end})

-- count translate() invocations = number of voiced units speak processes
local realtr=require("translate")
local trcount=0
local trmt=getmetatable(realtr)
if type(realtr)=="table" and rawget(realtr,"translate") then
  local orig=realtr.translate
  realtr.translate=function(...) trcount=trcount+1; io.stderr:write("  translate('"..tostring((...)).."')\n"); return orig(...) end
end

local vs=require("voicesynth")
vs.loadvoice("Gintaras", nil, 0)

-- speak arg order (from main.root.14): (name/voice, R7?, rate, pitch, numgroup, punc, R6, pause_word, pause_sentence, use_dict)
-- We pass tempo-neutral 100 for rate/pitch and pause_word=pause_sentence=100 (the "100%" multipliers).
local function run(label, text, pw, ps)
  local seq={}
  local co=coroutine.create(function()
    vs.speak("Gintaras", text, 100, 100, 0, 0, 0, pw, ps, false)
  end)
  local guard=0
  while true do
    guard=guard+1; if guard>200000 then break end
    local r={coroutine.resume(co)}
    local ok=r[1]
    if not ok then seq.err=r[2]; break end
    -- yields are (buffer, n) where n=#samples; capture n (3rd value)
    local buf=r[2]; local n=r[3]
    if coroutine.status(co)=="dead" and buf==nil then break end
    if n then seq[#seq+1]=n end
    if coroutine.status(co)=="dead" then break end
  end
  io.write(string.format("[%s] text=%q pw=%s ps=%s blocks(samples)=", label, text, tostring(pw), tostring(ps)))
  print(table.concat(seq, ","), seq.err and ("ERR:"..tostring(seq.err)) or "")
end

run("single_word",  "labas",   100, 100)
run("word_period",  "labas.",  100, 100)
run("word_comma",   "labas,",  100, 100)
run("word_quest",   "labas?",  100, 100)
-- vary pause_word (R7) and pause_sentence (R8) independently to identify which branch scales
run("period_pw0_ps200","labas.", 0,   200)
run("comma_pw200_ps0", "labas,", 200, 0)
run("plain_pw200",     "labas",  200, 0)
run("plain_pw0",       "labas",  0,   0)
