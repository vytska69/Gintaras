# Removing the license gate (open-source build)

The original WinTalker engine is time- and IMEI-limited. The check lives in the
Lua, not in Java, so removing it requires patching `main.bin`.

## Where it lives

`main.bin` is a **LuaJIT 2.1 bytecode bundle**, XOR-0xAF encrypted. Its top chunk
`package.preload`s eight sub-modules (`javajni`, `database`, `dictionary`, `md5`,
`trans`, `translate`, `voicesynth`, `main`) then does `require("main").start(...)`.

The `main` module reads the Java fields `imei`, `number`, `act`, `uptime`,
`expiry` and computes a *licensed* boolean:

```
licensed = md5(imei .. <salt> .. "16ac"):sub(1,8) == act       -- and a SIM variant
```

When **not** licensed the synthesis path speaks the `expiry` field
("Prašome suaktyvinti licenciją") instead of the user's text — i.e. the engine
goes silent for real input once the trial/IMEI check fails. This is the most
likely reason the original APK "stopped working".

## The patch

In the `main` module the computed flag is copied into the synthesis call's
argument tuple by a single bytecode instruction:

```
0506   MOV  55, 48        ; r55 = computed "licensed" flag
```

We replace it with a constant-true load:

```
0506   KPRI 55, 2         ; r55 = true  (always licensed)
```

so synthesis always proceeds with the real text. At the byte level (after XOR
decryption) this is exactly two bytes inside a **unique** 4-byte pattern:

```
12 37 30 00   (MOV  55,48)   ->   2b 37 02 00   (KPRI 55,2)
```

`tools/delicense_mainbin.py` performs decrypt → patch → re-encrypt. The pattern
occurs exactly once in the whole bundle, so the patch is unambiguous and
reversible.

## Verification done

* Re-encrypted bundle still loads in LuaJIT 2.1; all 8 sub-modules bundle.
* Only the two intended bytes differ from the original (offsets 2681, 2683 of
  the decrypted stream).
* Disassembly confirms `0506` is now `KPRI 55, 2`.

The shipped `app/src/main/assets/main.bin` is the delicensed build; the original
is kept as `engine/extracted/assets/main.bin.orig-licensed`. Final confirmation
(audible speech) requires an on-device run, which can't be done in this
network-restricted cloud environment.

The Java layer was independently stripped of all IMEI/SIM/activation collection
(no `READ_PHONE_STATE`), so no device identifiers are gathered at all.
