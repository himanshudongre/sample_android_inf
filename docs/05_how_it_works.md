# 05 — How It Works (component by component)

This explains every moving part, grouped into **the build** (Bazel) and **the
app** (Java). Read alongside the files themselves — they're heavily commented.

---

## Part A — The build (how Bazel turns the repo into an APK)

### A1. `MODULE.bazel` — the dependency manifest

Bazel's modern dependency system ("Bzlmod") starts here. This file:

1. Declares the Bazel rule-sets we need:
   - **`rules_android`** — provides `android_binary` (Bazel removed its built-in
     Android rules in v8, so this external rule-set is now required).
   - **`rules_jvm_external`** — resolves Java/Android libraries from Maven.
2. Wires up the **Android SDK** via a module extension that reads `$ANDROID_HOME`
   and exposes it as the `@androidsdk` repo, then registers it as a toolchain.
3. Declares the actual libraries via `maven.install(...)`:
   `com.google.mediapipe:tasks-vision` and `com.google.ai.edge.litert:litert`.
   `rules_jvm_external` downloads these **and their whole transitive dependency
   tree**, exposing each as `@maven//:<group_artifact>`.

> Why Maven instead of a raw `aar_import` of a local file? Because a bare
> `aar_import` does **not** resolve transitive dependencies (protobuf, Guava,
> AndroidX, …). `rules_jvm_external` does, automatically. See
> [07 — Dependency Management](07_dependency_management.md).

### A2. `.bazelversion` & `.bazelrc` — pinned version and flags

- `.bazelversion` pins **Bazel 8.7.0** so everyone (and CI) uses the same Bazel.
  `bazelisk` reads it and fetches that exact version.
- `.bazelrc` sets project-wide flags: enable Bzlmod, use a hermetic JDK 17 to
  compile, and produce a fat APK (`arm64-v8a` + `x86_64`) by default. It also
  defines `--config=arm64` / `--config=x86_64` / `--config=allabi` presets.

### A3. `third_party/` — the decoupling layer

Each runtime gets a tiny package that re-exports the Maven target under a clean,
stable label:

```python
# third_party/litert/BUILD.bazel
alias(name = "litert", actual = "@maven//:com_google_ai_edge_litert_litert")
```

So the app depends on `//third_party/litert`, never on a Maven coordinate or an
`.aar` path. This is the whole "decouple it into its own folder" requirement:
swapping the resolution strategy (Maven ↔ vendored offline `.aar`) is a one-line
edit here and the app is unaffected. Each folder also has a `vendor/README.md`
explaining the offline `aar_import` variant.

### A4. `app/BUILD.bazel` — the application target

A single `android_binary` named `app` that:
- compiles `srcs` (all the Java),
- packages `resource_files` (layout/strings/theme) and `assets` (model, labels,
  image),
- merges the `AndroidManifest.xml`,
- links `deps = ["//third_party/litert", "//third_party/mediapipe"]`,
- and emits `bazel-bin/app/app.apk`.

The native `.so` libraries for LiteRT and MediaPipe come *inside* their AARs and
are packaged automatically — which is exactly why **no NDK is required**.

### Build graph in one line

```
MODULE.bazel → (rules_android + rules_jvm_external + @androidsdk)
            → @maven//:litert, @maven//:tasks_vision
            → //third_party/litert, //third_party/mediapipe
            → //app:app  → app.apk
```

---

## Part B — The app (what the Java does)

### B1. `MainActivity.java` — the UI and orchestration

- Inflates `res/layout/activity_main.xml` (an `ImageView`, two `Button`s, two
  `TextView`s — plain framework widgets, no AndroidX needed).
- Loads `sample.jpg` from assets (or draws a synthetic placeholder if missing).
- On a button tap, runs the selected backend **on a background thread**
  (`ExecutorService`) — model inference must never block the UI thread — then
  posts results back with `runOnUiThread`.
- Wraps the backend in try-with-resources so native handles are always released,
  and turns setup failures (e.g. missing model) into a friendly on-screen hint.

### B2. `InferenceBackend.java` — the abstraction

A 3-method interface (`name()`, `classify(Bitmap)`, `close()`). Because the UI
only talks to this interface, the two backends are interchangeable — and a future
**EUPE ViT** backend just implements the same interface with zero UI changes.

### B3. `LiteRtClassifier.java` — the low-level path (ViT-ready)

This is the most important file for the EUPE port. It does the entire pipeline by
hand:

1. **Load** the `.tflite` from assets via memory-mapping (`MappedByteBuffer`) and
   build an `org.tensorflow.lite.Interpreter`.
2. **Inspect** the model's input tensor (`getInputTensor(0).shape()` /
   `.dataType()`) so the code adapts to the model's real shape and type rather
   than hard-coding them.
3. **Preprocess** the `Bitmap` (resize + normalize) into the input tensor — done
   in `ImageUtils` (see B5).
4. **Run** `interpreter.run(input, output)`.
5. **Postprocess**: dequantize if needed, optional softmax, top-K, map indices →
   labels.

A clearly-marked **CONFIG block** at the top holds everything you'd change for a
new model: model/label file names, normalization mean/std, NHWC vs NCHW layout,
whether to apply softmax, thread count, top-K.

### B4. `MediaPipeClassifier.java` — the high-level path

Builds a MediaPipe `ImageClassifier` from `BaseOptions.setModelAssetPath(...)`,
wraps the `Bitmap` in an `MPImage`, calls `classify(...)`, and reads
`result.classificationResult().classifications().get(0).categories()`. There is
**no preprocessing/labels code** — MediaPipe does all of it from the model's
embedded metadata. (It still runs the model on LiteRT under the hood.)

### B5. `ImageUtils.java` — preprocessing (the part models are picky about)

Static helpers that convert a `Bitmap` into the exact tensor a model expects:
- `resize(...)` to the model's input size,
- `toFloat32Buffer(...)` applying `(pixel − mean) / std`, in **NHWC** (TensorFlow)
  or **NCHW** (PyTorch) layout,
- `toUint8Buffer(...)` for fully-quantized models,
- `softmax(...)`.

This is where most "the model runs but the answers are wrong" bugs live — the
normalization and layout must match how the model was trained. See
[06 — Porting EUPE ViT](06_porting_eupe_vit.md).

### B6. `Classification.java` — the result type

A tiny immutable `(label, score)` pair, with a `toString()` for tidy display.
Shared by both backends so the UI treats their outputs identically.

---

## The assets pipeline

`tools/download_model.sh` fetches the model and sample image, and **extracts the
label list from the model's own metadata** (a `.tflite` with associated files has
them appended as a ZIP, so `unzip model.tflite` yields the labels). This
guarantees the label order matches the model's output indices exactly. Those
files land in `app/src/main/assets/` (git-ignored) and are packaged into the APK
by `android_binary`.

Next: [06 — Porting EUPE ViT](06_porting_eupe_vit.md).
