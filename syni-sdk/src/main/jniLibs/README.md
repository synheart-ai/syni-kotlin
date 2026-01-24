# Native Libraries

This directory contains prebuilt native libraries for syni-runtime.

## Required Files

Place the cross-compiled `libsyni_ffi.so` for each target architecture:

```
jniLibs/
├── arm64-v8a/
│   └── libsyni_ffi.so      # 64-bit ARM (most modern devices)
├── armeabi-v7a/
│   └── libsyni_ffi.so      # 32-bit ARM (older devices)
├── x86_64/
│   └── libsyni_ffi.so      # 64-bit x86 (emulator, Chromebooks)
└── x86/
    └── libsyni_ffi.so      # 32-bit x86 (older emulator)
```

## Building for Android

To cross-compile syni-runtime for Android, you need:

1. Android NDK installed
2. Rust toolchain with Android targets

### Setup Rust Targets

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
rustup target add i686-linux-android
```

### Build Script

Create a `.cargo/config.toml` in syni-runtime with linker paths:

```toml
[target.aarch64-linux-android]
linker = "/path/to/ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang"

[target.armv7-linux-androideabi]
linker = "/path/to/ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi21-clang"

[target.x86_64-linux-android]
linker = "/path/to/ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/x86_64-linux-android21-clang"

[target.i686-linux-android]
linker = "/path/to/ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/i686-linux-android21-clang"
```

Then build:

```bash
cd syni-runtime
cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi
cargo build --release --target x86_64-linux-android
cargo build --release --target i686-linux-android
```

Copy the resulting libraries:

```bash
cp target/aarch64-linux-android/release/libsyni_ffi.so ../syni-kotlin/syni-sdk/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libsyni_ffi.so ../syni-kotlin/syni-sdk/src/main/jniLibs/armeabi-v7a/
cp target/x86_64-linux-android/release/libsyni_ffi.so ../syni-kotlin/syni-sdk/src/main/jniLibs/x86_64/
cp target/i686-linux-android/release/libsyni_ffi.so ../syni-kotlin/syni-sdk/src/main/jniLibs/x86/
```

## Minimum Supported Devices

- `arm64-v8a`: Android 5.0+ (API 21+) on 64-bit ARM
- `armeabi-v7a`: Android 4.0+ (API 14+) on 32-bit ARM
- `x86_64`: Android 5.0+ on x86_64
- `x86`: Android 4.0+ on x86
