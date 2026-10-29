The Doom demo runs Freedoom in two Android notifications: an animated game picture and a separate row of native controls. The demo selects the live messaging-image renderer. Its game image fills the available width without cropping, each control tap releases automatically, and notification updates retain a stable sorting timestamp.

This is the current guide. [Research and older measurements](doom-research.md) record how the implementation was selected; the [native engine contract](doom-engine-contract.md) covers JNI, input acknowledgements, audio and source provenance. The broader [ViewFlipper investigation](viewflipper-limits.md) covers notification and widget capabilities beyond Doom.

## Use

Start from a visible app action after obtaining notification permission:

```ts
if (await Noti.requestPermission()) {
  await Noti.startDoom({ renderer: 'live' });
}

const status = await Noti.getDoomStatus();
await Noti.doomAction('pause'); // Toggles Pause/Resume.
await Noti.doomAction('mute');  // Toggles game audio.
await Noti.stopDoom();
```

Expand **Doom** and **Doom controls** in the notification shade. Movement and Use press their native key for 250 ms; Fire presses for 160 ms. Repeated taps extend that key's pending release. Pause and Stop release pending keys.

The service handles notification actions and frame production without JavaScript ticks. Stop pauses the native engine; playing again resumes the same session until the app process closes. A fatal engine error requires a fresh process. Audio is enabled by default and follows native audio focus; the Sound action mutes it.

`Noti.startDoom()` without options still defaults to ViewFlipper for compatibility. Stop before switching renderers. `status.renderer` reports the active renderer, including automatic fallback to ViewFlipper if opening the live stream fails. Status frame counters and requested cadence describe production/scheduling, not measured screen FPS. See [the public types](../packages/react-native-noti/src/types.ts).

## Rendering and notification order

The live path uses `Notification.MessagingStyle` with an `image/gif` attachment. SystemUI's animated-image player reads a seekable proxy file supplied by the app. This path is separate from ViewFlipper and from the library's general `Noti.Gif` component. The game simulation and audio remain in the app process.

| Setting | Current value |
| --- | --- |
| Native game framebuffer | 320×200 opaque ARGB pixels |
| Simulation | 35 Hz |
| Source sampling request | Every 33 ms |
| Encoded GIF canvas | 952×595, full 8:5 game view |
| Animation request | 40 ms per step |
| Stream | Four slots, 64 KiB encoded-data budget per slot |
| Color encoding | RGB332, with bounded spatial fallback for complex frames |

`DoomLiveSession` uses `StorageManager.openProxyFileDescriptor` and a native `O_DIRECT` descriptor. Each reader freezes a complete encoded frame for all partial reads of that slot; a rewind starts a new pass with recent data. This avoids mixing frames during decoding. Slot sizes and offsets stay fixed. Ordinary cached proxy reads replayed their initial pixels in the experiment.

The 952 px width fills the tested hosts while remaining below a verified animated-image sampling threshold: a 960 px GIF was reduced to 480 px on the emulator. Supplying a larger image therefore does not always produce a larger visible game. The 64 KiB budget preserves spatial sample step 1 for the real Doom test fixture; color quantization still applies. Image size also depends on SystemUI, display density, font scale and OEM layout.

The controls card belongs to the foreground service. Movement, Fire and Use do not republish it. Pause/Resume and Sound update that card while retaining its session `Notification.when`. The image also retains a stable, later timestamp across heartbeats and paused snapshots. `setShowWhen(false)` only hides the clock; it does not freeze this sorting time. This removed the observed control-card jump above the game. System ranking can also consider channel settings and other criteria.

The image refreshes metadata roughly every five seconds and expires after ten seconds without a refresh. Pause serves an immutable one-frame snapshot; Resume restores the live URI. Stop removes both cards. The producer must remain alive to generate new frames. Opening the live stream can fall back to ViewFlipper, but the app cannot detect every OEM decoder or animation policy. The underlying [proxy descriptor API](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,%20android.os.ProxyFileDescriptorCallback,%20android.os.Handler)) is public; this changing-animation combination remains experimentally verified behavior rather than a universal live-video contract.

Implementation: [service and controls](../packages/react-native-noti/android/src/main/java/expo/modules/notificationmotion/DoomService.kt), [live stream](../packages/react-native-noti/android/src/main/java/expo/modules/notificationmotion/DoomLiveFrames.kt), [native GIF encoder](../packages/react-native-noti/android/src/main/cpp/doom_gif.c).

## Validation and performance scope

Latest verified release: `doom-tap-controls-release.apk`, SHA-256 `92c0e11c292fc4b6728fa3d16854624c49b72dc73f8ef30da49b3c98b6626f44`. It was built with R8, installed and exercised through the real app and SystemUI on both devices. The [release results](doom-tap-controls-results.json) contain the exact APK, native-library hash, checks and measured bounds.

| Device | Expanded live and paused image | Latest checks |
| --- | --- | --- |
| Xiaomi Redmi Note 12 Pro 5G, Android 14 / HyperOS 2 | 815×509 px | 30 passed |
| Pixel 8 emulator, Android 16 | 817×510 px | 30 passed |

On each device, all six buttons released automatically, repeated taps released after the final pulse, Pause/Resume and Sound worked, and Stop removed the service. The game remained above the controls at 14 recorded stages, including a paused-image heartbeat and reopening the shade. The larger-image revision also verified visible motion, identical paused pixels, and 24 GIF fixtures against an independent decoder's expected quantized pixels; see [size validation](doom-max-size-results.json).

The latest size and ordering fixes have not received a new sustained FPS or input-latency benchmark. Earlier matched Xiaomi trials of the **640×400, 32 KiB** live renderer reduced repeated-input median service-receipt-to-recorded-response time from 521 to 172 ms against the optimized ViewFlipper renderer. Recorded motion improved less, and some backward frame transitions remained. Those figures belong to the older measured APK, not the current larger canvas. [Research and metric definitions](doom-research.md#live-renderer-measurements) preserve the comparisons and their limits.

## Build and reproduce

The demo requires Android API 31+, the installed Android SDK/NDK and JDK, and notification permission. From the repository root:

```sh
bun install --frozen-lockfile
bun run prebuild
cd apps/demo/android
./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a \
  -Pandroid.enableMinifyInReleaseBuilds=true --max-workers=2 --console=plain
```

Set `ANDROID_HOME` and `JAVA_HOME` for the local toolchain if needed. The output is `apps/demo/android/app/build/outputs/apk/release/app-release.apk`, with JavaScript bundled; Metro is unnecessary for that APK. The retained local tested APK is under ignored `artifacts/doom-optimization/`.

Use [the native comparison lab](../tools/doom-lab/README.md) for bounded recordings, rendered-input markers and proxy consistency checks, [the height lab](../tools/notification-height-lab/README.md) for static layout probes, and [the native build README](../packages/react-native-noti/android/src/main/cpp/README.md) for engine/JNI tests and licensing. Static layout probes do not replace live-GIF checks. Historical comparisons require their recorded APK/source snapshot; rebuilding the lab uses today's renderer settings.
