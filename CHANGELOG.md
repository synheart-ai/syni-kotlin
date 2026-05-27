# Changelog

All notable changes to this package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.3] - 2026-05-26

### Changed (BREAKING — coord rename, no code change)

- **Maven artifact renamed**: `ai.synheart:syni-sdk` → `ai.synheart:syni`.
  Matches the naming convention of the rest of the Synheart family
  (`synheart-core`, `synheart-auth`, `synheart-wear`). The old
  `syni-sdk` artifact stays on Maven Central but is not updated
  further; switch your dependency to `ai.synheart:syni:0.0.3`.
- Gradle module renamed `:syni-sdk` → `:syni` (only affects monorepo
  consumers using composite builds).

### Notes

- Same source tree, same public API as 0.0.2 modulo the editorial
  wording cleanup. No behavioral change.

## [0.0.2] - 2026-05-26

### Added

- `SyniAgent.bindPersona(persona)` for cloud-only flows that need a
  persona without a local model.
- `SyniAgent.deleteModel(model): Long` returns bytes freed; permanently
  removes the GGUF + sibling `tokenizer.json` from disk (~1.1 GB).
- `SyniAgent.installedBytes(model): Long` reports current on-disk
  usage; pair with `deleteModel` to confirm reclaim size before the
  user accepts.
- `SyniInstaller.deleteModel(spec)` / `installedBytes(spec)` for direct
  use.

### Changed (BREAKING — pre-1.0, no consumers)

- `SyniCloudConfig.authToken: suspend () -> String?` replaced with
  `SyniCloudConfig.authHeaders: suspend (method, url) -> Map<String, String>`
  — request-aware so device-attestation auth signed over `method + url`
  (e.g. `X-Synheart-Proof: <jws>`) is expressible. Matches
  `package:syni`'s `authHeaders` callback.
- Native runtime distribution: switched from JNI (CMake + `syni_jni.c`
  + bundled `.so` files) to JNA. The AAR no longer ships any native
  binary. Consumers install `syni-runtime` once with
  `synheart install runtime syni`; .so files land in
  `<app>/synheart/vendor/syni/android/jniLibs/` and are wired into the
  consumer app's `jniLibs.srcDirs`. Mirrors `synheart-core-kotlin`.
- Maven coordinates: group `com.syni` → `ai.synheart`. Artifact stays
  `syni-sdk`.
- Public API rewritten to match `package:syni` (Flutter) — Kotlin /
  Swift / Flutter SDKs now share one shape. App code is structurally
  identical modulo platform idioms (`Flow` / `AsyncSequence` / `Stream`).
- Package renamed `com.syni.sdk` → `ai.synheart.syni`.
- `Syni` singleton + `SyniRequest`/`SyniResponse`/`SyniResult` /
  `Persona` / `EngineType` / `generate()` / `downloadModel()` removed in
  favor of:
  - `SyniAgent` (constructed; not a singleton) with `install()`,
    `restoreInstallIfReady()`, `uninstall()`, `chat()`, `chatStream()`,
    `dispose()`, `installState`, `currentState`, `isInstalled`, `hasCloud`.
  - `SyniInstallState` sealed (`NotInstalled` / `Installing(stage,progress)`
    / `Installed(personaId,modelPath,runtimeVersion)` / `Failed(reason,cause)`)
    + `SyniInstallStage` enum.
  - `SyniChatResponse` / `SyniResponseKind` / `SyniChatEvent` sealed with
    `SyniChatDelta(text)` + `SyniChatFinal(response)`.
  - `SyniPersona`, `SyniSpecPersona` (loads bundled
    `assets/personas/prod/<id>.json`), `SyniSpecPersonaException`.
  - `SyniModelSpec`, `SyniModels`, `SyniModelOption` /
    `SyniLocalModel` / `SyniCloudModel`, `SyniModelCatalog`.
  - `SyniCloudConfig`, `SyniCloudException`, `SyniInstallException`.
  - `SyniExecutionMode { LOCAL_ONLY, CLOUD_ONLY, LOCAL_FIRST }`.
- In-process router / schema validator / budget enforcer / keyboard
  bridge removed: that logic now lives in the runtime (schema / budget)
  or the host SDK (persona routing).

### Notes

- Local streaming is V1 chunked-from-buffer: `chatStream` emits one delta
  with the full text followed by `SyniChatFinal`. Token-level streaming
  needs a new JNI binding to `syni_engine_run_stream_json`; the API
  contract is forward-compatible so consumers don't change.
- Cloud streaming is full SSE — content frames arrive as deltas.

## [0.0.1] - 2026-05-15

Initial pre-publication snapshot.
