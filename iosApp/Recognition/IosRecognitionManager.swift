import AVFoundation
import Foundation
import Shared

@MainActor
final class IosRecognitionManager {
    static let shared = IosRecognitionManager()

    private init() {}

    func recognize(from entryPoint: RecognitionEntryPoint) async throws -> RecognitionResult {
        guard await requestMicrophonePermission() else {
            throw RecognitionManagerError.microphonePermissionDenied
        }

        let audio = try await recordAudio()
        let byteArray = KotlinByteArray(size: Int32(audio.count))
        for (index, byte) in audio.enumerated() {
            byteArray.set(index: Int32(index), value: Int8(bitPattern: byte))
        }

        let signature = ShazamSignatureGenerator.shared.fromI16(samples: byteArray)
        let sampleDurationMs = Int64(audio.count / 2) * 1000 / Int64(RecognitionAudio.shared.FINGERPRINT_SAMPLE_RATE)

        return try await withCheckedThrowingContinuation { continuation in
            IosRecognitionManagerLogic.shared.recognize(
                signature: signature,
                sampleDurationMs: sampleDurationMs,
                entryPoint: entryPoint
            ) { result, error in
                if let error {
                    if error is RecognitionAlreadyRunningException {
                        continuation.resume(throwing: RecognitionManagerError.alreadyRunning)
                    } else {
                        continuation.resume(throwing: error)
                    }
                    return
                }
                guard let result else {
                    continuation.resume(throwing: RecognitionManagerError.failed)
                    return
                }
                continuation.resume(returning: result)
            }
        }
    }

    private func requestMicrophonePermission() async -> Bool {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
    }

    private func recordAudio() async throws -> Data {
        let session = AVAudioSession.sharedInstance()
        let previousCategory = session.category
        let previousMode = session.mode
        let previousOptions = session.categoryOptions
        var didActivate = false
        defer {
            if didActivate {
                try? session.setActive(false, options: [.notifyOthersOnDeactivation])
            }
            try? session.setCategory(previousCategory, mode: previousMode, options: previousOptions)
        }
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker])
        try session.setActive(true, options: [])
        didActivate = true

        let engine = AVAudioEngine()
        let input = engine.inputNode
        let inputFormat = input.inputFormat(forBus: 0)
        guard inputFormat.channelCount > 0, inputFormat.sampleRate > 0 else {
            throw RecognitionManagerError.failed
        }
        let sampleRate = inputFormat.sampleRate
        let bufferSize: AVAudioFrameCount = 4096
        var collected = Data()
        let maxBytes = Int(sampleRate * 2 * Double(RecognitionAudio.shared.RECORDING_DURATION_MS) / 1000.0)

        input.installTap(onBus: 0, bufferSize: bufferSize, format: inputFormat) { buffer, _ in
            let samples = Self.monoInt16Data(buffer)
            if !samples.isEmpty {
                collected.append(samples)
            }
            if collected.count > maxBytes {
                collected = collected.prefix(maxBytes)
            }
        }

        engine.prepare()
        try engine.start()
        try await Task.sleep(nanoseconds: UInt64(RecognitionAudio.shared.RECORDING_DURATION_MS) * 1_000_000)
        input.removeTap(onBus: 0)
        engine.stop()

        guard !collected.isEmpty else {
            throw RecognitionManagerError.failed
        }
        if Int(sampleRate) != Int(RecognitionAudio.shared.FINGERPRINT_SAMPLE_RATE) {
            let byteArray = KotlinByteArray(size: Int32(collected.count))
            for (index, byte) in collected.enumerated() {
                byteArray.set(index: Int32(index), value: Int8(bitPattern: byte))
            }
            let resampled = RecognitionAudio.shared.resample16BitMono(
                input: byteArray,
                inputRate: Int32(sampleRate),
                outputRate: RecognitionAudio.shared.FINGERPRINT_SAMPLE_RATE
            )
            var out = Data(count: Int(resampled.size))
            for i in 0..<Int(resampled.size) {
                let value = resampled.get(index: Int32(i))
                out[i] = UInt8(bitPattern: value)
            }
            return out
        }
        return collected
    }

    private static func monoInt16Data(_ buffer: AVAudioPCMBuffer) -> Data {
        let frames = Int(buffer.frameLength)
        let channels = Int(buffer.format.channelCount)
        let stride = Int(buffer.stride)
        guard frames > 0, channels > 0, stride > 0 else { return Data() }
        var samples = [Int16]()
        samples.reserveCapacity(frames)
        switch buffer.format.commonFormat {
        case .pcmFormatInt16:
            guard let data = buffer.int16ChannelData else { return Data() }
            for frame in 0..<frames {
                var sum = 0
                for channel in 0..<channels {
                    sum += Int(data[channel][frame * stride])
                }
                samples.append(Int16(clamping: sum / channels))
            }
        case .pcmFormatInt32:
            guard let data = buffer.int32ChannelData else { return Data() }
            for frame in 0..<frames {
                var sum = 0
                for channel in 0..<channels {
                    sum += Int(data[channel][frame * stride])
                }
                samples.append(Int16(clamping: sum / channels / 65536))
            }
        case .pcmFormatFloat32:
            guard let data = buffer.floatChannelData else { return Data() }
            for frame in 0..<frames {
                var sum: Float = 0
                for channel in 0..<channels {
                    sum += data[channel][frame * stride]
                }
                samples.append(Int16((max(-1, min(1, sum / Float(channels))) * 32767).rounded()))
            }
        default:
            return Data()
        }
        return samples.withUnsafeBytes { Data($0) }
    }
}

enum RecognitionManagerError: LocalizedError {
    case alreadyRunning
    case microphonePermissionDenied
    case failed

    var errorDescription: String? {
        switch self {
        case .alreadyRunning:
            return "Recognition is already in progress."
        case .microphonePermissionDenied:
            return "Microphone access is required to recognize music."
        case .failed:
            return "Recognition failed. Please try again."
        }
    }
}
