import AVFoundation
import AudioToolbox
import CoreAudio
import GintarasKit

/// System-wide Lithuanian voice: an AVSpeechSynthesisProviderAudioUnit (iOS 16+)
/// that bridges the system speech requests (VoiceOver, any app) to the shared
/// Gintaras engine. Follows Apple's "AVSpeechSynthesisProviderAudioUnit" pattern
/// (WWDC22 "Create your own speech synthesizer"); verify bus/format wiring in Xcode.
public final class GintarasSpeechSynthesizer: AVSpeechSynthesisProviderAudioUnit {

    private var engine: GintarasEngine?
    private let outputBus: AUAudioUnitBus
    private var busArray: AUAudioUnitBusArray!

    // Rendered audio for the in-flight request (output-bus format) + cursor.
    private var pending: AVAudioPCMBuffer?
    private var framePos: AVAudioFramePosition = 0

    private static let voiceIdentifier = "com.rosasoft.wintalker.gintaras"

    public override init(componentDescription: AudioComponentDescription,
                         options: AudioComponentInstantiationOptions = []) throws {
        let format = AVAudioFormat(standardFormatWithSampleRate: 22050, channels: 1)!
        outputBus = try AUAudioUnitBus(format: format)
        try super.init(componentDescription: componentDescription, options: options)
        busArray = AUAudioUnitBusArray(audioUnit: self, busType: .output, busses: [outputBus])
        engine = GintarasEngine(bundle: Bundle(for: GintarasSpeechSynthesizer.self))
    }

    public override var outputBusses: AUAudioUnitBusArray { busArray }

    public override func allocateRenderResources() throws {
        try super.allocateRenderResources()
    }

    public override func deallocateRenderResources() {
        super.deallocateRenderResources()
    }

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

    /// Synthesize one request: extract the text, render it to the output format and
    /// stash it for `internalRenderBlock` to stream out.
    public override func synthesizeSpeechRequest(_ speechRequest: AVSpeechSynthesisProviderRequest) {
        let text = Self.text(from: speechRequest)
        let params = GintarasSettings.params(rate: 100, pitch: 100)
        let fmt = outputBus.format
        pending = engine?.synthesizeBuffer(text, params: params, format: fmt)
        framePos = 0
    }

    public override func cancelSpeechRequest() {
        pending = nil
        framePos = 0
    }

    public override var internalRenderBlock: AUInternalRenderBlock {
        return { [weak self] actionFlags, _, frameCount, _, outputData, _, _ in
            guard let self = self else { return noErr }
            let abl = UnsafeMutableAudioBufferListPointer(outputData)
            let want = Int(frameCount)

            guard let buf = self.pending, let src = buf.floatChannelData else {
                // nothing to render -> silence + complete
                for b in abl {
                    if let p = b.mData { memset(p, 0, Int(b.mDataByteSize)) }
                }
                actionFlags.pointee = .offlineUnitRenderAction_Complete
                return noErr
            }

            let total = Int(buf.frameLength)
            let remaining = max(0, total - Int(self.framePos))
            let n = min(want, remaining)
            let s = src[0]
            for ch in abl {
                guard let dst = ch.mData?.assumingMemoryBound(to: Float.self) else { continue }
                for i in 0..<n { dst[i] = s[Int(self.framePos) + i] }
                for i in n..<want { dst[i] = 0 }
            }
            self.framePos += AVAudioFramePosition(n)
            if Int(self.framePos) >= total {
                actionFlags.pointee = .offlineUnitRenderAction_Complete
            }
            return noErr
        }
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
