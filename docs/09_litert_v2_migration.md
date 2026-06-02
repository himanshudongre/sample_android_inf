# 09 — Migration to LiteRT 2.x (CompiledModel · C++ · from source · x86 + Android)

Status: **in progress** (started 2026-06). This document is the plan of record for
re-architecting the inference layer in response to review feedback.

## Why (review feedback)

1. **x86 + Android flexibility** — the prebuilt Android AAR only ships Android
   native libs. We want the same inference engine to run on an **x86_64 Linux
   host** (dev/CI) and on **Android** (arm64 device → SA8255, plus x86_64 emulator).
2. **Build the runtime from source** — instead of consuming a prebuilt LiteRT.
3. **LiteRT 2.x CompiledModel API** — replace the LiteRT **1.x Interpreter** API
   with the **2.x CompiledModel** API.

Plus a scope decision: **drop MediaPipe; LiteRT 2.x only.** The EUPE pipeline
(and the upcoming client action-recognition pipeline) is custom pre/post-
processing around an encoder — best expressed as explicit C++ around LiteRT, not
a MediaPipe graph. A clean interface seam is kept so a MediaPipe path could be
added later if the client's deliverable requires it.

## Target architecture

```
                 ┌──────────────────────────────────────────────┐
                 │  C++ inference core  (core/)                   │
                 │  - LiteRT 2.x CompiledModel C++ API            │
                 │  - EUPE preprocessing (resize, [0,1],          │
                 │    per-channel ImageNet, NCHW)                 │
                 │  - postprocessing (embedding / later: temporal │
                 │    aggregation for action recognition)         │
                 └───────────────┬───────────────┬───────────────┘
        links LiteRT (per target)│               │
                 ┌───────────────▼──────┐  ┌──────▼──────────────┐
                 │ host CLI (host/)      │  │ Android app (app/)  │
                 │ x86_64 Linux binary   │  │ JNI .so + thin UI   │
                 │ proves x86 + parity   │  │ (Kotlin/Java)       │
                 └───────────────────────┘  └─────────────────────┘
```

- **One C++ core, two front-ends.** The same `core/` compiles into (a) a Linux
  x86_64 CLI and (b) an Android JNI `.so`. This is what delivers "x86 + Android."
- **LiteRT consumed as built-from-source artifacts.** Per the chosen strategy
  ("build to an artifact, then consume it"), we build LiteRT once from source for
  each target and vendor the headers + per-ABI shared libs under
  `third_party/litert/`. Our `cc_library` links them. (We are *not* compiling
  LiteRT inside the app's Bazel graph.)
- **NDK is now required** (it was deliberately avoided in the prebuilt-AAR
  design) — we compile our own C++ for Android.

### Planned layout

```
third_party/litert/
├── include/                 # LiteRT public C++ headers (from the source build)
├── lib/
│   ├── linux_x86_64/        # liblitert*.so built for the host
│   ├── android_arm64-v8a/   # built with --config=android_arm64
│   └── android_x86_64/      # built with --config=android_x86_64
└── BUILD.bazel              # cc_import/cc_library, platform-selected
core/                        # our inference core
├── litert_core.{h,cc}       # CompiledModel wrapper: load, buffers, run, accelerator
├── eupe_pipeline.{h,cc}     # pre/post-processing (ViT now; action-rec later)
└── BUILD.bazel              # cc_library
host/
├── main.cc                  # x86_64 CLI: image + model -> embedding (host validation)
└── BUILD.bazel              # cc_binary
app/
├── jni/jni_bridge.cc        # JNI -> core
├── src/main/...             # thin UI (Kotlin/Java), CameraX later
└── BUILD.bazel              # cc_binary(.so) + android_binary
```

## Phases (incremental; keep the working Java sample until the new path is proven)

- **Phase 1 — Build LiteRT 2.x from source (foundation, on the Linux box).**
  Build `//litert/cc:litert_compiled_model` for host x86_64 and Android ABIs,
  capture the exact artifact layout (lib names + header tree), and vendor them
  into `third_party/litert/`. Driver: [`tools/build_litert_from_source.sh`](../tools/build_litert_from_source.sh).
  *Long pole / highest risk — heavy build, occasionally finicky ([LiteRT #196](https://github.com/google-ai-edge/LiteRT/issues/196)).*
- **Phase 2 — C++ core + host CLI.** Implement `core/` (CompiledModel wrapper +
  EUPE preprocessing) and a `host/main.cc` CLI; validate on x86_64 Linux against
  the PyTorch reference embedding (D=192). Proves point 1 (x86) and correctness.
- **Phase 3 — Android: NDK toolchain + JNI + app.** Bazel NDK config, build the
  core for Android ABIs, JNI bridge, `android_binary` that loads the `.so`; thin
  UI showing the embedding + latency (the on-device EUPE milestone).
- **Phase 4 — Accelerators.** CPU first; then wire GPU and the Qualcomm **NPU**
  path through `CompiledModel` for SA8255.
- **Phase 5 — Cleanup.** Remove the old Java Interpreter sample + MediaPipe;
  overhaul `docs/01–08` to match the new architecture.

## Open items / to confirm during build

- Exact LiteRT artifact paths/names (Phase 1 output) — feeds Phase 2/3 Bazel wiring.
- NDK version LiteRT requires (repo `./configure` will specify; expect r25+).
- Whether the host build needs clang/libc++ specifically (build deps list says yes).
- Client action-recognition sample: when it arrives, fold its pre/post-processing
  into `core/eupe_pipeline.*` and reassess any MediaPipe need.
