import Foundation

/// Reading settings shared between the app and the Speech Synthesis Provider
/// extension via an App Group (so changing them in the app affects the system
/// voice). Mirrors the Android SharedPreferences keys/values.
public enum GintarasSettings {

    /// App Group identifier — must match both target entitlements (see project.yml).
    public static let appGroup = "group.com.rosasoft.wintalker"

    private static var store: UserDefaults {
        UserDefaults(suiteName: appGroup) ?? .standard
    }

    // Keys
    public static let kPunctuation = "punctuation"
    public static let kNumgroup = "numgroup"
    public static let kUseDictionary = "use_dictionary"
    public static let kPauseWord = "pause_word"
    public static let kPauseSentence = "pause_sentence"
    public static let kPitch = "pitch" // app "Tembras" (multiplies the request pitch)
    public static let kUserDict = "user_dict" // [[word, replacement], …]

    private static func int(_ key: String, _ def: Int32) -> Int32 {
        store.object(forKey: key) == nil ? def : Int32(store.integer(forKey: key))
    }
    private static func bool(_ key: String, _ def: Bool) -> Bool {
        store.object(forKey: key) == nil ? def : store.bool(forKey: key)
    }

    public static func set(_ key: String, _ value: Int) { store.set(value, forKey: key) }
    public static func set(_ key: String, _ value: Bool) { store.set(value, forKey: key) }
    public static func intValue(_ key: String, _ def: Int32) -> Int32 { int(key, def) }
    public static func boolValue(_ key: String, _ def: Bool) -> Bool { bool(key, def) }

    // MARK: - User dictionary (custom pronunciations)

    public typealias DictEntry = (word: String, replacement: String)

    public static func userDictionary() -> [DictEntry] {
        guard let arr = store.array(forKey: kUserDict) as? [[String]] else { return [] }
        return arr.compactMap { $0.count == 2 ? (word: $0[0], replacement: $0[1]) : nil }
    }

    public static func setUserDictionary(_ entries: [DictEntry]) {
        store.set(entries.map { [$0.word, $0.replacement] }, forKey: kUserDict)
    }

    /// User entries rendered in the built-in dictionary's text format
    /// ("word replacement", one per line). Prepended to `stdlit.dct` so user
    /// pronunciations win over the built-ins (longest-/first-stem wins).
    public static func userDictionaryDct() -> String {
        userDictionary()
            .map { (w, r) in (w.split(separator: " ").first.map(String.init) ?? w,
                              r.trimmingCharacters(in: .whitespacesAndNewlines)) }
            .filter { !$0.0.isEmpty && !$0.1.isEmpty }
            .map { "\($0.0.lowercased()) \($0.1)" }
            .joined(separator: "\n")
    }

    /// Engine params from the stored settings, combined with the per-request
    /// `rate`/`pitch` the system passes (both percentages, 100 = normal).
    public static func params(rate: Int32 = 100, pitch: Int32 = 100) -> GintarasEngine.Params {
        var p = GintarasEngine.Params()
        p.rate = rate
        // request pitch * app "Tembras" / 100
        p.pitch = pitch * int(kPitch, 100) / 100
        p.punctuationLevel = int(kPunctuation, 1)
        p.numgroup = int(kNumgroup, 16)
        p.useDictionary = bool(kUseDictionary, true)
        p.pauseWord = int(kPauseWord, 100)
        p.pauseSentence = int(kPauseSentence, 100)
        return p
    }
}
