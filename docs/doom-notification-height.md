**The current 640×400 Doom image is not the maximum size of a chat notification.** A static layout probe on the Android 16 emulator displayed the same 8:5 aspect ratio at **817×510 pixels**, a 27.5% height increase, when supplied with a 1280×800 image. No production Doom code was changed for this investigation.

Measured on 2026-10-29, `emulator-5554`, 1080×2400, density 420 dpi:

| Supplied image | Visible image bounds | Whole notification row |
| --- | --- | --- |
| 640×400 | 640×400 px | 996×671 px |
| 1280×800 | 817×510 px | 996×781 px |
| 1280×1280 | 746×669 px, cropped | 996×940 px |
| 640×1600 | 298×669 px, cropped | 996×940 px |

The larger landscape image preserves the Doom aspect ratio. The taller probes reach a 940 px row, approximately 358 dp at this density, including the notification header, sender and padding. Their green top and red bottom marker bars disappear through cropping; extra source height does not create unlimited space. This is the observed bound for this template and device configuration, not a universal image-height guarantee.

`cmd overlay lookup com.android.systemui com.android.systemui:dimen/notification_max_height` returned `358.0dip` on both the emulator and the Xiaomi. The phone was locked during this investigation, so the four-image layout probe ran only on the emulator. The previous Xiaomi continuous-controls captures show the existing Doom image at 640×400 px, matching the current GIF canvas.

There is a subtle source-code trap: the `messaging_image_max_height` resource is 136 dp, but that number alone is not the effective inline-image ceiling. `MessagingImageMessage.onMeasure` replaces the superclass measurement using the incoming width/height constraints and drawable aspect ratio. These measured images exceed 136 dp. The image also avoids becoming wider than its intrinsic width, which explains why the current 640 px canvas leaves room unused. See [MessagingImageMessage](https://android.googlesource.com/platform/frameworks/base/+/aa9d89ca3c98/core/java/com/android/internal/widget/MessagingImageMessage.java), [MessagingLinearLayout](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/java/com/android/internal/widget/MessagingLinearLayout.java), and [SystemUI dimensions](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/res/values/dimens.xml).

For Doom, a larger encoded canvas can use more width and therefore gain height while preserving the view. More vertical space beyond that would require a different composition or cropping. The probes used static PNGs in the same `MessagingStyle` configuration as the live renderer; they establish layout behavior, **not GIF throughput or input latency**. A larger live canvas still needs a performance check before replacing the successful 640×400 renderer.

Reproduce with [the isolated height lab](../tools/notification-height-lab/README.md). [Structured results](doom-notification-height-results.json). The temporary APK was removed after measurement.

The subsequent [Doom size implementation](doom-max-size.md) now fills the observed landscape width. Its live-GIF test also caught a sampling threshold that the static PNG probe did not expose.
