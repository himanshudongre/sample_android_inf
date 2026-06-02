#!/usr/bin/env bash
# =============================================================================
# build_litert_from_source.sh   —   Phase 1 of docs/09_litert_v2_migration.md
#
# Builds LiteRT 2.x (the CompiledModel C++ runtime) FROM SOURCE for:
#   - linux_x86_64       (the dev/CI host)
#   - android arm64-v8a  (device → SA8255)
#   - android x86_64     (emulator)
# then vendors the produced shared libraries + public headers into
#   third_party/litert/{lib/<target>, include}
# so the rest of the project can link them ("build to an artifact, then consume").
#
# ── IMPORTANT ───────────────────────────────────────────────────────────────
# This is the heavy, foundational step and MUST run on the Linux build box. The
# LiteRT/TensorFlow source build is large (tens of GB, long, occasionally
# finicky — see github.com/google-ai-edge/LiteRT/issues/196). The exact output
# artifact paths/names are not fully documented upstream, so this script BUILDS,
# then DISCOVERS and REPORTS what was produced, and copies a best-effort set.
# Review the printed manifest and adjust third_party/litert/BUILD.bazel + the
# Phase 2/3 wiring to match. Report the manifest back so we lock it in.
#
# Requirements (Linux x86_64; see the LiteRT BUILD_INSTRUCTIONS):
#   build-essential curl git openjdk-17-jdk python3 python3-pip python3-dev
#   unzip wget zip llvm-18 clang-18 libc++-dev libc++abi-dev
#   Android SDK + NDK (set ANDROID_HOME and ANDROID_NDK_HOME); NDK r25+.
#   Bazelisk (as `bazel`).
#
# Usage:
#   ./tools/build_litert_from_source.sh                 # host + both android ABIs
#   ./tools/build_litert_from_source.sh --host-only     # just the x86_64 host
#   LITERT_REF=v2.1.0 ./tools/build_litert_from_source.sh   # pin a LiteRT tag
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

LITERT_GIT="https://github.com/google-ai-edge/LiteRT"
LITERT_REF="${LITERT_REF:-main}"                 # pin a tag/commit for reproducibility
SRC_DIR="${REPO_ROOT}/build/litert-src"          # git-ignored (under build/)
VENDOR_DIR="${REPO_ROOT}/third_party/litert"
TARGET="//litert/cc:litert_compiled_model"       # the CompiledModel C++ library
HOST_ONLY=0
[[ "${1:-}" == "--host-only" ]] && HOST_ONLY=1

echo "==> LiteRT from-source build"
echo "    ref     : ${LITERT_REF}"
echo "    src     : ${SRC_DIR}"
echo "    vendor  : ${VENDOR_DIR}"
echo "    target  : ${TARGET}"
echo

# --- 0. Sanity: tools present ------------------------------------------------
command -v bazel >/dev/null || { echo "ERROR: bazel (Bazelisk) not found." >&2; exit 1; }
if [[ "${HOST_ONLY}" -eq 0 ]]; then
  : "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME (NDK r25+) for Android builds, or pass --host-only}"
  : "${ANDROID_HOME:?Set ANDROID_HOME for Android builds, or pass --host-only}"
fi

# --- 1. Get the LiteRT source ------------------------------------------------
mkdir -p "${REPO_ROOT}/build"
if [[ -d "${SRC_DIR}/.git" ]]; then
  echo "==> LiteRT source present; fetching ${LITERT_REF}"
  git -C "${SRC_DIR}" fetch --depth 1 origin "${LITERT_REF}"
  git -C "${SRC_DIR}" checkout -q FETCH_HEAD
else
  echo "==> Cloning LiteRT @ ${LITERT_REF}"
  git clone --depth 1 --branch "${LITERT_REF}" "${LITERT_GIT}" "${SRC_DIR}" 2>/dev/null \
    || git clone "${LITERT_GIT}" "${SRC_DIR}"   # fall back if ref is a commit
fi

# --- 2. Configure (LiteRT inherits TensorFlow's ./configure) -----------------
# ./configure is interactive. For Android it needs the NDK/SDK paths; we pass
# them via env so it can run non-interactively where supported. If it still
# prompts, run it once by hand in ${SRC_DIR} and re-run this script.
echo "==> ./configure (in ${SRC_DIR})"
( cd "${SRC_DIR}" && \
  TF_SET_ANDROID_WORKSPACE=$([[ "${HOST_ONLY}" -eq 0 ]] && echo 1 || echo 0) \
  ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-}" \
  ANDROID_SDK_HOME="${ANDROID_HOME:-}" \
  ./configure ) || {
    echo "NOTE: ./configure may need to be run interactively once in ${SRC_DIR}." >&2
  }

COMMON_FLAGS=(-c opt --cxxopt=-std=c++17)

# build_one <label> <bazel-config-or-empty> <vendor-subdir>
build_one() {
  local name="$1" cfg="$2" sub="$3"
  echo
  echo "==> Building ${name}  (${TARGET})"
  ( cd "${SRC_DIR}" && bazel build "${COMMON_FLAGS[@]}" ${cfg:+--config=${cfg}} "${TARGET}" )

  local out_lib="${VENDOR_DIR}/lib/${sub}"
  mkdir -p "${out_lib}"
  echo "--- ${name}: shared libraries produced ---"
  # Discover the .so artifacts Bazel produced and copy them in.
  local found=0
  while IFS= read -r so; do
    cp -f "${so}" "${out_lib}/"
    echo "    $(basename "${so}")"
    found=1
  done < <(find "${SRC_DIR}/bazel-bin/litert" -name '*.so' 2>/dev/null | sort)
  [[ "${found}" -eq 1 ]] || echo "    (no .so found — inspect ${SRC_DIR}/bazel-bin/litert manually)"
}

# --- 3. Build per target -----------------------------------------------------
build_one "linux_x86_64" "" "linux_x86_64"
if [[ "${HOST_ONLY}" -eq 0 ]]; then
  build_one "android_arm64-v8a" "android_arm64"  "android_arm64-v8a"
  build_one "android_x86_64"    "android_x86_64" "android_x86_64"
fi

# --- 4. Vendor the public headers (best effort) ------------------------------
# The C++ CompiledModel API lives under litert/cc and litert/c. Copy those public
# headers; the Phase 2 cc_library include path will point here. (Transitive deps
# such as abseil/flatbuffers may also be needed — confirm during Phase 2.)
echo
echo "==> Vendoring public headers into ${VENDOR_DIR}/include"
mkdir -p "${VENDOR_DIR}/include/litert"
for d in c cc; do
  if [[ -d "${SRC_DIR}/litert/${d}" ]]; then
    rsync -a --include='*/' --include='*.h' --exclude='*' \
      "${SRC_DIR}/litert/${d}/" "${VENDOR_DIR}/include/litert/${d}/" 2>/dev/null \
      || cp -R "${SRC_DIR}/litert/${d}" "${VENDOR_DIR}/include/litert/"
  fi
done

echo
echo "==> Manifest of vendored artifacts:"
find "${VENDOR_DIR}/lib" -type f 2>/dev/null | sed "s|^|    |"
echo "    headers under ${VENDOR_DIR}/include/litert (count: $(find "${VENDOR_DIR}/include" -name '*.h' 2>/dev/null | wc -l | tr -d ' '))"
echo
echo "NEXT: review the manifest above and report it back. Large .so files should be"
echo "committed via Git LFS (add a pattern to .gitattributes), or rebuilt per machine."
