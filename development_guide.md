# Metrolist Dev Guide

This file outlines the process of setting up a local dev environment for Metrolist.

## Prerequisites

- JDK 21
- Android platform tools (if you don't have a keystore already)
- protobuf-compiler v3.21 or newer

## Basic setup

This has been tested on Linux, but should work on other platforms with some adjustments.

```bash
git clone https://github.com/MetrolistGroup/Metrolist
cd Metrolist
git submodule update --init --recursive
cd app
bash generate_proto.sh
cd ..
[ ! -f "app/persistent-debug.keystore" ] && keytool -genkeypair -v -keystore app/persistent-debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" || echo "Keystore already exists."
./gradlew :app:assembleFossDebug
ls app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

### GitHub Secrets Configuration

This project uses GitHub Secrets to securely store API keys for building releases. To set up the secrets:

1. Go to your GitHub repository settings
2. Navigate to **Settings** → **Secrets and variables** → **Actions**
3. Add the following repository secrets:
   - `LASTFM_API_KEY`: Your LastFM API key
   - `LASTFM_SECRET`: Your LastFM secret key

4. Get your LastFM API credentials from: https://www.last.fm/api/account/create

**Note:** These secrets are automatically injected into the build process via GitHub Actions and are not visible in the source code.
