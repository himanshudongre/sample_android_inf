# `third_party/` — external ML runtimes, isolated in their own folder

This directory is the whole point of the "decoupling" exercise: the heavy,
third-party inference runtimes (**LiteRT** and **MediaPipe**) are kept here,
clearly separated from our own application code in [`/app`](../app). The app
never references a Maven coordinate or an `.aar` path directly — it only depends
on the clean, stable Bazel labels defined here:

| Bazel label                  | What it gives you                                    |
| ---------------------------- | ---------------------------------------------------- |
| `//third_party/litert`       | The LiteRT (TF-Lite) `Interpreter` runtime           |
| `//third_party/mediapipe`    | The MediaPipe Tasks **Vision** library               |

## How dependencies are actually resolved

There are two ways to consume a prebuilt Android library (`.aar`) in Bazel, and
they have an important difference:

1. **Maven resolution (what we use by default).** We list the library
   coordinates in [`/MODULE.bazel`](../MODULE.bazel) and let `rules_jvm_external`
   download them *and their entire transitive dependency tree* (protobuf, Guava,
   AndroidX, etc.), locking everything in `maven_install.json`. Each
   `BUILD.bazel` in here just `alias`es the resolved target to a friendly name.

2. **Vendored `.aar` via `aar_import` (offline / air-gapped option).** You can
   instead commit the actual `.aar` files into `litert/vendor/` and
   `mediapipe/vendor/`. The catch: `aar_import` does **not** pull in transitive
   dependencies, so you must enumerate them yourself. See each subfolder's
   `vendor/README.md`. This path is documented but commented-out by default,
   because it is more fragile and most build machines have network access.

Either way, the **app code does not change** — it keeps depending on
`//third_party/litert` and `//third_party/mediapipe`. Swapping resolution
strategies is a one-line change in the relevant `BUILD.bazel`.

See [`/docs/07_dependency_management.md`](../docs/07_dependency_management.md)
for the full explanation, including how to update versions and re-pin the lock
file.
