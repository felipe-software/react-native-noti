# ViewFlipper limits lab

Standalone, offline Java APKs for testing Android's RemoteViews implementation without React Native or Metro. The application package is `dev.noti.flipperlab`; the optional observer is `dev.noti.flipperobserver`. Both are debug applications. All experiments are explicitly scoped to these packages.

Read [the investigation](../../docs/viewflipper-limits.md) for interpretation and measured results. This is experimental tooling, separate from the production library.

`fetch-aosp.py` downloads the pinned framework sources and their hashes into ignored artifacts. It is optional and requires network access. `render-report.py` rebuilds the self-contained interactive HTML from the retained investigation JSON; `render-comparison.py` renders the emulator/Xiaomi comparison.

## Build and run

Requires Python 3, JDK 17+, Android SDK platform 36 and build tools 36.0.0. Set `ANDROID_HOME` and `JAVA_HOME` when they differ from the macOS defaults. No Gradle, npm dependencies, downloaded media, or network access is needed to build.

```sh
python3 tools/viewflipper-lab/build.py
```

Install `build/viewflipper-lab.apk` on the selected emulator or authorized physical Android device through the active device tool. Grant the lab its notification permission and the ability to bind its private test widget host, using its actual serial:

```sh
adb -s emulator-5554 shell pm grant dev.noti.flipperlab android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell appwidget grantbind --package dev.noti.flipperlab
```

Open `dev.noti.flipperlab` with the deep link `flipperlab://run`. With T3, use `device_list` and `device_open`, then the returned agent-device launcher and **all** its config/session/target flags for installation, opening, screenshots and recordings. Open the app before any UI action. The Run experiments button performs the same suite. Keep the activity visible until its status says Complete; this takes roughly 25 seconds. Expected platform rejections count as successful boundary observations.

```sh
adb -s emulator-5554 exec-out run-as dev.noti.flipperlab cat files/results.json > results.json
```

Require `complete: true` and inspect every `matchesExpectation` value. An outcome of `rejected` is often the intended result. `observe` cases record the actual method availability rather than pre-assuming it. The method inventory includes inherited public methods carrying `RemotableViewMethod`.

The same build runs on Android 14 and Android 16. The DrawInstructions probe records `unsupported` when its platform class is absent; all other probes continue. Notification strip thresholds and the two chosen widget payload sizes retain the emulator's expectations so an OEM difference remains visible as a mismatch, with the actual observed values preserved. `compare-devices.py` generates the cross-device JSON from the retained native and delivery runs.

Xiaomi devices can reject ADB installation with `INSTALL_FAILED_USER_RESTRICTED` even when normal USB debugging works. Enable Install via USB or approve the device's installation prompt before retrying. A successful app-open command is insufficient evidence: verify the package exists and the lab actually produced its results file.

The suite creates and deletes its own AppWidgetHost, number 704. The Pin animation widget button requests an additional widget in the real launcher, outside that private host. Actual placement requires launcher support and completion of its pin flow. It does not alter existing widgets.

## Notification delivery experiment

Install `build/viewflipper-observer.apk`, then enable **that separate package** as the observer:

```sh
adb -s emulator-5554 shell cmd notification allow_listener dev.noti.flipperobserver/dev.noti.flipperlab.LabListener
```

Open `dev.noti.flipperobserver` once after installation to clear its initial stopped state and request binding. On HyperOS, also complete the native notification-access confirmation; a shell grant alone appeared enabled but did not bind the service. Confirm `adb -s SERIAL exec-out run-as dev.noti.flipperobserver cat files/connected.txt` returns an uptime value before running trials. Zero callbacks from an unbound observer are not a rate-limit result.

Open the lab with `flipperlab://rates`. It offers 2, 4, 8 and 20 updates/s for eight seconds each, with four-second gaps and a final one-second delivery allowance per phase. Allow about one minute. The observer records only notifications from the lab package; it ignores all other notifications.

```sh
adb -s emulator-5554 exec-out run-as dev.noti.flipperlab cat files/results.json > rates.json
adb -s emulator-5554 exec-out run-as dev.noti.flipperobserver cat files/listener.jsonl > listener.jsonl
```

Count unique sequences per notification ID `10000 + rate` and match the sender/receiver uptime timestamps. Do not enable the listener under the producer's package: Android exempts registered listener packages from the normal enqueue checks, which would invalidate this experiment. Listener receipt proves posting, not that every intermediate update was displayed.

`summarize-rates.py rates.json listener.jsonl --out rate-summary.json` performs this matching. It uses both sequence and sender uptime, so old runs in the observer's append-only log cannot inflate a later run's counts.

## Playback and measurement

Deep links select independent notification demos:

| Link | Experiment |
| --- | --- |
| `flipperlab://demo?kind=frames&interval=33` | 120 text/color frames; host interval in milliseconds |
| `flipperlab://demo?kind=frames&interval=1` | Aggressive interval; does not promise 1000 displayed fps |
| `flipperlab://demo?kind=uri` | 96 generated 160×120 PNGs through a private, grantable provider |
| `flipperlab://demo?kind=property` | XML state animator; no flipper or frame deck |
| `flipperlab://demo?kind=adapter` | Intentionally empty collection outside an AppWidgetHost |
| `flipperlab://demo?kind=draw` | Experimental Android 16 RemoteCompose time expression |

`flipperlab://pin` writes `files/pin-request.json` with the support and request-return values. Those values do not prove actual launcher placement; validate the success callback or a placed widget separately.

Expand the notification before recording it. Obtain crop bounds from the view tree or image inspection; never infer tap coordinates from screenshots. The supplied encoder for DrawInstructions uses the public API and a small binary document based on Android 16 source. It includes a layout root: an earlier rootless prototype crashed the platform's accessibility inspector. This encoder is a protocol experiment; use a version-compatible supported writer and complete accessibility semantics before product integration.

The suite's Choreographer measurements sample the selected child in a local host. Measure actual recorded pixels separately with FFmpeg and FFprobe:

```sh
python3 tools/viewflipper-lab/analyze.py recording.mp4 \
  --crop 817:525:179:836 --start 1 --end 10 --out measurement.json
```

The analyzer uses original video timestamps and reports the crop, threshold and decoded sample count. It needs no NumPy. A recording cannot establish a display ceiling above its own sampling rate. The teal centroid fields are useful for the vector circle, which changes too little of the whole crop to trigger the default whole-image threshold.

To test producer exit, background the lab and terminate only its PID, without force-stopping the package. Verify `pidof dev.noti.flipperlab` returns nothing before capturing playback. A force-stop is a different experiment: it also changes Android's package and notification lifecycle state.

## Doom transport probe

Open `flippertransport://run` in the lab package. The separate `:transport` activity compares a 320×200 ARGB bitmap with a 24-bit BMP URI served through a memory-backed file descriptor by the app's other process. Each trial discards 20 warm-up pairs, alternates both orderings, and retains 80 samples per mode. Dimensions and an image pixel are verified on every inflation. The result is `files/doom-transport.json`.

This probe times frame preparation, the RemoteViews/parcel round trip and synchronous inflation. It does not pass through NotificationManager or SystemUI, attach a view to a window, or measure a touch. Keep those limitations when interpreting its results. Read [the Doom research record](../../docs/doom-research.md#earlier-viewflipper-trials).

```sh
python3 tools/viewflipper-lab/summarize-doom-transport.py \
  artifacts/doom-optimization/transport-emulator-run1.json \
  artifacts/doom-optimization/transport-emulator-run2.json \
  --out docs/doom-optimization-results.json
```

## Cleanup

Open `flipperlab://cleanup` to cancel lab notifications and delete the private widget host. Remove the pinned lab widget through the launcher, or uninstall the lab APK. Revoke the two test grants when finished:

```sh
adb -s emulator-5554 shell cmd notification disallow_listener dev.noti.flipperobserver/dev.noti.flipperlab.LabListener
adb -s emulator-5554 shell appwidget revokebind --package dev.noti.flipperlab
```
