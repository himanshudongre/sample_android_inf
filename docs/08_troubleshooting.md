# 08 — Troubleshooting

Symptoms → causes → fixes for the issues you're most likely to hit on a first
build. Run with `--verbose_failures` (already on by default in `.bazelrc`) for
fuller errors.

---

## Toolchain / SDK

### "Either ANDROID_HOME or the path attribute must be set" / SDK not found
`$ANDROID_HOME` isn't set (or points to the wrong place).
```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # Linux (Android Studio default)
# export ANDROID_HOME="$HOME/Library/Android/sdk"  # macOS
echo "$ANDROID_HOME" && ls "$ANDROID_HOME"
```
Add it to `~/.bashrc`/`~/.zshrc` so it persists. See [doc 02](02_setup_linux.md).

### "Failed to find target platform" / no `platforms;android-XX`
No SDK platform (or one older than the app's `targetSdkVersion 34`) is installed.
```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

### "You have not accepted the license agreements"
```bash
yes | sdkmanager --licenses
```

### `bazel: command not found` / wrong Bazel version
Install Bazelisk as `bazel` (it reads `.bazelversion` → 8.7.0). See doc 02 Step 1.
Check with `bazel version`.

---

## Build

### Maven resolution fails / "Could not find artifact …"
A pinned version may no longer exist, or the network/proxy is blocking Maven.
- Verify versions ([doc 07](07_dependency_management.md) → "Verifying the latest
  available versions") and update them in [`MODULE.bazel`](../MODULE.bazel).
- Re-pin: `bazel run @maven//:pin`.
- Behind a corporate proxy? Set `HTTPS_PROXY`/`HTTP_PROXY`, or add an internal
  mirror to the `repositories` list.

### "maven_install.json … out of date" / repin required
You changed the `artifacts` list. Regenerate the lock file:
```bash
bazel run @maven//:pin
```

### `org.tensorflow.lite.Interpreter` cannot be resolved (compile error)
You're on **LiteRT 2.x**, which removed that class. Pin a **1.x** version in
`MODULE.bazel` and re-pin. See [doc 07](07_dependency_management.md) → "LiteRT 1.x
vs 2.x".

### Android toolchain / platform resolution errors from rules_android
Some rules_android + Bazel combinations need explicit Android platform flags. Try:
```bash
bazel build //app:app --android_platforms=@rules_android//:x86_64   # match your ABI
```
If you see errors mentioning AAR import + platforms, confirm you're on Bazel
**8.7.0** (`.bazelversion`) and rules_android **0.7.2** (`MODULE.bazel`), the
combination this project is verified against.

### First build is very slow / seems stuck
Normal: the first build downloads Bazel, a JDK, Android tools, and all Maven deps.
Subsequent builds are incremental. To watch progress: `bazel build //app:app
--show_progress_rate_limit=0`.

### Out of memory during build
```bash
bazel build //app:app --local_ram_resources=HOST_RAM*.5
```
or raise limits in a `user.bazelrc` (git-ignored).

---

## Install / run

### `adb: no devices/emulators found`
Start an emulator (`emulator -avd pixel_api34`) or plug in a phone with USB
debugging on. Confirm with `adb devices`. See [doc 02](02_setup_linux.md) Step 4.

### `INSTALL_FAILED_NO_MATCHING_ABIS`
The APK doesn't contain the device's CPU ABI. This is the most common install
error. Rebuild matching the target:
```bash
# x86_64 emulator:
bazel build //app:app --config=x86_64
# arm64 phone or Apple-Silicon emulator:
bazel build //app:app --config=arm64
```
The default fat APK (`bazel build //app:app`, no `--config`) includes both and
usually "just works".

### App installs but immediately shows "ERROR … model is missing"
You haven't downloaded the model into assets. From the repo root:
```bash
./tools/download_model.sh
bazel build //app:app && adb install -r bazel-bin/app/app.apk
```

### Results look like nonsense (LiteRT path)
The model runs but preprocessing doesn't match its training recipe. Re-check
`NORM_MEAN`/`NORM_STD`, `LAYOUT` (NHWC vs NCHW), and input size in
[`LiteRtClassifier.java`](../app/src/main/java/com/example/sampleinf/LiteRtClassifier.java).
Especially relevant when porting EUPE ViT — see [doc 06](06_porting_eupe_vit.md).

### MediaPipe path errors but LiteRT works
MediaPipe Tasks needs a model with MediaPipe-style **metadata**. The sample model
has it; a custom/ViT model usually won't. That's expected — the EUPE port uses the
LiteRT path. See [doc 06](06_porting_eupe_vit.md).

### See what's happening at runtime
```bash
adb logcat | grep -iE "sampleinf|AndroidRuntime|tflite|mediapipe"
```

---

## Still stuck?

Capture the full failing command output and check it against the pinned versions
table in the [README](../README.md). The combination Bazel 8.7.0 +
rules_android 0.7.2 + rules_jvm_external 7.0 + LiteRT 1.0.1 + tasks-vision
0.10.29 is the configuration this project targets.
