import SwiftUI
import GintarasKit

/// Reading settings, stored in the shared App Group so they also drive the system
/// voice extension. Values mirror the Android arrays.xml.
struct SettingsView: View {
    @State private var punctuation: Int
    @State private var numgroup: Int
    @State private var pauseWord: Int
    @State private var pauseSentence: Int
    @State private var pitch: Int
    @State private var useDictionary: Bool

    init() {
        _punctuation = State(initialValue: Int(GintarasSettings.intValue(GintarasSettings.kPunctuation, 1)))
        _numgroup = State(initialValue: Int(GintarasSettings.intValue(GintarasSettings.kNumgroup, 16)))
        _pauseWord = State(initialValue: Int(GintarasSettings.intValue(GintarasSettings.kPauseWord, 100)))
        _pauseSentence = State(initialValue: Int(GintarasSettings.intValue(GintarasSettings.kPauseSentence, 100)))
        _pitch = State(initialValue: Int(GintarasSettings.intValue(GintarasSettings.kPitch, 100)))
        _useDictionary = State(initialValue: GintarasSettings.boolValue(GintarasSettings.kUseDictionary, true))
    }

    var body: some View {
        Form {
            Section("Skyryba") {
                Picker("Tarti skyrybą", selection: $punctuation) {
                    Text("Išjungta").tag(1)
                    Text("Kai kurie").tag(2)
                    Text("Dauguma").tag(0)
                    Text("Visi").tag(3)
                }
            }
            Section("Skaičiai") {
                Picker("Skaitmenų grupavimas", selection: $numgroup) {
                    Text("Po vieną").tag(1)
                    Text("Po du").tag(2)
                    Text("Po tris").tag(3)
                    Text("Numatytas").tag(16)
                }
            }
            Section("Tembras") {
                Picker("Aukštis", selection: $pitch) {
                    ForEach([80, 90, 100, 110, 120], id: \.self) { Text("\($0)%").tag($0) }
                }
            }
            Section("Pauzės") {
                Picker("Tarp žodžių", selection: $pauseWord) {
                    Text("Trumpa").tag(50); Text("Įprasta").tag(100); Text("Ilga").tag(300)
                }
                Picker("Tarp sakinių", selection: $pauseSentence) {
                    Text("Trumpa").tag(50); Text("Įprasta").tag(100); Text("Ilga").tag(150)
                }
            }
            Section("Žodynas") {
                Toggle("Naudoti žodyną", isOn: $useDictionary)
            }
        }
        .navigationTitle("Nustatymai")
        .onDisappear(perform: save)
    }

    private func save() {
        GintarasSettings.set(GintarasSettings.kPunctuation, punctuation)
        GintarasSettings.set(GintarasSettings.kNumgroup, numgroup)
        GintarasSettings.set(GintarasSettings.kPauseWord, pauseWord)
        GintarasSettings.set(GintarasSettings.kPauseSentence, pauseSentence)
        GintarasSettings.set(GintarasSettings.kPitch, pitch)
        GintarasSettings.set(GintarasSettings.kUseDictionary, useDictionary)
    }
}
