# Vendored EUPE checkpoint (Git LFS)

This folder holds the **EUPE-ViT-T PyTorch checkpoint**, committed to the repo via
**Git LFS** so that build machines **without Hugging Face access** still get the
weights straight from the repo.

| | |
| --- | --- |
| File | `EUPE-ViT-T.pt` |
| Source | https://huggingface.co/facebook/EUPE-ViT-T (`resolve/main/EUPE-ViT-T.pt`) |
| Size | 64,570,935 bytes (~61.6 MB) |
| SHA-256 | `b29b906339c9ae21d35a15602ef9d2fce9145828da9ad9cd797fac11ece60487` |
| Format | PyTorch checkpoint (ZIP container) |
| Storage | Git LFS (see `/.gitattributes`: `models/eupe/*.pt filter=lfs`) |

## Cloning this repo so the weights come down

The machine that clones the repo needs **git-lfs installed**, or this file will
arrive as a small text "pointer" instead of the real 61.6 MB checkpoint.

```bash
# one-time, per machine:
git lfs install

# then either clone fresh (LFS files download automatically):
git clone https://github.com/himanshudongre/sample_android_inf

# or, if you already cloned without LFS, fetch the binaries:
git lfs pull
```

Verify you got the real file (not a pointer):

```bash
wc -c models/eupe/EUPE-ViT-T.pt          # should be ~64,570,935, not ~130
shasum -a 256 models/eupe/EUPE-ViT-T.pt  # should match the SHA-256 above
```

## How it's used

[`tools/prepare_eupe_model.sh`](../../tools/prepare_eupe_model.sh) automatically
uses this vendored checkpoint (no Hugging Face download) and converts it to
`app/src/main/assets/eupe_vit_t.tflite`. See
[`/docs/06_porting_eupe_vit.md`](../../docs/06_porting_eupe_vit.md).

> Note: conversion still clones the EUPE **code** from
> github.com/facebookresearch/EUPE and needs `torch` + `litert-torch` (from
> PyPI). Only the *model weights* are vendored here; if the build machine is also
> cut off from GitHub/PyPI, vendor those too or use an internal mirror.
