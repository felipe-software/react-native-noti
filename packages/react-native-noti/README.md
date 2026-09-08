# react-native-noti

`Noti` is an Android-only Expo Module for declarative custom notifications and home-screen widgets. React Native serializes a JSX scene into Android `RemoteViews`; SystemUI or the launcher owns and renders the resulting hierarchy.

Android 12 / API 31 or newer is required.

## Install

Install `react-native-noti`, add its config plugin to the Expo configuration, and run `bunx expo prebuild --clean --platform android`. Set the plugin option `widgets` to `true` to register the AppWidget provider. Plugin options and named animation changes require a native rebuild.

Built-in animations are `fade`, `crossfade`, `slideUp`, `slideDown`, `zoom`, and `spin`. See the [workspace README](../../README.md) for the usage example.

## Components

- `Noti.View`: column, row, or overlay composition.
- `Noti.Grid`: native grid with one to six columns.
- `Noti.Text` and `Noti.Button`: styled text and `PendingIntent` actions.
- `Noti.Image`: base64 data URI, `file://`, or `content://` image.
- `Noti.Gif`: time-aware GIF frames hosted by a native flipper.
- `Noti.Progress`: determinate or indeterminate native progress.
- `Noti.Chronometer`: host-side clock that does not require JavaScript ticks.
- `Noti.Spacer`: explicit empty geometry.
- `Noti.ScrollView`: bounded, controlled, or autoplay viewport.
- `Noti.ViewFlipper`: automatic or index-controlled child switching.
- `Noti.Loop`: sampled timing or spring animation.
- `Noti.AnimatePresence`: keyed enter and exit composition between updates.
- `Noti.Crossfade`: keyed opacity transition between snapshots.

Lengths use `rem`, where one rem serializes to 16 Android density-independent pixels. Presentation roots should normally use `width: '100%'`; fixed `rem` widths are best reserved for bounded children.

## Notifications

`Noti.create` returns the numeric Android notification ID. `Noti.update`, `Noti.scrollTo`, and `Noti.dismiss` accept that ID or a complete `{ id, tag }` identity. `Noti.listActive` and `Noti.adopt` reconnect to active notifications owned by the same application. Android identifies notifications by `(tag, id)` and does not allow adopting another application's notifications.

`collapsed` is required. `expanded` and `headsUp` are optional independent scenes. Android 12+ wraps each custom layout in its decorated notification template, and the usable size can vary by OEM, font scale, and system decoration.

Native action-row buttons are supplied through the notification `actions` option. `Noti.Button` creates a tappable action inside the custom scene. Both are delivered through `Noti.addActionListener` while the JavaScript runtime is alive.

## Widgets

Widgets use the same serializer and renderer as notifications. `Noti.widgets.requestPin` opens the launcher pin flow, `list` returns installed widget IDs, `update` targets one widget, and `updateAll` publishes a scene to every installed widget. Action and pin events are available through `addActionListener` and `addPinnedListener`.

The provider persists the last serialized scene and restores it after reinflation or process restart. A foreground service or headless task can call `Noti.widgets.updateAll` whenever the lyric snapshot changes. The launcher may pause or restart automatic flippers when the widget is not visible.

## Animation and GIF behavior

`Noti.Loop` samples runtime values into 2–48 forward frames. When no frame count is supplied, it targets approximately 30 fps. Reverse playback can produce up to 94 snapshots. Supported runtime values are opacity, translation, scale, rotation, and background color. Spring motion is sampled before publication; no physics engine runs in the remote host.

`Noti.Gif` creates a time-aware schedule of up to 30 frames by default and 60 at most. Original frame delays are preserved, repeated held frames reuse the same bitmap, and decoded frames are resized to the rendered bounds. The `automatic`, `smoothness`, `quality`, and `none` optimization modes trade dimensions against frame retention under a configurable memory budget. The default per-GIF budget is 2 MB.

## Limits

- 96 serialized nodes and seven nesting levels per presentation.
- 512 built nodes and 12 levels across a native render.
- Two to 12 direct `ViewFlipper` children.
- One to seven visible `ScrollView` rows and at most 12 autoplay rows.
- Up to 48 forward runtime frames or 94 snapshots with reverse playback.
- Up to 60 scheduled GIF frames and a 20 ms minimum GIF interval.
- A 3.5 MB decoded-image budget per rendered scene.
- Up to three native notification actions.
- Up to 32 managed notification identities per process.

These guards bound payload construction but cannot guarantee identical behavior across SystemUI and launcher implementations. Binder transaction size, host memory, background policy, layout measurement, and OEM restrictions still apply.
