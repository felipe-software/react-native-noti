# Doom responsiveness after the ViewFlipper investigation

The [October 29 live-renderer follow-up](doom-live-rendering.md) implements a further data-path change and validates the complete Expo release APK. This page records the preceding ViewFlipper-only revision and its two-device measurements.

2026-10-28. Implemented and measured using the actual native service and engine inside an isolated Android comparison APK. The Expo demo copy is updated; a complete Expo application build was not part of this validation.

During repeated input, median service-receipt-to-recorded-response time fell from **1,061 to 661 ms on the Android 16 emulator**, and from **1,051 to 520 ms on the Xiaomi running Android 14 / HyperOS 2**. The final implementation received all 144 offered taps across these two trials. These measurements exclude touch dispatch before the service receives the input.

## What changed

- **Stable controls:** the six buttons and Pause, Sound and Stop now live in a separate foreground-service notification. Game-picture updates no longer replace the row holding an in-flight press. The image uses a low-importance channel; controls use a default-importance channel with sound, vibration and badges disabled. This avoided automatic bundling on the tested Android 16 host. Existing user channel settings take precedence.
- **Shorter interactive history:** repeated input selects 10-frame decks; idle playback returns to 15 frames. Sampling and requested playback remain 33 ms; the engine remains at 35 simulation ticks/s. Ordinary notification updates still pass the existing conservative rate model. Its steady-state minimum interval is approximately 317 ms; the `4.5` parameter does not permit publishing every 222 ms.
- **Rendered-input acknowledgement:** native input receives a monotonic ID. A wrapper around the original command builder ties it to a simulated command; frame metadata acknowledges it after that command's game tic is rendered. The service no longer guesses a fixed 60 ms settling delay. Pause and queue overflow invalidate pending IDs; invalidated requests cannot qualify as responses merely because a later ID was rendered.
- **Frames selected when SystemUI reads them:** every publication gets unique deferred deck URIs. The first image read freezes one consistent deck from the newest captured frames, shared by every index even when decoding happens out of order. Later reads return identical bytes. A watermark prevents a newly frozen deck from selecting frames before the previously frozen deck. This reduces image age accumulated before decoding; it does not bypass notification delivery or posting limits.
- **Bounded lifetime:** the raw cache holds 48 frames and the deferred cache holds eight decks. Controls belong to the foreground service. The separate video expires after 10 seconds without a refresh; paused pictures refresh every five seconds and on screen-on. Stop and normal destruction cancel both notifications. No wake lock is added.

The two notification rows are a visible usability tradeoff: expand the game picture and use **Doom controls** for input. The provider retains seekable BMP memory files and its signature permission. Deferred decks freeze once; they are not mutable slot URIs that change under an already displayed notification.

## Recorded comparison

Each device ran one complete baseline/candidate pair: 12 isolated right-turn taps and 60 repeated right-turn taps. The baseline scheduler and attached controls come from commit `b056478e9b930fd705ead02f3f1f7844bbd23151`. Both variants share the native engine with measurement metadata, the same lab-only frame marker and an initially muted game. Each trial starts a fresh producer process.

| Device and input | Received before → after | Median response before → after | p90 before → after |
| --- | --- | --- | --- |
| Android 16 emulator, isolated | 12/12 → 12/12 | 543 → 419 ms | 592 → 528 ms |
| Android 16 emulator, repeated | 45/60 → 60/60 | 1,061 → 661 ms | 1,263 → 816 ms |
| Xiaomi Android 14, isolated | 8/12 → 12/12 | 477 → 546 ms | 543 → 572 ms |
| Xiaomi Android 14, repeated | 52/60 → 60/60 | 1,051 → 520 ms | 1,152 → 584 ms |

Isolated taps on Xiaomi did **not** get faster. The baseline lost four of those taps, so these distributions contain different received populations. The supported conclusion is improved receipt on both devices and substantially fresher responses during sustained input in these runs.

Median native rendered-frame age during repeated input fell from 1,026 to 613 ms on the emulator and from 943 to 498 ms on Xiaomi. A separate notification-listener APK observed every publication in the four selected trials: 101/101 and 125/125 on the emulator; 96/96 and 110/110 on Xiaomi. The retained notification-service logs contain no package rate-shedding messages. Notify-to-observer medians remained approximately 221–259 ms.

The analyzer identifies actual game images in SystemUI recordings using a frame-ID strip and joins them to native rendered-input metadata. A binary clock in the launcher calibrates video timestamps to device uptime. The metric starts at service receipt, not finger-down. Sampling and calibration add uncertainty of roughly one captured frame. Requested repeated-tap spacing was 100 ms; actual received intervals were slower and device-dependent, and are retained in the results. These short paired runs are not a performance guarantee.

The full emulator recordings each contained one backward frame-ID transition; the Xiaomi recordings contained none. This does not establish that every host transition or replay is eliminated. No claim of 60 unique game frames/s follows from this work. The previously affected Samsung still needs a matched run with this revision.

## Validation and reproduction

The [native Doom lab](../tools/doom-lab/README.md) builds the production Kotlin, engine and resources directly with the Android SDK. It keeps baseline and candidate in an isolated package and observes delivery from a separate package. Trial commands and video decoding have deadlines.

Completed checks: arm64 Android native/Kotlin/resource build; host gameplay and JNI tests; demo TypeScript checks; seven Android cache checks; and emulator lifecycle checks. Cache checks cover out-of-order first reads, frozen repeat reads, advancing decks, no-new-frame holds, paused captures, bounded retention and clear. Lifecycle checks cover pause/resume, all six control receipts, sound-action round trip, Stop cleanup and process-death cleanup. See [implementation results](doom-implementation-results.json). Full videos and traces remain locally in ignored `artifacts/doom-optimization/`.

The final tested APK SHA-256 is `a2d8381c9e29eec6ebc6a3675bc7d82c75f6c1a72b9cff350229260fee02a21a`. Selected trials: `emulator-baseline-2`, `emulator-latched-1`, `xiaomi-baseline-2`, `xiaomi-latched-1`. Earlier folders contain exploratory variants or failed setup attempts and are excluded from the final comparison.

The preceding transport-only probe remains in [transport results](doom-optimization-results.json). Its approximately 1.9 ms warm direct-bitmap saving was measured in a local inflation harness and did not establish an input-latency improvement. Production retains BMP/memfd transport. Broader source and device findings remain in [ViewFlipper limits](viewflipper-limits.md) and the [Xiaomi follow-up](viewflipper-xiaomi.md).
