# Vendoring the MediaPipe `.aar` (offline builds)

By default this project resolves MediaPipe Tasks Vision from Maven (see
[`../BUILD.bazel`](../BUILD.bazel)). Use this folder only for a **fully offline /
air-gapped** build.

## 1. Download the `.aar`

Match the version pinned in [`/MODULE.bazel`](../../../MODULE.bazel) (currently
`tasks-vision 0.10.29`):

```bash
# Run from the repo root:
VER=0.10.29
curl -L -o third_party/mediapipe/vendor/tasks-vision-${VER}.aar \
  "https://maven.google.com/com/google/mediapipe/tasks-vision/${VER}/tasks-vision-${VER}.aar"
```

## 2. Discover its transitive dependencies

MediaPipe Tasks pulls in a fair number of runtime libraries (protobuf-javalite,
Guava, flogger, AndroidX annotations, etc.). `aar_import` will **not** add them
automatically. List them by reading the repo-root `maven_install.json` (after one
online `bazel run @maven//:pin`) or by inspecting the POM:

```bash
curl -L "https://maven.google.com/com/google/mediapipe/tasks-vision/0.10.29/tasks-vision-0.10.29.pom"
```

Add each transitive artifact to the `deps` of the `aar_import` target.

## 3. Switch the BUILD target

In [`../BUILD.bazel`](../BUILD.bazel): uncomment the `load(...)` +
`aar_import(...)` block and point the `alias`'s `actual` at `:mediapipe_vendored`.

> This `vendor/` folder is intentionally empty in version control.
