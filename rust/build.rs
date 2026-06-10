fn main() {
    // The bundled Oboe C++ (built by oboe-sys) pulls in C++ runtime symbols such
    // as `__cxa_pure_virtual`. Ensure `libc++_shared.so` is recorded as a NEEDED
    // dependency of the cdylib so the dynamic linker can resolve those symbols at
    // load time on Android. Without this, a build can end up with `__cxa_pure_virtual`
    // undefined and no NEEDED entry for libc++_shared, causing dlopen to fail with
    // "cannot locate symbol __cxa_pure_virtual". The Android app additionally preloads
    // it via System.loadLibrary("c++_shared"); cargo-ndk puts the NDK sysroot lib dir
    // (which contains libc++_shared.so) on the link search path.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-lib=dylib=c++_shared");
    }
}
