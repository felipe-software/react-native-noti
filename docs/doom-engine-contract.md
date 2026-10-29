Native Doom JNI contract

The Kotlin owner is [DoomEngine.kt](../packages/react-native-noti/android/src/main/java/expo/modules/notificationmotion/DoomEngine.kt) in `expo.modules.notificationmotion`, with these JNI signatures. The [Doom guide](doom.md) documents the current notification renderer and public API.

```kotlin
object DoomEngine {
  init { System.loadLibrary("noti_doom") }
  external fun start(wadPath: String): Boolean
  external fun render(): IntArray?
  external fun renderState(state: LongArray): IntArray?
  external fun key(key: Int, pressed: Boolean)
  external fun keyTracked(key: Int, pressed: Boolean): Long
  external fun stop()
  external fun setVolume(volume: Float)
}
```

- `wadPath` is an absolute, readable file path. Copy the bundled Android asset `doom/freedoom1.wad` into app-private files before calling `start`.
- First `start()` waits for engine initialization; call it on a background thread. It returns false for unreadable/invalid WADs, initialization errors, or a different WAD after initialization. Runtime engine errors are logged as `NotiDoom`, stop frame delivery, and require a fresh process before starting again.
- Native code owns the engine thread and real Doom 35 Hz simulation. Kotlin should only poll `render()` at the notification refresh rate; it must not drive simulation ticks.
- `render()` returns the latest 320 × 200 frame as 64,000 opaque ARGB pixels (row-major), suitable for `Bitmap.setPixels`. Returns null before the first frame or while stopped. All calls are thread-safe.
- `renderState(state)` returns the same pixels and matching metadata in an array of at least five longs: frame sequence, applied input ID, discarded-through input ID, game tic, monotonic render time in nanoseconds. Null or shorter arrays throw `IllegalArgumentException`. The legacy `render()` remains available.
- `keyTracked(key, pressed)` returns a positive monotonic ID for an accepted event, or zero otherwise. A rendered response requires `appliedInput >= requestedId` and `discardedThrough < requestedId`. Queue receipt alone is insufficient. Stop and queue overflow invalidate earlier IDs. A wrapper records input in an actual tic command; `DG_DrawFrame` acknowledges it after that command's game tic is simulated and rendered. An acknowledged input need not change pixels, for example Use away from a door.
- `start()` is idempotent while running. `stop()` pauses the engine and releases held controls; another `start()` with the same WAD resumes the session. Native Doom's global state is retained until process exit. Switching WADs in one process is unsupported.
- Keys are Doom key values, NOT Android KeyEvent codes: forward/up `0xAD`, backward/down `0xAF`, turn left `0xAC`, turn right `0xAE`, fire `0xA3`, use/open `0xA2`, strafe left `0xA0`, strafe right `0xA1`, strafe modifier/right-alt `0xB8`, run/right-shift `0xB6`, enter `13`, escape `27`. Use `0xA2` for doors; space is not bound to use in doomgeneric.
- The notification service sends key down then releases movement/Use after 250 ms and Fire after 160 ms. Repeated presses replace/extend that key's pending release. The native key API can express down/up states, but the notification UI uses momentary buttons; the continuous-toggle option has been removed.
- `setVolume(volume)` sets the master gain from 0 to 1. Audio plays through an AAudio stream (48 kHz stereo, game usage) only while the engine runs and the volume is above 0; `stop()` and volume 0 pause output, holding sound effects and music where they are. Callers own audio focus.
- Starts directly in Freedoom Phase 1 E1M1, normal skill, with sound effects and OPL music.

Implementation and provenance:

- CMake entry: `packages/react-native-noti/android/src/main/cpp/CMakeLists.txt`; Gradle builds and packages `libnoti_doom.so` automatically. Consumer R8 rules preserve the JNI binary names. The library uses a static C++ runtime and 16 KiB ELF segment alignment.
- [doomgeneric upstream](https://github.com/ozkl/doomgeneric), pinned commit `dcb7a8dbc7a16ce3dda29382ac9aae9d77d21284`, is vendored unchanged under `cpp/vendor/doomgeneric`. [Chocolate Doom 2.2.1](https://github.com/chocolate-doom/chocolate-doom/tree/chocolate-doom-2.2.1) (commit `2ddd290b8226e1d1f0c52e344e76150261a8c0c3`) supplies the OPL music player, MIDI parser and DOSBox OPL emulator, vendored unchanged under `cpp/vendor/chocolate-doom` (GPL-2.0-or-later). Engine and adapter licensing: GPL-2.0-or-later. Platform exit/error/configuration entry points are substituted via CMake compilation definitions and `doom_guard.c`; no engine source patch is needed.
- [Freedoom Phase 1 0.13.0](https://github.com/freedoom/freedoom/releases/tag/v0.13.0), complete unchanged `freedoom1.wad` (28,795,076 bytes), supplies redistributable BSD-3-Clause game data. The Android `doom/` assets include both licenses, Freedoom credits, and a notice. No proprietary Doom game data is used.
- `python3 packages/react-native-noti/android/src/main/cpp/prepare_sources.py` reproduces engine/data downloads with pinned SHA-256 checks. Native/app builds do not download anything. See `cpp/README.md` for build/test commands and distribution notes.

Initial port validation, before the October 28 service revision:

- Android NDK 27.1.12297006, API 31, CMake native compile/link passed for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- Linux host gameplay integration test passed against the bundled real WAD: nonblank 64,000-pixel ARGB frame, player movement, turning, weapon ammunition consumption, stopped state, key release, and 35 Hz timing excluding paused duration (18 tics in approximately 500 ms).
- Malformed IWAD initialization test passed: engine failure is contained without terminating the process.
- Host JVM exercised the exact JNI class/method signatures, ARGB frame shape, input, and stop/resume successfully.

`doom_input.c` wraps `G_BuildTiccmd`, renaming only the upstream implementation symbol at compile time; vendored sources remain unchanged. Host gameplay/JNI tests cover rendered-input acknowledgement, pause invalidation and metadata argument validation. [Historical trials](doom-research.md#earlier-viewflipper-trials) distinguish the isolated service tests from subsequent full-app validation.

The optional live renderer adds `DoomGifEncoder` JNI methods in `doom_gif.c` without changing the engine's 35 Hz simulation. Consumer R8 rules preserve its native class names. The latest complete arm64 Expo release was built with R8 and passed 30 real-app control/order checks on each of the Android 16 emulator and physical Xiaomi Android 14. See [the current guide](doom.md) and [release results](doom-tap-controls-results.json); older renderer performance measurements are indexed in [the research record](doom-research.md).
