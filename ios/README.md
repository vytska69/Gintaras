# Gintaras for iOS / macOS

A full iOS port of the Gintaras Lithuanian text‑to‑speech engine. It reuses the
**shared Rust core** (`../core`, `gintaras-core`) — a byte‑exact reimplementation
of the original WinTalker engine — and exposes it on iOS in two ways:

1. **Host app** (`Gintaras`) — a SwiftUI app to preview the voice and configure
   reading settings (punctuation, number grouping, pitch/Tembras, pauses,
   dictionary). Settings are stored in a shared **App Group** so they also drive
   the system voice.
2. **System voice** (`GintarasVoice`) — an `AVSpeechSynthesisProviderAudioUnit`
   *Speech Synthesis Provider* extension (iOS 16+). Once installed and enabled,
   "Gintaras" appears as a Lithuanian voice everywhere: VoiceOver, Spoken
   Content, and any app that uses `AVSpeechSynthesizer`.

```
ios/
├── build-rust.sh                 # builds the Rust core → GintarasCoreFFI.xcframework
├── project.yml                   # XcodeGen project (app + extension + shared kit)
├── App/                          # host-app Info.plist + entitlements (App Group)
├── Voice/                        # extension Info.plist (NSExtension/ssyn) + entitlements
├── Resources/                    # voice data (Gintaras.dta) + dictionaries
└── Sources/
    ├── GintarasKit/              # shared Swift wrapper over the Rust C ABI
    │   ├── GintarasEngine.swift  #   load assets, synthesize → PCM / AVAudioPCMBuffer
    │   └── Settings.swift        #   App-Group-backed settings, param mapping
    ├── GintarasApp/              # host app (SwiftUI: preview + settings)
    └── GintarasVoice/            # the system Speech Synthesis Provider
        └── GintarasSpeechSynthesizer.swift
```

The voice data (`Gintaras.dta`) and dictionaries (`ruleslit.rul`, `stdlit.dct`,
`spelllit.dct`, `punc0-3lit.dct`) live in `Resources/` and are bundled into both
targets — the validated original data, identical to the byte‑exact reference.

## Build (requires macOS + Xcode + Rust + XcodeGen)

```sh
# 1. Install tools (once)
brew install xcodegen
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios

# 2. Build the shared Rust core as an xcframework
cd ios
./build-rust.sh            # → ios/GintarasCoreFFI.xcframework

# 3. Generate and open the Xcode project
xcodegen generate
open Gintaras.xcodeproj
```

In Xcode, set your Development Team on all three targets (the App Group
`group.com.rosasoft.wintalker` requires a provisioning profile), then run the
`Gintaras` scheme on a device or simulator. The app also runs on **Mac
Catalyst** (`SUPPORTS_MACCATALYST`), sharing the same code and the same Rust core.

> Mac Catalyst uses the Rust `*-apple-ios-macabi` targets, which are tier‑3 and
> have no prebuilt std, so `build-rust.sh` builds them with the **nightly**
> toolchain's `build-std`. Set `CATALYST=0 ./build-rust.sh` to skip Catalyst
> (iOS device + simulator only).

## CI: unsigned `.ipa` + `.pkg` (no Apple account, no secrets)

`.github/workflows/apple-build.yml` builds, on a macOS runner with **no Apple
Developer account and no repository secrets**:

- **`ios-ipa`** — an **unsigned iOS `.ipa`**. Builds only the iOS device slice of
  the Rust core (`IPHONEOS_ONLY=1`, so no simulator/Catalyst → no nightly),
  archives with `CODE_SIGNING_ALLOWED=NO`, zips `Payload/Gintaras.app` into
  `Gintaras-unsigned.ipa`.
- **`mac-pkg`** — an **ad-hoc signed Mac Catalyst `.pkg`**. Builds the Catalyst
  slice (`MACCATALYST_ONLY=1`, nightly `build-std`), archives unsigned, then
  ad-hoc signs the app + framework + extension (`codesign --sign -`, required so
  it runs on Apple Silicon) and wraps it with `productbuild`.

Both run on every push to `Ios` (touching `ios/` or `core/`) and on
`workflow_dispatch`, and upload their result as a build artifact.

### Installing the `.ipa`

An unsigned `.ipa` **cannot be installed directly** — re-sign it with your own
identity:

- **Sideloadly** or **AltStore** — drop in the `.ipa`, sign with your Apple ID
  (free account works; re-sign every 7 days), install over USB/Wi-Fi. This also
  re-signs the bundled `GintarasVoice` extension and applies the App Group.
- **`codesign`/`xcrun`** locally with your own cert + provisioning profile.

### Installing the `.pkg`

It is ad-hoc signed, **not notarized**, so Gatekeeper will warn:

```sh
sudo installer -pkg Gintaras-unsigned.pkg -target /
# or: right-click the .pkg → Open → Open anyway
```

> Caveat: ad-hoc signing can't grant the **App Group** / system-voice
> entitlements (those need a real Apple Team + provisioning). So the Mac app
> launches and you can preview the voice, but the *system* Speech Synthesis
> Provider and cross-process settings sharing need proper signing. A signed App
> Store `.ipa`/`.pkg` pipeline can be added later once a Developer account is in
> place.

## Enabling the system voice

After installing the app on a device:

**Settings → Accessibility → Spoken Content → Voices → Lithuanian → Gintaras**

(also selectable in VoiceOver and any app that lists system voices). Changing
reading options in the host app updates the system voice immediately, via the
shared App Group.

## Notes

- The engine is the shared `gintaras-core` (Rust); there is no platform‑specific
  DSP. PCM output is byte‑identical to the validated Android/Java reference.
- The extension renders offline (`internalRenderBlock`) at the engine's native
  22050 Hz mono, resampling to the host's requested format via `AVAudioConverter`.
- `DEVELOPMENT_TEAM`, code‑signing and framework embedding for the extension are
  the usual Xcode concerns — `project.yml` embeds `GintarasKit` once in the host
  app and resolves it from the extension at runtime via `@executable_path`.
- macOS ships as **Mac Catalyst** (App Store `.pkg`) from the same targets; a
  fully native AppKit target could be added later if desired.
