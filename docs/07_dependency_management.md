# 07 — Dependency Management

How third-party code enters this build, how to update versions safely, and how to
go fully offline.

---

## The three layers of dependencies

1. **Bazel rule-sets** (`bazel_dep` in [`MODULE.bazel`](../MODULE.bazel)) — fetched
   from the [Bazel Central Registry](https://registry.bazel.build):
   `rules_android`, `rules_jvm_external`.
2. **The Android SDK** — *not* downloaded by Bazel; located on your machine via
   `$ANDROID_HOME` (a module extension wraps it as `@androidsdk`).
3. **Java/Android libraries** (`maven.install` in `MODULE.bazel`) — LiteRT,
   MediaPipe, and everything they transitively depend on, fetched from Google
   Maven + Maven Central by `rules_jvm_external`.

## Why Maven resolution (not raw `aar_import`)

You can consume a prebuilt Android library two ways in Bazel:

| | `rules_jvm_external` (what we use) | bare `aar_import` of a local file |
| --- | --- | --- |
| Transitive deps (protobuf, Guava, AndroidX…) | **resolved automatically** | **you must list them all yourself** |
| Reproducibility | lock file with SHA-256 of every artifact | manual |
| Offline | needs one online resolve, then cached | fully offline once vendored |

A ViT/LiteRT/MediaPipe stack has a deep transitive tree, so automatic resolution
is far less error-prone. The clean labels in `//third_party/*` mean the app never
sees this choice — see [05 — How It Works](05_how_it_works.md), A3.

## The lock file (`maven_install.json`)

```bash
bazel run @maven//:pin
```

This resolves the full graph and writes `maven_install.json` (every artifact +
its verified hash). **Commit it.** Benefits:
- reproducible builds (everyone gets identical dependency bytes),
- faster builds (no re-resolution),
- supply-chain integrity (hashes are checked).

Re-run `pin` only after you edit the `artifacts`/`repositories` list.

## Updating a version

1. Edit the relevant pin:
   - Bazel itself → [`.bazelversion`](../.bazelversion)
   - rule-sets → `bazel_dep(...)` versions in [`MODULE.bazel`](../MODULE.bazel)
     (check latest on the [Bazel Central Registry](https://registry.bazel.build))
   - LiteRT / MediaPipe → the `artifacts` list in `MODULE.bazel`
2. For Maven changes, re-pin: `bazel run @maven//:pin`.
3. Rebuild and smoke-test on a device.

### ⚠️ The one version trap to remember: LiteRT 1.x vs 2.x

We pin **LiteRT 1.x** (`com.google.ai.edge.litert:litert:1.0.1`) on purpose.
LiteRT **2.x removed `org.tensorflow.lite.Interpreter`** in favor of a different
`CompiledModel` API
([issue #4775](https://github.com/google-ai-edge/LiteRT/issues/4775)). Bumping to
2.x would break [`LiteRtClassifier.java`](../app/src/main/java/com/example/sampleinf/LiteRtClassifier.java).
When updating, pick the **latest 1.x** release, or migrate the code to the
`CompiledModel` API deliberately.

### Verifying the latest available versions

```bash
# LiteRT (look for the newest 1.x):
curl -s "https://maven.google.com/web/index.html#com.google.ai.edge.litert:litert"
# MediaPipe tasks-vision:
#   https://mvnrepository.com/artifact/com.google.mediapipe/tasks-vision
# rules_android / rules_jvm_external:
#   https://registry.bazel.build/modules/rules_android
#   https://registry.bazel.build/modules/rules_jvm_external
```

## Going fully offline (air-gapped builds)

If your build machine can't reach the internet, vendor the AARs:

1. Follow [`third_party/litert/vendor/README.md`](../third_party/litert/vendor/README.md)
   and [`third_party/mediapipe/vendor/README.md`](../third_party/mediapipe/vendor/README.md)
   to download the `.aar` files and their transitive deps.
2. Switch each `//third_party/*` `alias` to the `aar_import` target (commented
   examples are already in the BUILD files).
3. Pre-fetch Bazel + rule-sets on a connected machine and copy the Bazel cache,
   or use `--distdir` / a private mirror.

The app code does not change either way.

Next: [08 — Troubleshooting](08_troubleshooting.md).
