package expo.modules.notificationmotion

/** Native Doom owns its 35 Hz simulation thread; only snapshots cross JNI. */
internal object DoomEngine {
  init { System.loadLibrary("noti_doom") }
  external fun start(wadPath: String): Boolean
  external fun render(): IntArray?
  external fun key(key: Int, pressed: Boolean)
  external fun stop()
}
