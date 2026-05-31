# WinTalker / Gintaras lietuviškas TTS — APK analizė

Šaltinis: `WinTalker.Voice.apk` (com.rosasoft.wintalker), 2015, ~4 MB.
Tikslas: naujas Android app + bibliotekos esamam varikliui, **arm ir arm64**.

## 1. APK turinys

| Failas | Dydis | Architektūra | Paskirtis |
|---|---|---|---|
| `lib/armeabi/librosasofttts.so` | 34 KB | ARM32 | JNI tiltas + LuaJIT bootstrap + LPeg |
| `lib/armeabi/libtranscr.so` | 2.6 MB | ARM32 | Lietuviška lingvistinė transkripcija |
| `lib/armeabi/libluajit.so` | 400 KB | ARM32 | LuaJIT 2.0 (atviras kodas) |
| `assets/main.bin` | 52 KB | — | LuaJIT bytecode, **užšifruotas XOR 0xAF** |
| `assets/Gintaras.dta` | 3.6 MB | — | Balso duomenų bazė (difonai/garsas) |
| `assets/*.dct,*.rul` | — | — | Lietuviški žodynai ir taisyklės |
| `classes.dex` | — | — | Java: TtsService, CheckVoiceData, SpeakSettings, ModifyDictionary, GetSampleText |

**Tik `armeabi` (32-bit ARM)** — jokio arm64. App turi 64-bit pakeisti, kad veiktų naujausiuose Android.

## 2. Vykdymo architektūra

```
Android TextToSpeech framework
        │  onSynthesizeText(text)
        ▼
com.rosasoft.wintalker.TtsService (Java)
  • System.loadLibrary("luajit"); System.loadLibrary("rosasofttts")
  • initLua(): skaito main.bin, DEŠIFRUOJA (XOR 0xAF), perduoda InitJNI
  • native InitJNI / LoadJNI / RunJNI / DeInitJNI
        │  JNI
        ▼
librosasofttts.so  (34 KB, tik LPeg + JNI klijai)
  • luaL_newstate + luaL_openlibs + luaopen_lpeg
  • InitJNI: luaL_loadbuffer(main.bin dešifruotas) → LuaJIT bytecode
  • LoadJNI/RunJNI: kviečia Lua funkcijas per pcall
        │  paleidžia
        ▼
main.bin  (LuaJIT 2.0 bytecode, XOR 0xAF) — VISA logika čia:
  • LPeg taisyklės teksto apdorojimui
  • LuaJIT FFI → libtranscr.so (transkripcija, kirčiavimas)
  • voicesynth/loadvoice/loaddatabase — GRYNAS Lua, skaito Gintaras.dta,
    difonų konkatenacinė sintezė → PCM
        │  FFI
        ▼
libtranscr.so  (PROPRIETARI — vienintelis arm64 barjeras)
```

## 3. main.bin šifravimas (IŠSPRĘSTA)

`main.bin[i] = luajit_bytecode[i] XOR 0xAF`. Simetrinis. Žr. `tools/decrypt_mainbin.py`.
Patvirtinta: dešifruotas pradžia `1B 4C 4A 02` = `\x1bLJ` + versija 2 (LuaJIT 2.0, stripped).
Dešifravimas vyksta Java pusėje → pernešamas į bet kurią architektūrą.

## 4. libtranscr.so ABI (atkurta iš FFI cdef — `engine/reconstructed/libtranscr.h`)

```c
void init_transcr(void);
void PradApdZod (const char*, char*, int, char);            // žodžio paruošimas
void SpellZod   (const char*, char*, int, char);            // raidžiavimas
int  KircTranskr (const char*, char*, int, char);           // kirčiuota transkripcija
int  KircTranskr1(const char*, char*, int, int, char);
int  ilgiai (const char*, char*, int, int, char);           // garsų ilgiai
int  tonai  (const char*, char*, int, int, char);           // tonai (pitch)
int  tonai1 (const char*, char*, int, int, double,double,double, int, char);
```

### libtranscr.so vidinė sandara (svarbu arm64 portavimui)
| Sekcija | Dydis | Turinys |
|---|---|---|
| `.text` | **~47 KB** | Visas algoritmas (~12000 ARM32 instrukcijų) |
| `.rodata` | ~677 KB | Lentelės (string'ai, taisyklės) |
| `.data.rel.ro` | ~1.0 MB | Rodyklių lentelės (lksikonas) — **32-bit rodyklės** |
| `.data` | ~40 KB | — |
| `.bss` | ~290 KB | Darbinė atmintis |

Išorinės priklausomybės (tik standartinės): `libc/libm/libdl/liblog` —
`memcpy, str*, sprintf, sscanf, fopen/fclose/fscanf, cos/exp/log, toupper`.

**Išvada:** Kodas tik ~47 KB; likę ~1.7 MB — architektūrai nepriklausomi
lingvistiniai duomenys. arm64 portas = pernešti 47 KB kodą + atkurti lentelių
struktūras (data.rel.ro 32-bit rodyklės → 64-bit). Realu, bet rimtas RE darbas.

## 5. Portavimo į arm64 planas (sluoksniais)

| Komponentas | arm64 strategija | Statusas |
|---|---|---|
| libluajit.so | Perkompiliuoti LuaJIT iš šaltinio (clang aarch64) | Pernešama ✓ |
| librosasofttts.so | Atkurti nuo nulio: LPeg (atviras) + JNI klijai | Pernešama ✓ |
| main.bin | Dešifruoti → dekompiliuoti → perkompiliuoti arm64 LuaJIT | Pernešama ✓ |
| Sintezė (voicesynth) | Grynas Lua main.bin viduje | Pernešama ✓ |
| Gintaras.dta + žodynai | Duomenys, naudojami kaip yra | Pernešama ✓ |
| **libtranscr.so** | RE: 47 KB kodo → C + lentelių atkūrimas → arm64 | **Ilgoji dalis** |
| App (TtsService ir kt.) | Naujas modernus Android projektas nuo nulio | Kuriama |

## 6. Aplinkos apribojimai

- `dl.google.com` / `maven.google.com` BLOKUOTI (nėra Android SDK/NDK).
- Veikia: github, Maven Central, PyPI, Ubuntu archyvai.
- Turima: clang 18 (aarch64+arm taikiniai), capstone 5.0.7, cmake, gradle, java 21.
- `.so` galima cross-kompiliuoti su clang (Android simboliai paliekami undefined,
  juos išsprendžia įrenginio dinaminis linkeris).
- Galutinį APK vartotojas surenka Android Studio (parsisiunčia SDK/NDK lokaliai).
