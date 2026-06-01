# `models/` — model notes & staging

The app loads its model from `app/src/main/assets/`, populated by
[`/tools/download_model.sh`](../tools/download_model.sh). This folder is a place
to keep notes about the model and to stage candidate models (e.g. a freshly
converted EUPE ViT) before copying them into `assets/`.

## The sample model: EfficientNet-Lite0 (float32)

| Property            | Value                                                |
| ------------------- | ---------------------------------------------------- |
| Task                | Image classification (ImageNet-1k)                   |
| Input               | `1 × 224 × 224 × 3`, **float32**, layout **NHWC**     |
| Normalization       | `(pixel − 127.5) / 127.5`  → range **[−1, 1]**        |
| Output              | `1 × 1000` float scores (one per ImageNet class)     |
| Metadata            | Yes — includes the label map (so MediaPipe can use it)|
| Size                | ~18 MB                                                |

These properties are exactly the values configured in
[`LiteRtClassifier.java`](../app/src/main/java/com/example/sampleinf/LiteRtClassifier.java)
(`NORM_MEAN`, `NORM_STD`, `LAYOUT`, …). It is a deliberately ViT-shaped setup:
224×224 input, single image in, class scores out.

## Swapping in EUPE ViT later

A Vision Transformer exported to `.tflite` plugs into the **LiteRT path** with
the same code — you only adjust the CONFIG block in `LiteRtClassifier.java` to
match the ViT's input size / normalization / layout, drop the model + labels into
`assets/`, and rebuild. Full walkthrough:
[`/docs/06_porting_eupe_vit.md`](../docs/06_porting_eupe_vit.md).

> Note: the MediaPipe path only works with models that carry the metadata
> MediaPipe expects. A custom ViT usually won't, which is why the EUPE port
> targets the LiteRT interpreter path.
