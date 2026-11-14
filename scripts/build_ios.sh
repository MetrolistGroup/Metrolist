#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"

dist_dir="$root_dir/dist"
build_dir="$root_dir/iosApp/build"
derived_data_dir="$root_dir/iosApp/DerivedData"
scheme="iosApp"
project_path="$root_dir/iosApp/iosApp.xcodeproj"
bundle_id="com.metrolist.MetrolistApp"

mode="sim"
configuration="Debug"
run_after_build=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ipa|--release)
      mode="ipa"
      configuration="Release"
      shift
      ;;
    --device)
      mode="device"
      configuration="Debug"
      shift
      ;;
    --sim|--simulator)
      mode="sim"
      configuration="Debug"
      shift
      ;;
    --run)
      run_after_build=true
      shift
      ;;
    --clean)
      echo "Cleaning iOS build artifacts..."
      rm -rf "$build_dir" "$derived_data_dir" "$dist_dir/Metrolist.ipa" "$dist_dir/Metrolist.app"
      echo "Clean finished."
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      echo "Usage: $0 [--sim|--ipa|--device] [--run] [--clean]"
      exit 1
      ;;
  esac
done

mkdir -p "$dist_dir"

if ! command -v xcodebuild &>/dev/null; then
  echo "Error: xcodebuild is not available on this environment (requires macOS)." >&2
  exit 1
fi

marketing_version="$(bash "$root_dir/scripts/ios_version.sh")"
build_version="$marketing_version"

echo "=================================================="
echo " Building Metrolist iOS ($configuration - Mode: $mode)"
echo "=================================================="

if [[ "$mode" == "ipa" ]]; then
  echo "Step 1: Preparing app bundle for IPA packaging..."
  archive_path="$build_dir/Metrolist.xcarchive"
  rm -rf "$archive_path"
  
  xcodebuild archive \
    -project "$project_path" \
    -scheme "$scheme" \
    -configuration "$configuration" \
    -destination 'generic/platform=iOS' \
    -archivePath "$archive_path" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY="" \
    AD_HOC_CODE_SIGNING_ALLOWED=YES \
    ENABLE_DEBUG_DYLIB=NO \
    ARCHS=arm64 \
    COMPILER_INDEX_STORE_ENABLE=NO \
    MARKETING_VERSION="$marketing_version" \
    CURRENT_PROJECT_VERSION="$build_version"
  app_bundle=$(find "$archive_path/Products/Applications" -name "*.app" -type d | head -n 1)

  if [[ -z "$app_bundle" || ! -d "$app_bundle" ]]; then
    echo "Error: Could not find built .app bundle to package IPA."
    exit 1
  fi

  app_info="$app_bundle/Info.plist"
  executable_name="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app_info")"
  executable="$app_bundle/$executable_name"
  [[ "$(/usr/libexec/PlistBuddy -c 'Print :DTPlatformName' "$app_info")" == "iphoneos" ]]
  [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app_info")" == "$bundle_id" ]]
  [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app_info")" == "$marketing_version" ]]
  [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$app_info")" == "$build_version" ]]
  [[ "$(/usr/libexec/PlistBuddy -c 'Print :CADisableMinimumFrameDurationOnPhone' "$app_info")" == "true" ]]
  lipo "$executable" -verify_arch arm64
  xcrun vtool -show-build "$executable" | grep -Eq 'platform[[:space:]]+IOS$'

  echo "Step 2: Creating unsigned IPA package in dist/..."
  payload_dir="$build_dir/Payload"
  rm -rf "$payload_dir" "$dist_dir/Metrolist.ipa"
  mkdir -p "$payload_dir"
  cp -R "$app_bundle" "$payload_dir/"
  
  (cd "$build_dir" && zip -qr "$dist_dir/Metrolist.ipa" Payload)
  unzip -tq "$dist_dir/Metrolist.ipa"
  echo "IPA package created: $dist_dir/Metrolist.ipa"
  echo "You can install this IPA using AltStore, SideStore, TrollStore, or Sideloadly."

elif [[ "$mode" == "sim" ]]; then
  echo "Step 1: Building for iOS Simulator..."
  xcodebuild build \
    -project "$project_path" \
    -scheme "$scheme" \
    -configuration "$configuration" \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$derived_data_dir" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGN_IDENTITY="" \
    MARKETING_VERSION="$marketing_version" \
    CURRENT_PROJECT_VERSION="$build_version"

  app_path=$(find "$derived_data_dir/Build/Products/$configuration-iphonesimulator" -name "*.app" -type d | head -n 1)
  if [[ -n "$app_path" && -d "$app_path" ]]; then
    rm -rf "$dist_dir/Metrolist.app"
    cp -R "$app_path" "$dist_dir/Metrolist.app"
    echo "Simulator App bundle created: $dist_dir/Metrolist.app"
  else
    echo "Error: Could not find built app bundle in derived data."
    exit 1
  fi

  if [[ "$run_after_build" == true ]]; then
    echo "Step 2: Deploying and launching on booted iOS Simulator..."
    if xcrun simctl list devices | grep -q "Booted"; then
      xcrun simctl install booted "$dist_dir/Metrolist.app"
      xcrun simctl launch booted "$bundle_id"
      echo "App launched on iOS Simulator."
    else
      echo "No booted simulator found. Boot a simulator with: xcrun simctl boot 'iPhone 15 Pro'"
    fi
  fi

elif [[ "$mode" == "device" ]]; then
  echo "Step 1: Building for physical device..."
  xcodebuild build \
    -project "$project_path" \
    -scheme "$scheme" \
    -configuration "$configuration" \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "$derived_data_dir" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGN_IDENTITY="" \
    MARKETING_VERSION="$marketing_version" \
    CURRENT_PROJECT_VERSION="$build_version"

  echo "Device build complete. Use --ipa for an installable package."
fi
