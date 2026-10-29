**Doom now fills the available chat-image width while keeping the complete 8:5 game view.** On the Android 16 emulator, the actual image grew from 640×400 to **817×510 pixels**, a 27.5% increase in each dimension and approximately 63% more visible area. Both the live animation and paused image use the larger bounds. This is the maximum landscape width observed in this messaging template; taller source images can be cropped rather than making the full game larger.

After the user unlocked the physical Xiaomi, the same release displayed the complete game at **815×509 pixels**, up from 640×400: approximately 27.3% larger in each dimension and 62% more area. The phone retains these bounds when paused.

The subsequent [control update](doom-tap-controls.md) removes latched movement/fire and restores momentary buttons. This page's validation records the preceding size-update APK; the larger renderer remains in use.

The encoded GIF canvas is **952×595**. That choice avoids a verified animated-image resizing problem: a first 960×600 implementation appeared as only 480×300 on the emulator. AOSP's default `LocalImageResolver` samples in powers of two against a 480 px reference, so 960 px can trigger 2× sampling. The static PNG layout probe did not reveal this live-GIF behavior. The selected 952 px width is the largest integer 8:5 canvas below that threshold and exceeds the measured available image width. See [LocalImageResolver](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/com/android/internal/widget/LocalImageResolver.java) and the earlier [height investigation](doom-notification-height.md).

The original engine still renders 320×200 pixels. Native nearest-neighbor enlargement preserves the full game rather than inventing extra scene detail. The encoder keeps RGB332 colors, four animation slots and the 40 ms requested cadence. Its fixed encoded-data budget increases from 32 to **64 KiB per slot**: the real Doom fixture needed 53,586 compressed bytes to retain spatial sample step 1 at the new size. Keeping the old budget forced step 4 on that fixture. The bounded fallback also accepts step 32 for difficult inputs at the smaller supported budget. The seekable stream, stable URI, controls and notification update cadence retain their existing behavior.

The minified arm64 Expo release passed these checks:

- Twenty-four native GIF fixtures decoded pixel-exactly through independent JVM ImageIO, including a real Doom frame without spatial reduction at the production 64 KiB budget.
- Nineteen real-app checks on Android 16: expanded dimensions, continuous controls, collapse/expand state, combined movement/fire, Pause/Resume, screen-off release and Stop.
- Sixteen real-app checks on the Xiaomi Android 14 / HyperOS phone: expanded dimensions, continuous controls, collapse/expand state, combined movement/fire, Pause/Resume and Stop. The screen-off check remains emulator-only to avoid returning the phone to its secure lock screen.
- Actual game screenshots changed while turning on both devices; paused game pixels were identical in two captures. Pause retained 817×510 bounds on the emulator and 815×509 on the Xiaomi.
- Release build with R8, TypeScript checks and whitespace checks passed.

The same APK was installed and exercised on both devices. The Xiaomi check was completed after the user unlocked the phone; no production code or APK change was needed for that follow-up. The earlier Xiaomi latency/FPS measurements describe older APKs.

This update does not establish a new FPS or input-latency measurement. A larger canvas and doubled packet budget add work; the visible-motion check confirms animation rather than proving equal sustained performance. Historical latency/FPS comparisons in [live rendering](doom-live-rendering.md) used the earlier 640×400 canvas.

Release APK: `artifacts/doom-optimization/doom-max-size-release.apk`, SHA-256 `2dc08f9fccab267cbc54163a99ec3a8f5f37e787e91392746a9b00162deb20da`. [Structured validation](doom-max-size-results.json). Local command traces, recording, screenshots and pixel checks are in `artifacts/doom-optimization/max-size/`.
