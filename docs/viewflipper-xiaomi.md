# Xiaomi comparison

2026-10-28 · Redmi Note 12 Pro 5G (`22101316G`) · Android 14 / API 34 · HyperOS `OS2.0.16.0.UMOMIXM` · physical 1080×2400 display, 440 dpi, active 120 Hz mode.

**The Xiaomi confirms substantial capabilities and some meaningful version/device differences.** Of the 87 native probes, 86 produced the same outcome as the Android 16 emulator. The remaining probe found that the public DrawInstructions class is absent on this phone. The comparable probes completed without expectation mismatches.

The two native runs used the same producer APK, verified by SHA-256. The later observer bootstrap and pin diagnostic are documented separately. See the [comparison data](viewflipper-device-comparison.json), [complete Xiaomi results](viewflipper-xiaomi-results.json), [Android 14 source hashes](viewflipper-xiaomi-sources.json), and [original investigation](viewflipper-limits.md).

## What matched

| Capability or boundary | Android 16 emulator | Xiaomi Android 14 |
| --- | --- | --- |
| 512 direct flipper children | Inflated | Inflated |
| Nested RemoteViews wrappers | 10 accepted; 11 rejected | 10 accepted; 11 rejected |
| One compiled XML layout with 32 nesting levels | Inflated | Inflated |
| Notification bitmap payload | 4.72 MB retained; 5.24 MB stripped | Same observations |
| Widget bitmap budget on a 1080×2400 display | 15,552,000 bytes | Same reported maximum |
| Widget payload samples | 12.58 MB accepted; 16.78 MB rejected | Same observations |
| 150-item collection in a private widget host | Two active child views | Two active child views |
| Collection adapter in a notification-style host | Did not bind | Did not bind |
| Ten tested transform setters | Accepted | Accepted |
| Hidden GONE flipper while its window is visible | Continued advancing | Continued advancing |
| Detached flipper | Stopped advancing | Stopped advancing |
| Large Binder metadata transaction | Rejected | Rejected |

On the Xiaomi, direct resource lookups also confirmed `config_notificationWarnRemoteViewSizeBytes = 2000000`, `config_notificationStripRemoteViewSizeBytes = 5000000`, and `notification_max_height = 358dp`. The emulator's maximum-height dimension also returned 358dp. A configured dimension is not proof that every decorated presentation exposes that much usable space. The bitmap budget uses physical display pixels, rather than the app's smaller content area: the Xiaomi app reported 1080×2176 content metrics, while the widget service still enforced `6 × 1080 × 2400` bytes.

## What differed

The Xiaomi's display was in 120 Hz mode. Its local Choreographer probe selected a new flipper child roughly 120 times per second with a 1 ms interval. The same-build emulator run selected about 58/s. These are host-state samples over about 1.7 seconds, not proof of that many visibly presented images.

| Requested interval | Emulator selected-child changes/s | Xiaomi selected-child changes/s |
| --- | --- | --- |
| 1 ms | 58.0 | 121.2 |
| 8 ms | 57.9 | 113.9 |
| 16 ms | 54.8 | 60.0 |
| 33 ms | 28.7 | 29.3 |
| 66 ms | 14.6 | 14.7 |

The small apparent excess over 120 in the shortest sample is consistent with startup/window quantization; it is not evidence of a display exceeding its refresh mode. Hardware, OS version, refresh rate and host load differ, so this does not isolate an OEM-only effect.

Actual notification recordings remained bounded by their own sampling throughput. With the same pixel-change analyzer, the Xiaomi showed about **27.1 recorded changes/s** at 33 ms, versus **19.9/s** in the earlier emulator clip. At 1 ms, recordings showed about 28.7/s and 29.0/s respectively. The aggressive Xiaomi recording contained only 258 decoded samples across the roughly nine-second analysis window. It cannot determine whether the display presented more changes between encoded samples.

The DrawInstructions/RemoteCompose document that worked on API 36 cannot run on this API 34 build: `android.widget.RemoteViews$DrawInstructions` was absent. The lab now checks the actual class and records `unsupported`, instead of failing the rest of the suite. The frame-deck, transform and XML-animation mechanisms remained available. [Public API reference](https://developer.android.com/reference/android/widget/RemoteViews.DrawInstructions).

## Notification delivery

After confirming the independent observer was bound, the phone repeated the same eight-second rate phases:

| Calls/s | Calls offered | Emulator posted sequences | Xiaomi posted sequences | Xiaomi median send → listener |
| --- | --- | --- | --- | --- |
| 2 | 16 | 16 | 16 | 230.5 ms |
| 4 | 32 | 32 | 32 | 240 ms |
| 8 | 64 | 40 | 40 | 234 ms |
| 20 | 160 | 44 | 54 | 235 ms |

NotificationService logged shedding for the lab on the Xiaomi as well. The greater count at 20 calls/s is an observation from one short trial, not a new guaranteed 6.75 updates/s ceiling. Its accepted offers fell into two-second sender-time buckets of 19, 12, 11 and 12; the acceptance pattern was not a simple fixed five-token bucket. A live notification assistant was enabled on both devices.

The AOSP version baselines contain relevant differences: Android 14's estimator uses alpha 0.8 and updates interarrival estimates when notifications are posted/updated. Android 16 uses alpha 0.7 and records accepted enqueue events separately, before assistant deferral. Those differences explain why one universal cadence model should not be assumed. They do not establish that Xiaomi's proprietary implementation exactly equals the AOSP baseline, or isolate the cause of each observed gap. [Android 14 estimator](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/notification/RateEstimator.java), [Android 14 accounting](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/core/java/com/android/server/notification/NotificationUsageStats.java#137), [Android 16 accounting](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/notification/NotificationUsageStats.java#133).

## Playback and launcher evidence

Real SystemUI checks confirmed URI-backed playback and continuous XML state-animator motion. All 96 URI frames also decoded in the local probe, representing 7,372,800 ARGB bytes while keeping the serialized parcel small.

The regular notification flipper continued after the producer PID was terminated. The retained [cropped recording](../artifacts/viewflipper-limits/xiaomi/notification-after-exit-verified-crop.mp4), captured with the notification rectangle verified on screen, showed 244 significant changes across 8.43 seconds, about 28.9/s. The producer PID was absent before and after capture. This demonstrates playback of preloaded frames, not generation of new frames after the producer exits. An earlier capture showed the home screen and was excluded; zero changes in the wrong rectangle say nothing about animation behavior.

The Xiaomi's pin diagnostic returned `pinningSupported: true` and `requestAccepted: true`, but no new launcher widget placement was observed. An accepted pin request is not a placement-success callback. Consequently, this run verifies the private AppWidgetHostView and widget service on the phone, **not playback inside the actual MIUI launcher**. The emulator's real-launcher widget check remains a separate successful observation. The lab now writes `pin-request.json` so this distinction is reviewable.

## Reproduction and cleanup

Two setup conditions mattered on this device:

- Install via USB was already enabled. The ordinary per-app USB install prompt still required approving the lab APK. No security setting was changed.
- Shell-enabled notification access did not produce a bound observer. The observer was launched explicitly, and HyperOS's native notification-access confirmation was completed. A connection marker was verified before the retained rate trial. Initial runs with no observer callbacks were excluded.

The observer continues to filter by the lab's package before accessing notification extras. `summarize-rates.py` matches both sequence and sender uptime, so historical rows cannot inflate a later trial's counts.

Both temporary APKs were removed from the phone after evidence collection. The observer's notification access and the lab's widget-binding grant were revoked; the temporary device-side recording was removed. The production apps were not modified. Local, cropped notification evidence is under `artifacts/viewflipper-limits/xiaomi/`; the checked-in results retain the structured probes, delivery observations and cleanup record.
