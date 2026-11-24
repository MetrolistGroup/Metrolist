import CarPlay
import Shared
import UIKit

@MainActor
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private weak var interfaceController: CPInterfaceController?
    private var rootTemplate: CPListTemplate?
    private var stateObserver: (() -> Void)?
    private var currentStrings: CarPlayStrings?
    private var pendingConnection = false

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController,
    ) {
        self.interfaceController = interfaceController
        self.pendingConnection = true
        IosCarPlayBridge.shared.loadStrings { [weak self] strings in
            DispatchQueue.main.async {
                guard let self, self.pendingConnection else { return }
                self.currentStrings = strings
                let template = self.makeRootTemplate(strings: strings)
                self.rootTemplate = template
                interfaceController.setRootTemplate(template, animated: true, completion: nil)
                self.observePlaybackState()
            }
        }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController,
    ) {
        pendingConnection = false
        stateObserver?()
        stateObserver = nil
        self.interfaceController = nil
        rootTemplate = nil
    }

    private func makeRootTemplate(strings: CarPlayStrings) -> CPListTemplate {
        let template = CPListTemplate(title: "Metrolist", sections: [])
        IosCarPlayBridge.shared.loadRootItems { [weak self] items in
            DispatchQueue.main.async {
                guard let self else { return }
                template.updateSections([self.section(for: items, containerId: nil, strings: strings)])
            }
        }
        return template
    }

    private func observePlaybackState() {
        stateObserver?()
        stateObserver = IosCarPlayBridge.shared.addStateListener { [weak self] state in
            DispatchQueue.main.async {
                self?.updateNowPlaying(state: state)
            }
        }
    }

    private func updateNowPlaying(state: CarPlayNowPlayingState) {
        let nowPlaying = CPNowPlayingTemplate.shared
        var buttons: [CPNowPlayingButton] = []
        let prev = CPNowPlayingImageButton(image: UIImage(systemName: "backward.fill") ?? UIImage()) { _ in
            IosCarPlayBridge.shared.skipToPrevious()
        }
        prev.isEnabled = state.canSkipPrevious
        let playPauseImage = UIImage(systemName: state.isPlaying ? "pause.fill" : "play.fill") ?? UIImage()
        let playPause = CPNowPlayingImageButton(image: playPauseImage) { _ in
            IosCarPlayBridge.shared.togglePlayPause()
        }
        let next = CPNowPlayingImageButton(image: UIImage(systemName: "forward.fill") ?? UIImage()) { _ in
            IosCarPlayBridge.shared.skipToNext()
        }
        next.isEnabled = state.canSkipNext
        buttons = [prev, playPause, next]
        nowPlaying.updateNowPlayingButtons(buttons)
    }

    private func section(for items: [CarPlayBrowseItem], containerId: String?, strings: CarPlayStrings) -> CPListSection {
        let rows: [CPListItem] = items.map { item in
            let listItem = CPListItem(text: item.title, detailText: item.subtitle)
            listItem.handler = { [weak self] _, completion in
                self?.handleSelection(item: item, containerId: containerId, strings: strings)
                completion()
            }
            return listItem
        }
        return CPListSection(items: rows)
    }

    private func handleSelection(item: CarPlayBrowseItem, containerId: String?, strings: CarPlayStrings) {
        if item.isContainer {
            pushContainer(item: item, strings: strings)
            return
        }
        guard let containerId else { return }
        let components = item.id.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false)
        guard components.count == 2 else { return }
        let songId = String(components[1])
        IosCarPlayBridge.shared.playSongFromContainer(containerId: containerId, songId: songId)
        interfaceController?.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil)
    }

    private func pushContainer(item: CarPlayBrowseItem, strings: CarPlayStrings) {
        let template = CPListTemplate(title: item.title, sections: [])
        interfaceController?.pushTemplate(template, animated: true, completion: nil)
        IosCarPlayBridge.shared.loadChildren(parentId: item.id) { [weak self] children in
            DispatchQueue.main.async {
                guard let self else { return }
                template.updateSections([self.section(for: children, containerId: item.id, strings: strings)])
            }
        }
    }
}
