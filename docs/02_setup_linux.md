# 02 — Setup on Linux (primary path)

This is the recommended environment. Linux is the smoothest platform for Bazel +
Android, and (per your setup) the build machine already has **Android Studio**
installed, which means the Android SDK is mostly in place already.

Commands below assume **Debian/Ubuntu** (`apt`). Adjust the package manager for
other distros. Everything is copy-pasteable.

> **You do NOT need to install a JDK or the Android NDK.**
> Bazel ships its own JDK to run, and our [`.bazelrc`](../.bazelrc) downloads a
> hermetic JDK 17 to *compile* the app. The NDK isn't needed because we only
> consume prebuilt native libraries that already live inside the LiteRT/MediaPipe
> AARs.

---

## Step 0 — Basic command-line tools

```bash
sudo apt-get update
sudo apt-get install -y git curl unzip
```

## Step 1 — Install Bazelisk (the Bazel launcher)

`bazelisk` is the recommended way to run Bazel: it reads
[`.bazelversion`](../.bazelversion) and automatically downloads the exact Bazel
version this project needs (**8.7.0**). Install it as `bazel`:

```bash
sudo curl -fL -o /usr/local/bin/bazel \
  https://github.com/bazelbuild/bazelisk/releases/latest/download/bazelisk-linux-amd64
sudo chmod +x /usr/local/bin/bazel

bazel version    # first run downloads Bazel 8.7.0; prints the version
```

## Step 2 — Locate the Android SDK installed by Android Studio

Android Studio installs the SDK at `~/Android/Sdk` by default. Confirm:

```bash
ls "$HOME/Android/Sdk"     # you should see directories like platform-tools, platforms, ...
```

If yours is elsewhere, find it in Android Studio under
**Settings → Languages & Frameworks → Android SDK → "Android SDK Location"**.

Export `ANDROID_HOME` so Bazel (and the SDK tools) can find it, and add the tools
to your `PATH`. Put these lines in `~/.bashrc` (or `~/.zshrc`) so they persist:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Then reload: `source ~/.bashrc`.

> Bazel's Android rules read `ANDROID_HOME` to find the SDK, then auto-pick the
> **highest installed platform** as the compile SDK and the **latest installed
> build-tools**. So the next step just makes sure suitable versions exist.

## Step 3 — Install the required SDK components

Use the `sdkmanager` CLI (installed with Android Studio's "command-line tools").
If `sdkmanager` isn't found, install it from Android Studio:
**Settings → Android SDK → SDK Tools → check "Android SDK Command-line Tools"**.

```bash
sdkmanager \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "emulator" \
  "system-images;android-34;google_apis;x86_64"
```

What each is for:
- **platform-tools** — provides `adb` (install/launch apps on devices).
- **platforms;android-34** — the API 34 SDK used to *compile* (matches the app's
  `targetSdkVersion`). API 35/36 also fine; Bazel uses the highest installed.
- **build-tools;34.0.0** — `aapt2`, `d8`, etc. used while packaging the APK.
- **emulator** + **system-images;…;x86_64** — a virtual phone to run on. The
  `x86_64` image matches our default APK (which includes the `x86_64` ABI).

Accept the licenses (required, one-time):

```bash
yes | sdkmanager --licenses
```

## Step 4 — Get a device to run on

Pick **one** of:

### Option A — Android emulator (no hardware needed)

```bash
# Create a virtual device once:
avdmanager create avd -n pixel_api34 \
  -k "system-images;android-34;google_apis;x86_64" -d pixel_6

# Start it (leave this running in its own terminal):
emulator -avd pixel_api34
```

In another terminal, confirm it's connected:

```bash
adb devices         # should list "emulator-5554   device"
```

### Option B — A physical Qualcomm phone (recommended sanity check for SA8255 work)

1. On the phone: **Settings → About phone → tap "Build number" 7×** to unlock
   Developer options.
2. **Settings → Developer options → enable "USB debugging"**.
3. Plug in via USB and accept the "Allow USB debugging?" prompt.
4. Confirm:
   ```bash
   adb devices       # should list your phone's serial + "device"
   ```

Physical phones are `arm64-v8a`, which our default APK also includes.

## Step 5 — Verify the toolchain

```bash
bazel version                 # Bazel 8.7.0
echo "$ANDROID_HOME"          # /home/you/Android/Sdk
adb devices                   # at least one "device" listed
sdkmanager --list_installed   # shows platform-tools, platforms;android-34, build-tools, ...
```

If all four look right, you're ready to build:
➡ [04 — Build & Run](04_build_and_run.md).

(macOS users: see [03 — Setup on macOS](03_setup_macos.md) first.)
