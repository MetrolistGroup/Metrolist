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
            let trackId = result.trackId.isEmpty ? "\(result.title):\(result.artist)" : result.trackId
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
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker])
        try session.setActive(true, options: [])
        defer {
            try? session.setActive(false, options: [.notifyOthersOnDeactivation])
            try? session.setCategory(previousCategory, mode: previousMode, options: previousOptions)
        }

        let inputFormat = session.inputFormat(format: .standard)
        let sampleRate = inputFormat.sampleRate > 0 ? inputFormat.sampleRate : 44_100
        let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: sampleRate, channels: 1, interleaved: true)
            ?? AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 44_100, channels: 1, interleaved: true)!

        let engine = AVAudioEngine()
        let input = engine.inputNode
        let bufferSize: AVAudioFrameCount = 4096
        var collected = Data()
        let maxBytes = Int(sampleRate * 2 * Double(RecognitionAudio.shared.RECORDING_DURATION_MS) / 1000.0)

        input.installTap(onBus: 0, bufferSize: bufferSize, format: format) { buffer, _ in
            guard let channelData = buffer.int16ChannelData else { return }
            let frameCount = Int(buffer.frameLength)
            let byteCount = frameCount * MemoryLayout<Int16>.size
            let bytes = Data(bytes: channelData[0], count: byteCount)
            collected.append(bytes)
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
            return nil
        case .microphonePermissionDenied:
            return nil
        case .failed:
            return nil
        }
    }
}

extension Notification.Name {
    static let metrolistRecognitionCompleted = Notification.Name("com.metrolist.recognition.completed")
    static let metrolistRecognitionRequested = Notification.Name("com.metrolist.recognition.requested")
}
