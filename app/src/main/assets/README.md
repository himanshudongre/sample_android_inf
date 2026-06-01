# `assets/` — runtime files packaged into the APK

Files placed here are bundled into the app and read at runtime via
`Context.getAssets()`. This sample expects three files, all produced by
[`/tools/download_model.sh`](../../../../tools/download_model.sh) and therefore
**not committed to git**:

| File                        | Used by                | Notes                                         |
| --------------------------- | ---------------------- | --------------------------------------------- |
| `efficientnet_lite0.tflite` | LiteRT **and** MediaPipe | The model. Must contain TFLite metadata.    |
| `labels.txt`                | LiteRT path            | One class name per line, in the model's order. Extracted from the model metadata. |
| `sample.jpg`                | UI (image to classify) | Optional; the app draws a placeholder if absent. |

Run the download script from the repo root before building:

```bash
./tools/download_model.sh
```

This `README.md` is intentionally committed so the Bazel `assets` glob in
[`/app/BUILD.bazel`](../../../BUILD.bazel) is never empty (which keeps the build
working even before you download the model).
