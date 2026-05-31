# Gintaras — lietuviškas Android TTS

Naujas, modernus Android Text-To-Speech aplikacijos projektas lietuviškam
**Gintaras** balsui, sukurtas aplink `WinTalker.Voice.apk` (2015) ištrauktą
sintezės variklį.

## Statusas

| Tikslas | Būsena |
|---|---|
| Variklio failų ištraukimas iš APK | ✅ Atlikta |
| `main.bin` iššifravimas (LuaJIT bytecode, XOR 0xAF) | ✅ Atlikta |
| Architektūros reverse-engineering | ✅ Atlikta (`docs/ANALYSIS.md`) |
| Naujas app nuo nulio (Gradle, Java, manifest, resursai) | ✅ Atlikta |
| **arm (armeabi-v7a)** — naujas app + esamas variklis | ✅ Surenkamas (žr. žemiau) |
| **arm64-v8a** — variklio portas | 🚧 Vykdoma (žr. `docs/ANALYSIS.md` §5) |

## Projekto struktūra

```
app/                         Android aplikacijos modulis (naujas, nuo nulio)
  src/main/java/...          TtsService, CheckVoiceData, GetSampleText,
                             SpeakSettings, ModifyDictionary
  src/main/assets/           main.bin, Gintaras.dta, žodynai, taisyklės
  src/main/jniLibs/
    armeabi-v7a/             Variklio .so (libluajit, librosasofttts, libtranscr)
    arm64-v8a/               (tuščia — portas vykdomas)
engine/extracted/            Originalūs failai iš APK (atskaitos taškas)
engine/reconstructed/        Atkurta: libtranscr.h (FFI ABI)
docs/ANALYSIS.md             Pilna variklio analizė
tools/decrypt_mainbin.py     main.bin <-> LuaJIT bytecode (XOR 0xAF)
```

## Kaip surinkti APK (arm / armeabi-v7a)

Reikia Android SDK + (pasirinktinai) NDK. Lengviausia per Android Studio:

```bash
# komandinėje eilutėje (su įdiegtu SDK, ANDROID_HOME nustatytu):
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Įdiegus įrenginyje, sistemos *Nustatymai → Kalbos įvestis → Tekstas į kalbą*
pasirinkite „WinTalker Voice".

> Pastaba: šios aplinkos tinklo politika blokuoja `dl.google.com`/`maven.google.com`,
> todėl APK surinkti čia (debesyje) negalima — SDK/NDK parsisiunčiami lokaliai.

## arm64 portas

Vienintelė proprietari arm64 kliūtis — `libtranscr.so` (lietuvių lingvistinė
transkripcija). Jos kodas tik ~47 KB; likę ~1.7 MB — architektūrai nepriklausomos
lentelės. Likę komponentai (LuaJIT, LPeg/librosasofttts, sintezės Lua) pernešami.
Detalus planas: `docs/ANALYSIS.md` §5.

## Kilmė ir licencija

Variklio binarai ir balso duomenys priklauso originaliam `WinTalker.Voice.apk`
(com.rosasoft.wintalker). Šis projektas modernizuoja aplikacijos sluoksnį;
variklio nuosavybės teisės priklauso jų autoriams.
