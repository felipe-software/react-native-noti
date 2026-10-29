# ViewFlipper investigation

2026-10-28 · Initial run: Android 16 / API 36, Pixel 8 ARM64 emulator, 1080×2400 at 420 dpi. Follow-up: physical Redmi Note 12 Pro 5G, Android 14 / HyperOS 2.

**The demonstrated design space is very broad. It is bounded by Android's transport, host execution, memory, timing, lifecycle and input rules.** Generated imagery can depict anything the app can render, but a ViewFlipper does not become a general application runtime inside SystemUI.

This investigation reads the actual notification library, the sibling Every Second Pays implementation, and AOSP `android-16.0.0_r1`, then exercises the platform directly. It adds a standalone [native lab](../tools/viewflipper-lab/README.md), [machine-readable observations](viewflipper-limits-results.json), and a [source manifest](viewflipper-limits-sources.json). Production app/library behavior was not changed.

The [physical Xiaomi comparison](viewflipper-xiaomi.md) adds a second OS/OEM target: 86 matching probe outcomes, an unavailable DrawInstructions API, different local timing and delivery behavior, and confirmed notification playback after producer exit. It includes Android 14 source baselines and separate raw evidence. The detailed measurements below describe the original emulator run unless stated otherwise.

## What the projects establish

The Doom demo runs the native game engine in the application's process. It renders frames, serves immutable per-frame BMP URIs from a bounded ring, and publishes a deck for SystemUI to flip locally. Controls return through PendingIntents. The game simulation and audio remain app work. Its earlier [verification notes](doom-verification.md) already document Samsung rate shedding, delayed inflation, URI revocation, buffering and lost control taps.

The sibling app at `../every-second-pays` is especially instructive. `MoneyAmbient.kt` calculates seeded random lanes, collection events, mirroring and positions in app code. `MoneyWidgetRenderer.kt` builds up to 150 future frames into `RemoteCollectionItems`. Compiled XML animations carry bills, digits and aurora blobs between those snapshots. `MoneyWidgets.kt` replaces the timeline with partial updates and schedules non-wakeup resynchronization alarms. Random trajectories are computed by the producer; the launcher plays the supplied timeline and animations. With no future refresh, a finite timeline eventually repeats or becomes stale.

These are complementary mechanisms:

| Mechanism | Where new state is computed | What the remote host does |
| --- | --- | --- |
| Bitmap/URI frame deck | App renderer, decoder, game engine or server-fed producer | Select and draw supplied images |
| Property snapshots | App serialization | Apply permitted transforms to supplied child views |
| XML tween/layout/state animations | Compiled resources describe the motion | Interpolate and draw locally |
| AdapterViewFlipper timeline | App builds collection items | Materialize/recycle a small active view set |
| DrawInstructions / RemoteCompose | App encodes a compatible document | Evaluate supported expressions and draw operations locally |

## Boundaries measured directly

The lab completed 87 probes, including expected failures. No assertion/expectation mismatches remained. Tests distinguish local inflation, parcel deserialization, actual system-service acceptance, and real SystemUI/launcher rendering; these are different levels of evidence.

| Experiment | Observation | Consequence |
| --- | --- | --- |
| 13, 60, 120 and 512 direct children | All inflated; 512 text children took about 46 ms in the final local run | The library's 12-child guard is not an Android child-count limit. This is a tested lower bound, not a proven maximum. |
| 10 versus 11 nested `RemoteViews.addView` wrappers | 10 accepted; 11 rejected with `Too many nested views` during unparceling | Android has a separate nested-parcel limit. |
| 32 levels inside one compiled XML layout | Accepted | XML nesting and nested RemoteViews parcels are different quantities. |
| 4,718,592 inline bitmap bytes in a notification | Custom expanded view retained | Below this build's strip threshold. |
| 5,242,880 inline bitmap bytes in a notification | Notification remained, custom expanded view removed | A successful notify call does not prove that custom content survived. |
| 12,582,912 inline bitmap bytes in a widget | Accepted by AppWidgetManager | Widget budget differs from the notification budget. |
| 16,777,216 inline bitmap bytes in a widget | Rejected; error reported maximum 15,552,000 | This emulator's budget is exactly `6 × 1080 × 2400` bytes. |
| 96 URI images, 160×120 | Local inflation decoded all 96; metadata parcel was 16,640 bytes; actual SystemUI playback also worked | URI transport removes inline pixel payload, while decoded host memory remains about 7.37 MB for these ARGB images. |
| Same 128×128 bitmap reused 20 times | Parcel 3,740 bytes versus 6,704 for 20 distinct bitmaps | Bitmap identity affects caching; small Parcel.dataSize alone does not describe pixel memory or shared-memory descriptors. |
| 1,400 repeated large metadata actions | Notify threw TransactionTooLargeException, reporting 1,481,044 bytes | Image-free metadata can still exhaust Binder. This is one rejected sample, not an exact universal threshold. |
| 150 collection items in a real AppWidgetHostView | Adapter count 150, active child-view count 2 | A collection can reduce live hierarchy cost dramatically. Serialized data still occupies memory. |
| Same remote adapter outside AppWidgetHostView | Adapter not bound; framework logged widget-only restriction | Inflating AdapterViewFlipper in a notification does not make its collection transport work. |

AOSP has a default 2,000,000-byte notification warning threshold and a 5,000,000-byte strip threshold, applied to estimated bitmap memory in each custom presentation. These are resource defaults, not a universal five-megabyte Binder allowance. The widget service uses its display-derived bitmap budget. The Binder transaction buffer is shared by in-flight transactions, so reserve margin instead of treating one measured size as guaranteed capacity. [Notification checks](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/notification/NotificationManagerService.java#8910), [resource defaults](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/res/res/values/config.xml#5960), [widget budget](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/appwidget/java/com/android/server/appwidget/AppWidgetServiceImpl.java#480), [Binder documentation](https://developer.android.com/reference/android/os/TransactionTooLargeException).

## Timing, updates and lifecycle

An earlier local Choreographer run sampled selected-child changes at approximately 60/s with a 1 ms interval, 60/s at 8 ms, 57/s at 16 ms, 29/s at 33 ms and 15/s at 66 ms. The final retained run measured about 39, 37, 39, 29 and 15/s respectively. The environment changed between runs, including the addition of a launcher flipper widget; this uncontrolled comparison does not isolate the cause. Both timing series are retained in the JSON. They sample view state, not pixels delivered to the display.

Recorded notification pixels told a different story. In approximately nine-second windows, the 1 ms configuration yielded 260 significant changes / 8.966 s, about **29.0/s**; the 33 ms configuration yielded 178 / 8.958 s, about **19.9/s**. The 1 ms recording itself contained only 262 decoded samples in that window. It cannot establish whether the physical display changed faster. Encoder sampling, host load, capture overhead and scheduling affect the result. These measurements establish observed behavior, **not a universal 30 fps ceiling**, and the two recordings are separate runs on a populated emulator.

ViewFlipper accepts negative, zero and very small intervals through the remote setter on this build. That setter has no runtime clamp. Nonpositive values were tested only on a non-autostarting view; the lab does not run an unbounded zero-delay host loop. The timer uses `postDelayed` after advancing the child, so it has no media presentation timestamps, catch-up scheduler, per-frame durations or synchronization contract with audio. A variable-duration movie requires resampling, repeated frames, differently configured timelines, or another playback mechanism. [ViewFlipper scheduling](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/widget/ViewFlipper.java#98).

Notification update frequency is separate again. An independent listener APK observed eight-second posting phases:

| Offered updates/s | Calls | Unique posted sequences received | Median send → listener |
| --- | --- | --- | --- |
| 2 | 16 | 16 | 212 ms |
| 4 | 32 | 32 | 210 ms |
| 8 | 64 | 40 | 210 ms |
| 20 | 160 | 44 | 208 ms |

All offered calls returned normally. The missing sequences demonstrate silent shedding/coalescing at the posting boundary; the source and system logs identify rate limiting. Listener receipt is not proof of a displayed frame. The observer has a separate package because registered listener packages are explicitly exempt from these enqueue checks. AOSP defaults to five updates/s with an exponentially weighted interarrival estimator, rather than a fixed "five calls in each second" bucket. Other exemptions and OEM implementation differences exist. A live notification assistant on this emulator accounts for a source-level 200 ms deferral before posting; that delay is not a ViewFlipper requirement. [Rate checks](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/notification/NotificationManagerService.java#9027), [estimator](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/service/notification/RateEstimator.java), [assistant deferral](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/notification/NotificationManagerService.java#9666).

Host lifecycle also matters:

- Setting a flipper itself to `GONE` advanced its index from 0 to 18 in 650 ms while its window remained visible. Hidden descendants can retain active timers.
- Detaching the same view stopped index advancement. `isFlipping()` still returned true: it reports the started flag, not proof that frames are currently advancing.
- A text-only reapply preserved index 7; explicitly sending `setDisplayedChild(0)` reset it. Host reinflation can still reconstruct the entire hierarchy.
- A pinned launcher widget continued animating after the lab PID was terminated and verified absent. Its preloaded deck continued looping; it did not generate new app state.
- The RemoteCompose circle also kept moving after producer termination. In a 39.2-second capture its teal centroid traversed downsampled x positions 27.5–41.5. Whole-crop change thresholds missed that small moving object, so centroid tracking is the useful evidence here.

These do not prove survival across force-stop, reboot, launcher/SystemUI restart, lock screen or Doze. They prove the specific producer-exit and visibility cases. ViewFlipper's Android 16 source checks window visibility and started state; older versions have different user-presence handling. [Lifecycle implementation](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/widget/ViewFlipper.java#139).

## Motion and input beyond a frame deck

All ten tested remote float setters worked: alpha, rotation, rotation X/Y, scale X/Y, translation X/Y/Z and elevation. This supports programmatically generated transform snapshots, including 3D-looking flips. They are individual property updates, not an unrestricted animator-construction API.

`ViewFlipper.setAutoStart(boolean)`, `ViewAnimator.setAnimateFirstView(boolean)` and `AdapterViewFlipper.setFlipInterval(int)` failed through RemoteViews because they lack the required annotation. Those properties can still be configured in a compiled layout where supported. ViewFlipper's own `setFlipInterval` is annotated. The exact method signature and concrete view class matter. The full runtime method inventory is in the results JSON. [Remote method gate](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/widget/RemoteViews.java#2143), [adapter API implementation](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/widget/AdapterViewFlipper.java#126).

Two small experiments reduce the need for large decks:

- One child in a ViewFlipper ran an infinite 700 ms XML rotation. It does not require two distinct images to animate.
- A TextView carrying a compiled `stateListAnimator` translated and rotated continuously without any flipper. Local property readings confirmed movement; a separate SystemUI recording captures the notification version. State animators can provide continuous or state-triggered motion using a small hierarchy.

Compiled tween sets, layout animations, interpolators, state animators, drawables and property snapshots can be combined. Arbitrary bitmaps can represent shaders, particles, games or video computed elsewhere. That expands visual content without allowing arbitrary app classes, Java callbacks or a game loop to execute in the remote host. The API 36 inflation probe accepted 21 framework classes, including RadioGroup; it rejected View, Space, EditText, WebView, SurfaceView, TextureView, VideoView, ScrollView and an unannotated custom TextView subclass. The production library's JavaScript runtime does not migrate into SystemUI.

Input follows its own geometry. A legacy XML translation moved the visible button by 120 pixels, but an injected local-host touch reached it at its original layout bounds and missed the translated visual bounds. The test counted immediate ACTION_DOWN events, not asynchronously posted clicks. A tween's drawn position is therefore insufficient to define an input target. Property transforms have different view-matrix behavior and need their own interaction checks. Native PendingIntent clicks and the existing Doom controls remain useful, but they do not provide arbitrary pointer streams, held-key guarantees, drag/joystick input or unrestricted touch listeners. Notification reinflation can interrupt a press; the prior Doom recordings already show lost taps. [ViewAnimator tween behavior](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/widget/ViewAnimator.java#160).

For URI streams, maintain immutable frame identities, retain files long enough for delayed inflation, and handle grants and provider/process lifetime explicitly. Do not equate URI transport with zero memory or mutate a file while an older presentation may still decode it. The existing Doom ring solves a real asynchronous-consumer problem; enlarging a deck simply moves more cost into SystemUI. Widget image URIs have additional scheme/access checks; notification URI permissions also change as updates replace one another.

## The additional programmable surface

Android's public `RemoteViews.DrawInstructions` API reaches the platform RemoteCompose player. The lab sent a **119-byte** document, a **188-byte** RemoteViews parcel, and inflated `com.android.internal.widget.remotecompose.player.RemoteComposePlayer`; its reported document API level was 6. SystemUI displayed the resulting circle with a time-based sine expression, with no bitmap deck and no producer ticks. The producer was then terminated, and the motion continued in the host.

This is the strongest new direction from this investigation: supported expression/drawing operations can evaluate dynamic visuals in the remote host. It is a separate mechanism from ViewFlipper. AOSP includes arithmetic, paths, text, layout, animation and action operations; operation availability must be matched to the device's player/profile. It does not imply every current AndroidX RemoteCompose operation works on every platform player. [Public API](https://developer.android.com/reference/android/widget/RemoteViews.DrawInstructions), [platform integration](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/widget/RemoteViews.java#5894), [operation definitions](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/com/android/internal/widget/remotecompose/core/Operations.java).

An initial rootless prototype triggered a NullPointerException in `CoreDocumentAccessibility` during accessibility inspection and restarted SystemUI. Adding the proper RootLayoutComponent corrected this tested case; subsequent inspection and the completed lab run survived. The bad prototype is not retained as a demo. This is a reason to use structurally valid, version-compatible documents and verify semantics, interaction, sizing and lifecycle before integration. AndroidX RemoteCompose was still alpha22 on 2026-10-28; the library's version and the embedded platform player's protocol are separate compatibility questions. [Release status](https://developer.android.com/jetpack/androidx/releases/compose-remote).

## Where to push next

The clearest implementation direction is a renderer that chooses among a small XML hierarchy, a recycled widget timeline, a bounded URI frame stream and a compatible RemoteCompose document. Keep input and renderer state independent, measure observed delivery, and adapt batch length/resolution to actual host behavior.

Further experiments that were **not completed here** are a multi-OEM/API matrix; sustained temperature/battery profiling on hardware; screen-off/Doze/reboot and host-restart recovery; long-running phase drift; URI eviction and late-reader stress; stable-control reuse during real notification updates; full RemoteCompose semantics, gestures and state transitions; animated drawable startup/state transitions; and a fair peak-FPS benchmark with independently verified capture throughput. The Android 16 emitter is not integrated into the React Native library.

The library's 96-node serializer budget, seven-level serializer nesting budget, 512-node native budget, 12 direct-child guard, 48 forward-frame/94 reverse-snapshot limits and 3.5 MB image budget are application choices. Relaxing them can unlock designs, but a useful replacement is a budget based on the selected rendering path and measured payload/host costs. The Android nested-parcel limit can be lower than the renderer's own 12-level guard.

The original emulator evidence is scoped to `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`. Existing product widgets and a notification assistant were present. The linked Xiaomi follow-up adds a physical Android 14 build, while preserving the original observations. Neither run predicts behavior on every phone. This investigation establishes specific capabilities, failure modes and a repeatable way to continue testing them; it does not claim that every device or possible composition has been exhausted.

Local screenshots, recordings, per-frame analyses, listener logs and the initial accessibility trace are under `artifacts/viewflipper-limits/` (ignored by Git). The checked-in JSON retains the structured probes, delivery summary and recording summaries so the conclusions remain reviewable after cloning.
