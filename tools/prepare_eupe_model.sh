#!/usr/bin/env bash
# =============================================================================
# prepare_eupe_model.sh
#
# ONE-COMMAND EUPE ViT preparation: download the PyTorch checkpoint, get the
# EUPE code, and convert it to a LiteRT (.tflite) model the Android app can run.
#
#   facebook/EUPE-ViT-T (.pt on Hugging Face)
#        │  (1) download checkpoint        ── this script
#        │  (2) clone EUPE repo (for code) ── this script
#        ▼
#   tools/convert_eupe_to_tflite.py  (litert-torch: PyTorch -> LiteRT)
#        ▼
#   app/src/main/assets/eupe_vit_t.tflite
#
# WHY a separate script (vs the Bazel build)?
#   Model conversion is a heavyweight, one-time, OFFLINE step that needs PyTorch
#   + litert-torch. It is NOT part of building the APK and does NOT run on the
#   device. Run it once on a Linux workstation; commit/copy the resulting
#   .tflite (or just re-run this script on each machine).
#
# REQUIREMENTS
#   - Linux (the EUPE repo states Linux-only) with: git, curl, python3
#   - Python deps: torch (>=2.7.1), numpy, litert-torch
#       * pass --venv to have this script create a venv and pip-install them, OR
#       * activate the EUPE conda env yourself and `pip install litert-torch`
#         first, then run without --venv.
#
# USAGE
#   ./tools/prepare_eupe_model.sh                 # use current python env
#   ./tools/prepare_eupe_model.sh --venv          # create .venv-eupe and install deps
#   ./tools/prepare_eupe_model.sh --img-size 256  # match a different input size
# =============================================================================

set -euo pipefail

# --- Defaults / args ---------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

IMG_SIZE=224
USE_VENV=0
WORKDIR="${REPO_ROOT}/build/eupe"
OUT="${REPO_ROOT}/app/src/main/assets/eupe_vit_t.tflite"
ENTRYPOINT="eupe_vitt16"

# Hugging Face direct-download URL for the (non-gated) checkpoint.
CKPT_URL="https://huggingface.co/facebook/EUPE-ViT-T/resolve/main/EUPE-ViT-T.pt"
EUPE_GIT="https://github.com/facebookresearch/EUPE"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --img-size)  IMG_SIZE="$2"; shift 2 ;;
    --venv)      USE_VENV=1; shift ;;
    --workdir)   WORKDIR="$2"; shift 2 ;;
    --out)       OUT="$2"; shift 2 ;;
    --entrypoint) ENTRYPOINT="$2"; shift 2 ;;
    -h|--help)   sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "${WORKDIR}"
EUPE_DIR="${WORKDIR}/EUPE"
CKPT="${WORKDIR}/EUPE-ViT-T.pt"
# Canonical checkpoint vendored in this repo via Git LFS (works with no HF access):
VENDORED_CKPT="${REPO_ROOT}/models/eupe/EUPE-ViT-T.pt"

echo "==> EUPE preparation"
echo "    workdir : ${WORKDIR}"
echo "    output  : ${OUT}"
echo "    img-size: ${IMG_SIZE}"
echo

# --- (1) Obtain the checkpoint ----------------------------------------------
# Prefer the copy vendored in the repo (Git LFS) so this works offline / on
# machines without Hugging Face access. Fall back to downloading from HF.
if [[ -s "${VENDORED_CKPT}" ]]; then
  # If the repo was cloned WITHOUT git-lfs, this path is a tiny pointer file,
  # not the real weights — detect that and tell the user how to fix it.
  if [[ "$(wc -c < "${VENDORED_CKPT}")" -lt 1000000 ]]; then
    echo "ERROR: ${VENDORED_CKPT} is a Git LFS pointer, not the real checkpoint." >&2
    echo "       This repo stores the weights via Git LFS. Fetch them with:" >&2
    echo "         git lfs install && git lfs pull" >&2
    exit 1
  fi
  echo "==> Using vendored checkpoint (Git LFS): ${VENDORED_CKPT}"
  CKPT="${VENDORED_CKPT}"
elif [[ -s "${CKPT}" ]]; then
  echo "==> Checkpoint already downloaded ($(wc -c < "${CKPT}") bytes): ${CKPT}"
else
  echo "==> Vendored checkpoint not found; downloading from Hugging Face"
  echo "    ${CKPT_URL}"
  curl -fL --retry 3 -o "${CKPT}" "${CKPT_URL}"
  echo "    saved -> ${CKPT} ($(wc -c < "${CKPT}") bytes)"
fi

# --- (2) Get the EUPE code (provides hubconf.py + the `eupe` package) --------
if [[ -d "${EUPE_DIR}/.git" ]]; then
  echo "==> EUPE repo already cloned, skipping"
else
  echo "==> Cloning ${EUPE_GIT}"
  git clone --depth 1 "${EUPE_GIT}" "${EUPE_DIR}"
fi

# --- (3) Python environment --------------------------------------------------
PY="python3"
if [[ "${USE_VENV}" -eq 1 ]]; then
  VENV="${REPO_ROOT}/.venv-eupe"
  if [[ ! -d "${VENV}" ]]; then
    echo "==> Creating venv at ${VENV}"
    python3 -m venv "${VENV}"
  fi
  # shellcheck disable=SC1091
  source "${VENV}/bin/activate"
  PY="python"
  echo "==> Installing conversion deps (torch, numpy, litert-torch) — this is large"
  pip install --upgrade pip
  pip install "torch>=2.7.1" numpy litert-torch
fi

# Verify the needed Python modules are importable; if not, stop with guidance.
if ! "${PY}" -c "import torch" 2>/dev/null; then
  echo "ERROR: PyTorch not importable in the current environment." >&2
  echo "  Rerun with venv setup:  ./tools/prepare_eupe_model.sh --venv" >&2
  exit 1
fi
if ! "${PY}" -c "import litert_torch" 2>/dev/null && ! "${PY}" -c "import ai_edge_torch" 2>/dev/null; then
  echo "ERROR: the PyTorch->LiteRT converter is not installed. Install it with:" >&2
  echo "  pip install litert-torch" >&2
  echo "(It was formerly named ai_edge_torch.)" >&2
  exit 1
fi

# --- (4) Convert -------------------------------------------------------------
echo "==> Converting to LiteRT"
"${PY}" "${SCRIPT_DIR}/convert_eupe_to_tflite.py" \
  --repo-dir   "${EUPE_DIR}" \
  --weights    "${CKPT}" \
  --out        "${OUT}" \
  --entrypoint "${ENTRYPOINT}" \
  --img-size   "${IMG_SIZE}"

echo
echo "==> Done. Converted model at: ${OUT}"
echo "    Remember: set the Android preprocessing to match (input ${IMG_SIZE}x${IMG_SIZE},"
echo "    NCHW, [0,1] + ImageNet mean/std). See docs/06_porting_eupe_vit.md."
