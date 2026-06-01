#!/usr/bin/env python3
# =============================================================================
# convert_eupe_to_tflite.py
#
# Converts the EUPE ViT-T PyTorch checkpoint into a LiteRT (.tflite) model that
# the Android app can run on-device.
#
#   EUPE-ViT-T.pt  (PyTorch)  ──litert-torch──►  eupe_vit_t.tflite  (LiteRT)
#
# WHAT EUPE-ViT-T IS
#   A DINOv2-style Vision Transformer *encoder* (ViT-T/16). It does NOT output
#   class probabilities — it outputs an embedding:
#     - forward(x)                -> the normalized CLASS-TOKEN embedding [B, D]
#                                    (D ≈ 192 for ViT-T; this script prints it)
#     - forward_features(x)       -> dict with "x_norm_clstoken" [B, D] and
#                                    "x_norm_patchtokens" [B, num_patches, D]
#   This script exports the class-token embedding by default — the simplest,
#   most useful single-vector output for an on-device demo. To classify /
#   segment / estimate depth you would attach a task head (not included in the
#   released backbone) and export that instead.
#
# IMPORTANT: this must run on a LINUX workstation (per the EUPE repo), NOT on the
# Android device and NOT as part of the Bazel build. It is a one-time, offline
# model-preparation step whose OUTPUT (.tflite) you copy into the app's assets.
#
# PREREQUISITES (on the Linux machine)
#   1. Clone the EUPE repo and set up its env (gives you the `eupe` package):
#        git clone https://github.com/facebookresearch/EUPE
#        cd EUPE && micromamba env create -f conda.yaml && micromamba activate eupe
#   2. Download the checkpoint EUPE-ViT-T.pt from
#        https://huggingface.co/facebook/EUPE-ViT-T
#   3. Install Google's PyTorch->LiteRT converter:
#        pip install litert-torch
#
# USAGE
#   python tools/convert_eupe_to_tflite.py \
#       --repo-dir   /path/to/EUPE \
#       --weights    /path/to/EUPE-ViT-T.pt \
#       --out        app/src/main/assets/eupe_vit_t.tflite \
#       --img-size   224          # square; must be a multiple of 16 (224 or 256)
# =============================================================================

import argparse
import sys


def parse_args():
    p = argparse.ArgumentParser(description="Convert EUPE ViT-T to .tflite (LiteRT).")
    p.add_argument("--repo-dir", required=True,
                   help="Path to a local clone of github.com/facebookresearch/EUPE")
    p.add_argument("--weights", required=True,
                   help="Path to the EUPE-ViT-T.pt checkpoint")
    p.add_argument("--out", default="app/src/main/assets/eupe_vit_t.tflite",
                   help="Output .tflite path (default: into the app assets)")
    p.add_argument("--entrypoint", default="eupe_vitt16",
                   help="torch.hub entrypoint (eupe_vitt16 / eupe_vits16 / eupe_vitb16)")
    p.add_argument("--img-size", type=int, default=224,
                   help="Square input size; MUST be a multiple of 16 (e.g. 224 or 256)")
    return p.parse_args()


def main():
    args = parse_args()
    if args.img_size % 16 != 0:
        sys.exit(f"--img-size must be a multiple of 16 (patch size); got {args.img_size}")

    import torch  # noqa: E402

    # --- 1. Load the EUPE backbone via its torch.hub entrypoint. -------------
    # source='local' loads the hubconf.py from the cloned repo; `weights` points
    # at the downloaded .pt checkpoint.
    print(f"==> Loading {args.entrypoint} from {args.repo_dir}")
    model = torch.hub.load(
        args.repo_dir, args.entrypoint, source="local", weights=args.weights
    )
    model = model.eval()

    # --- 2. Wrap it so the exported graph returns a single tensor: the
    #        normalized class-token embedding. (litert-torch needs tensor
    #        outputs; forward_features returns a dict.) -----------------------
    class ClsTokenEncoder(torch.nn.Module):
        def __init__(self, backbone):
            super().__init__()
            self.backbone = backbone

        def forward(self, x):
            feats = self.backbone.forward_features(x)
            return feats["x_norm_clstoken"]  # [B, embed_dim]
            # To export patch tokens instead/as-well, return
            # feats["x_norm_patchtokens"] (shape [B, num_patches, embed_dim]).

    wrapped = ClsTokenEncoder(model).eval()

    # --- 3. Sanity-check a forward pass and report the output shape. ---------
    # Export with a FIXED input size (no dynamic shapes) — most robust for the
    # converter and for the RoPE position embeddings EUPE uses.
    sample = torch.randn(1, 3, args.img_size, args.img_size)
    with torch.no_grad():
        out = wrapped(sample)
    print(f"==> Forward OK. Input {tuple(sample.shape)}  ->  embedding {tuple(out.shape)}")
    print(f"    (embedding dimension D = {out.shape[-1]} — note this for the app)")

    # --- 4. Convert to LiteRT and export. -----------------------------------
    # The converter was renamed: the package `ai_edge_torch` became `litert_torch`
    # (identical API). Prefer the new one; fall back to the legacy import only if
    # it still ships a real .convert() (a recent `ai_edge_torch` is an empty shim
    # that just warns and has no .convert).
    print("==> Converting to LiteRT (this can take a while)")
    edge_torch = None
    try:
        import litert_torch as edge_torch  # noqa: E402  (current package)
    except ImportError:
        try:
            import ai_edge_torch as edge_torch  # noqa: E402  (legacy package)
        except ImportError:
            edge_torch = None
    if edge_torch is None or not hasattr(edge_torch, "convert"):
        sys.exit(
            "No working PyTorch->LiteRT converter found.\n"
            "Install the current package:  pip install litert-torch"
        )

    edge_model = edge_torch.convert(wrapped, (sample,))
    edge_model.export(args.out)
    print(f"==> Wrote {args.out}")

    print()
    print("Next steps:")
    print(f"  1. The model input is FLOAT32 NCHW [1, 3, {args.img_size}, {args.img_size}],")
    print("     scaled to [0,1] then ImageNet-normalized (mean .485/.456/.406,")
    print("     std .229/.224/.225). Configure the Android preprocessing to match.")
    print(f"  2. Output is a {int(out.shape[-1])}-d embedding (NOT class scores).")
    print("     See docs/06_porting_eupe_vit.md for how to consume it on-device.")


if __name__ == "__main__":
    main()
