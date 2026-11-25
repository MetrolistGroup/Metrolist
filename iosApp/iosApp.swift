/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

import AVFoundation
import BackgroundTasks
import Combine
import Foundation
import MediaPlayer
import Sentry
import Shared
import SwiftUI
import UIKit
import WidgetKit

@main
struct MetrolistApp: App {
    @UIApplicationDelegateAdaptor(AutomaticBackupAppDelegate.self) private var appDelegate

    init() {
        IosCrashRecovery.shared.install()
        NSSetUncaughtExceptionHandler { exception in
            IosCrashRecovery.shared.recordNative(message: "\(exception.name.rawValue): \(exception.reason ?? "")\n\(exception.callStackSymbols.joined(separator: "\n"))")
        }
        configureAppAudioSession()
        IosNowPlayingAnimatedArtworkBridge.shared.register(provider: IosNowPlayingAnimatedArtworkHost())
        IosNowPlayingWidget.shared.register(reloader: WidgetTimelineReloader())
        if #available(iOS 17.0, *) {
            PlaybackControlIntent.handler = { IosNowPlayingWidget.shared.handle(command: $0) }
        }
        if UserDefaults.standard.object(forKey: "iosOnboardingComplete") == nil {
            let existingDatabase = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
                .first?.appendingPathComponent("metrolist/metrolist.db")
            let isUpgrade = existingDatabase.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
            UserDefaults.standard.set(isUpgrade, forKey: "iosOnboardingComplete")
        }
        if !IosCrashRecovery.shared.hasPendingReport {
            MetrolistPlatform.shared.initialize()
        }
        if UserDefaults.standard.bool(forKey: "sentryEnabled") {
            startSentryReporting()
        }
    }

    var body: some Scene {
        WindowGroup {
            AppHostView()
        }
    }

    private func configureAppAudioSession() {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playback, mode: .default)
        } catch {
            IosApplicationLogBridgeKt.logIosApplicationError(
                tag: "AudioSession",
                message: "Audio session configuration error: \(error.localizedDescription)"
            )
        }
    }
}

private final class AutomaticBackupAppDelegate: NSObject, UIApplicationDelegate {
    private static let taskIdentifier = "com.metrolist.automatic-backup"
    private let settingsChanged = Notification.Name("com.metrolist.automaticBackupSettingsChanged")

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil,
    ) -> Bool {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskIdentifier, using: nil) { [weak self] task in
            guard let task = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self?.run(task)
        }
        NotificationCenter.default.addObserver(self, selector: #selector(schedule), name: settingsChanged, object: nil)
        schedule()
        return true
    }

    @objc private func schedule() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
        guard !IosCrashRecovery.shared.hasPendingReport,
              IosAutomaticBackupBridgeKt.iosAutomaticBackupEnabled() else { return }

        let interval = TimeInterval(IosAutomaticBackupBridgeKt.iosAutomaticBackupIntervalMillis()) / 1000
        let lastSuccess = TimeInterval(IosAutomaticBackupBridgeKt.iosAutomaticBackupLastSuccessEpochMillis()) / 1000
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.earliestBeginDate = IosAutomaticBackupBridgeKt.iosAutomaticBackupNeedsCleanup()
            ? Date(timeIntervalSinceNow: 5 * 60)
            : Date(timeIntervalSince1970: lastSuccess + interval)
        request.requiresNetworkConnectivity = false
        request.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(request)
    }

    private func run(_ task: BGProcessingTask) {
        guard !IosCrashRecovery.shared.hasPendingReport else {
            task.setTaskCompleted(success: false)
            return
        }
        let completionLock = NSLock()
        var didComplete = false
        let complete: (Bool) -> Void = { [weak self] succeeded in
            completionLock.lock()
            let shouldComplete = !didComplete
            didComplete = true
            completionLock.unlock()
            guard shouldComplete else { return }
            task.setTaskCompleted(success: succeeded)
            self?.schedule()
        }
        task.expirationHandler = {
            IosAutomaticBackupBridgeKt.cancelIosAutomaticBackup()
            complete(false)
        }
        IosAutomaticBackupBridgeKt.createIosAutomaticBackup { succeeded in
            complete(succeeded.boolValue)
        }
    }
}

private final class WidgetTimelineReloader: NSObject, IosWidgetTimelineReloader {
    func reloadTimelines() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}

private struct AppHostView: View {
    @State private var statusBarHidden = false
    @State private var sentryEnabled = UserDefaults.standard.bool(forKey: "sentryEnabled")

    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .statusBarHidden(statusBarHidden)
            .onAppear {
                syncSentryConsent()
            }
            .onReceive(NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification).receive(on: RunLoop.main)) { _ in
                syncSentryConsent()
            }
            .onReceive(NotificationCenter.default.publisher(for: Notification.Name("com.metrolist.statusBarVisibilityChanged"))) { notification in
                statusBarHidden = (notification.object as? NSNumber)?.boolValue ?? false
            }
            .onOpenURL { url in
                MainViewControllerKt.handleOpenUrl(url: url.absoluteString)
            }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                guard let url = activity.webpageURL else { return }
                MainViewControllerKt.handleOpenUrl(url: url.absoluteString)
            }
    }

    private func syncSentryConsent() {
        let consent = UserDefaults.standard.bool(forKey: "sentryEnabled")
        guard consent != sentryEnabled else { return }
        sentryEnabled = consent
        if consent {
            startSentryReporting()
        } else {
            SentrySDK.close()
        }
    }
}

private func startSentryReporting() {
    guard IosSentryKt.iosCrashReportingAvailable() else { return }
    SentrySDK.start { options in
        options.dsn = "https://fe4474514a54a507252b26c60e229016@o4511744603193344.ingest.de.sentry.io/4511744608436304"
        options.debug = false
        options.environment = "production"
        options.sendDefaultPii = false
        options.tracesSampleRate = 0.0
        options.failedRequestTargets = [
            try? NSRegularExpression(
                pattern: #"^https://(?:(?:[a-z0-9-]+\.)*metrolist\.cc|metroserverx\.meowery\.eu)(?::443)?(?:[/?#]|$)"#,
                options: .caseInsensitive
            )
        ].compactMap { $0 }
        options.failedRequestStatusCodes = [HttpStatusCodeRange(min: 500, max: 599)]
        options.attachScreenshot = false
        options.attachViewHierarchy = false
        options.releaseName = IosSentryKt.iosSentryRelease()
        options.initialScope = { scope in
            let revision = IosSentryKt.iosSentrySourceRevision()
            if !revision.isEmpty {
                scope.setTag(value: revision, key: "source_revision")
            }
            return scope
        }
        options.beforeBreadcrumb = { breadcrumb in
            redactBreadcrumb(breadcrumb)
            return breadcrumb
        }
        options.beforeSend = { event in
            if event.exceptions?.contains(where: { $0.type == "HTTPClientError" }) == true,
               let status = event.context?["response"]?["status_code"] as? NSNumber,
               LyricsResponseKt.isExpectedLyricsServiceFailure(url: event.request?.url, statusCode: status.int32Value) {
                return nil
            }
            event.user = nil
            if let request = event.request {
                request.url = request.url.map(redactDiagnostic)
                request.queryString = nil
                request.fragment = nil
                request.cookies = nil
                request.headers = nil
            }
            event.message = event.message.map { SentryMessage(formatted: redactDiagnostic($0.formatted)) }
            event.extra = event.extra?.mapValues(redactDiagnosticValue)
            event.exceptions?.forEach { $0.value = $0.value.map(redactDiagnostic) }
            event.threads?.forEach { $0.name = $0.name.map(redactDiagnostic) }
            event.breadcrumbs?.forEach(redactBreadcrumb)
            return event
        }
    }
}

private func redactDiagnostic(_ text: String) -> String {
    DiagnosticRedactionKt.redactDiagnosticText(text: text)
}

private func redactBreadcrumb(_ breadcrumb: Breadcrumb) {
    breadcrumb.message = breadcrumb.message.map(redactDiagnostic)
    breadcrumb.data = breadcrumb.data?
        .filter { $0.key != "http.query" && $0.key != "http.fragment" }
        .mapValues(redactDiagnosticValue)
}

private func redactDiagnosticValue(_ value: Any) -> Any {
    (value as? String).map(redactDiagnostic) ?? value
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

private final class IosNowPlayingAnimatedArtworkHost: NSObject, IosNowPlayingAnimatedArtworkProvider {
    var isSupported: Bool {
        if #available(iOS 26.0, *) {
            return !MPNowPlayingInfoCenter.supportedAnimatedArtworkKeys.isEmpty
        }
        return false
    }

    func createEntries(spec: IosNowPlayingAnimatedArtworkSpec, squareFallbackImage: UIImage?) -> [String: Any] {
        guard #available(iOS 26.0, *) else { return [:] }
        let supportedKeys = Set(MPNowPlayingInfoCenter.supportedAnimatedArtworkKeys)
        var entries: [String: Any] = [:]
        if supportedKeys.contains(MPNowPlayingInfoProperty1x1AnimatedArtwork),
           let artworkId = spec.squareArtworkId,
           let videoUrl = spec.squareVideoUrl {
            entries[MPNowPlayingInfoProperty1x1AnimatedArtwork] = makeAnimatedArtwork(
                artworkId: artworkId,
                videoUrl: videoUrl,
                previewImageUrl: spec.squarePreviewImageUrl,
                preloadedPreview: spec.squarePreviewImage,
                squareFallback: squareFallbackImage,
                targetAspectRatio: 1.0
            )
        }
        if supportedKeys.contains(MPNowPlayingInfoProperty3x4AnimatedArtwork),
           let artworkId = spec.tallArtworkId,
           let videoUrl = spec.tallVideoUrl {
            entries[MPNowPlayingInfoProperty3x4AnimatedArtwork] = makeAnimatedArtwork(
                artworkId: artworkId,
                videoUrl: videoUrl,
                previewImageUrl: spec.tallPreviewImageUrl,
                preloadedPreview: spec.tallPreviewImage,
                squareFallback: nil,
                targetAspectRatio: 3.0 / 4.0
            )
        }
        return entries
    }

    @available(iOS 26.0, *)
    private func makeAnimatedArtwork(
        artworkId: String,
        videoUrl: String,
        previewImageUrl: String?,
        preloadedPreview: UIImage?,
        squareFallback: UIImage?,
        targetAspectRatio: CGFloat
    ) -> MPMediaItemAnimatedArtwork {
        let readyPreview = preloadedPreview.flatMap { Self.normalizedPreviewImage($0, targetAspectRatio: targetAspectRatio) }
        let readyFallback = squareFallback.flatMap { Self.normalizedPreviewImage($0, targetAspectRatio: targetAspectRatio) }
        return MPMediaItemAnimatedArtwork(
            artworkID: artworkId,
            previewImageRequestHandler: { requestedSize, completion in
                let finish = OnceCompletion(completion)
                if let readyPreview {
                    finish.call(readyPreview)
                    return
                }
                if let previewImageUrl {
                    let (width, height) = Self.targetPreviewDimensions(requestedSize: requestedSize, targetAspectRatio: targetAspectRatio)
                    IosNowPlayingAnimatedArtworkKt.fetchIosAnimatedArtworkPreviewImage(
                        url: previewImageUrl,
                        targetWidth: Int32(width),
                        targetHeight: Int32(height)
                    ) { image in
                        if let valid = image.flatMap({ Self.normalizedPreviewImage($0, targetAspectRatio: targetAspectRatio) }) {
                            finish.call(valid)
                        } else if let readyFallback {
                            finish.call(readyFallback)
                        } else {
                            Self.extractVideoPreviewFrame(
                                artworkId: artworkId,
                                videoUrl: videoUrl,
                                targetAspectRatio: targetAspectRatio,
                                finish: finish
                            )
                        }
                    }
                    return
                }
                if let readyFallback {
                    finish.call(readyFallback)
                    return
                }
                Self.extractVideoPreviewFrame(
                    artworkId: artworkId,
                    videoUrl: videoUrl,
                    targetAspectRatio: targetAspectRatio,
                    finish: finish
                )
            },
            videoAssetFileURLRequestHandler: { _, completion in
                let finish = OnceCompletion(completion)
                IosNowPlayingAnimatedArtworkKt.fetchIosAnimatedArtworkVideoFilePath(
                    artworkId: artworkId,
                    sourceUrl: videoUrl
                ) { path in
                    guard let path, !path.isEmpty else {
                        finish.call(nil)
                        return
                    }
                    let fileURL = URL(fileURLWithPath: path)
                    Task {
                        let valid = await Self.matchesVideoAspectRatio(fileURL: fileURL, targetAspectRatio: targetAspectRatio)
                        finish.call(valid ? fileURL : nil)
                    }
                }
            }
        )
    }

    private static func targetPreviewDimensions(requestedSize: CGSize, targetAspectRatio: CGFloat) -> (Int, Int) {
        let defaultWidth = 768
        let defaultHeight = targetAspectRatio < 1.0 ? 1024 : 768
        guard requestedSize.width > 0, requestedSize.height > 0 else {
            return (defaultWidth, defaultHeight)
        }
        let maxSide = min(max(Int(requestedSize.width.rounded()), Int(requestedSize.height.rounded())), 1024)
        if targetAspectRatio < 1.0 {
            let height = max(maxSide, 256)
            let width = max(Int((CGFloat(height) * targetAspectRatio).rounded()), 192)
            return (width, height)
        }
        let side = max(maxSide, 256)
        return (side, side)
    }

    private static func normalizedPreviewImage(_ image: UIImage, targetAspectRatio: CGFloat) -> UIImage? {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }
        let ratio = size.width / size.height
        if abs(ratio - targetAspectRatio) <= 0.06 {
            return image
        }
        guard abs(targetAspectRatio - 1.0) < 0.01 else { return nil }
        let side = min(size.width, size.height)
        let origin = CGPoint(x: (side - size.width) / 2, y: (side - size.height) / 2)
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            image.draw(at: origin)
        }
    }

    @available(iOS 26.0, *)
    private static func extractVideoPreviewFrame(
        artworkId: String,
        videoUrl: String,
        targetAspectRatio: CGFloat,
        finish: OnceCompletion<UIImage?>
    ) {
        IosNowPlayingAnimatedArtworkKt.fetchIosAnimatedArtworkVideoFilePath(
            artworkId: artworkId,
            sourceUrl: videoUrl
        ) { path in
            guard let path, !path.isEmpty else {
                finish.call(nil)
                return
            }
            let asset = AVURLAsset(url: URL(fileURLWithPath: path))
            Task {
                let generator = AVAssetImageGenerator(asset: asset)
                generator.appliesPreferredTrackTransform = true
                do {
                    let result = try await generator.image(at: .zero)
                    let image = UIImage(cgImage: result.image)
                    finish.call(Self.normalizedPreviewImage(image, targetAspectRatio: targetAspectRatio))
                } catch {
                    finish.call(nil)
                }
            }
        }
    }

    @available(iOS 26.0, *)
    private static func matchesVideoAspectRatio(fileURL: URL, targetAspectRatio: CGFloat) async -> Bool {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return false }
        let asset = AVURLAsset(url: fileURL)
        do {
            let tracks = try await asset.loadTracks(withMediaType: .video)
            guard let track = tracks.first else { return false }
            let naturalSize = try await track.load(.naturalSize)
            let transform = try await track.load(.preferredTransform)
            let oriented = naturalSize.applying(transform)
            let width = abs(oriented.width)
            let height = abs(oriented.height)
            guard width > 0, height > 0 else { return false }
            return abs((width / height) - targetAspectRatio) <= 0.06
        } catch {
            return false
        }
    }
}

private final class OnceCompletion<Value> {
    private let lock = NSLock()
    private var completion: ((Value) -> Void)?

    init(_ completion: @escaping (Value) -> Void) {
        self.completion = completion
    }

    func call(_ value: Value) {
        lock.lock()
        let handler = completion
        completion = nil
        lock.unlock()
        handler?(value)
    }
}
