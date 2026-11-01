The delivery demo tracks a simulated order in one normal custom Android notification: a real Mapbox map that zooms in as the courier approaches, animated courier and home markers, and the ETA, distance and a progress track above the map. Mapbox renders off-screen about once every 4.7 seconds. Between renders, SystemUI only moves and scales the last one, so the map never costs a render per frame.

## Use

The map needs a public Mapbox token. The SDK download needs no secret token, because Mapbox's Maven repository now serves artifacts without credentials.

```sh
cp apps/demo/.env.example apps/demo/.env.local   # then add your pk. token
bun install --frozen-lockfile
bun run prebuild
ANDROID_HOME="$HOME/Library/Android/sdk" bun --cwd apps/demo android --device Pixel_8_API_36 --port 8081
```

Tap **Track order** on the **Delivery** card, then pull down the shade. A delivery takes 80 seconds. **Stop** in the app or swiping the card away ends it early. The card follows the system's light or dark mode when a delivery starts. The map styles are two constants at the top of the [demo screen](../apps/demo/src/Delivery.tsx).

The demo screen fetches a driving route from the Directions API and passes it to the [local Expo module](../apps/demo/modules/delivery-map/index.ts). Mapbox stays out of `react-native-noti`, so the library does not force the SDK or its Maven repository on other apps.

## Rendering

| Layer | Rate | Source |
| --- | --- | --- |
| Off-screen map | 17 renders per delivery, about 0.21/s | Mapbox `Snapshotter`, 1029×582 px on a 1080 px wide screen |
| Map and markers on screen | 15 updates/s measured | `ViewFlipper` frames, flipped by SystemUI every 66 ms |
| New frames, text and progress | Every 3 s | A normal `notify()` carrying 54 frames |

The card uses `DecoratedCustomViewStyle` with one `ViewFlipper`. Each frame is a small layout that only sets transforms: translation and scale for the map, the courier, the home marker and its pulse. Every frame in a deck shares one map bitmap, so SystemUI scales the same image on the GPU and nothing is re-encoded. A deck holds about 3.7 s of frames and is replaced every 3 s, so a late replacement keeps moving instead of freezing or looping.

Everything is a function of time, including the camera, which frames the remaining route and only ever zooms in at a limited rate. Knowing the future camera lets [the timeline](../apps/demo/modules/delivery-map/android/src/main/java/dev/reactnativenoti/deliverymap/DeliveryTimeline.kt) plan each render before it is needed. Each render is made at the zoom it reaches last, at most 0.27 levels ahead, and overscans the view by 35%, so the camera can drift and zoom within it. A new render crossfades in over 400 ms.

A crossfade holds two map bitmaps, and custom views are stripped from a notification at 5 MB, so a render has at most 600,000 pixels. On the tested 1080 px, 420 dpi screens that is 93% of 1:1. Denser screens get a softer map.

Mapbox draws the route, above the roads and under the labels, so a street name on the route stays readable. Markers and text are native views. Dark mode swaps the style URL and uses `values-night` colors.

With the screen off, nothing renders or publishes. On waking, the card stays on the old map's own sharp view until a fresh render arrives. The service cannot tell when the shade is closed: the flipper stops by itself, but the service keeps publishing decks and rendering maps while the screen is on.

Implementation: [frames](../apps/demo/modules/delivery-map/android/src/main/java/dev/reactnativenoti/deliverymap/DeliveryDeck.kt), [service](../apps/demo/modules/delivery-map/android/src/main/java/dev/reactnativenoti/deliverymap/DeliveryService.kt).

## Findings to keep

- **`MapSnapshotOptions.size` is in output pixels on 11.34.1.** `pixelRatio` only scales content inside that image. A 516 px request at ratio 2.4 returned a 516 px bitmap covering 215 map points.
- **RGB_565 tints pale maps.** Rounding to 5 and 6 bits turned neutral land magenta and buildings green. Staying on ARGB_8888 costs 7% of linear resolution here.
- **The first symbol layer is not where labels start.** `navigation-day-v1` has symbol layers among its roads, so inserting a route below the first one puts it under the road fills, and it looks hollow. Insert above the last non-symbol layer instead.
- **Old SwiftShader cannot draw this style.** An emulator booted over SSH with `-gpu auto` used it, and Mapbox logged `Vertex shader active uniforms exceed GL_MAX_VERTEX_UNIFORM_VECTORS (256)`. The snapshot never returned. `-gpu host` with a display works.
- **Custom content cannot leave its column.** SystemUI clips negative margins, so the map cannot run under the app icon. The column is 817 px wide on Android 16 and 764 px on Android 17 on the same 1080 px screen, so everything is positioned from the center and the width only affects framing.
- **Detaching a foreground service can overwrite newer updates.** ActivityManager keeps the last notification posted under the service's id, and [`setServiceForegroundInnerLocked`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/am/ActiveServices.java) can post it again asynchronously on `stopForeground`. An earlier build ended with the live card stuck over the finished delivery. The service now updates the card while attached, detaches, then posts once more so the card can be dismissed.
- **Host load swamps these measurements.** At a load average above 100, a deck took 955 ms to build, against about 6 ms on an idle host.

## Validation and scope

Latest verified release: R8, arm64-v8a and x86_64, SHA-256 `6bd1835b29c2ec4d1fb15e20d8150b150ea601181ac822a9005d2c789e3b9a28`. The [results file](delivery-map-results.json) has the exact numbers and devices.

| Measure | Android 17, x86_64, host GPU | Android 16, arm64, busy host |
| --- | --- | --- |
| Map renders | 17 of 17 | 17 of 17 |
| Render time, median / first | 18 / 732 ms | 99 / 4,359 ms |
| Map updates on screen | 15.2/s | 14.2/s, and 14.7 to 15.2/s until the last 15 s |
| Gaps between updates, p50 / p99 / max | 67 / 83 / 134 ms | 67 / 198 / 345 ms |
| Largest courier step between updates | 4.5 px | 5.5 px, then 32.6 px near arrival |
| Deck build, sampled | 6 to 14 ms | 5 to 38 ms |
| SystemUI CPU, shade open / closed | 13.5% / 2.2% of one core | Not sampled |
| App CPU, shade open / closed | 2.3% / 5.6% of one core | Not sampled |

The Android 16 jumps near arrival happened at two deck changes while that host's load average was above 30. Rates come from H.264 screen recordings cropped to the map, and CPU from one 10-second `top` sample each. These are not display-refresh or power measurements.

Also checked on this build: arrival leaves a dismissible card on five of five runs (two on Android 17, three on Android 16), a second delivery from **Track again**, **Stop**, swiping the card away mid-delivery, the collapsed card, dark mode, 25 s of screen off with no renders or decks, and Doom still playing. A delivery also finished with the network cut 14 s in, on tiles cached by earlier runs.

**Not tested:** physical devices, including the Xiaomi used for Doom, other OEMs and Android versions below 16, battery and thermal behavior, the lock screen, cities other than this San Francisco route, network loss on a route with no cached tiles, and the service's 30 s first-map timeout.

The Mapbox native libraries add about 26 MB per ABI. Keep the Mapbox wordmark and credits visible, as [the layout](../apps/demo/modules/delivery-map/android/src/main/res/layout/delivery_expanded.xml) does, because this image is cropped and the SDK's own overlay is turned off.
