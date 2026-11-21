/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

import AVFoundation
import BackgroundTasks
import Combine
import Foundation
import Sentry
import Shared
import SwiftUI

@main
struct MetrolistApp: App {
    @UIApplicationDelegateAdaptor(AutomaticBackupAppDelegate.self) private var appDelegate

    init() {
        IosCrashRecovery.shared.install()
        NSSetUncaughtExceptionHandler { exception in
            IosCrashRecovery.shared.recordNative(message: "\(exception.name.rawValue): \(exception.reason ?? "")\n\(exception.callStackSymbols.joined(separator: "\n"))")
        }
        configureAppAudioSession()
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
