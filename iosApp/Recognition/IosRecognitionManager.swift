import AVFoundation
import Foundation
import Shared
import UIKit

@MainActor
final class IosRecognitionManager {
    static let shared = IosRecognitionManager()

    private var isRunning = false

    private init() {}

    func recognize(from entryPoint: RecognitionEntryPoint) async throws -> RecognitionResult {
        guard !isRunning else {
            throw RecognitionManagerError.alreadyRunning
        }
        isRunning = true
        defer { isRunning = false }

        let granted = await requestMicrophonePermission()
        guard granted else {
            throw RecognitionManagerError.microphonePermissionDenied
        }

        let audio = try await recordAudio()
        let byteArray = KotlinByteArray(size: Int32(audio.count))
        for (index, byte) in audio.enumerated() {
            byteArray.set(index: Int32(index), value: Int8(bitPattern: byte))
        }

        let signature = ShazamSignatureGenerator.shared.fromI16(samples: byteArray)
        let sampleDurationMs = Int64(audio.count / 2) * 1000 / Int64(RecognitionAudio.shared.FINGERPRINT_SAMPLE_RATE)

        let result = try await withCheckedThrowingContinuation { continuation in
            IosRecognitionBridge.shared.recognize(signature: signature, sampleDurationMs: sampleDurationMs) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let result else {
                    continuation.resume(throwing: RecognitionManagerError.failed)
                    return
                }
                continuation.resume(returning: result)
            }
        }

        let recognizedAt = Int64(Date().timeIntervalSince1970 * 1000)
        let history = IosRecognitionBridge.shared.createRecognitionHistory(result: result, recognizedAtEpochMillis: recognizedAt)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            IosRecognitionBridge.shared.saveRecognitionHistory(history: history) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }

        Task.detached {
            guard let videoId = try? await Self.resolveVideoId(for: result), !videoId.isEmpty else { return }
            let trackId = history.trackId
            try? await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                IosRecognitionBridge.shared.updateRecognitionVideoId(trackId: trackId, youtubeVideoId: videoId) { error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume(returning: ())
                    }
                }
            }
        }

        notify(result: result, entryPoint: entryPoint)
        return result
    }

    private static func resolveVideoId(for result: RecognitionResult) async throws -> String? {
        try await withCheckedThrowingContinuation { continuation in
            IosRecognitionBridge.shared.resolveYoutubeVideoId(result: result) { value, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: value)
                }
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
        let sampleRate = inputFormat.sampleRate > 0 ? inputFormat.sampleRate : 44_100
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
        guard frames > 0, channels > 0 else { return Data() }
        var samples = [Int16]()
        samples.reserveCapacity(frames)
        switch buffer.format.commonFormat {
        case .pcmFormatInt16:
            guard let data = buffer.int16ChannelData else { return Data() }
            for frame in 0..<frames {
                var sum = 0
                for channel in 0..<channels {
                    sum += Int(data[channel][frame])
                }
                samples.append(Int16(clamping: sum / channels))
            }
        case .pcmFormatInt32:
            guard let data = buffer.int32ChannelData else { return Data() }
            for frame in 0..<frames {
                var sum = 0
                for channel in 0..<channels {
                    sum += Int(data[channel][frame])
                }
                samples.append(Int16(clamping: sum / channels / 65536))
            }
        case .pcmFormatFloat32:
            guard let data = buffer.floatChannelData else { return Data() }
            for frame in 0..<frames {
                var sum: Float = 0
                for channel in 0..<channels {
                    sum += data[channel][frame]
                }
                samples.append(Int16((max(-1, min(1, sum / Float(channels))) * 32767).rounded()))
            }
        default:
            return Data()
        }
        return samples.withUnsafeBytes { Data($0) }
    }

    private func notify(result: RecognitionResult, entryPoint: RecognitionEntryPoint) {
        let payload = RecognitionPayload(result: result, recognizedAtEpochMillis: Int64(Date().timeIntervalSince1970 * 1000))
        NotificationCenter.default.post(
            name: .metrolistRecognitionCompleted,
            object: nil,
            userInfo: [
                "payload": (try? payload.encode()) ?? "",
                "entryPoint": entryPoint.rawValue,
            ]
        )
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

extension Notification.Name {
    static let metrolistRecognitionCompleted = Notification.Name("com.metrolist.recognition.completed")
    static let metrolistRecognitionRequested = Notification.Name("com.metrolist.recognition.requested")
}
