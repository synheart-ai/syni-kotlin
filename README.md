# syni-kotlin

[![Maven Central](https://img.shields.io/maven-central/v/ai.synheart/syni-sdk.svg)](https://central.sonatype.com/artifact/ai.synheart/syni-sdk)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

> Source-available · Android SDK for [Syni](https://docs.synheart.ai/syni/overview) — adaptive on-device LLM inference with hybrid local/cloud chat, structured persona conditioning, and a streaming chat API designed for the UI thread.

---

## Features

- 🧠 **On-device inference** — Qwen 2 / 2.5 and Gemma 3 GGUF models out of the box; bring your own GGUF for other supported architectures.
- 🌐 **Hybrid local / cloud** — same agent API, choose execution mode per call (`LOCAL_FIRST`, `CLOUD_FIRST`, `LOCAL_ONLY`, `CLOUD_ONLY`).
- 🎭 **Versioned personas** — load by id from bundled [syni-spec](https://docs.synheart.ai/syni-spec/overview) JSON; the same id resolves to the same behavior on client and server.
- 🧵 **Coroutine-friendly** — `suspend` calls + `Flow<SyniChatEvent>` for streaming; safe to call from the main thread.
- 🔒 **Verified model downloads** — pinned SHA-256 per model, checked at install time.
- 📡 **Streaming chat** with token-level deltas (`SyniChatDelta`) plus a final structured response (`SyniChatFinal`).

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("ai.synheart:syni-sdk:0.0.2")
}
```

### Requirements

- Android 8.0+ (`minSdk 26`)
- Kotlin 2.0+
- Java 17 toolchain
- AGP 9.0+ (consumer side)

The native runtime (`libsyni_ffi.so`) ships inside the AAR for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`. No CLI step required on Android — Gradle pulls everything.

## Usage

A runnable example app lives in [`example-app/`](example-app/). Abridged:

```kotlin
import ai.synheart.syni.SyniAgent
import ai.synheart.syni.SyniModels
import ai.synheart.syni.SyniSpecPersona

class Demo(application: Application) {
    private val agent = SyniAgent(context = application)

    suspend fun start() {
        // Load a persona by id from bundled spec assets.
        val persona = SyniSpecPersona.load(context, "focus.coach.v1")

        // First-run install: download + verify the model, load the
        // engine, bind the persona. Emits lifecycle events on
        // `agent.installState: StateFlow<SyniInstallState>`.
        agent.install(
            persona = persona,
            model = SyniModels.qwen25_15bInstructQ4,
        )

        // Single-turn chat.
        val response = agent.chat("How can I focus right now?")
        println(response.displayText)
    }
}
```

### Streaming

```kotlin
agent.chatStream("hello").collect { event ->
    when (event) {
        is SyniChatEvent.Delta -> print(event.delta)
        is SyniChatEvent.Final -> println("\n[${event.response.displayText.length} chars]")
    }
}
```

### Hybrid local / cloud

```kotlin
val agent = SyniAgent(
    context = application,
    cloudConfig = SyniCloudConfig(
        baseUrl = "https://api.synheart.ai",
        authToken = { "<bearer-token>" },
        tenantId = "<tenant>",
        userId = "<user>",
    ),
)

agent.chat(
    message = "how was my recent session?",
    mode = SyniExecutionMode.CLOUD_FIRST, // try cloud, fall back to local
)
```

### Install lifecycle

The `installState` flow emits the full state machine — wire it into your UI to surface progress:

```kotlin
agent.installState.collect { state ->
    when (state) {
        SyniInstallState.NotInstalled -> showInstallPrompt()
        is SyniInstallState.Installing -> showProgress(state.stage, state.progress)
        is SyniInstallState.Installed -> showReady()
        is SyniInstallState.Failed -> showError(state.reason)
    }
}
```

Stages include `DOWNLOADING_MODEL`, `VERIFYING_MODEL`, `LOADING_ENGINE`, and `BINDING_PERSONA`.

## Where this fits

`syni-sdk` is the **agent layer** — inference, install lifecycle, persona binding, chat orchestration. It does not own:

- HSI signal collection (the [`synheart-core`](https://github.com/synheart-ai/synheart-core-kotlin) SDK does), or
- the four-authority gate (consent + capability + activation + session; also a host concern).

Synheart-ecosystem apps typically depend on `synheart-core` and use `SyniModule` (which wraps this SDK with those layers). Standalone use of `syni-sdk` is fully supported when you don't need the wider Synheart contract.

## Documentation

- [Syni overview](https://docs.synheart.ai/syni/overview) — Synheart's on-device LLM stack
- [Syni Spec](https://docs.synheart.ai/syni-spec/overview) — canonical persona / safety / schema contracts
- [Flutter sibling](https://github.com/synheart-ai/syni-flutter) — same agent surface, Dart idioms
- [Swift sibling](https://github.com/synheart-ai/syni-swift) — same agent surface, Swift idioms

## Contributing

This is a source-available repository. Issues and feature requests are welcome; pull requests are **not accepted** at this time. See [CONTRIBUTING.md](CONTRIBUTING.md) for the rationale and the supported contribution path. Security reports go through [SECURITY.md](SECURITY.md).

## License

[Apache 2.0](LICENSE) © Synheart.
