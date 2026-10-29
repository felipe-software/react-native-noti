# Continuous Doom controls

Historical experiment, removed at the user's request. The current app uses [momentary controls with stable notification timestamps](doom-tap-controls.md). The `controls` option and held-key status fields described below are no longer part of the current API; the remaining text records the earlier implementation and its exact release.

2026-10-29. The demo keeps the live messaging-image renderer and adds continuous native controls. Tap a direction or Fire once to keep its Doom key pressed, then tap again to release it. Orange controls are active. Movement, turning and firing can be combined. Use remains a short press for doors and switches.

```ts
await Noti.startDoom({ renderer: 'live', controls: 'hold' });
const { heldControls } = await Noti.getDoomStatus();
await Noti.doomAction('pause'); // Releases every held key.
await Noti.stopDoom();
```

`controls: 'tap'` retains the earlier 250 ms movement/turn pulses and 160 ms fire pulses. It remains the library default; the demo opts into `hold`. Stop before changing renderers or control modes. In hold mode, `doomAction` toggles movement/fire just like the notification controls.

The five stateful controls use framework `CheckBox` views styled as game buttons. SystemUI changes their checked appearance locally and sends a native `RemoteViews.setOnCheckedChangeResponse` to an explicit service PendingIntent. Android supplies `RemoteViews.EXTRA_CHECKED`. The service then publishes its held-key state into both notification presentations; their checkbox instances are separate, so local state alone would be lost when switching between collapsed and expanded views. Changes are coalesced for 120 ms and still pass the existing notification rate guard. This removes the need to repeat taps or dispatch key events through JavaScript while a control stays active. The mutable PendingIntents are scoped to these explicit service actions; ordinary actions retain immutable intents.

Pause and Stop release every key. Resuming starts with the controls released. Closing the notification shade alone does not mean releasing a latched key; tap it off or use Pause. The foreground service continues to handle controls while JavaScript is backgrounded.

Android documents [checked responses and checked-state updates](https://developer.android.com/reference/android/widget/RemoteViews#setOnCheckedChangeResponse(int,%20android.widget.RemoteViews.RemoteResponse)). Its programmatic `setCompoundButtonChecked` does not dispatch a checked response, so restoring a notification's selected controls does not create a feedback loop. `CheckBox` is an allowed remote view on the supported Android versions; `ToggleButton` lacks the corresponding annotation in the checked Android 14 source.

The release was built with R8 and exercised through its real Play button and SystemUI on Xiaomi Android 14 and the Android 16 emulator. Local commands, native service state, recordings and screenshots are retained under `artifacts/doom-optimization/hold-controls/`; [structured results](doom-continuous-results.json) record the exact APK and checks. `dumpsys activity service dev.reactnativenoti.demo/expo.modules.notificationmotion.DoomService` prints the native status, including the held keys.

This is an interaction change. The native engine/encoder library is byte-identical to the preceding 40 ms release, with SHA-256 `59d320b4be03988a12664893132a161e7d51bb58fdbda3a1f5a2db65d0ece8eb`. It does not establish a new FPS or input-latency measurement, or eliminate the recorded playback reversals described in [live rendering](doom-live-rendering.md).
