import SwiftUI
import GintarasKit

/// Reading settings, stored in the shared App Group so they also drive the system
/// voice extension. Values mirror the Android arrays.xml. Each control writes to
/// the store immediately (no save-on-disappear, which is unreliable).
struct SettingsView: View {
    /// Called after the user dictionary changes, so the preview engine can reload.
    var onDictionaryChanged: (() -> Void)? = nil

    private func intBinding(_ key: String, _ def: Int32) -> Binding<Int> {
        Binding(get: { Int(GintarasSettings.intValue(key, def)) },
                set: { GintarasSettings.set(key, $0) })
    }
    private func boolBinding(_ key: String, _ def: Bool) -> Binding<Bool> {
        Binding(get: { GintarasSettings.boolValue(key, def) },
                set: { GintarasSettings.set(key, $0) })
    }

    var body: some View {
        Form {
            Section("Skyryba") {
                Picker("Tarti skyrybą", selection: intBinding(GintarasSettings.kPunctuation, 1)) {
                    Text("Išjungta").tag(1)
                    Text("Kai kurie").tag(2)
                    Text("Dauguma").tag(0)
                    Text("Visi").tag(3)
                }
            }
            Section("Skaičiai") {
                Picker("Skaitmenų grupavimas", selection: intBinding(GintarasSettings.kNumgroup, 16)) {
                    Text("Po vieną").tag(1)
                    Text("Po du").tag(2)
                    Text("Po tris").tag(3)
                    Text("Numatytas").tag(16)
                }
            }
            Section("Tembras") {
                Picker("Aukštis", selection: intBinding(GintarasSettings.kPitch, 100)) {
                    ForEach([80, 90, 100, 110, 120], id: \.self) { Text("\($0)%").tag($0) }
                }
            }
            Section("Pauzės") {
                Picker("Tarp žodžių", selection: intBinding(GintarasSettings.kPauseWord, 100)) {
                    Text("Trumpa").tag(50); Text("Įprasta").tag(100); Text("Ilga").tag(300)
                }
                Picker("Tarp sakinių", selection: intBinding(GintarasSettings.kPauseSentence, 100)) {
                    Text("Trumpa").tag(50); Text("Įprasta").tag(100); Text("Ilga").tag(150)
                }
            }
            Section("Žodynas") {
                Toggle("Naudoti žodyną", isOn: boolBinding(GintarasSettings.kUseDictionary, true))
                NavigationLink("Tvarkyti žodyną") {
                    DictionaryView(onChange: onDictionaryChanged)
                }
            }
        }
        .navigationTitle("Nustatymai")
    }
}
