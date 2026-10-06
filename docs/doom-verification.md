Doom notification demo validation — 2026-10-03

Validated on the dedicated `Noti_Doom_API_36` x86_64 emulator (`emulator-5556`),
Android API 36, using the Expo development client and Metro on port 8082.
The previously running emulator-5554 and Metro 8081 were not modified.

- `bun run typecheck` passes for the app and library.
- `:app:assembleDebug -PreactNativeArchitectures=x86_64 --max-workers=2` passes.
- Native engine builds pass on arm64-v8a, armeabi-v7a, x86 and x86_64.
- Native tests validate actual WAD rendering, position/angle changes, ammunition
  consumption, held-key release, pause/resume timing, JNI signatures and contained
  invalid-WAD initialization failure. See the engine README for reproducible tests.
- The app requests notification permission and starts the game from Play Doom.
- Expanded SystemUI notification displays the live 320×200 engine framebuffer,
  six control buttons, Pause/Resume and Stop Doom without clipping.
- Tapping Fire reduced ammunition from 50 to 49. Movement and turn taps visibly
  changed the viewpoint; backward and Use buttons were also exercised. Door
  activation itself was not independently verified in a level traversal.
- Pause changes the action to Resume; Resume restores gameplay.
- Stop Doom removes the notification and leaves no DoomService in `dumpsys`.
- Playing after Stop resumes the session and recreates the notification.
- Controls work with the notification shade covering the React activity. No
  AndroidRuntime/NotiDoom crash was observed during these checks.

Original baseline (before the height/FPS revision): the notification requested at most four publications per second; engine simulation
is independent at 35 ticks/s. This is not a claim of 35 displayed fps. A local
27.366-second recording (mostly intentionally paused) contained 15 significant
image changes, with about three changes per second in the five active input
seconds. Static game frames and the luma threshold affect this measurement.

Local artifacts (ignored by Git): `artifacts/doom-notification.png`,
`artifacts/doom-controls.mp4`, `artifacts/doom-notification.mp4` and
`artifacts/doom-visible-motion.json`. The latter was measured with
`measure-notification-fps.py --crop 817:420:179:836` using original timestamps.

All four configured Codex instances participated: 1F handled the native engine,
2F researched/reviewed notification integration, Matei/Sol prepared the isolated
device and usage monitor, and Felipe/Astra integrated and validated the demo.
Sanitized, event-driven quota and per-session usage snapshots are in the local
`artifacts/doom-monitor/` directory. Account quota snapshots are not per-task
budgets and may be stale while a provider is idle.


Height and FPS revision — 2026-10-03

- Expanded notification now measures 940 px at 420 dpi (358 dp), matching the
  emulator's `com.android.systemui:dimen/notification_max_height` resource.
  The game viewport grew from 160 to 200 dp. The redundant status line is hidden
  to give that space to the game; six controls and both system actions remain
  visible and clickable. Other Android/OEM layouts can impose different limits.
- Live native frames are sampled on a 33 ms deadline and delivered in batches of
  eight. SystemUI's ViewFlipper displays the batch locally, like Bad Apple,
  rather than asking NotificationManager to accept 30 updates per second.
  Published frames are 240×150 RGB565 to keep an eight-frame payload bounded;
  native rendering remains 320×200. Expected buffering is about 230–265 ms,
  plus SystemUI delivery time. This is a requested 30 fps, not guaranteed 30 fps.
- `artifacts/doom-30fps-final.mp4` records 80 right-turn taps spaced by 100 ms
  (10 ms holds) via agent-device. The 12.837 s recording has 257 significant
  image changes (20.02/s including startup/idle); steady moving windows mostly
  show 21–25 changes/s. Pixel-change analysis uses the original presentation
  timestamps and crop 817:525:179:836. `doom-30fps-final.json` contains details.
  The prior 4 Hz implementation showed about 2–4 changes/s during turn taps;
  the old capture used separately issued taps, so it is not a perfectly matched
  input benchmark. No claim of a locked 30 fps is made.
- Pausing switches to Resume and two screenshots a second apart have identical
  game pixels. Resume restores the Pause action. Fire was tapped successfully.
  Stop removes the notification and `dumpsys activity services` reports no service.
- Final x86_64 debug APK build, TypeScript checks and `git diff --check` pass.
  No AndroidRuntime, NotiDoom or NotificationService errors appeared in the
  captured validation logs. Installed only on Noti_Doom_API_36 / emulator-5556.
- All four configured Codex instances were attempted again. Felipe remains at
  its quota limit, so the primary reviewed native snapshot locking itself;
  Matei implemented batching and 2F reviewed height constraints. The existing
  sanitized event monitor tracked usage during this revision.

Input response and controls revision — 2026-10-03

- Saved the preceding complete implementation in local commit `d59ebb5` before
  editing. No push was performed. The changes below remain outside that checkpoint.
- Ordinary playback now uses seven 33 ms samples per batch. After an isolated
  input (350 ms without another control), the service prioritizes the latest
  snapshot after 60 ms for native simulation to react, respecting a 210 ms
  publication gap. It discards buffered older snapshots for this update. Normal
  publication cannot overtake that pending response. Repeated taps keep batching.
- `NotiDoomLatency` debug logging is opt-in with Android's log.tag property and
  records input receipt/publication uptime, never enabled by application code.
- Controls are 64 dp tall (previously 48), with 18 sp labels, inset-free backgrounds
  and a 60 ms XML state animator to scale X/Y to 0.95 while pressed and back to 1.
  The game area is 184 dp, keeping the combined 248 dp content budget and 358 dp
  expanded notification. The image is uncropped, bottom-aligned to the buttons;
  aspect-ratio bars can remain at the sides or above on other screen widths.
- Confirmed the scale animation in SystemUI while paused, in
  `artifacts/doom-press-feedback.mp4`. Android recreates decorated custom views
  during notification updates: a press spanning an update can be cancelled.
  One initial pause tap needed a retry; long-hold test taps were also interrupted.
  This platform limitation remains; no promise of continuous hold controls is made.
- Comparison recordings use three ordinary right-turn taps with the same script:
  `doom-tap-baseline.mp4` (checkpoint service, new layout) and `doom-tap-final.mp4`.
  Row-normalized horizontal edges across three world-image bands reject lighting
  flashes, HUD and weapon motion. Estimated first visible turns occurred
  478/344/313 ms after baseline telemetry taps and 85/65/160 ms after final taps.
  These are small emulator samples with differing game scenes, video frame
  quantization and unknown telemetry/encoder alignment, not a guaranteed
  touch-to-photon latency. Script, JSON and adjacent-frame visual evidence are
  saved as `doom-measure-input.py`, `doom-input-comparison.json` and
  `doom-input-comparison.png` in artifacts.
- Replayed the earlier 80-tap continuous-turn benchmark. Final recording
  `doom-input-final-fps.mp4` has 24–27 significant visible changes/s in steady
  movement windows (22.64/s including initial idle); original requested rate
  remains about 30 fps. Measurement details: `doom-input-final-fps.json`.
- Final x86_64 debug APK built and installed only on Noti_Doom_API_36 / emulator-5556.
  TypeScript checks and `git diff --check` pass. Pause freezes image pixels over
  one second, Resume restores playback, Stop removes the notification and service.
  No app AndroidRuntime or NotiDoom errors appeared in captured validation logs.
- All four configured Codex instances were attempted. Felipe is still quota-limited;
  the primary handled service changes, 1F implemented XML and analyzed video, and
  2F reviewed Android APIs. The existing sanitized usage monitor ran during work.

Samsung freeze revision — 2026-10-04

Validated on a Galaxy S24 (SM-S921B), Android 16 / One UI 8.5, release build
(arm64-v8a) over wireless adb. The emulator was not re-run for this revision.

- Root cause of the reported freeze: NotificationManagerService rate limiting.
  The previous build published about 4.3 updates/s; after about 8 s the device
  logged `Package enqueue rate is 6.16. Shedding …` and SystemUI received no
  further Doom update for the remaining 68 s while the app kept calling notify.
  Lifetime stats for the package showed 904 rate violations of 1151 enqueues.
  The freeze began before any input; it is most visible while moving because
  the last accepted batch keeps looping.
- AOSP's RateEstimator (alpha 0.7, 5 updates/s) updates its estimate only after
  an update is accepted. The logged 6.16 equals 1/(0.7 × 0.2318 s), which means
  this build updates the estimate before checking it: any steady cadence faster
  than about 286 ms is rejected, and rejected attempts keep it rejected.
- Every Doom notify now passes a model of that stricter estimator capped at
  4.5 updates/s. Because the estimate is a moving average, idle play sends
  15-frame batches (2 updates/s) to bank headroom; an isolated tap's response
  (latest frame after 60 ms, older frames dropped) and one follow-up update that
  ends its still are sent as soon as the model allows. A policy simulation of
  the estimator chose this over spending headroom on faster updates while
  interacting, which made later taps wait (p90 234 ms at one tap per 0.7 s).
- Frames moved from inline RGB565 bitmaps (240×150, about 500 KB per update to
  NotificationManager and every listener) to native 320×200 BMPs, each with its
  own URI, served by DoomFrameProvider from a 48-frame ring:
  - NotificationManager's per-update grants are revoked when a newer update
    drops a URI. A build relying on them crashed with
    BadForegroundServiceNotificationException when SystemUI inflated an update
    late. The provider is exported but requires STATUS_BAR_SERVICE (signature),
    which SystemUI holds (verified on this device), so revocation is harmless.
  - An intermediate build reused fixed slot URIs instead. Because each update
    reaches SystemUI about 220 ms after notify, a tap response inflated after
    its follow-up had repointed the slots replayed the follow-up's frames, then
    the follow-up played them again: visible flicker after taps. Per-frame URIs
    make every update show its own frames.
  - PNG was replaced with BMP because Bitmap.compress cost about 17 ms per frame
    (52% of a core on a provider binder thread).
  - Frames are returned as a seekable memfd. Through a pipe, ImageDecoder falls
    back to a Java stream; BMP frames then took about 350 ms (vs 85 ms) from
    notify to SystemUI binding the row.
  - Multi-frame batches are padded with their last frame to a full batch plus
    three, so a short or late batch holds its image instead of replaying.
- Results: no shedding, inflation failures, permission denials, SELinux denials
  or process deaths across the runs, including repeated shade close/reopen and
  row collapse/expand during play. Pause showed Resume and held the frame;
  Resume restored the Pause action and gameplay.
- Tap timing from NotiDoomLatency and SystemUI logs, final build, 29 scripted
  taps (25 registered): tap received → response posted p50 92 ms / p90 159 ms
  (including the 60 ms settle). Posted → row bound in SystemUI is about 290 ms
  and outside the app's control: NotificationManagerService holds every post
  for DELAY_FOR_ASSISTANT_TIME (200 ms) while a notification assistant is
  enabled (here com.google.android.ext.services), then SystemUI inflates.
  Tap → bound for isolated taps measured p50 235 ms; matching bind events to
  updates in logs is approximate (±1 update), so these are estimates.
- With per-frame URIs a tap response is a single image; SystemUI binds it
  54 ms (p50) after listeners receive it, versus about 105 ms for 18-image
  batches. Posted → bound for tap responses: p50 269 ms, p90 301 ms. Tap
  received → response posted: p50 101 ms, p90 223 ms (26 of 29 taps
  registered). No denials, expired frames, shedding or crashes, including
  repeated row collapse/expand and shade close/reopen.
- CPU while playing (top, per thread, BMP frames): app 43% (native engine 25%,
  main 12%), down from 87% with PNG frames; SystemUI 83%, mostly its main and
  render threads drawing and re-inflating the notification.
- Each update still rebuilds the controls, so a tap spanning an update can be
  lost: 25 of 29, 17 of 19 and 32 of 38 scripted taps registered in three runs.
