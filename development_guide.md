# Metrolist Dev Guide
This file outlines the process of setting up a local dev environment for Metrolist.

## Prerequisites
- Java JDK 21
- Android Studio (or VSCode with Kotlin extensions)
- Go 1.20+
- protoc
- protoc-gen-go

## Basic setup
```bash
git clone https://github.com/MetrolistGroup/Metrolist
cd Metrolist
git submodule update --init --recursive
cd app
bash generate_proto.sh
cd ..
[ ! -f "app/persistent-debug.keystore" ] && keytool -genkeypair -v -keystore app/persistent-debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" || echo "Keystore already exists."
./gradlew :app:assembleuniversalFossDebug
ls app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk
```
