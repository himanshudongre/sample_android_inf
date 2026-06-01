# 06 — Porting the EUPE ViT model

This covers the **real** model: [`facebook/EUPE-ViT-T`](https://huggingface.co/facebook/EUPE-ViT-T)
from [facebookresearch/EUPE](https://github.com/facebookresearch/EUPE)
("Efficient Universal Perception Encoder", [arXiv 2603.22387](https://arxiv.org/abs/2603.22387)).

---

## What EUPE-ViT-T actually is (and why it changes the plan)

It is a **DINOv2-style Vision Transformer encoder** (ViT-T/16, ~6M params), not
an image classifier. Concretely (verified from the repo source):

| Property | Value |
| --- | --- |
| Architecture | `DinoVisionTransformer`, patch 16, RoPE position embeddings |
| Input | `1 × 3 × S × S`, **float32**, layout **NCHW** (`S` a multiple of 16; eval default **256**, 224 also fine) |
| Preprocessing | scale to **[0,1]**, then **per-channel** ImageNet `mean=(0.485,0.456,0.406)`, `std=(0.229,0.224,0.225)` |
| Output | an **embedding**, not class scores: `forward()` → class-token vector `[1, D]` (D≈192 for ViT-T); `forward_features()` → `x_norm_clstoken` + `x_norm_patchtokens` |
| Distribution | PyTorch `EUPE-ViT-T.pt` only — **no tflite/onnx** provided |

Two consequences:
1. **A conversion step is required** (PyTorch → LiteRT). It is not a drop-in
   `.tflite`.
2. **The output is a feature vector**, so the sample app's
   "scores → labels → softmax → top-k" flow doesn't apply unmodified. You must
   decide what the on-device app *does* with the embedding (see Step 2).

---

## Step 1 — Download + convert `.pt` → `.tflite` (on a Linux workstation)

This is a one-time, **offline** step (not part of the Bazel build, not run on the
device). It needs PyTorch + [`litert-torch`](https://github.com/google-ai-edge/litert-torch).

### Option A — one command (recommended)

[`tools/prepare_eupe_model.sh`](../tools/prepare_eupe_model.sh) does everything:
uses the checkpoint **vendored in this repo via Git LFS** at
[`models/eupe/EUPE-ViT-T.pt`](../models/eupe/) (so **no Hugging Face access is
needed**; it falls back to downloading from HF only if that file is missing),
clones the EUPE repo for its code, and runs the conversion into the app's assets.

> Make sure the weights actually came down with the clone: this repo stores them
> via Git LFS, so the cloning machine needs `git lfs install` (then `git lfs pull`
> if you cloned before installing LFS). See [`models/eupe/README.md`](../models/eupe/README.md).

```bash
# From this repo's root, on Linux:
./tools/prepare_eupe_model.sh --venv        # --venv creates .venv-eupe and pip-installs deps
# or, if you already have torch + litert-torch in your active env:
./tools/prepare_eupe_model.sh
# choose a different input size with:  --img-size 256
```

Result: `app/src/main/assets/eupe_vit_t.tflite`. (The downloaded checkpoint and
cloned repo live under `build/eupe/`, which is git-ignored.)

> Note on the environment: conversion only needs `torch` + `numpy` +
> `litert-torch` (the EUPE *backbone* loads via `torch.hub` from the clone — you
> do not need the full EUPE training env for conversion). `--venv` installs these
> for you. If `litert-torch` and `torch` versions disagree, install `torch>=2.7.1`
> first, then `litert-torch`. The EUPE `conda.yaml` is the reference env if you
> prefer conda.

### Option B — manual (full control)

```bash
# 1) EUPE code + checkpoint
git clone https://github.com/facebookresearch/EUPE
#    download EUPE-ViT-T.pt from https://huggingface.co/facebook/EUPE-ViT-T
curl -fL -o EUPE-ViT-T.pt \
  https://huggingface.co/facebook/EUPE-ViT-T/resolve/main/EUPE-ViT-T.pt

# 2) deps (in a venv or the eupe conda env)
pip install "torch>=2.7.1" numpy litert-torch

# 3) convert (from this repo's root)
python tools/convert_eupe_to_tflite.py \
  --repo-dir /path/to/EUPE \
  --weights  /path/to/EUPE-ViT-T.pt \
  --out      app/src/main/assets/eupe_vit_t.tflite \
  --img-size 224
```

Either way, the converter loads the model via its `torch.hub` entrypoint
(`eupe_vitt16`), wraps it to return the class-token embedding as a single tensor,
prints the embedding dimension `D`, and exports a fixed-input-size `.tflite`.

**Conversion caveats to expect**
- **RoPE + fixed shapes**: EUPE uses rotary position embeddings; export with a
  **fixed** input size (the script does) rather than dynamic shapes for the most
  reliable conversion. If a specific op fails to convert, that's an
  `litert-torch` op-coverage issue — check its issue tracker / update it.
- **Quantization** (optional, for size/speed on the SoC) can be added in
  `litert-torch` later; start with float32 to get a correct baseline.

---

## Step 2 — Decide what the on-device app does with the embedding

The released backbone has **no task head**, so out of the box you get a vector,
not a label. Options, easiest first:

- **A. Encoder + latency (recommended first milestone).** Run the encoder, show
  the embedding's dimension, L2 norm, first few values, and the inference time.
  This *proves EUPE ViT runs on Android via LiteRT* — the actual goal of this
  phase — with zero extra assets. (Implementation sketch in Step 4.)
- **B. Classification.** Attach a linear/task head trained on top of the frozen
  features (the EUPE repo trains ImageNet/ADE20K/NYUv2 heads, but those weights
  aren't in the backbone HF repo). Export `head(encoder(x))` together, then the
  output *is* class scores and you can reuse the labels/softmax/top-k logic from
  `LiteRtClassifier`.
- **C. Similarity / retrieval.** Precompute embeddings for a few reference
  images, embed the live image, and show cosine-similarity ranking. Good for a
  "does it understand images" demo without a trained head.

> This is a product decision — pick A to hit the "it runs on-device" milestone
> fast; move to B/C when a head or reference set is available.

## Step 3 — Configure on-device preprocessing

Whatever the app does, the input tensor must match Step-1's recipe. The helper
[`ImageUtils.toFloat32BufferPerChannel`](../app/src/main/java/com/example/sampleinf/ImageUtils.java)
already implements exactly this (scale to [0,1] + per-channel mean/std, NHWC or
NCHW):

```java
private static final int     INPUT_SIZE = 224;     // must equal --img-size used at conversion
private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
private static final float[] STD  = {0.229f, 0.224f, 0.225f};
private static final ImageUtils.Layout LAYOUT = ImageUtils.Layout.NCHW;  // EUPE/PyTorch

Bitmap resized = ImageUtils.resize(bitmap, INPUT_SIZE, INPUT_SIZE);
ByteBuffer input = ImageUtils.toFloat32BufferPerChannel(resized, INPUT_SIZE, INPUT_SIZE, MEAN, STD, LAYOUT);
```

(The EUPE eval transform resizes the whole image to a square `S×S` — a plain
resize, not resize-shortest-side+crop — so `ImageUtils.resize` matches it.)

## Step 4 — Sketch of an `EupeEncoder` backend

Implement the same [`InferenceBackend`](../app/src/main/java/com/example/sampleinf/InferenceBackend.java)
interface so the UI is unchanged. The only differences from `LiteRtClassifier`
are preprocessing (Step 3) and postprocessing (read an embedding, don't softmax):

```java
// after interpreter.run(input, output):
float[][] out = new float[1][embeddingDim];   // embeddingDim = D printed by the converter
interpreter.run(input, out);
float[] emb = out[0];
double norm = 0; for (float v : emb) norm += v * v; norm = Math.sqrt(norm);
// Display: "EUPE ViT  (NN ms)\n dim=D  L2=norm\n emb[0..4]=..."
```

(Once you choose the Step-2 behavior, I can wire this `EupeEncoder` into the app
and add a button for it — it slots in next to the existing two backends.)

---

## Looking ahead: GPU / NPU on Qualcomm SA8255 (CDC)

The encoder runs on CPU first for correctness. For the SoC's accelerators, attach
a delegate in `Interpreter.Options` — the single seam that doesn't disturb
preprocessing, the UI, or the build:
- **GPU**: LiteRT GPU delegate (a LiteRT **1.x** Interpreter feature).
- **Qualcomm NPU/DSP**: the **QNN / Qualcomm AI Engine Direct** delegate, so the
  ViT runs on SA8255 accelerators. ViTs are compute-heavy, so this is where real
  CDC performance comes from. (Some ops — e.g. parts of RoPE/attention — may fall
  back to CPU depending on delegate coverage; profile and iterate.)

Next: [07 — Dependency Management](07_dependency_management.md).
