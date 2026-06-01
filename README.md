# Sample Android Inference App (LiteRT + MediaPipe, built with Bazel)

A small, **self-contained** Android app that runs on-device image classification
using two inference runtimes — **LiteRT** (the low-level `Interpreter`, the
successor to TensorFlow Lite) and **MediaPipe Tasks** (the high-level vision API)
— wired up with the **Bazel** build system.

The point of this project is the *plumbing*, not the model: it demonstrates that
you can take inference runtimes that normally live inside Google's giant
mediapipe / LiteRT source trees, **decouple** them, pull them in as ordinary
third-party dependencies resolved by Bazel, and build a clean app that compiles
and runs on an Android emulator or phone.

It is the stepping stone to the real goal: replacing the sample model with the
**EUPE ViT** model and, eventually, targeting Qualcomm SA8255 (CDC) hardware.
The LiteRT path here is written to be "ViT-ready" so that swap is small.

---

## What's in the box

```
sample_android_inf/
├── MODULE.bazel              # Bazel dependencies (LiteRT, MediaPipe) — Bzlmod
├── .bazelrc / .bazelversion  # Bazel flags + pinned Bazel version (8.7.0)
├── .gitattributes            # Git LFS tracking for the vendored EUPE checkpoint
├── BUILD.bazel               # repo-root Bazel package
├── third_party/              # the decoupled runtimes, each in its own folder
│   ├── litert/               #   -> //third_party/litert
│   └── mediapipe/            #   -> //third_party/mediapipe
├── app/                      # the Android application
│   ├── BUILD.bazel           #   android_binary target  (build //app:app)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/sampleinf/   # heavily-commented Java sources
│       ├── res/              # layout, strings, theme (framework widgets, no AndroidX UI)
│       └── assets/           # model + labels + image (downloaded, not in git)
├── models/
│   ├── README.md             # model notes
│   └── eupe/EUPE-ViT-T.pt    # EUPE ViT-T checkpoint — vendored via Git LFS
├── tools/
│   ├── download_model.sh          # fetch the SAMPLE model (EfficientNet) + labels + image
│   ├── prepare_eupe_model.sh      # one command: convert the vendored EUPE .pt -> .tflite
│   └── convert_eupe_to_tflite.py  # PyTorch -> LiteRT conversion engine (litert-torch)
└── docs/                     # ← full step-by-step documentation (start here)
```

## Quick start (TL;DR)

On a machine that already has the Android SDK (e.g. via Android Studio):

```bash
# 1. Get the code (git lfs first, so the vendored EUPE weights come down too)
git lfs install
git clone <your-repo-url> sample_android_inf
cd sample_android_inf

# 2. Install Bazelisk AS `bazel` so the pinned Bazel 8.7.0 (.bazelversion) is used.
#    A system/apt `bazel` will NOT work (it ignores .bazelversion and is too old
#    to recognize MODULE.bazel as the workspace root). Details: docs/02_setup_linux.md
sudo curl -fL -o /usr/local/bin/bazel \
  https://github.com/bazelbuild/bazelisk/releases/latest/download/bazelisk-linux-amd64
sudo chmod +x /usr/local/bin/bazel
hash -r && bazel version            # must print 8.7.0 (not an older version)

# Point Bazel at the Android SDK that Android Studio installed:
export ANDROID_HOME="$HOME/Android/Sdk"

# 3. Fetch the SAMPLE model (EfficientNet) + labels + image into app assets.
#    NOTE: this is the demo model, NOT EUPE. (EUPE is a separate step — see below.)
./tools/download_model.sh

# 4. Lock the dependency graph (first build only)
bazel run @maven//:pin

# 5. Build the APK
bazel build //app:app

# 6. Install on a running emulator or a plugged-in phone
adb install -r bazel-bin/app/app.apk
```

Then open **Sample Inference** on the device and tap **Run LiteRT** or
**Run MediaPipe**.

## Running the EUPE ViT model

The Quick start above runs the **sample** app, whose model is **EfficientNet**
(fetched by `download_model.sh`). That first milestone just proves the
Bazel + LiteRT/MediaPipe toolchain builds and runs end to end. EUPE is the
**next** step and works differently in two ways:

1. **You don't download the EUPE model — it's already in the repo.** The
   checkpoint is vendored via Git LFS at `models/eupe/EUPE-ViT-T.pt`. But it is a
   **PyTorch `.pt`**, and LiteRT can't run that directly — it must be **converted**
   to `.tflite` once (offline, using `litert-torch`):

   ```bash
   ./tools/prepare_eupe_model.sh --venv
   # uses the vendored .pt  ->  app/src/main/assets/eupe_vit_t.tflite
   ```

   So: `download_model.sh` = download the *sample* model; `prepare_eupe_model.sh`
   = *convert* the already-present EUPE model. Different models, different steps.

2. **EUPE is an encoder** (it outputs a feature embedding, not class labels), so
   its on-device backend is wired into the app **after** the sample build is
   verified working.

Full walkthrough: [docs/06_porting_eupe_vit.md](docs/06_porting_eupe_vit.md).

## Full documentation

Read these in order — they are written to take a brand-new machine from nothing
to a running app, and to explain every moving part.

| Doc | What it covers |
| --- | --- |
| [docs/01_overview.md](docs/01_overview.md)            | Architecture, glossary, how the pieces fit |
| [docs/02_setup_linux.md](docs/02_setup_linux.md)      | **Primary**: full toolchain setup on Linux + Android Studio |
| [docs/03_setup_macos.md](docs/03_setup_macos.md)      | macOS notes (incl. Apple-Silicon caveats) |
| [docs/04_build_and_run.md](docs/04_build_and_run.md)  | Clone → build → install → run, in detail |
| [docs/05_how_it_works.md](docs/05_how_it_works.md)    | Component-by-component walkthrough + data flow |
| [docs/06_porting_eupe_vit.md](docs/06_porting_eupe_vit.md) | Swapping the sample model for EUPE ViT |
| [docs/07_dependency_management.md](docs/07_dependency_management.md) | How Bazel resolves deps; versions; offline vendoring |
| [docs/08_troubleshooting.md](docs/08_troubleshooting.md) | Common errors and fixes |

## Pinned versions (June 2026)

| Component         | Version | Why pinned |
| ----------------- | ------- | ---------- |
| Bazel             | 8.7.0   | LTS; compatible with rules_android 0.7.2 |
| rules_android     | 0.7.2   | Builds the APK (Bazel's built-in Android rules were removed in Bazel 8) |
| rules_jvm_external| 7.0     | Resolves Maven deps + transitive graph |
| LiteRT            | 1.0.1   | **1.x only** — 2.x removed the `Interpreter` API |
| MediaPipe tasks-vision | 0.10.29 | Vision tasks (ImageClassifier) |

See [docs/07_dependency_management.md](docs/07_dependency_management.md) to verify
or bump these.
