C++ implementation of Shazam's audio fingerprinting algorithm based on Vibra (https://github.com/BayernMuller/vibra)

## Building FFTW for Android

This library requires FFTW3 static libraries for each Android ABI. To build them:

1. Install Android NDK
2. Run the build script:
   ```bash
   cd third_party
   ANDROID_NDK_HOME=/path/to/ndk ./build-fftw-android.sh
   ```
3. The script will download, compile and install FFTW to `third_party/fftw-android/<abi>/`

## Pre-built FFTW

If you have pre-built FFTW libraries, place them in:
```
third_party/fftw-android/
├── arm64-v8a/
│   ├── include/
│   │   └── fftw3.h
│   └── lib/
│       └── libfftw3.a
├── armeabi-v7a/
│   ├── include/
│   │   └── fftw3.h
│   └── lib/
│       └── libfftw3.a
├── x86_64/
│   ├── include/
│   │   └── fftw3.h
│   └── lib/
│       └── libfftw3.a
└── x86/
    ├── include/
    │   └── fftw3.h
    └── lib/
        └── libfftw3.a
```