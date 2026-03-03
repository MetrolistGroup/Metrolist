# Meowisai Dev Guide
This file outlines the process of setting up a local dev environment for Meowisai.

## Prerequisites
- Java JDK 21
- Android platform tools
- protobuf-compiler
- Android NDK

## Basic setup
```bash
git clone https://github.com/MeowisaiGroup/Meowisai
cd Meowisai
git submodule update --init --recursive
cd app
bash generate_proto.sh
cd ..
bash app/src/main/cpp/vibrafp/third_party/build-fftw-android.sh --ndk ~/Android/Sdk/ndk/27.0.12077973 --out app/src/main/cpp/vibrafp/third_party/fftw-android
[ ! -f "app/persistent-debug.keystore" ] && keytool -genkeypair -v -keystore app/persistent-debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" || echo "Keystore already exists."
./gradlew :app:assembleuniversalFossDebug
ls app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk
```
