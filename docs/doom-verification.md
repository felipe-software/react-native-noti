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
