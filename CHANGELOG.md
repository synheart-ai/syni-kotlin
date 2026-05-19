# Changelog

All notable changes to this package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed (BREAKING — pre-1.0, no consumers)

- Public API rewritten to match `package:syni` (Flutter) — Kotlin / Swift /
  Flutter SDKs now share one shape. App code is structurally identical
  modulo platform idioms (`Flow` / `AsyncSequence` / `Stream`).
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
