# 01 — Overview & Architecture

## What this project is

A minimal Android app that performs **on-device image classification** through
two independent inference runtimes, built entirely with **Bazel**. It exists to
prove out a build/integration pattern:

> Take ML runtimes that normally live *inside* the huge mediapipe / LiteRT source
> repositories, pull them in as ordinary **third-party dependencies** that Bazel
> resolves, and build a **clean, decoupled** application that compiles and runs on
> an Android emulator and a phone.

Once that pattern is solid, the same skeleton is reused to host the **EUPE ViT**
model (and later to target Qualcomm SA8255 / CDC hardware).

## The two inference paths

The app deliberately includes two ways to run a model so you can see the full
spectrum, from "do everything yourself" to "let the framework do it":

| | **LiteRT path** | **MediaPipe path** |
|---|---|---|
| Class | [`LiteRtClassifier.java`](../app/src/main/java/com/example/sampleinf/LiteRtClassifier.java) | [`MediaPipeClassifier.java`](../app/src/main/java/com/example/sampleinf/MediaPipeClassifier.java) |
| API level | Low-level `org.tensorflow.lite.Interpreter` | High-level `ImageClassifier` (Tasks) |
| You write | resize, normalize, run, softmax, label lookup | almost nothing |
| Reads labels from | a separate `labels.txt` | the model's embedded metadata |
| Best for | **arbitrary models — including EUPE ViT** | models packaged with MediaPipe-style metadata |
| Runs on | LiteRT | MediaPipe Tasks (which itself runs on LiteRT) |

Because MediaPipe Tasks uses LiteRT internally, this single app genuinely
exercises **both** third-party dependencies.

## High-level architecture

```
        ┌─────────────────────────────────────────────────────────┐
        │                     MainActivity (UI)                     │
        │   loads image • taps run • shows results (off-UI thread)  │
        └───────────────┬───────────────────────┬──────────────────┘
                        │  InferenceBackend       │  InferenceBackend
                        ▼  (interface)            ▼  (interface)
        ┌───────────────────────────┐ ┌───────────────────────────┐
        │      LiteRtClassifier      │ │     MediaPipeClassifier     │
        │  ImageUtils → Interpreter  │ │     ImageClassifier         │
        └─────────────┬─────────────┘ └─────────────┬──────────────┘
                      │ depends on                    │ depends on
                      ▼                               ▼
        ┌───────────────────────────┐ ┌───────────────────────────┐
        │  //third_party/litert      │ │  //third_party/mediapipe    │
        │  (alias → @maven LiteRT)   │ │  (alias → @maven MediaPipe) │
        └─────────────┬─────────────┘ └─────────────┬──────────────┘
                      └───────────────┬───────────────┘
                                      ▼
                    rules_jvm_external resolves from Maven
                    (Google Maven + Maven Central), locked
                    into maven_install.json
```

The application code (`/app`) only ever names the **stable local labels**
`//third_party/litert` and `//third_party/mediapipe`. It never mentions a Maven
coordinate or an `.aar` path. That indirection is the "decoupling": how the
runtimes are obtained can change (Maven today, vendored `.aar` for air-gapped
builds tomorrow) without touching a line of app code.

## Data flow of one classification

1. `MainActivity` loads `sample.jpg` from assets into a `Bitmap`.
2. User taps a button → work is dispatched to a background thread.
3. The chosen backend turns the `Bitmap` into the exact tensor the model expects
   (the LiteRT path does this explicitly in `ImageUtils`; MediaPipe does it
   internally from metadata).
4. The model runs; raw scores come back.
5. Scores are turned into a ranked list of `(label, score)` and shown in the UI.

## Glossary

- **LiteRT** — "Lite Runtime", Google's on-device inference engine; the rebrand
  of **TensorFlow Lite**. The `.tflite` file format and the
  `org.tensorflow.lite.Interpreter` API are part of it.
- **MediaPipe / MediaPipe Tasks** — Google's framework of ready-made on-device ML
  solutions (vision, audio, text). "Tasks" is the modern high-level API.
- **`.tflite`** — a serialized model (a FlatBuffer). May also embed *metadata* and
  *associated files* (like a label list) appended as a ZIP.
- **Bazel** — Google's build system. Hermetic, reproducible, scales to huge repos.
- **Bzlmod** — Bazel's modern dependency system; configured via `MODULE.bazel`.
- **rules_android** — the external Bazel rule-set that provides `android_binary`
  etc. (Bazel removed its built-in Android rules in version 8.)
- **rules_jvm_external** — Bazel rule-set that resolves Java/Android dependencies
  (and their transitive deps) from Maven repositories.
- **AAR** — "Android ARchive", the packaging format for an Android library
  (compiled code + resources + native `.so` libraries).
- **ABI** — the CPU architecture of native code (e.g. `arm64-v8a` for phones,
  `x86_64` for the desktop emulator).
- **NDK** — the Android Native Development Kit (for compiling C/C++). **Not needed
  here** — we only consume prebuilt native libs inside the AARs.
- **ViT** — Vision Transformer, the model family the EUPE model belongs to.
- **CDC / SA8255** — the Qualcomm automotive Cockpit Domain Controller / SoC that
  is the ultimate deployment target.

Next: [02 — Setup on Linux](02_setup_linux.md).
