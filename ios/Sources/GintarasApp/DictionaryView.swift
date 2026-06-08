import SwiftUI
import GintarasKit

/// User dictionary editor: add/remove custom pronunciations. Entries are stored in
/// the shared App Group and prepended to the built-in stdlit.dct at engine
/// creation, so they read just like the built-in dictionary.
///
/// "Žodis" is the written word; "Tarimas" is how it should be read, spelled
/// phonetically in Lithuanian letters (e.g. word "wifi" → tarimas "vaifai").
struct DictionaryView: View {
    var onChange: (() -> Void)? = nil

    @State private var entries: [GintarasSettings.DictEntry] = GintarasSettings.userDictionary()
    @State private var newWord = ""
    @State private var newRepl = ""

    var body: some View {
        Form {
            Section("Pridėti žodį") {
                TextField("Žodis", text: $newWord)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Tarimas", text: $newRepl)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Pridėti") { add() }
                    .disabled(trimmed(newWord).isEmpty || trimmed(newRepl).isEmpty)
            }
            Section("Žodžiai") {
                if entries.isEmpty {
                    Text("Žodynas tuščias").foregroundStyle(.secondary)
                } else {
                    ForEach(entries.indices, id: \.self) { i in
                        HStack {
                            Text(entries[i].word)
                            Spacer()
                            Text(entries[i].replacement).foregroundStyle(.secondary)
                        }
                    }
                    .onDelete { offsets in
                        entries.remove(atOffsets: offsets)
                        persist()
                    }
                }
            }
        }
        .navigationTitle("Žodynas")
        .toolbar { EditButton() }
    }

    private func trimmed(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func add() {
        let w = trimmed(newWord), r = trimmed(newRepl)
        guard !w.isEmpty, !r.isEmpty else { return }
        entries.removeAll { $0.word.lowercased() == w.lowercased() }
        entries.append((word: w, replacement: r))
        newWord = ""; newRepl = ""
        persist()
    }

    private func persist() {
        GintarasSettings.setUserDictionary(entries)
        onChange?()
    }
}
