import Foundation
import AVFoundation
import GintarasCoreFFI

/// Swift wrapper around the shared Rust core (`gintaras-core`, C ABI in
/// `core/include/gintaras.h`). Loads the voice data + dictionaries from a bundle
/// and synthesizes Lithuanian text to PCM. Used by both the in-app preview and the
/// system Speech Synthesis Provider extension.
public final class GintarasEngine {

    /// Reading parameters (mirror the Android settings).
    public struct Params {
        public var rate: Int32 = 100
        public var pitch: Int32 = 100
        public var punctuationLevel: Int32 = 1
        public var numgroup: Int32 = 16
        public var useDictionary: Bool = true
        public var pauseWord: Int32 = 100
        public var pauseSentence: Int32 = 100
        public init() {}
    }

    private let handle: OpaquePointer
    /// Native output sample rate (22050 Hz).
    public let sampleRate: Double

    /// Create the engine from the bundled assets (`Gintaras.dta` + the .dct/.rul
    /// dictionaries). Returns nil if the voice data is missing.
    public init?(bundle: Bundle) {
        func load(_ name: String, _ ext: String) -> Data? {
            bundle.url(forResource: name, withExtension: ext).flatMap { try? Data(contentsOf: $0) }
        }
        guard let dta = load("Gintaras", "dta") else { return nil }
        let rules = load("ruleslit", "rul")
        // Prepend the user dictionary (App Group) to the built-in stdlit.dct so
        // custom pronunciations apply just like the built-ins.
        var std = load("stdlit", "dct")
        let userDct = GintarasSettings.userDictionaryDct()
        if !userDct.isEmpty {
            var combined = Data((userDct + "\n").utf8)
            combined.append(std ?? Data())
            std = combined
        }
        let spell = load("spelllit", "dct")
        let p0 = load("punc0lit", "dct"), p1 = load("punc1lit", "dct")
        let p2 = load("punc2lit", "dct"), p3 = load("punc3lit", "dct")

        // Copy each optional buffer to a stable allocation for the create call
        // (the core copies them internally; we free right after).
        func dup(_ d: Data?) -> (UnsafeMutablePointer<UInt8>?, Int) {
            guard let d = d, !d.isEmpty else { return (nil, 0) }
            let p = UnsafeMutablePointer<UInt8>.allocate(capacity: d.count)
            d.copyBytes(to: p, count: d.count)
            return (p, d.count)
        }
        let bDta = dup(dta), bRules = dup(rules), bStd = dup(std), bSpell = dup(spell)
        let bP0 = dup(p0), bP1 = dup(p1), bP2 = dup(p2), bP3 = dup(p3)
        defer {
            for b in [bDta, bRules, bStd, bSpell, bP0, bP1, bP2, bP3] { b.0?.deallocate() }
        }

        guard let h = gintaras_engine_create(
            bDta.0, bDta.1, bRules.0, bRules.1, bStd.0, bStd.1, bSpell.0, bSpell.1,
            bP0.0, bP0.1, bP1.0, bP1.1, bP2.0, bP2.1, bP3.0, bP3.1
        ) else { return nil }
        handle = h
        sampleRate = Double(gintaras_sample_rate(h))
    }

    deinit { gintaras_engine_destroy(handle) }

    /// Synthesize `text` into 16-bit mono PCM at `sampleRate`.
    public func synthesizePCM(_ text: String, params: Params) -> [Int16] {
        var p = GintarasParams(
            rate: params.rate, pitch: params.pitch,
            punctuation_level: params.punctuationLevel, numgroup: params.numgroup,
            use_dictionary: params.useDictionary ? 1 : 0,
            pause_word: params.pauseWord, pause_sentence: params.pauseSentence)
        let bytes = Array(text.utf8)
        var outLen = 0
        let ptr = bytes.withUnsafeBufferPointer { tb in
            withUnsafePointer(to: &p) { pp in
                gintaras_synthesize(handle, tb.baseAddress, tb.count, pp, &outLen)
            }
        }
        guard let ptr = ptr, outLen > 0 else { return [] }
        let result = Array(UnsafeBufferPointer(start: ptr, count: outLen))
        gintaras_free_pcm(ptr, outLen)
        return result
    }

    /// Synthesize into an `AVAudioPCMBuffer` in the requested `format` (resampling
    /// from the native 22050 Hz mono if needed).
    public func synthesizeBuffer(_ text: String, params: Params, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let pcm = synthesizePCM(text, params: params)
        if pcm.isEmpty { return AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 1) }
        // native float buffer at 22050 mono
        guard let nativeFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                            sampleRate: sampleRate, channels: 1, interleaved: false),
              let native = AVAudioPCMBuffer(pcmFormat: nativeFmt, frameCapacity: AVAudioFrameCount(pcm.count))
        else { return nil }
        native.frameLength = AVAudioFrameCount(pcm.count)
        let dst = native.floatChannelData![0]
        for i in 0..<pcm.count { dst[i] = Float(pcm[i]) / 32768.0 }

        if format.sampleRate == sampleRate && format.channelCount == 1 {
            return native
        }
        // resample / reformat to the host's requested format
        guard let conv = AVAudioConverter(from: nativeFmt, to: format) else { return native }
        let ratio = format.sampleRate / sampleRate
        let cap = AVAudioFrameCount(Double(pcm.count) * ratio) + 1024
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: cap) else { return native }
        var done = false
        var err: NSError?
        conv.convert(to: out, error: &err) { _, status in
            if done { status.pointee = .endOfStream; return nil }
            done = true; status.pointee = .haveData; return native
        }
        return out
    }
}
