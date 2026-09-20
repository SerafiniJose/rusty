# Build from source

**Toolchain:** JDK 17, Android SDK (compileSdk 36), Gradle 8.13 (via the wrapper), AGP 8.13.2, Kotlin 2.0.21.

```bash
git clone https://github.com/SerafiniJose/rusty.git
cd rusty
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
```

The Android build consumes **prebuilt native libraries** committed under
`app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libspotify_receiver_core.so`, so you do **not**
need the Rust toolchain to build the APK.

## Rebuilding the native core (only when Rust changes)

The native core lives in [`rust/`](../rust/). It cross-compiles with
[`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk), which writes the refreshed `.so`
files straight into `jniLibs` for both ABIs:

```bash
cargo install cargo-ndk                                    # one-time
rustup target add aarch64-linux-android armv7-linux-androideabi
export ANDROID_NDK_HOME=/path/to/ndk                       # NDK r27+

cd rust
cargo ndk -t armeabi-v7a -t arm64-v8a --platform 26 \
  -o ../app/src/main/jniLibs build --release
```

> **`--platform 26` is required.** The audio path is cpal's AAudio backend, which
> links `libaaudio.so` — and the NDK ships that library only for API ≥ 26 (which is
> also the app's `minSdk`). Omitting it fails to link with `unable to find library -laaudio`.

> The JNI symbol names (`Java_dev_rusty_app_NativeBridge_*`) are derived from the
> app package. If you ever change the package, the native symbols must be regenerated to match.

---

[← Back to the README](../README.md)
