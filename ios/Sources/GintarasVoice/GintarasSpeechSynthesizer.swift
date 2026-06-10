import AVFoundation
import AudioToolbox
import CoreAudio
import Foundation
import GintarasKit

/// System-wide Lithuanian voice: an AVSpeechSynthesisProviderAudioUnit (iOS 16+)
/// that bridges the system speech requests (VoiceOver, any app) to the shared
/// Gintaras engine. Follows Apple's "AVSpeechSynthesisProviderAudioUnit" pattern
/// (WWDC22/23 "Create your own speech synthesizer").
public final class GintarasSpeechSynthesizer: AVSpeechSynthesisProviderAudioUnit {

    private var engine: GintarasEngine?
    private let outputBus: AUAudioUnitBus
    private var busArray: AUAudioUnitBusArray!

    // --- Render handoff -------------------------------------------------------
    // `synthesizeSpeechRequest` (some system thread) renders the whole utterance
    // up front into a plain Float buffer; the realtime `internalRenderBlock`
    // drains it. The buffer is manually managed (no ARC on the audio thread) and
    // both sides are guarded by a small lock that is held only for the brief
    // copy/swap — never during synthesis.
    private let lock = NSLock()
    private var samples: UnsafeMutableBufferPointer<Float>?
    private var framePos = 0

    private static let voiceIdentifier = "com.rosasoft.wintalker.gintaras"

    public override init(componentDescription: AudioComponentDescription,
                         options: AudioComponentInstantiationOptions = []) throws {
        let format = AVAudioFormat(standardFormatWithSampleRate: 22050, channels: 1)!
        outputBus = try AUAudioUnitBus(format: format)
        try super.init(componentDescription: componentDescription, options: options)
        busArray = AUAudioUnitBusArray(audioUnit: self, busType: .output, busses: [outputBus])
        engine = GintarasEngine(bundle: Bundle(for: GintarasSpeechSynthesizer.self))
    }

    deinit { samples?.deallocate() }

    public override var outputBusses: AUAudioUnitBusArray { busArray }

    /// The voice(s) this provider offers to the system.
    public override var speechVoices: [AVSpeechSynthesisProviderVoice] {
        get {
            [AVSpeechSynthesisProviderVoice(
                name: "Gintaras",
                identifier: Self.voiceIdentifier,
                primaryLanguages: ["lt-LT"],
                supportedLanguages: ["lt-LT"])]
        }
        set { /* fixed voice */ }
    }

    /// Replace the pending audio (frees the previous buffer). `new` ownership is
    /// transferred in; pass nil to clear.
    private func install(_ new: UnsafeMutableBufferPointer<Float>?) {
        lock.lock()
        let old = samples
        samples = new
        framePos = 0
        lock.unlock()
        old?.deallocate()
    }

    /// Synthesize one request: extract the text + the SSML prosody (rate / pitch /
    /// volume that VoiceOver and other clients set), render to the output format
    /// and stash it for `internalRenderBlock` to stream out.
    public override func synthesizeSpeechRequest(_ speechRequest: AVSpeechSynthesisProviderRequest) {
        let text = Self.text(from: speechRequest)
        let pr = Self.prosody(from: speechRequest.ssmlRepresentation)
        let params = GintarasSettings.params(rate: Int32(pr.rate), pitch: Int32(pr.pitch))
        guard let buf = engine?.synthesizeBuffer(text, params: params, format: outputBus.format),
              let src = buf.floatChannelData, buf.frameLength > 0 else {
            install(nil)
            return
        }
        let n = Int(buf.frameLength)
        let out = UnsafeMutableBufferPointer<Float>.allocate(capacity: n)
        let ch0 = src[0]
        if pr.volume < 0.999 {
            let g = Float(pr.volume)
            for i in 0..<n { out[i] = ch0[i] * g }   // apply requested volume
        } else {
            out.baseAddress!.update(from: ch0, count: n)
        }
        install(out)
    }

    public override func cancelSpeechRequest() {
        install(nil)
    }

    public override var internalRenderBlock: AUInternalRenderBlock {
        return { [weak self] actionFlags, _, frameCount, _, outputData, _, _ in
            guard let self = self else { return noErr }
            let abl = UnsafeMutableAudioBufferListPointer(outputData)
            let want = Int(frameCount)

            self.lock.lock()
            let buf = self.samples
            let total = buf?.count ?? 0
            let pos = self.framePos
            let n = (buf == nil) ? 0 : min(want, max(0, total - pos))
            for ch in abl {
                guard let dst = ch.mData?.assumingMemoryBound(to: Float.self) else { continue }
                if let b = buf, n > 0 {
                    for i in 0..<n { dst[i] = b[pos + i] }
                }
                if n < want {
                    for i in n..<want { dst[i] = 0 }    // pad the tail with silence
                }
            }
            self.framePos = pos + n
            let finished = (buf == nil) || (pos + n >= total)
            self.lock.unlock()

            if finished { actionFlags.pointee = .offlineUnitRenderAction_Complete }
            return noErr
        }
    }

    /// rate/pitch (engine %, 100 = normal) + volume (0…1) requested by the client.
    struct Prosody { var rate = 100; var pitch = 100; var volume = 1.0 }

    /// Parse the first `<prosody>` tag's rate/pitch/volume from the request SSML.
    /// VoiceOver and other clients pass speaking rate, pitch and volume this way
    /// (e.g. `<prosody rate="200%">…`); `AVSpeechUtterance` does NOT expose them
    /// for SSML-built utterances, so we read the tag ourselves.
    static func prosody(from ssml: String) -> Prosody {
        var p = Prosody()
        guard let open = ssml.range(of: "<prosody", options: .caseInsensitive) else { return p }
        let rest = ssml[open.upperBound...]
        guard let gt = rest.range(of: ">") else { return p }
        let attrs = String(rest[..<gt.lowerBound])
        if let v = attr("rate", attrs)   { p.rate  = pct(v, ["x-slow":50,"slow":75,"medium":100,"fast":150,"x-fast":200,"default":100]) ?? p.rate }
        if let v = attr("pitch", attrs)  { p.pitch = pct(v, ["x-low":50,"low":75,"medium":100,"high":150,"x-high":200,"default":100]) ?? p.pitch }
        if let v = attr("volume", attrs) { p.volume = vol(v) }
        return p
    }

    private static func attr(_ name: String, _ s: String) -> String? {
        // value may be in double or single quotes
        guard let re = try? NSRegularExpression(pattern: "\(name)\\s*=\\s*[\"']([^\"']*)[\"']", options: .caseInsensitive),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              let r = Range(m.range(at: 1), in: s) else { return nil }
        return String(s[r])
    }

    /// SSML rate/pitch -> engine percent (100 = normal). Handles "200%", relative
    /// "+10%"/"-10%", bare numbers (>10 = percent, else multiplier) and keywords.
    private static func pct(_ raw: String, _ keywords: [String: Int]) -> Int? {
        let t = raw.trimmingCharacters(in: .whitespaces).lowercased()
        if let k = keywords[t] { return k }
        let relative = t.hasPrefix("+") || t.hasPrefix("-")
        var s = t; if s.hasSuffix("%") { s.removeLast() }
        guard let d = Double(s) else { return nil }
        let v = relative ? 100.0 + d : (t.contains("%") || abs(d) > 10 ? d : d * 100.0)
        return max(20, min(1000, Int(v.rounded())))
    }

    /// SSML volume -> linear gain 0…1. Handles "0".."100", "n%", "+/-ndB", keywords.
    private static func vol(_ raw: String) -> Double {
        let t = raw.trimmingCharacters(in: .whitespaces).lowercased()
        let kw: [String: Double] = ["silent":0,"x-soft":0.25,"soft":0.5,"medium":0.75,"loud":1.0,"x-loud":1.0,"default":1.0]
        if let k = kw[t] { return k }
        if t.hasSuffix("db"), let db = Double(t.dropLast(2)) { return max(0, min(1, pow(10, db / 20))) }
        var s = t; if s.hasSuffix("%") { s.removeLast() }
        guard let d = Double(s) else { return 1.0 }
        return max(0, min(1, d / 100.0))
    }

    /// Plain text to speak for a request. Prefer letting AVFoundation parse the
    /// SSML (`AVSpeechUtterance(ssmlRepresentation:).speechString`); fall back to
    /// stripping tags ourselves. Note: `attributedSpeechString` crashes for
    /// SSML-created utterances, so we use `speechString`.
    static func text(from request: AVSpeechSynthesisProviderRequest) -> String {
        if let u = AVSpeechUtterance(ssmlRepresentation: request.ssmlRepresentation) {
            let s = u.speechString.trimmingCharacters(in: .whitespacesAndNewlines)
            if !s.isEmpty { return s }
        }
        return plainText(from: request.ssmlRepresentation)
    }

    /// Strip SSML tags / decode entities to plain text. The system wraps the
    /// utterance text in `<speak>…</speak>`; a fuller SSML parse (prosody) is a
    /// future refinement.
    static func plainText(from ssml: String) -> String {
        var t = ssml
        if let re = try? NSRegularExpression(pattern: "<[^>]+>") {
            t = re.stringByReplacingMatches(in: t, range: NSRange(t.startIndex..., in: t), withTemplate: "")
        }
        t = t.replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Factory the extension system instantiates — it is the class named by
/// `NSExtensionPrincipalClass` and the `AudioComponents` `factoryFunction` in
/// Info.plist. An AUv3 audio-unit extension's principal class MUST conform to
/// `AUAudioUnitFactory`; pointing those keys directly at the
/// `AVSpeechSynthesisProviderAudioUnit` subclass fails (it has no `init()` and
/// isn't a factory), so the custom voice never loads. This factory vends the
/// actual audio unit.
public final class GintarasSpeechSynthesizerFactory: NSObject, AUAudioUnitFactory {
    public func beginRequest(with context: NSExtensionContext) {}

    public func createAudioUnit(with componentDescription: AudioComponentDescription) throws -> AUAudioUnit {
        return try GintarasSpeechSynthesizer(componentDescription: componentDescription, options: [])
    }
}
