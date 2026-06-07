import SwiftUI
import GintarasKit

final class PreviewModel: ObservableObject {
    private let engine = GintarasEngine(bundle: .main)
    private let player = SpeechPlayer()

    @Published var text = "Labas! Čia lietuviškas Gintaro balsas."
    @Published var rate: Double = 1.0
    @Published var pitch: Double = 1.0
    @Published var ready = false

    init() { ready = engine != nil }

    func speak() {
        guard let engine = engine else { return }
        let p = GintarasSettings.params(rate: Int32(rate * 100), pitch: Int32(pitch * 100))
        if let buf = engine.synthesizeBuffer(text, params: p, format: player.format) {
            player.play(buf)
        }
    }

    func stop() { player.stop() }
}

struct ContentView: View {
    @StateObject private var model = PreviewModel()

    var body: some View {
        NavigationStack {
            Form {
                Section("Tekstas") {
                    TextEditor(text: $model.text)
                        .frame(minHeight: 90)
                }
                Section("Greitis: \(String(format: "%.1f×", model.rate))") {
                    Slider(value: $model.rate, in: 0.5...2.0, step: 0.1)
                }
                Section("Tembras: \(String(format: "%.1f×", model.pitch))") {
                    Slider(value: $model.pitch, in: 0.5...2.0, step: 0.1)
                }
                Section {
                    HStack {
                        Button("Skaityti") { model.speak() }
                            .buttonStyle(.borderedProminent)
                            .disabled(!model.ready)
                        Spacer()
                        Button("Stop") { model.stop() }
                            .buttonStyle(.bordered)
                    }
                    NavigationLink("Nustatymai") { SettingsView() }
                }
                if !model.ready {
                    Section {
                        Text("Balso duomenys nerasti (Gintaras.dta).")
                            .foregroundStyle(.red)
                    }
                }
                Section {
                    Text("Sisteminis balsas: įjunk „Gintaras" Nustatymai → Pritaikymas neįgaliesiems → Įgarsinamasis turinys → Balsai.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Gintaras TTS")
        }
    }
}
