# 04 — Build & Run (clone → build → install → run)

This is the end-to-end procedure to get the app running on a fresh machine,
assuming you've done the toolchain setup ([02 Linux](02_setup_linux.md) /
[03 macOS](03_setup_macos.md)).

---

## Step 1 — Get the code from git

```bash
git lfs install                              # so Git LFS files (EUPE weights) download
git clone <your-repo-url> sample_android_inf
cd sample_android_inf
```

> The **sample** model (EfficientNet) is not stored in git — you'll fetch it in
> Step 3. The **EUPE ViT** checkpoint *is* vendored via Git LFS at
> `models/eupe/EUPE-ViT-T.pt`, which is why `git lfs install` above matters (only
> needed for the EUPE step; the sample build works without it). Everything else
> (all source, Bazel config, docs) is in the repo.

## Step 2 — Confirm the environment

```bash
bazel version          # -> 8.7.0
echo "$ANDROID_HOME"   # -> path to your Android SDK (must be set!)
adb devices            # -> at least one device/emulator "device"
```

If `ANDROID_HOME` is empty, the build will fail to find the SDK. Re-do Step 2 of
the setup doc.

## Step 3 — Download the model, labels, and sample image

```bash
./tools/download_model.sh
```

This populates `app/src/main/assets/` with:
- `efficientnet_lite0.tflite` (~18 MB) — the model
- `labels.txt` (1000 ImageNet classes, extracted from the model metadata)
- `sample.jpg` — a test image

You only need to run it once (re-run to refresh). See
[07 — Dependency Management](07_dependency_management.md) and
[06 — Porting EUPE ViT](06_porting_eupe_vit.md) for using a different model.

## Step 4 — Lock the dependency graph (first build only)

```bash
bazel run @maven//:pin
```

This resolves every Maven dependency (LiteRT, MediaPipe, and their transitive
deps) and writes the verified, fully-pinned graph to `maven_install.json`, which
**should be committed to git**. After it exists, builds are reproducible and don't
re-resolve from the network. You only re-run `pin` when you change the dependency
list in [`MODULE.bazel`](../MODULE.bazel). (If you skip this step, the build still
works but does a live resolution each time and prints a reminder to pin.)

## Step 5 — Build the APK

Pick the form that matches your device's CPU (ABI):

```bash
# Default: a "fat" APK that runs on BOTH arm64 phones and the x86_64 emulator
bazel build //app:app

# Smaller, single-ABI alternatives (optional):
bazel build //app:app --config=x86_64    # standard desktop emulator only
bazel build //app:app --config=arm64     # physical phones / Apple-Silicon emulator only
```

On success the APK is at:

```
bazel-bin/app/app.apk
```

The very first build downloads Bazel rule-sets, a hermetic JDK 17, the Android
build tools, and the Maven deps — it can take several minutes. Later builds are
incremental and fast.

## Step 6 — Install on the device/emulator

```bash
adb install -r bazel-bin/app/app.apk
```

`-r` reinstalls over a previous copy. If you have multiple devices connected, add
`-s <serial>` (get serials from `adb devices`).

## Step 7 — Launch & use

Either tap **Sample Inference** in the device's app launcher, or start it from the
command line:

```bash
adb shell am start -n com.example.sampleinf/.MainActivity
```

In the app:
- The bundled image (Grace Hopper) is shown at the top.
- Tap **Run LiteRT (Interpreter)** — the low-level path. You'll see the top-3
  classes and the inference time in ms.
- Tap **Run MediaPipe (Tasks)** — the high-level path. Results should be similar.

> Expected top classes for the sample image include things like "military
> uniform", "bow tie", "suit". Exact scores differ slightly between the two paths
> because the LiteRT path uses our hand-written preprocessing while MediaPipe uses
> the model's metadata — that's normal and instructive.

## Step 8 — Iterate

After editing code, just rebuild + reinstall:

```bash
bazel build //app:app && adb install -r bazel-bin/app/app.apk
```

## Common one-liners

```bash
# Read the app's logs (handy for the "model not found" / error messages):
adb logcat | grep -i sampleinf

# Uninstall:
adb uninstall com.example.sampleinf

# What got built / where:
bazel cquery --output=files //app:app
```

If anything fails, see [08 — Troubleshooting](08_troubleshooting.md).
Next, to understand what each file does: [05 — How It Works](05_how_it_works.md).
