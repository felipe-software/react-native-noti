Native Doom JNI contract

Kotlin owner should create this exact class in `expo.modules.notificationmotion`:

```kotlin
object DoomEngine {
  init { System.loadLibrary("noti_doom") }
  external fun start(wadPath: String): Boolean
  external fun render(): IntArray?
  external fun key(key: Int, pressed: Boolean)
  external fun stop()
}
```

- `wadPath` is an absolute, readable file path. Copy the bundled Android asset `doom/freedoom1.wad` into app-private files before calling `start`.
- First `start()` waits for engine initialization; call it on a background thread. It returns false for unreadable/invalid WADs, initialization errors, or a different WAD after initialization. Runtime engine errors are logged as `NotiDoom`, stop frame delivery, and require a fresh process before starting again.
- Native code owns the engine thread and real Doom 35 Hz simulation. Kotlin should only poll `render()` at the notification refresh rate; it must not drive simulation ticks.
- `render()` returns the latest 320 × 200 frame as 64,000 opaque ARGB pixels (row-major), suitable for `Bitmap.setPixels`. Returns null before the first frame or while stopped. All calls are thread-safe.
- `start()` is idempotent while running. `stop()` pauses the engine and releases held controls; another `start()` with the same WAD resumes the session. Native Doom's global state is retained until process exit. Switching WADs in one process is unsupported.
- Keys are Doom key values, NOT Android KeyEvent codes: forward/up `0xAD`, backward/down `0xAF`, turn left `0xAC`, turn right `0xAE`, fire `0xA3`, use/open `0xA2`, strafe left `0xA0`, strafe right `0xA1`, strafe modifier/right-alt `0xB8`, run/right-shift `0xB6`, enter `13`, escape `27`. Use `0xA2` for doors; space is not bound to use in doomgeneric.
- Notification buttons should send key down then key up after a short pulse (e.g. 180–250 ms), or implement explicit toggles. Repeated presses must replace/extend any pending release for that key. Fire can be held.
- Starts directly in Freedoom Phase 1 E1M1, normal skill, sound disabled.

Implementation and provenance:

- CMake entry: `packages/react-native-noti/android/src/main/cpp/CMakeLists.txt`; Gradle builds and packages `libnoti_doom.so` automatically. Consumer R8 rules preserve the JNI binary names. The library uses a static C++ runtime and 16 KiB ELF segment alignment.
- [doomgeneric upstream](https://github.com/ozkl/doomgeneric), pinned commit `dcb7a8dbc7a16ce3dda29382ac9aae9d77d21284`, is vendored unchanged under `cpp/vendor/doomgeneric`. Engine and adapter licensing: GPL-2.0-or-later. Platform exit/error/configuration entry points are substituted via CMake compilation definitions and `doom_guard.c`; no engine source patch is needed.
- [Freedoom Phase 1 0.13.0](https://github.com/freedoom/freedoom/releases/tag/v0.13.0), complete unchanged `freedoom1.wad` (28,795,076 bytes), supplies redistributable BSD-3-Clause game data. The Android `doom/` assets include both licenses, Freedoom credits, and a notice. No proprietary Doom game data is used.
- `python3 packages/react-native-noti/android/src/main/cpp/prepare_sources.py` reproduces engine/data downloads with pinned SHA-256 checks. Native/app builds do not download anything. See `cpp/README.md` for build/test commands and distribution notes.

Validation completed without building/installing the Android app or interacting with any emulator:

- Android NDK 27.1.12297006, API 31, CMake native compile/link passed for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- Linux host gameplay integration test passed against the bundled real WAD: nonblank 64,000-pixel ARGB frame, player movement, turning, weapon ammunition consumption, stopped state, key release, and 35 Hz timing excluding paused duration (18 tics in approximately 500 ms).
- Malformed IWAD initialization test passed: engine failure is contained without terminating the process.
- Host JVM exercised the exact JNI class/method signatures, ARGB frame shape, input, and stop/resume successfully.
- Android runtime and notification rendering remain for the app owner to verify after the Kotlin service/UI integration.
