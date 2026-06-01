# 03 — Setup on macOS (secondary path)

macOS works fine for this project — we consume **prebuilt** AARs, so you avoid the
painful part of macOS/Apple-Silicon Android work (compiling TensorFlow/MediaPipe
from source). The steps mirror [02 — Setup on Linux](02_setup_linux.md); only the
install commands differ.

> Apple-Silicon note: because nothing is compiled from source here, an M-series
> Mac is fine. Just make sure the emulator uses an **arm64** system image (the
> default on Apple Silicon), and build with `--config=arm64` so the APK contains
> the matching ABI (see [04 — Build & Run](04_build_and_run.md)).

---

## Step 0 — Homebrew

Install [Homebrew](https://brew.sh) if you don't have it, then:

```bash
brew install git curl
```

## Step 1 — Bazelisk

```bash
brew install bazelisk      # provides the `bazel` command, reads .bazelversion
bazel version              # downloads + prints Bazel 8.7.0
```

## Step 2 — Android SDK

If you have **Android Studio**, the SDK is at `~/Library/Android/sdk`. Otherwise
install just the command-line tools with `brew install --cask android-commandlinetools`.

Add to `~/.zshrc`:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Reload: `source ~/.zshrc`.

## Step 3 — SDK components

```bash
sdkmanager \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "emulator" \
  "system-images;android-34;google_apis;arm64-v8a"     # arm64 image for Apple Silicon

yes | sdkmanager --licenses
```

(On an Intel Mac, use the `x86_64` system image instead.)

## Step 4 — Device

```bash
# Emulator (Apple Silicon → arm64 image):
avdmanager create avd -n pixel_api34 \
  -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_6
emulator -avd pixel_api34

# In another terminal:
adb devices
```

Or use a physical phone (enable USB debugging — see doc 02, Step 4 Option B).

## Step 5 — Verify

```bash
bazel version
echo "$ANDROID_HOME"
adb devices
```

➡ Continue to [04 — Build & Run](04_build_and_run.md). On Apple Silicon, build
with `--config=arm64` so the APK's ABI matches the arm64 emulator/phone.
