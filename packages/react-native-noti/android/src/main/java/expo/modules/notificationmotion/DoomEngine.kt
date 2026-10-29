package expo.modules.notificationmotion

/** Native Doom owns its 35 Hz simulation thread; only snapshots cross JNI. */
internal object DoomEngine {
  init { System.loadLibrary("noti_doom") }
  external fun start(wadPath: String): Boolean
  external fun render(): IntArray?
  /** Pixels and matching metadata: frame ID, applied input ID, discarded-through ID, game tic, render time ns. */
  external fun renderState(state: LongArray): IntArray?
  external fun key(key: Int, pressed: Boolean)
  external fun keyTracked(key: Int, pressed: Boolean): Long
  external fun stop()
  /** 0 mutes (and pauses audio output), 1 is full volume. */
  external fun setVolume(volume: Float)
}
