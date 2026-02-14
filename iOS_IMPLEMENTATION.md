# Metrolist iOS Support

This documentation explains the iOS implementation in the Metrolist project.

## Architecture Overview

Metrolist now uses **Kotlin Multiplatform (KMP)** to share code between Android and iOS:

```
project/
├── shared/               # KMP module (Android + iOS)
│   ├── commonMain/       # Shared business logic
│   ├── androidMain/      # Android-specific implementations
│   └── iosMain/          # iOS-specific implementations
├── app/                  # Android application
└── iosApp/               # iOS application wrapper
```

## Shared Module

The `shared` module contains all business logic and is compiled to:
- **Android**: JVM bytecode (used as a regular Gradle dependency)
- **iOS**: XCFramework (linked in Xcode project)

### Database - SQLDelight

Migrated from Room (Android-only) to SQLDelight (multiplatform):

**Schema**: `shared/src/commonMain/sqldelight/com/metrolist/shared/db/MetrolistDatabase.sq`

Contains all tables:
- song, artist, album, playlist
- Relationship tables (song_artist_map, song_album_map, etc.)
- Utility tables (search_history, lyrics, format, etc.)

**Platform Drivers**:
- **Android**: `AndroidSqliteDriver` (in `androidMain`)
- **iOS**: `NativeSqliteDriver` (in `iosMain`)

### Dependency Injection - Koin

Migrated from Hilt (Android-only) to Koin (multiplatform):

**Common Module**: Database and repository setup
**Platform Modules**: Platform-specific dependencies (Context on Android, etc.)

### Music Player

Abstraction layer for audio playback:

**Interface**: `MusicPlayer` in `commonMain`

**Implementations**:
- **Android**: `MusicPlayerImpl` using ExoPlayer
- **iOS**: `MusicPlayerImpl` using AVPlayer

Features:
- Play/pause/stop
- Seek, next, previous
- Repeat and shuffle modes
- State management with Kotlin Flow

### Repository Layer

`MusicRepository` provides data access:
- Song CRUD operations  
- Playlist management
- Artist and album queries
- Search functionality

### UI - Compose Multiplatform

Shared UI code using Compose Multiplatform:

**`MetrolistApp`**: Main app composable
- Material 3 design
- Song list with search
- Player controls

## iOS App

Location: `iosApp/`

**Structure**:
- `iOSApp.swift`: Main SwiftUI app entry point
- Links to `shared.framework` from KMP build

**How it works**:
1. SwiftUI creates a `UIViewController` from the shared module
2. The shared module provides a Compose Multiplatform UI
3. All business logic runs in the shared Kotlin code

## Building

### Android
```bash
./gradlew :app:assembleDebug
```

### iOS

1. Build the shared framework:
```bash
./gradlew :shared:assembleSharedReleaseXCFramework
```

2. Open `iosApp/iosApp.xcodeproj` in Xcode

3. Link the framework from:
   `shared/build/XCFrameworks/release/shared.xcframework`

4. Build and run on simulator or device

## Current Status

- ✅ Database migration to SQLDelight
- ✅ Dependency injection with Koin
- ✅ Music player abstraction
- ✅ Repository layer
- ✅ Basic Compose Multiplatform UI
- ✅ iOS app wrapper

**Note**: This is a foundation for iOS support. Many Android-specific features still need migration:
- Complete UI migration from Android Views/Compose
- Download management
- Background playback/services
- Notifications
- Widget support (platform-specific)

## Development Notes

### Adding New Features

When adding features:

1. **Shared logic**: Add to `shared/src/commonMain`
2. **Platform-specific**: Add to `androidMain` or `iosMain`
3. **Database changes**: Update SQLDelight schema
4. **DI setup**: Add to Koin modules

### Testing

- Android: Standard Android testing
- iOS: Test on simulator and device
- Shared code: Can write common tests in `commonTest`

### Dependencies

Key multiplatform libraries:
- **SQLDelight**: Database
- **Koin**: Dependency injection
- **Compose Multiplatform**: UI framework
- **Ktor**: Networking (already KMP)
- **Kotlinx Serialization**: JSON (already KMP)

### Known Limitations

1. **No background audio on iOS yet** - needs implementation
2. **No iOS notifications** - needs implementation  
3. **Limited UI** - only basic screens migrated
4. **Room database not migrated** - Android still uses Room, shared uses SQLDelight (parallel)
5. **Download management** - Android-specific, needs iOS implementation
