import Foundation
import AVFoundation

/// Minimal AVAudioEngine player for previewing synthesized PCM in-app.
final class SpeechPlayer {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    /// Native voice format: 22050 Hz mono float.
    let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 22050, channels: 1)!

    init() {
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
    }

    func play(_ buffer: AVAudioPCMBuffer) {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
            if !engine.isRunning { try engine.start() }
            node.stop()
            node.scheduleBuffer(buffer, at: nil)
            node.play()
        } catch {
            // ignore in preview
        }
    }

    func stop() {
        node.stop()
    }
}
