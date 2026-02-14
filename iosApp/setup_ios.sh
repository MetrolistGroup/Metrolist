#!/bin/bash

# Setup script for iOS app
echo "Setting up iOS app..."

# Create Xcode project structure
mkdir -p iosApp/iosApp.xcodeproj

# Build shared framework
echo "Building shared framework..."
cd ..
./gradlew :shared:assembleSharedReleaseXCFramework

echo "iOS setup complete!"
echo ""
echo "Next steps:"
echo "1. Open iosApp folder in Xcode"
echo "2. Create new iOS App project if needed"
echo "3. Link the shared.framework from shared/build/XCFrameworks/release/"
echo "4. Build and run on simulator or device"
