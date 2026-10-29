# Doom notification comparison lab

This isolated native APK compiles the repository's actual Doom engine, Kotlin service, provider and layouts without generating an Expo application. Its package is `dev.noti.doomlab`; separate `dev.noti.doomobserver` records callbacks for that producer only. It does not change the sibling application.

Mode `baseline` uses the service and attached-control layout from commit `b056478e9b930fd705ead02f3f1f7844bbd23151`. Mode `candidate` uses the current ViewFlipper service; `live` uses the production animated-image stream. All share the current engine with frame/input telemetry, receive the same lab-only frame marker and start muted. Baseline scheduling stays unchanged. A launcher binary clock calibrates video timestamps to device uptime before the shade opens. Instrumentation and test broadcasts exist only in this harness.

Build at the repository root:

```sh
python3 tools/doom-lab/build.py --abi arm64-v8a
```

Requires Android SDK platform/build-tools 36, NDK `27.1.12297006`, SDK CMake `3.22.1`, JDK 17, and Kotlin `2.1.20` compiler JARs in the Gradle dependency cache. The script uses `ANDROID_HOME` and `JAVA_HOME`, with local macOS SDK/SDKMAN defaults; it does not install missing dependencies. Outputs, temporary signing key and source/APK SHA-256 manifest go to ignored `tools/doom-lab/build/`.

Each build also archives its APK, manifest and source snapshot under ignored `artifacts/doom-optimization/builds/<apk-sha256>.*`. Keep that snapshot with recordings: later source edits do not change the APK used for an earlier trial.

Optional `--live-slots 4|16|32` and `--live-delay-ms 20|30|40|50` change only the instrumented live renderer for focused diagnostics; the build manifest records both overrides. Omit them for production-source comparisons and proxy consistency tests. The latter deliberately compare complete encoded bytes against the production encoder.

Install both APKs on the explicitly selected device, grant the producer notification permission and enable the observer for the test. Physical devices may require installation or notification-access confirmation. The listener filters by producer package before reading extras, but Android grants notification access to the service at system level; remove it afterward.

```sh
adb -s DEVICE install -r tools/doom-lab/build/doom-lab.apk
adb -s DEVICE install -r tools/doom-lab/build/doom-observer.apk
adb -s DEVICE shell pm grant dev.noti.doomlab android.permission.POST_NOTIFICATIONS
adb -s DEVICE shell cmd notification allow_listener dev.noti.doomobserver/dev.noti.doomobserver.DoomListener
```

Open the observer once through the device tool if needed to clear its freshly installed stopped state. Verify `files/connected.txt` via `run-as` contains a numeric uptime. Select the device through T3 `device_open` and copy its exact launcher, host config and session; sessions differ between devices.

```sh
python3 tools/doom-lab/run-trial.py \
  --agent /path/from/device_open/agent-device \
  --serial DEVICE --config HOST_CONFIG --session DEVICE_SESSION \
  --mode baseline --out artifacts/doom-optimization/DEVICE-baseline-N
python3 tools/doom-lab/run-trial.py \
  --agent /path/from/device_open/agent-device \
  --serial DEVICE --config HOST_CONFIG --session DEVICE_SESSION \
  --mode candidate --out artifacts/doom-optimization/DEVICE-candidate-N
python3 tools/doom-lab/analyze.py artifacts/doom-optimization/DEVICE-baseline-N
python3 tools/doom-lab/analyze.py artifacts/doom-optimization/DEVICE-candidate-N
```

For the live follow-up, run a matched `candidate` / `live` pair using the same commands, changing `--mode` and output directories. Keep recording, device-preview, audio and notification-expansion conditions consistent. The live image must be expanded; the runner rejects collapsed thumbnails. Legacy exploratory mode `gif` is not the integrated production renderer.

The trial replays 12 isolated and 60 repeated right-turn taps through live SystemUI controls. Targets come from the accessibility tree. Requested intervals differ from actual received intervals, which the analysis retains. Each variant starts fresh after a five-second quiet period. Trials have a 90-second alarm, commands 30-second deadlines, trace reads 10-second deadlines. Final cleanup may extend past the trial alarm while completing its bounded commands. The analyzer uses the Python standard library and FFprobe/FFmpeg with 20/30-second deadlines; NumPy is unnecessary.

`analysis.json` reports lost taps separately from service-receipt-to-recorded-state timing. Recorded frame IDs join metadata from a rendered game tic containing the input. Timing excludes touch dispatch before service receipt; sampling/calibration add uncertainty. Percentiles describe received inputs; one image can acknowledge several inputs. Frame age, callback matching, received input gaps and backward frame-ID transitions are retained separately. Live runs additionally require matching top/bottom markers to reject mixed frames. Game-body changes use a 32×18 RGB crop excluding markers and an adjacent-image difference threshold of 2/255. This differs from counting native frame IDs and does not measure display refresh rate. A configured interval does not establish 60 unique frames/s.

`DoomCacheTestActivity` runs seven deterministic cache checks. With no game service running, launch the full component `dev.noti.doomlab/expo.modules.notificationmotion.DoomCacheTestActivity`; read `files/doom-cache-test.json` using `run-as`. This activity exists only in the test APK.

`DoomLiveCacheTestActivity` similarly writes `files/doom-live-cache-test.json`, covering partial-read consistency, rewinds, slot snapshots, EOF, pause and close behavior. `tests/GifCodecSmoke.java` independently decodes 24 production-native encoder fixtures with JVM ImageIO; compile it into the host native build's JNI classes and pass an absolute 320×200 PPM path to include a real Doom frame. Mode `live-fallback` injects a reader-open failure in the instrumented lab copy to verify continued ViewFlipper gameplay. These hooks are absent from the release app.

Cleanup on each device used:

```sh
adb -s DEVICE shell cmd notification disallow_listener dev.noti.doomobserver/dev.noti.doomobserver.DoomListener
adb -s DEVICE uninstall dev.noti.doomobserver
adb -s DEVICE uninstall dev.noti.doomlab
```

See [live rendering](../../docs/doom-live-rendering.md) and its [results](../../docs/doom-live-results.json), plus the earlier [ViewFlipper responsiveness](../../docs/doom-optimization.md). Full recordings and command logs are local ignored artifacts.
