#!/usr/bin/env bash
# =============================================================================
# download_model.sh
#
# Fetches the machine-learning assets the app needs at runtime and places them in
# app/src/main/assets/ :
#
#   1. efficientnet_lite0.tflite  — the image-classification model (with embedded
#                                    TFLite metadata, so MediaPipe can use it too)
#   2. labels.txt                 — the model's class names, EXTRACTED FROM the
#                                    model's own metadata so they always match
#   3. sample.jpg                 — a test image to classify
#
# These files are intentionally NOT committed to git (see .gitignore). Run this
# script once after cloning, then build. Re-run any time to refresh.
#
# Usage:   ./tools/download_model.sh
# =============================================================================

set -euo pipefail

# --- Resolve paths relative to this script, so it works from any directory. ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ASSETS_DIR="${REPO_ROOT}/app/src/main/assets"
mkdir -p "${ASSETS_DIR}"

# --- Sources (override via env vars if these ever move). ----------------------
MODEL_URL="${MODEL_URL:-https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite}"
SAMPLE_IMAGE_URL="${SAMPLE_IMAGE_URL:-https://storage.googleapis.com/download.tensorflow.org/example_images/grace_hopper.jpg}"

MODEL_PATH="${ASSETS_DIR}/efficientnet_lite0.tflite"
LABELS_PATH="${ASSETS_DIR}/labels.txt"
SAMPLE_PATH="${ASSETS_DIR}/sample.jpg"

echo "==> Downloading model"
echo "    ${MODEL_URL}"
curl -fL --retry 3 -o "${MODEL_PATH}" "${MODEL_URL}"
echo "    saved -> ${MODEL_PATH} ($(wc -c < "${MODEL_PATH}") bytes)"

# --- Extract the label list straight from the model. --------------------------
# A .tflite file with "associated files" (like a label map) has those files
# stored as a ZIP archive appended to the model. So `unzip` on the .tflite can
# pull the label text file out — guaranteeing the labels match the model exactly.
echo "==> Extracting labels from the model's metadata"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

if unzip -o -q "${MODEL_PATH}" -d "${TMP_DIR}" 2>/dev/null; then
    # Find the first .txt file the metadata contains and use it as labels.txt.
    LABEL_SRC="$(find "${TMP_DIR}" -type f -name '*.txt' | head -n 1 || true)"
    if [[ -n "${LABEL_SRC}" ]]; then
        cp "${LABEL_SRC}" "${LABELS_PATH}"
        echo "    extracted $(wc -l < "${LABELS_PATH}") labels -> ${LABELS_PATH}"
    else
        echo "    WARNING: model metadata had no .txt label file."
        echo "             The MediaPipe path will still work (it reads metadata"
        echo "             directly), but the LiteRT path needs labels.txt."
    fi
else
    echo "    WARNING: could not unzip metadata from the model (no associated"
    echo "             files?). The LiteRT path needs labels.txt to show names."
fi

# --- Download the sample image. ----------------------------------------------
echo "==> Downloading sample image"
echo "    ${SAMPLE_IMAGE_URL}"
curl -fL --retry 3 -o "${SAMPLE_PATH}" "${SAMPLE_IMAGE_URL}"
echo "    saved -> ${SAMPLE_PATH} ($(wc -c < "${SAMPLE_PATH}") bytes)"

echo
echo "==> Done. assets/ now contains:"
ls -lh "${ASSETS_DIR}" | sed 's/^/    /'
echo
echo "Next: build the app  ->  bazel build //app:app"
