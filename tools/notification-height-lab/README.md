This isolated SDK-only app measures inline-image and whole-row bounds in the same `MessagingStyle` configuration as Doom. It posts one static PNG at a time. It does not run Doom, animate images, or measure performance.

Build with `node tools/notification-height-lab/build.mjs`. Android SDK 36/build-tools 36.0.0 and Java 17 must be installed; `ANDROID_HOME` and `JAVA_HOME` override the local defaults. Each build command has a 30-second timeout.

Install `artifacts/notification-height/build/height-lab.apk` on the selected Android device. Open it through the device tooling. The four buttons generate images with green top and red bottom borders so cropping is visible. Expand the notification to inspect each image. Clear probe removes the notification.

The optional `node tools/notification-height-lab/probe.mjs SERIAL SESSION` runner uses the local T3 agent-device launcher, host configuration and session flags. Open the device panel first and pass its exact serial/session. Update the launcher/configuration paths in the script if running on another host. It grants this test app notification permission, locates its buttons and expansion control through accessibility, records measured bounds and screenshots, and clears the test notification. Commands have 15-second timeouts and the run has a 90-second deadline. Uninstall `dev.noti.heightlab` when finished, including after an interrupted run.

Captures are under the ignored `artifacts/notification-height/` directory. Findings and limitations are in [the height investigation](../../docs/doom-notification-height.md).
