local data=io.open("/home/user/Gintaras/app/src/main/assets/Gintaras.dta","rb"):read("*a")
local pos=1
local function u8() local b=data:byte(pos); pos=pos+1; return b end
local function u16() local lo=data:byte(pos); local hi=data:byte(pos+1); pos=pos+2; return lo+hi*256 end
local MAP2={["S\0"]=true,["B\0"]=true,["V\0"]=true,["P\0"]=true,["N\0"]=true,["E\0"]=true,["D\0"]=true}
local names={}
local n=#data
while pos<=n do
  local b=u8()
  if b==255 then local idx=u16(); local cnt=u16(); pos=pos+2*cnt
  else
    local name=data:sub(pos,pos+b-1); pos=pos+b
    local bucket=MAP2[name:sub(1,2)] or false
    local cnt=u8()
    for k=1,cnt do local blen=u8(); if blen==255 then pos=pos+2 else pos=pos+blen end; if not bucket then pos=pos+2 end end
    names[name]=true
  end
end
-- check which 'tX' and 'Xr' units exist (X=a variants)
local function dec(s) local t={} for i=1,#s,2 do t[#t+1]=string.format(s:byte(i)>=32 and s:byte(i)<127 and "%c" or "[%02x]", s:byte(i)) end return table.concat(t) end
local function exists(chars)  -- chars = array of cp1257 byte
  local s={} for _,c in ipairs(chars) do s[#s+1]=string.char(c); s[#s+1]="\0" end
  return names[table.concat(s)]
end
print("ta (short a 0x61):", exists({0x74,0x61}))
print("t-á (long 0xe1):", exists({0x74,0xe1}))
print("ar (short a):", exists({0x61,0x72}))
print("á-r (long):", exists({0xe1,0x72}))
print("in:", exists({0x69,0x6e}))
print("nt:", exists({0x6e,0x74}))
print("as:", exists({0x61,0x73}))
print("ás:", exists({0xe1,0x73}))
-- list ALL units starting with 't'
io.write("all 't_' units: ")
for nm in pairs(names) do if nm:byte(1)==0x74 and nm:byte(2)==0 and #nm==4 then io.write("t"..dec(nm:sub(3)).." ") end end
print()
print("=== ALL units containing 't' (any length) ===")
local cnt=0
for nm in pairs(names) do
  if nm:find(string.char(0x74)..string.char(0)) and cnt<40 then
    io.write("'"..dec(nm).."' ")
    cnt=cnt+1
  end
end
print()
print("=== labas path: which exist? ===")
local function ex(str)  -- str is plain ascii, convert to utf16le
  local s={} for i=1,#str do s[#s+1]=str:sub(i,i); s[#s+1]="\0" end
  return names[table.concat(s)] and "Y" or "n"
end
print("-la:",ex("-la"),"  ab:",ex("ab"),"  ba:",ex("ba"),"  as:",ex("as"),"  la:",ex("la"))
print("-l:",ex("-l")," al:",ex("al")," -a:",ex("-a")," a-:",ex("a-"))
-- maybe units are -CV and VC- only (demisyllables). labas = -la + a? no.
-- Actually maybe: -la (onset+vowel), then ab? Let me list ALL 'a_' and '_a'
io.write("'a' units (a + one char, no boundary): ")
local c=0
for nm in pairs(names) do
  if nm:byte(1)==0x61 and nm:byte(2)==0 and #nm==4 and c<30 then io.write("'"..dec(nm).."' "); c=c+1 end
end
print()
