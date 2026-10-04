This is the actual Doom engine (doomgeneric) embedded for the Android notification
demo. It runs original Doom gameplay at 35 tics per second on a native worker and
publishes 320×200 opaque ARGB frames. Kotlin polls snapshots at its own notification
refresh rate. The exact Kotlin API is in `docs/doom-engine-contract.md` at repository
root. Sound and multiplayer are disabled.

`vendor/doomgeneric` contains unchanged upstream code from
https://github.com/ozkl/doomgeneric at commit
`dcb7a8dbc7a16ce3dda29382ac9aae9d77d21284`. The normal upstream Makefile's engine
source list is explicit in CMake; desktop platform backends are not built.
`doom_guard.c` replaces process termination and selects an app-private config
directory. Its C-only setjmp boundaries prevent Doom's `I_Error`/`I_Quit`/`exit`
from terminating the Android host. An engine fatal error permanently disables
that process's engine instance because Doom does not support safe reinitialization.

`stop()` pauses and releases input, retaining the engine's globals and worker
until process exit. The worker sleeps on a condition variable while stopped.
The game clock excludes this suspended interval, so resuming does not simulate
the intervening wall time. No JNI references or Activity/Service instances are
retained by native code.

Game data is the complete Freedoom Phase 1 0.13.0 IWAD, bundled in
`../assets/doom/freedoom1.wad`. Engine/license files and Freedoom license/credits
are also packaged as Android assets. Engine and adapter source are GPL-2.0-or-later;
Freedoom game data is BSD-3-Clause. Preserve the licenses/credits and provide the
corresponding source and build instructions with binary distributions under the
engine's GPL terms. This does not relicense existing unrelated repository files.

Recreate the pinned source and assets from official upstream archives:

```sh
python3 packages/react-native-noti/android/src/main/cpp/prepare_sources.py
```

The script validates both archive hashes and the IWAD hash. Source/data are checked
in, so ordinary Gradle builds need no network fetch. WAD SHA-256:
`7323bcc168c5a45ff10749b339960e98314740a734c30d4b9f3337001f9e703d`.

Gradle's `externalNativeBuild` compiles the shared library automatically. To compile
native code alone, without building/installing an app (run at repository root):

```sh
cmake -S packages/react-native-noti/android/src/main/cpp -B /tmp/noti-doom-arm64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/27.1.12297006/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/noti-doom-arm64
```

The port was compiled with NDK 27.1.12297006 for all four standard Android ABIs.
The consuming app can use its own NDK version. `libnoti_doom.so` has 16 KiB ELF
segment alignment. Upstream emits warnings for its intentional vanilla-overflow
pointer truncation and unsigned angle conversion; those are unchanged upstream.

Host integration validation needs CMake, a C/C++ compiler, and a JDK with
`JAVA_HOME` set. These tests run the real engine and IWAD, without an emulator:

```sh
cmake -S packages/react-native-noti/android/src/main/cpp -B /tmp/noti-doom-host -G Ninja \
  -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/noti-doom-host
ctest --test-dir /tmp/noti-doom-host --output-on-failure
javac -d /tmp/noti-doom-java packages/react-native-noti/android/src/main/cpp/tests/JniSmoke.java
java -Djava.library.path=/tmp/noti-doom-host -cp /tmp/noti-doom-java \
  expo.modules.notificationmotion.JniSmoke /tmp/noti-doom-host/test-assets/freedoom1.wad
```

The gameplay test inspects real player coordinates, view angle, ammunition, and
tic count exclusively after stopping the worker, and exports a diagnostic frame
to `/tmp/noti-doom-smoke.ppm`. It verifies opaque frames, movement, turning, firing,
pause/resume, released controls, and simulation timing. JNI tests exercise the exact
class and native method descriptors with a host JVM. The Android UI/service still
needs device validation by the application owner.
