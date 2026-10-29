This is the consolidated research record for the Doom notification experiments. Use [the current guide](doom.md) for the supported API, renderer settings and latest release validation. The implementation evolved across these trials; each measurement applies to its recorded APK and capture conditions.

## Evidence index

The structured evidence is retained under its original filenames:

| Evidence | Scope |
| --- | --- |
| [Transport probes](doom-optimization-results.json) | Local bitmap/URI preparation, parcel and inflation costs; no end-to-end input measurement |
| [ViewFlipper implementation](doom-implementation-results.json) | Deferred decks, separate controls, native input acknowledgements and two-device trials |
| [Live renderer](doom-live-results.json) | Matched live/ViewFlipper recordings, cadence sweep, codec/proxy tests and release checks |
| [Continuous controls](doom-continuous-results.json) | Historical checkbox-toggle experiment; removed from the current API |
| [Static image height](doom-notification-height-results.json) | Four PNG layout probes on the emulator; no animation measurement |
| [Larger live image](doom-max-size-results.json) | 952×595 GIF canvas, codec fixtures and live/paused image bounds |
| [Current momentary controls](doom-tap-controls-results.json) | Latest release, automatic key release and stable card order on both devices |

Raw recordings, screenshots, traces and archived lab APK/source hashes remain in ignored `artifacts/doom-optimization/`. The [native lab](../tools/doom-lab/README.md) explains reproduction and cleanup. [ViewFlipper limits](viewflipper-limits.md) and the [Xiaomi comparison](viewflipper-xiaomi.md) retain the wider notification/widget investigation.

## Earlier ViewFlipper trials

The October 3 port ran the actual 320×200 Doom engine in an Expo development client on the x86_64 API 36 emulator `Noti_Doom_API_36` (`emulator-5556`, Metro 8082). Host tests covered the WAD, movement, turning, ammunition, 35 Hz timing, pause/resume, JNI signatures and contained initialization failure. Four Android ABIs compiled. Early attached-control versions moved from roughly four notification publications/s to 33 ms ViewFlipper batches of RGB565 images. Small recordings showed roughly 21–27 significant image changes/s during repeated turning. These were scene-dependent pixel-change estimates, not locked FPS or well-matched latency benchmarks. Local evidence includes `doom-30fps-final.json`, `doom-input-comparison.json` and `doom-input-final-fps.json` in artifacts.

On October 4, a Galaxy S24 (`SM-S921B`, Android 16 / One UI 8.5) exposed a notification freeze: enqueue-rate shedding began after about eight seconds, while the app continued calling `notify`. The implementation added a conservative rate-estimator model, idle headroom, prioritized isolated responses and padded decks. The 4.5 rate-model parameter did not mean updates could safely arrive every 222 ms; the modeled steady-state gap was approximately 317 ms.

Transport experiments supplied several lasting constraints:

- Notification-scoped URI grants can be revoked before delayed inflation finishes. The provider uses the SystemUI signature-permission pattern rather than relying solely on transient grants.
- Repointing fixed BMP slot URIs let delayed updates decode another update's frames. Immutable frame/deck identities prevent that mismatch. This differs from the later live GIF protocol, which freezes encoded frames per reader pass.
- PNG compression cost about 17 ms per frame in the Samsung investigation. BMP/memfd reduced producer work; a pipe caused a slower decoder fallback than a seekable descriptor.
- Reinflating a row can interrupt a press. Three attached-control Samsung runs received 25/29, 17/19 and 32/38 taps, motivating separate controls.

The October 28 ViewFlipper revision separated the controls into a foreground-service card, used 10-frame interactive/15-frame idle decks, and selected fresh frames when SystemUI first read each unique deferred deck. The cache held 48 frames and eight decks. A native wrapper tied input IDs to actual simulated/rendered tic commands, replacing a guessed settling delay. Invalidation on pause or queue overflow prevented a later input from falsely acknowledging an earlier discarded request.

| Device/input | Received before → after | Median response before → after | p90 before → after |
| --- | --- | --- | --- |
| Android 16 emulator, isolated | 12/12 → 12/12 | 543 → 419 ms | 592 → 528 ms |
| Android 16 emulator, repeated | 45/60 → 60/60 | 1,061 → 661 ms | 1,263 → 816 ms |
| Xiaomi Android 14, isolated | 8/12 → 12/12 | 477 → 546 ms | 543 → 572 ms |
| Xiaomi Android 14, repeated | 52/60 → 60/60 | 1,051 → 520 ms | 1,152 → 584 ms |

These isolated native-APK pairs used 12 isolated and 60 repeated taps per variant, a fresh producer process, a shared measured engine and the baseline scheduler from `b056478e9b930fd705ead02f3f1f7844bbd23151`. The candidate received all 144 offered taps across both devices, but isolated Xiaomi latency did not improve. The full Expo application was not rebuilt for that revision. The tested APK was `a2d8381c9e29eec6ebc6a3675bc7d82c75f6c1a72b9cff350229260fee02a21a`; the implementation JSON retains selected trial names and further measurements. The earlier local transport probe's roughly 1.9 ms warm direct-bitmap saving did not establish an input-latency improvement. The Samsung was not revalidated with the later renderer.

## Live renderer measurements

The October 29 live renderer moved gameplay images out of repeated RemoteViews deck updates and into SystemUI's animated messaging-image decoder. Ordinary cached proxy reads replayed initial pixels; a native `O_DIRECT` descriptor and complete per-pass frame snapshots made the changing stream usable. Native hardware-bitmap parcel experiments did not establish a shared mutable image transport.

The selected Xiaomi pair used one archived lab APK (`cacbc74ee491ece1801ab75c7b305b150c6661ce639ba7475cc69e3cd2628412`), a **640×400 canvas, 32 KiB slots and a 40 ms GIF request**. Both runs recorded the screen with device preview closed, started fresh and muted, and waited five seconds before testing. Live preceded ViewFlipper; the order was not randomized.

| Xiaomi measure | Optimized ViewFlipper | Live GIF |
| --- | --- | --- |
| Repeated-input median response | 521 ms | 172 ms |
| Repeated-input p90 response | 606 ms | 202 ms |
| Isolated-input median response | 549 ms | 172 ms |
| Received taps | 72/72 | 72/72 |
| Distinct recorded native frames during repeated input | 17.1/s | 17.6/s |
| Recorded game-body changes during repeated input | 14.8/s | 17.0/s |
| Backward recorded frame transitions | 0 | 9 among 1,093 verified samples |

The response gain was larger than the motion-rate gain. No mixed top/bottom markers were decoded; two ambiguous bottom-marker samples were excluded. All 1,219 live encodes used spatial step 1, with RGB332 colors. There was one reader open and 12 notification calls, versus 110 for the ViewFlipper run.

Earlier **20 ms** emulator pairs also improved response: with preview closed, repeated median 654 → 177 ms and p90 900 → 229 ms; isolated median 487 → 161 ms; body changes 9.5 → 13.4/s and distinct recorded frames 11.4 → 14.7/s. Both variants received 72 taps. Live had zero backward transitions, versus two in ViewFlipper. A preview-enabled pair measured repeated median 651 → 170 ms. These are different trials/settings from the selected Xiaomi pair.

The Xiaomi cadence sweep showed why a faster GIF request was not automatically better. The first 20 ms run had 71 backward transitions among 1,102 verified samples despite a repeated median near 152 ms. Sixteen slots did not remove replay. Full 30 ms trials still replayed, including 27 transitions with preview closed. The 40 ms selection reduced reversals to nine; 50 ms also had nine, with fewer distinct frames and slower repeated response (184 ms median). Native reader snapshots were monotonic; the recorded replay cause was not fully isolated. Closing preview did not remove it.

Response starts at service receipt and ends at the first recorded rendered state acknowledging the input. It excludes earlier touch dispatch. A native frame-ID strip and a binary launcher clock join recordings to native metadata; sampling/calibration introduce uncertainty, and one image can acknowledge multiple inputs. Game-body changes use a 32×18 RGB crop excluding marker rows with mean adjacent difference above 2/255. They are scene-dependent and can include replay, rather than measuring display refresh rate.

Validation at the recorded revisions included 24 independent GIF-decoder fixtures, seven proxy consistency/lifetime checks on both devices, a forced reader-open failure continuing the same game in ViewFlipper, producer-death notification cleanup, and full arm64 Expo releases with R8. Other ABIs' initial compile coverage does not imply they received the latest release's device validation. The larger current canvas has no new matched FPS/latency benchmark.

## Image sizing and controls

The static height probe supplied PNGs through the same MessagingStyle configuration on the emulator. The 640×400 source displayed at its own size; 1280×800 displayed at 817×510. Square and portrait inputs reached a 669 px image height within a 940 px row (358 dp at 420 dpi), but cropped the source. Both the emulator and Xiaomi exposed a `notification_max_height` resource of 358 dp. The static four-image probe ran only on the emulator; that dimension is not a universal usable-image guarantee.

The `messaging_image_max_height` resource was 136 dp, but [MessagingImageMessage.onMeasure](https://android.googlesource.com/platform/frameworks/base/+/aa9d89ca3c98/core/java/com/android/internal/widget/MessagingImageMessage.java) replaces the superclass measurement using incoming constraints and the drawable aspect ratio. Measured inline images exceeded that resource value. Its intrinsic-width cap also explained unused space around the initial 640 px image.

Live GIFs exposed an additional constraint: 960×600 became 480×300 on the emulator. [LocalImageResolver](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/com/android/internal/widget/LocalImageResolver.java) contains power-of-two sampling against a 480 px reference. The selected 952×595 canvas stayed below that threshold, displayed at 817×510 on the emulator and 815×509 on Xiaomi, and retained those bounds while paused. At the new size the real Doom fixture required 53,586 compressed bytes for spatial step 1; a 32 KiB budget forced step 4, so production moved to 64 KiB. Twenty-four fixtures decoded against their expected RGB332/sampling output. The size release was `2dc08f9fccab267cbc54163a99ec3a8f5f37e787e91392746a9b00162deb20da`.

The continuous-control experiment used native CheckBox remote views, explicit mutable checked-response PendingIntents and the framework's checked extra. CheckBox was allowed on Android 14; ToggleButton lacked the RemoteView annotation. Programmatic checked-state updates suppress callbacks. Separate collapsed/expanded checkbox instances required a 120 ms coalesced republish to keep them synchronized. Gameplay checks passed, but the interaction felt poor and republishing made the controls repeatedly acquire a newer notification timestamp. The user rejected this mode; its API, state fields, resources and mutable checked intents were removed.

The final momentary-control release stopped movement-triggered control republishes and fixed each card's session `Notification.when`. The game retained a later timestamp than the controls through Pause, Resume, Sound and image heartbeats. Hiding the clock with `setShowWhen(false)` alone did not stabilize sorting. AOSP [NotificationRecord](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/notification/NotificationRecord.java) uses valid provided timestamps for time-based ranking, while [NotificationComparator](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/notification/NotificationComparator.java) also considers other ranking criteria.

The latest release passed 30 checks per device, including 14 recorded order checkpoints. The native engine/GIF library matched the size release byte-for-byte (`9ac88cb8fe423f153c3e431be6b33bf2b5509c4cfa983d827a3a3ceb2aed08b0`). Exact APK hashes, bounds, checks and limitations remain in the evidence index rather than being treated as new FPS/latency results.
