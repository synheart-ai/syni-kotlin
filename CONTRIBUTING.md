# Contributing to syni-kotlin

Thanks for your interest in `syni-kotlin`. This is the Kotlin/Android side
of the Synheart on-device LLM stack — for issues with model behavior or
runtime crashes the right place is usually the bug template here, but for
persona content questions see the
[Syni Spec documentation](https://docs.synheart.ai/syni-spec/overview).

## Local dev loop

```bash
# 1. Clone the repo
git clone https://github.com/synheart-ai/syni-kotlin
cd syni-kotlin

# 2. Install the runtime binaries (drops .so files into the consuming
#    app's vendor tree so a real on-device run works)
synheart install runtime syni

# 3. Build + test
./gradlew :syni-sdk:build
./gradlew :syni-sdk:test
```

The AAR ships **without** any native binary. `SyniNative` uses JNA to
resolve `libsyni_ffi` at runtime; the .so files come from
`synheart install runtime syni` and are wired into the consumer app's
`jniLibs.srcDirs` (see README for the consumer-side gradle snippet).

## Tests

- **Pure-Kotlin layer** (model spec parsing, persona JSON, error types,
  cloud client URL building) — testable here with `./gradlew test`.
- **Bridge + native runtime** — not unit-testable; exercised by
  consumer Android apps that have run `synheart install runtime syni`.
- **Network / SSE streaming** — add a MockWebServer seam to
  `SyniCloudClient` when a bug or feature warrants it; don't add it
  preemptively.

CI runs `./gradlew check test` on every push and PR. All must be green.

## Why we do not accept pull requests

This SDK is developed in an internal monorepo and mirrored to GitHub for
transparency. The public repository is source-available so anyone can read,
audit, and learn from the code that runs on their device — but the project
is not yet ready to absorb external code contributions.

Specifically:

- **Spec stability.** The Syni runtime contract and the persona/safety
  schemas in `syni-spec` are still evolving against internal RFCs.
  Accepting external changes before the spec settles would create churn
  for everyone, including contributors.
- **Review capacity.** A small team maintains this code. We would rather
  invest review time in stabilizing the runtime than in bouncing PRs back
  for rework.
- **Provenance.** We avoid contributor licensing overhead (CLAs, copyright
  assignment) by sourcing all code internally.

External pull requests are auto-closed. This is a temporary policy and may
relax once the spec is stable. Until then, issues are the supported way to
influence the direction of the SDK.

## What about typo / docs fixes?

Even small documentation fixes are best filed as an issue. Quote the
section, suggest the change, and we will roll it into the next internal
sync.

## Internal dev style notes

For Synheart team members working in the internal monorepo, the gates CI
checks before any commit lands:

- `./gradlew check` clean (lint + ktlint)
- `./gradlew test` green
- New public APIs need KDoc; new behavior needs a test under `src/test/`.
- CHANGELOG updated under the **Unreleased** section using
  [Keep a Changelog](https://keepachangelog.com/) style.

## Reporting issues

Please open issues at
[github.com/synheart-ai/syni-kotlin/issues](https://github.com/synheart-ai/syni-kotlin/issues).

For inference / runtime bugs (model load, generation quality, native
crashes), include:

- Android Gradle Plugin + Kotlin version (`./gradlew --version`)
- Device + Android OS version
- Model spec id (e.g. `qwen2.5-1.5b-instruct-q4_k_m`)
- Relevant logcat lines (the runtime tags its logs with `[synheart]`)

For suspected security issues, follow [`SECURITY.md`](SECURITY.md) instead
of opening a public issue.
