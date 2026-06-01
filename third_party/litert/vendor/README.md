# Vendoring the LiteRT `.aar` (offline builds)

By default this project resolves LiteRT from Maven (see
[`../BUILD.bazel`](../BUILD.bazel)). You only need this folder if you want a
**fully offline / air-gapped** build that does not download anything at build
time.

## 1. Download the `.aar`

Pick the **same version** pinned in [`/MODULE.bazel`](../../../MODULE.bazel)
(currently LiteRT `1.0.1` — a **1.x** release, because 2.x removed the
`Interpreter` API). Download it from Google's Maven into this folder:

```bash
# Run from the repo root:
VER=1.0.1
curl -L -o third_party/litert/vendor/litert-${VER}.aar \
  "https://maven.google.com/com/google/ai/edge/litert/litert/${VER}/litert-${VER}.aar"
```

## 2. Discover its transitive dependencies

`aar_import` will **not** pull these in for you. The easiest way to list them is
to let `rules_jvm_external` resolve once (online), then read `maven_install.json`
at the repo root — every artifact LiteRT depends on is listed there with its
coordinates. Add each one to the `deps` of the `aar_import` target.

Alternatively, inspect the POM:

```bash
curl -L "https://maven.google.com/com/google/ai/edge/litert/litert/1.0.1/litert-1.0.1.pom"
```

## 3. Switch the BUILD target

In [`../BUILD.bazel`](../BUILD.bazel): uncomment the `load(...)` +
`aar_import(...)` block and point the `alias`'s `actual` at `:litert_vendored`.

> Tip: commit large `.aar` files via Git LFS if your team uses it. This `vendor/`
> folder is intentionally left empty in version control.
