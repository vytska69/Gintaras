-- Faithful LuaJIT 2.1 bytecode -> readable register-machine pseudocode.
-- Operand extraction mirrors jit/bc.lua exactly; each opcode is transliterated to
-- a Lua-equivalent statement. Output is lossless and directly re-implementable.
local jutil=require("jit.util"); local vmdef=require("jit.vmdef")
local bit=require("bit"); local band,shr=bit.band,bit.rshift
local funcbc,funck,funcinfo=jutil.funcbc,jutil.funck,jutil.funcinfo
local bcnames=vmdef.bcnames
local function ctl(s) return (s:gsub("%c", function(c) return string.format("\\%03d", c:byte()) end)) end
local PRI={[0]="nil",[1]="false",[2]="true"}

local function kc_at(func,d) -- gc constant (string/proto/table/cdata)
  local k=funck(func,-d-1)
  if type(k)=="string" then return string.format('"%s"',ctl(k))
  elseif type(k)=="proto" then return "proto@"..(funcinfo(k).loc or "?")
  elseif type(k)=="table" then return "{tmpl}"
  else return tostring(k) end
end

-- semantic templates keyed by opcode name
local function emit(func, op, a,b,c,d, sd, pc)
  local Ra,Rb,Rc,Rd="R"..a,"R"..b,"R"..c,"R"..d
  local cmp={ISLT="<",ISGE=">=",ISLE="<=",ISGT=">",ISEQV="==",ISNEV="~=",ISEQS="==",ISNES="~=",ISEQN="==",ISNEN="~=",ISEQP="==",ISNEP="~="}
  if cmp[op] then
    local rhs = (op=="ISEQS" or op=="ISNES" or op=="ISEQN" or op=="ISNEN" or op=="ISEQP" or op=="ISNEP") and sd or Rd
    return string.format("if %s %s %s then -> (next JMP)", Ra, cmp[op], rhs)
  end
  if op=="IST"  then return "if "..Rd.." then -> (next JMP)" end
  if op=="ISF"  then return "if not "..Rd.." then -> (next JMP)" end
  if op=="ISTC" then return Ra.." = "..Rd.."; if "..Rd.." then -> (next JMP)" end
  if op=="ISFC" then return Ra.." = "..Rd.."; if not "..Rd.." then -> (next JMP)" end
  if op=="ISTYPE" then return "assert type("..Ra..") == "..d end
  if op=="ISNUM"  then return "assert isnum("..Ra..")" end
  if op=="MOV" then return Ra.." = "..Rd end
  if op=="NOT" then return Ra.." = not "..Rd end
  if op=="UNM" then return Ra.." = -"..Rd end
  if op=="LEN" then return Ra.." = #"..Rd end
  local vn={ADDVN="+",SUBVN="-",MULVN="*",DIVVN="/",MODVN="%"}
  if vn[op] then return string.format("%s = %s %s %s", Ra, Rb, vn[op], sd) end -- sd=KNUM[c]
  local nv={ADDNV="+",SUBNV="-",MULNV="*",DIVNV="/",MODNV="%"}
  if nv[op] then return string.format("%s = %s %s %s", Ra, sd, nv[op], Rb) end
  local vv={ADDVV="+",SUBVV="-",MULVV="*",DIVVV="/",MODVV="%"}
  if vv[op] then return string.format("%s = %s %s %s", Ra, Rb, vv[op], Rc) end
  if op=="POW" then return string.format("%s = %s ^ %s", Ra, Rb, Rc) end
  if op=="CAT" then return string.format("%s = concat(%s..%s)", Ra, Rb, Rc) end
  if op=="KSTR" then return Ra.." = "..sd end
  if op=="KCDATA" then return Ra.." = "..sd.." (cdata)" end
  if op=="KSHORT" then return Ra.." = "..sd end
  if op=="KNUM" then return Ra.." = "..sd end
  if op=="KPRI" then return Ra.." = "..(PRI[d] or sd) end
  if op=="KNIL" then return "R"..a..".."..Rd.." = nil" end
  if op=="UGET" then return Ra.." = UV"..d end
  if op=="USETV" then return "UV"..a.." = "..Rd end
  if op=="USETS" then return "UV"..a.." = "..sd end
  if op=="USETN" then return "UV"..a.." = "..sd end
  if op=="USETP" then return "UV"..a.." = "..(PRI[d] or sd) end
  if op=="UCLO" then return "close upvals >= R"..a.."; goto "..sd end
  if op=="FNEW" then return Ra.." = closure("..sd..")" end
  if op=="TNEW" then return Ra.." = {} (new, hint="..d..")" end
  if op=="TDUP" then return Ra.." = copy("..sd..")" end
  if op=="GGET" then return Ra.." = _G["..sd.."]" end
  if op=="GSET" then return "_G["..sd.."] = "..Ra end
  if op=="TGETV" then return Ra.." = "..Rb.."["..Rc.."]" end
  if op=="TGETS" then return Ra.." = "..Rb.."["..sd.."]" end
  if op=="TGETB" then return Ra.." = "..Rb.."["..c.."]" end
  if op=="TGETR" then return Ra.." = "..Rb.."["..Rc.."] (arr)" end
  if op=="TSETV" then return Rb.."["..Rc.."] = "..Ra end
  if op=="TSETS" then return Rb.."["..sd.."] = "..Ra end
  if op=="TSETB" then return Rb.."["..c.."] = "..Ra end
  if op=="TSETR" then return Rb.."["..Rc.."] = "..Ra.." (arr)" end
  if op=="TSETM" then return "setlist "..Rb.."[base="..sd.."...] = R"..(a)..".." end
  if op=="CALL" then  -- A=base B=nres+1 C=nargs+1
    local nres,nargs=b-1,c-1
    local args={}; for i=1,nargs do args[#args+1]="R"..(a+i) end
    local call="R"..a.."("..table.concat(args,",")..")"
    if nres==0 then return call.."  (0 results)"
    elseif nres<0 then return "R"..a..".. = "..call.." (multi)"
    else local res={}; for i=0,nres-1 do res[#res+1]="R"..(a+i) end; return table.concat(res,",").." = "..call end
  end
  if op=="CALLM" then local nargs=c; local args={}; for i=1,nargs do args[#args+1]="R"..(a+i) end; return "R"..a..".. = R"..a.."("..table.concat(args,",")..", ...multi)" end
  if op=="CALLT" then return "return R"..a.."(R"..(a+1).."..R"..(a+c-1)..")  (tailcall)" end
  if op=="CALLMT" then return "return R"..a.."(...multi)  (tailcall)" end
  if op=="ITERC" then return "R"..a..",.. = (for-iter) R"..(a-3).."(R"..(a-2)..",R"..(a-1)..")  [nres="..(b-1).."]" end
  if op=="ITERN" then return "R"..a..",.. = next(R"..(a-3)..",R"..(a-1)..")" end
  if op=="ITERL" then return "iter-loop -> "..sd end
  if op=="ISNEXT" then return "verify next-loop -> "..sd end
  if op=="VARG" then return "R"..a..".. = ...(varargs)" end
  if op=="RET0" then return "return" end
  if op=="RET1" then return "return "..Ra end
  if op=="RET"  then local n=d-2; local r={}; for i=0,n-1 do r[#r+1]="R"..(a+i) end; return "return "..table.concat(r,",") end
  if op=="RETM" then return "return R"..a..".. (multi)" end
  if op=="FORI" or op=="JFORI" then return "for "..Ra.."=R"..a.."(start),R"..(a+1).."(stop),R"..(a+2).."(step); idx R"..(a+3).."; if out-of-range goto "..sd end
  if op=="FORL" or op=="IFORL" or op=="JFORL" then return "R"..(a+3).." += R"..(a+2).."(step); if in-range goto "..sd end
  if op=="LOOP" or op=="ILOOP" or op=="JLOOP" then return "loop-hint -> "..sd end
  if op=="JMP" then return "goto "..sd end
  if op:sub(1,4)=="FUNC" then return "func-header" end
  return op.." a="..a.." b="..b.." c="..c.." d="..tostring(sd)
end

local function decode(func, out, uvc)
  local fi=funcinfo(func)
  out(string.format("-- params=%s framesize=%s upvalues=%s nconsts(gc=%s num=%s)",
    tostring(fi.params), tostring(fi.stackslots), tostring(fi.upvalues), tostring(fi.nupvalues), tostring(fi.nconsts)))
  local j=-1
  while j>-512 do local k=funck(func,j); if k==nil and j<-300 then break end
    if k~=nil then
      if type(k)=="string" then out(string.format("  K[%d]=%q", j, ctl(k)))
      elseif type(k)=="proto" then out(string.format("  K[%d]=proto@%s", j, tostring(funcinfo(k).loc)))
      elseif type(k)=="table" then local s={}; for tk,tv in pairs(k) do s[#s+1]=tostring(tk).."="..tostring(tv) end; out(string.format("  K[%d]=template{%s}", j, table.concat(s,",")))
      else out(string.format("  K[%d]=%s", j, tostring(k))) end
    end
    j=j-1
  end
  local jn=0
  while true do local ok,k=pcall(funck,func,jn); if not ok or k==nil then break end; out(string.format("  KNUM[%d]=%s", jn, tostring(k))); jn=jn+1; if jn>120 then break end end
  if uvc then for i=0,(fi.upvalues or 1)-1 do out(string.format("  UV%d -> %s", i, uvc[i] or "?")) end end
  out("-- code:")
  for pc=1,1e9 do
    local ins,m=funcbc(func,pc); if not ins then break end
    local o=band(ins,0xff); local op=bcnames:sub(o*6+1,o*6+6):gsub(" ","")
    local a=band(shr(ins,8),0xff)
    local c=band(shr(ins,16),0xff)
    local b=band(shr(ins,24),0xff)
    local d=shr(ins,16)
    local mb=band(m,15*8); local mc=band(m,15*128)
    if mb~=0 then d=band(d,0xff) end
    local sd
    if mc==13*128 then sd=string.format("%04d", pc+shr(ins,16)-0x7fff)
    elseif mc==10*128 then sd=kc_at(func,d)
    elseif mc==12*128 then sd=kc_at(func,d)
    elseif mc==11*128 then sd=kc_at(func,d)
    elseif mc==9*128 then sd=tostring(funck(func,d))
    elseif mc==8*128 then sd=PRI[d]
    elseif mc==7*128 then local v=d; if v>32767 then v=v-65536 end; sd=tostring(v)
    elseif mc==5*128 then sd="UV"..d
    else sd=tostring(d) end
    out(string.format("%04d  %s", pc, emit(func, op, a,b,c,d, sd, pc)))
  end
  out("")
end
return {decode=decode}
