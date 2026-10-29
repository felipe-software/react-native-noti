# Live Doom image transport

2026-10-29. This follow-up implements a second Doom renderer and validates a complete, minified Expo release APK. It follows the [ViewFlipper optimization](doom-optimization.md), which remains available.

The [continuous-controls experiment](doom-continuous-controls.md) was subsequently removed at the user's request. The current demo again uses [momentary controls](doom-tap-controls.md), with stable notification timestamps. The performance recordings on this page describe the earlier tap controls and their recorded APKs.

The later [maximum image size update](doom-max-size.md) enlarges the canvas to 952×595 and the per-slot budget to 64 KiB. The performance recordings below retain their original 640×400 canvas and 32 KiB budget.

The substantial change is the data path. A standard messaging notification contains an animated image. SystemUI's own animated-image player reads a seekable file descriptor supplied by the app; those reads receive recent encoded game frames. The game still runs in the app. NotificationManager no longer carries each gameplay frame deck.

## Implementation

`DoomLiveSession` exposes a fixed-layout GIF through `StorageManager.openProxyFileDescriptor`. Each reader freezes complete encoded frames during a forward read pass. A producer update between two reads cannot replace half of that frame. Rewinding starts a new pass with recent data. The descriptor uses the native `O_DIRECT` flag: the ordinary cached proxy initially replayed its first pixels indefinitely in the experiment.

There are four animation slots. The renderer measured here used a 32 KiB encoded-data budget per slot and a fixed 640×400 GIF canvas made from the engine's 320×200 pixels; the size follow-up changes those to 64 KiB and 952×595. Native LZW compression keeps offsets stable. A fixed RGB332 palette quantizes colors; unusually complex frames can reduce spatial detail to stay within the packet budget. This is a quality tradeoff relative to the original BMP renderer, not lossless transport of the original RGB pixels.

The animation now requests 40 ms steps (a 25-step/s request). The producer still samples every 33 ms and the Doom simulation still runs at 35 Hz. The earlier 20 ms request produced frequent short backward frame transitions in the Xiaomi recording; a longer 16-slot animation did not remove them. A full cadence sweep selected 40 ms; 50 ms did not further reduce recorded reversals and displayed fewer distinct frames. A decoder schedule does not establish the number of distinct visible game frames, and this work does not establish 60 unique frames/s.

Controls retain their separate foreground-service notification. The image notification refreshes metadata roughly every five seconds and expires after ten seconds without a refresh. Its message timestamp and live URI stay stable, allowing SystemUI to retain its existing readers. Pause publishes an immutable one-frame image; Resume restores the live URI. Closing a session rejects new opens while allowing already-open readers to finish with complete data.

When opening a live reader fails, the service continues the same game with ViewFlipper and reports `renderer: 'flipper'`. This handles file/proxy capability errors; it cannot detect every possible OEM rendering or animation policy. The provider uses the same signature-permission pattern as the original Doom image provider. No notification-listener service is part of the production renderer.

The underlying APIs are public. Android documents the [seekable proxy descriptor](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,%20android.os.ProxyFileDescriptorCallback,%20android.os.Handler)) and [read callbacks](https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback). AOSP's [animated-image notification manager](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/notification/ConversationNotifications.kt) starts and stops animated messaging images with notification visibility. Supplying a changing, frame-consistent animation through that path is the experimentally verified combination here, not a documented universal live-video contract.

## Measurements and validation

The [structured results](doom-live-results.json) select matched recordings and include the complete metric definitions, counts, frame ages and capture conditions. Earlier exploratory or failed-setup runs are excluded. The baseline for this follow-up is the already-optimized ViewFlipper service with stable controls, rendered-input acknowledgements and deferred decks.

The first complete integrated emulator comparison, using the earlier 20 ms request with the device preview enabled, measured repeated-input median response at 651 → 170 ms, with p90 787 → 259 ms. Recorded game-body changes increased from 10.4 to 13.3/s using the same pixel-change threshold. Both variants received all 72 offered taps. There were no mismatched top/bottom frame markers in the live run, and its metadata refreshes did not create readers beyond the three initial opens.

The second emulator pair, also using the earlier 20 ms request, closed the live device preview while keeping recording enabled. It measured repeated-input median response at **654 → 177 ms**, with p90 **900 → 229 ms**. Isolated-input medians were **487 → 161 ms**. Recorded game-body changes increased **9.5 → 13.4/s**, and distinct native frame IDs **11.4 → 14.7/s** during repeated input. All 72 taps were received in each run. The live run had zero mixed markers or backward frame transitions; the ViewFlipper run had two backward transitions. All 1,096 live encodes retained spatial sample step 1, with RGB332 color quantization. There were three reader opens and 11 notification calls, versus 116 calls for ViewFlipper. These short pairs do not isolate the causal cost of the device preview; their results are reported separately.

The selected Xiaomi pair uses the same archived comparison APK with recording enabled and device preview closed for both variants. Repeated-input median response was **521 → 172 ms**, with p90 **606 → 202 ms**. Isolated-input median response was **549 → 172 ms**. Both received all 72 offered taps. During repeated input, distinct recorded native frames were **17.1 → 17.6/s**, and game-body changes **14.8 → 17.0/s**. Thus the response gain is much larger than the motion-rate gain. The live recording still has **9 backward frame transitions among 1,093 verified samples**, versus zero in its ViewFlipper baseline. No mixed top/bottom frame markers were decoded; two ambiguous bottom-marker samples were excluded. The stream is more responsive but does not eliminate every visible playback irregularity.

Response time means service receipt to the first recorded image whose native simulated state includes that input. It excludes touch dispatch before service receipt. Multiple inputs can be acknowledged by one image. Clock calibration and capture sampling add uncertainty. The pixel-change measurement excludes the lab marker strips and is not a display-refresh or maximum-FPS measurement. Short paired runs do not establish sustained performance on every device.

Completed checks include the full Expo release build with R8, actual React Native Play → notification controls → Pause/Resume → Sound → Stop, independent JVM decoding of 24 native GIF fixtures, native gameplay/JNI tests, seven Android proxy consistency/lifetime checks, injected reader-failure recovery, and producer-death cleanup without a SystemUI restart. The final results record the tested APK hashes and any remaining device validation.

After the user unlocked the physical Xiaomi, the live stream and full minified Expo app ran on Android 14 / HyperOS 2. All six controls, stable pause pixels, Resume, Sound and Stop passed, as did all seven proxy consistency/lifetime checks. The first 20 ms comparison received all 72 taps in each variant and reduced repeated-input median response from 530 to 152 ms. However, its live recording contained 71 backward frame transitions, so its higher pixel-change rate does not establish uniformly smoother forward motion. Native reader snapshots were monotonic and no mixed top/bottom markers were decoded; the recorded replay cause has not been fully isolated. Earlier collapsed-image and locked-screen attempts remain excluded.

## Using the renderer

The demo's Play button opts into live rendering. Existing callers retain the previous default:

```ts
await Noti.startDoom({ renderer: 'live' }); // Experimental live image transport.
await Noti.stopDoom();
await Noti.startDoom({ renderer: 'flipper' }); // Original frame colors and frame decks.
```

`Noti.startDoom()` continues to select ViewFlipper. Stop before changing renderers. `DoomStatus.renderer` reports the active renderer, including an automatic fallback. This remains a native pipeline when JavaScript is suspended.

Reproduce the SDK-built comparison with [the native lab](../tools/doom-lab/README.md). The complete Expo release is built with:

```sh
bun install --frozen-lockfile
bun run prebuild
cd apps/demo/android
./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a \
  -Pandroid.enableMinifyInReleaseBuilds=true --max-workers=2
```

This produces `apps/demo/android/app/build/outputs/apk/release/app-release.apk`, with JavaScript bundled; Metro is unnecessary for that APK. Local recordings and full command traces are under ignored `artifacts/doom-optimization/`.
