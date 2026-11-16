.DEFAULT_GOAL := help

FLATPAK_REMOTE ?= flathub
FLATPAK_RUNTIME ?= org.gnome.Platform
FLATPAK_SDK ?= org.gnome.Sdk
FLATPAK_RUNTIME_VERSION ?= 51
FLATPAK_APP_ID ?= com.metrolist.desktop
FLATPAK_MANIFEST ?= flatpak/$(FLATPAK_APP_ID).yaml
FLATPAK_BUILD_DIR ?= build/flatpak
FLATPAK_REPO_DIR ?= build/flatpak-repo

.PHONY: help
help:
	@echo "Available targets:"
	@echo "  deps                 Install Java 21 (SDKMAN) and Android SDK"
	@echo "  build                Build desktop app"
	@echo "  build-clean           Clean and build desktop app"
	@echo "  run                   Run desktop app"
	@echo "  run-clean             Clean and run desktop app"
	@echo "  dist                  Create release distributable package"
	@echo "  dist-clean            Clean and create release distributable package"
	@echo "  build-appimage        Build AppImage package"
	@echo "  aur-install           Build and install the local Arch package"
	@echo "  flatpak-deps          Install Flatpak GNOME SDK/Platform runtimes"
	@echo "  flatpak-build         Build Flatpak package (without installing)"
	@echo "  flatpak-install       Build and install Flatpak locally"
	@echo "  flatpak-bundle        Build standalone Flatpak bundle"
	@echo "  flatpak-run           Run Flatpak in sandbox without installing"
	@echo "  flatpak-run-installed Run installed Flatpak app"
	@echo "  flatpak-uninstall     Uninstall local Flatpak app"
	@echo "  android-debug         Assemble Android debug APK"
	@echo "  android-debug-clean   Clean and assemble Android debug APK"
	@echo "  android-release       Assemble Android release APK"
	@echo "  android-release-clean Clean and assemble Android release APK"
	@echo "  ios-build             Validate and build iOS framework & Swift files"
	@echo "  ios-test              Run iOS Swift static analysis & syntax verification"
	@echo "  ios-clean             Clean iOS build artifacts"
	@echo "  test                  Run all unit tests"
	@echo "  test-desktop          Run desktop and shared unit tests"
	@echo "  lint                  Run ktlint checks"
	@echo "  format                Format Kotlin files with ktlint"
	@echo "  clean                 Clean Gradle build artifacts"
	@echo "  clean-all             Clean Gradle, AppImage dist, and Flatpak caches"

.PHONY: deps
deps:
	./scripts/deps.sh

.PHONY: build build-clean run run-clean dist dist-clean
build:
	./gradlew :desktopApp:build

build-clean:
	./gradlew clean :desktopApp:build

run:
	./gradlew :desktopApp:run

run-clean:
	./gradlew clean :desktopApp:run

dist:
	./gradlew :desktopApp:createReleaseDistributable

dist-clean:
	./gradlew clean :desktopApp:createReleaseDistributable

.PHONY: flatpak-deps flatpak-build flatpak-install flatpak-bundle flatpak-run flatpak-run-installed flatpak-uninstall
flatpak-deps:
	flatpak install --user -y $(FLATPAK_REMOTE) $(FLATPAK_RUNTIME)//$(FLATPAK_RUNTIME_VERSION) $(FLATPAK_SDK)//$(FLATPAK_RUNTIME_VERSION)

flatpak-build:
	flatpak-builder --install-deps-from=$(FLATPAK_REMOTE) --disable-rofiles-fuse --force-clean $(FLATPAK_BUILD_DIR) $(FLATPAK_MANIFEST)

flatpak-install:
	flatpak-builder --install-deps-from=$(FLATPAK_REMOTE) --disable-rofiles-fuse --force-clean --user --install $(FLATPAK_BUILD_DIR) $(FLATPAK_MANIFEST)

flatpak-bundle:
	flatpak-builder --install-deps-from=$(FLATPAK_REMOTE) --disable-rofiles-fuse --force-clean --repo=$(FLATPAK_REPO_DIR) $(FLATPAK_BUILD_DIR) $(FLATPAK_MANIFEST)
	flatpak build-bundle $(FLATPAK_REPO_DIR) $(FLATPAK_APP_ID).flatpak $(FLATPAK_APP_ID)

flatpak-run:
	@if [ ! -d "$(FLATPAK_BUILD_DIR)" ]; then \
		$(MAKE) flatpak-build; \
	fi
	flatpak-builder --run $(FLATPAK_BUILD_DIR) $(FLATPAK_MANIFEST) $(FLATPAK_APP_ID)

flatpak-run-installed:
	flatpak run $(FLATPAK_APP_ID)

flatpak-uninstall:
	flatpak uninstall --user -y $(FLATPAK_APP_ID)

.PHONY: build-appimage
build-appimage:
	./scripts/build_appimage.sh

.PHONY: aur-install
aur-install:
	./aur/install-local.sh

.PHONY: android-debug android-debug-clean android-release android-release-clean
android-debug:
	./gradlew :androidApp:assembleDebug

android-debug-clean:
	./gradlew clean :androidApp:assembleDebug

android-release:
	./gradlew :androidApp:assembleRelease

android-release-clean:
	./gradlew clean :androidApp:assembleRelease

.PHONY: ios-project ios-build ios-ipa ios-sim-run ios-test ios-clean
ios-project:
	@echo "Checking Xcode project structure..."
	test -f iosApp/iosApp.xcodeproj/project.pbxproj && echo "✅ iosApp.xcodeproj is ready."

ios-build:
	./scripts/build_ios.sh --sim

ios-ipa:
	./scripts/build_ios.sh --ipa

ios-sim-run:
	./scripts/build_ios.sh --sim --run

ios-test:
	@echo "Testing iOS App Swift syntax integrity..."
	find iosApp -name "*.swift" -print0 | xargs -0 xcrun --sdk iphonesimulator swiftc -target arm64-apple-ios17.0-simulator -parse
	@echo "All iOS Swift files verified successfully."

ios-clean:
	./scripts/build_ios.sh --clean

.PHONY: test test-desktop lint format
test:
	./gradlew test

test-desktop:
	./gradlew :shared:desktopTest :desktopApp:test

lint:
	./gradlew ktlintCheck

format:
	./gradlew ktlintFormat

.PHONY: clean clean-all
clean:
	./gradlew clean

clean-all:
	./gradlew clean
	rm -rf build/ dist/ .flatpak-builder $(FLATPAK_APP_ID).flatpak iosApp/build
