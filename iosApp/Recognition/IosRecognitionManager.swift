import AVFoundation
import Foundation
import Shared

@MainActor
final class IosRecognitionManager {
    static let shared = IosRecognitionManager()

    private var current: Task<String, Never>?

    private init() {}

    /// Returns "title - artist" for a match, otherwise a localized error message.
    func recognize() async -> String {
        // A second trigger joins the recognition already in progress.
        if let current { return await current.value }
        let task = Task { await run() }
        current = task
        defer { current = nil }
        return await task.value
    }

    private func run() async -> String {
        guard await requestMicrophonePermission() else {
            return (try? await IosRecognition.shared.microphoneDeniedMessage()) ?? ""
        }
        let (audio, sampleRate) = (try? await recordAudio()) ?? (Data(), 0)
        return (try? await IosRecognition.shared.recognize(pcm: audio, inputRate: Int32(sampleRate))) ?? ""
    }

    private func requestMicrophonePermission() async -> Bool {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            return true
        case .undetermined:
            return await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
            }
        default:
            return false
        }
    }

    private func recordAudio() async throws -> (Data, Double) {
        let session = AVAudioSession.sharedInstance()
        let previousCategory = session.category
        let previousMode = session.mode
        let previousOptions = session.categoryOptions
        // Leave the session active: Metrolist's own player may be using it.
        defer { try? session.setCategory(previousCategory, mode: previousMode, options: previousOptions) }
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.mixWithOthers, .defaultToSpeaker])
        try session.setActive(true)

        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.inputFormat(forBus: 0)
        guard format.channelCount > 0, format.sampleRate > 0 else { return (Data(), 0) }
        let durationMs = RecognitionAudio.shared.RECORDING_DURATION_MS
        let maxBytes = Int(format.sampleRate * 2 * Double(durationMs) / 1000)
        var collected = Data()
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            if collected.count < maxBytes {
                collected.append(Self.monoInt16Data(buffer).prefix(maxBytes - collected.count))
            }
        }
        // Stop the tap before reading `collected`, which it writes from the audio thread.
        let stop = {
            input.removeTap(onBus: 0)
            engine.stop()
        }
        engine.prepare()
        do {
            try engine.start()
            try await Task.sleep(nanoseconds: UInt64(durationMs) * 1_000_000)
        } catch {
            stop()
            throw error
        }
        stop()
        return (collected, format.sampleRate)
    }

    private nonisolated static func monoInt16Data(_ buffer: AVAudioPCMBuffer) -> Data {
        let frames = Int(buffer.frameLength)
        let channels = Int(buffer.format.channelCount)
        let stride = buffer.stride
        guard frames > 0, channels > 0 else { return Data() }
        var samples = [Int16](repeating: 0, count: frames)
        switch buffer.format.commonFormat {
        case .pcmFormatInt16:
            guard let data = buffer.int16ChannelData else { return Data() }
            for frame in 0..<frames {
                let sum = (0..<channels).reduce(0) { $0 + Int(data[$1][frame * stride]) }
                samples[frame] = Int16(clamping: sum / channels)
            }
        case .pcmFormatInt32:
            guard let data = buffer.int32ChannelData else { return Data() }
            for frame in 0..<frames {
                let sum = (0..<channels).reduce(0) { $0 + Int(data[$1][frame * stride]) }
                samples[frame] = Int16(clamping: sum / channels / 65536)
            }
        case .pcmFormatFloat32:
            guard let data = buffer.floatChannelData else { return Data() }
            for frame in 0..<frames {
                let sum = (0..<channels).reduce(Float(0)) { $0 + data[$1][frame * stride] }
                samples[frame] = Int16((max(-1, min(1, sum / Float(channels))) * 32767).rounded())
            }
        default:
            return Data()
        }
        return samples.withUnsafeBytes { Data($0) }
    }
}
