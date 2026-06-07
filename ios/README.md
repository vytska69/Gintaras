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
`Gintaras` scheme on a device or simulator.

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
- The same Rust core builds for macOS; a Catalyst/macOS target can be added to
  `project.yml` later.
