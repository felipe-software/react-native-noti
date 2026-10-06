package expo.modules.notificationmotion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/** Service-owned state and controls continue to work while React is suspended. */
internal object DoomController {
  @Volatile var service: DoomService? = null
  @Volatile private var snapshot: Map<String, Any?> = mapOf(
    "state" to "idle", "framesPublished" to 0, "notificationFps" to 30,
    "engineTickRate" to 35, "lastAction" to null, "error" to null, "muted" to false,
  )
  fun status(): Map<String, Any?> = snapshot
  @Synchronized fun update(vararg values: Pair<String, Any?>) { snapshot = snapshot + values }

  fun start(context: Context): Map<String, Any?> {
    val manager = context.getSystemService(NotificationManager::class.java)
    check(manager.areNotificationsEnabled()) { "Enable notifications before starting Doom." }
    check(manager.getNotificationChannel(DoomService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
      "The Doom notification channel is disabled. Enable it in Android settings."
    }
    // Fail in the caller rather than starting a service with missing game data.
    context.assets.open("doom/freedoom1.wad").use { }
    if (snapshot["state"] == "starting" || snapshot["state"] == "playing") return status()
    update("state" to "starting", "error" to null)
    try {
      context.startForegroundService(Intent(context, DoomService::class.java).setAction(DoomService.START))
    } catch (error: Exception) {
      update("state" to "error", "error" to error.message)
      throw error
    }
    return status()
  }

  fun action(action: String): Map<String, Any?> {
    require(action in DoomService.CONTROLS || action == "pause" || action == "mute") { "Unknown Doom control: $action" }
    check(snapshot["state"] == "playing" || snapshot["state"] == "paused") { "Wait for Doom to finish loading before sending controls." }
    val current = checkNotNull(service) { "Start Doom before sending controls." }
    current.control(action)
    return status()
  }

  fun stop(context: Context): Map<String, Any?> {
    update("state" to "stopped")
    // stopService also handles a start that has been queued but not delivered yet.
    context.stopService(Intent(context, DoomService::class.java))
    context.getSystemService(NotificationManager::class.java).cancel(DoomService.ID)
    return status()
  }
}

/** Recent frames, served to SystemUI by [DoomFrameProvider] so pixels stay out of Binder. */
internal object DoomFrames {
  const val AUTHORITY_SUFFIX = ".notificationmotion.doom"
  private const val WIDTH = 320
  private const val HEIGHT = 200
  private const val HEADER = 54
  // Android holds each update ~200 ms before SystemUI reads it, so keep the frames of the shown
  // update and of older ones that may still be inflating (about 1.6 s).
  private const val CAPACITY = 48
  private val session = SystemClock.elapsedRealtime().toString(36)
  private var next = 0L
  private val frames = object : LinkedHashMap<String, ByteArray>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > CAPACITY
  }

  fun add(context: Context, pixels: IntArray): Uri {
    val frame = encode(pixels)
    val name = synchronized(this) { "$session-${next++}.bmp".also { frames[it] = frame } }
    return Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
      .authority("${context.packageName}$AUTHORITY_SUFFIX").appendPath(name).build()
  }

  @Synchronized fun bmp(name: String): ByteArray? = frames[name]

  @Synchronized fun clear() = frames.clear()

  /**
   * Uncompressed 24-bit BMP. Bitmap.compress(PNG) cost ~17 ms a frame on a Galaxy S24, half a
   * core at 30 fps; this is a single pass over the pixels and trivial for SystemUI to decode.
   */
  private fun encode(pixels: IntArray): ByteArray {
    check(pixels.size == WIDTH * HEIGHT) { "Unexpected Doom frame size." }
    val stride = WIDTH * 3 // already a multiple of four, so rows need no padding
    val out = ByteArray(HEADER + stride * HEIGHT)
    fun int(at: Int, value: Int) { for (i in 0..3) out[at + i] = (value shr (8 * i)).toByte() }
    out[0] = 'B'.code.toByte(); out[1] = 'M'.code.toByte()
    int(2, out.size); int(10, HEADER); int(14, 40); int(18, WIDTH); int(22, HEIGHT)
    out[26] = 1; out[28] = 24
    int(34, stride * HEIGHT)
    var o = HEADER
    for (y in HEIGHT - 1 downTo 0) { // BMP rows are stored bottom-up, pixels as B, G, R
      for (x in 0 until WIDTH) {
        val c = pixels[y * WIDTH + x]
        out[o++] = c.toByte(); out[o++] = (c shr 8).toByte(); out[o++] = (c shr 16).toByte()
      }
    }
    return out
  }
}

/**
 * Mirrors NotificationManagerService's per-package update limit (RateEstimator: alpha 0.7,
 * 5 updates/s). Some builds, e.g. One UI 8.5, update the estimate before checking it, so an app
 * slightly over budget has every later update dropped. This models that stricter order.
 * The estimate is a moving average, so slow updates while idle bank headroom for fast ones.
 */
internal object DoomNotificationRate {
  private const val MAX_RATE = 4.5
  private const val MIN_INTERARRIVAL = 1.0 / (0.7 * MAX_RATE)
  private var interarrival = MIN_INTERARRIVAL
  private var lastAt = 0L

  /** Milliseconds until another notify() stays within [MAX_RATE]. */
  @Synchronized fun delayMs(now: Long = SystemClock.elapsedRealtime()): Long {
    if (lastAt == 0L) return 0L
    val requiredMs = (MIN_INTERARRIVAL - 0.7 * interarrival) / 0.3 * 1000
    return max(0L, ceil(requiredMs - (now - lastAt)).toLong())
  }

  @Synchronized fun record(now: Long = SystemClock.elapsedRealtime()) {
    if (lastAt != 0L) interarrival = 0.7 * interarrival + 0.3 * max(now - lastAt, 1L) / 1000.0
    lastAt = now
  }
}

class DoomService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private val manager get() = getSystemService(NotificationManager::class.java)
  private val audio get() = getSystemService(AudioManager::class.java)
  private val releases = mutableMapOf<Int, Runnable>()
  private var lastFrame: Uri? = null
  private val frameBatch = ArrayList<Uri>(BATCH_SIZE + 1)
  private var paused = false
  // Kept in the controller so a muted game stays muted across Stop and Play.
  private var muted = DoomController.status()["muted"] == true
  private var focusVolume = 0f
  private var focus: AudioFocusRequest? = null
  private var running = false
  private var nativeStarted = false
  private var frames = 0
  private var wadPath: String? = null
  private var lastFrameAt = 0L
  private var nextFrameAt = 0L
  private var lastInputAt = 0L
  private var flushAt = 0L
  private var flushed = false
  private var followUp = false
  private var startupThread: Thread? = null
  @Volatile private var destroyed = false

  override fun onCreate() {
    super.onCreate()
    DoomController.service = this
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (val action = intent?.action) {
      START -> if (paused) control("pause") else if (!running && startupThread == null) startGame()
      STOP -> DoomController.stop(this)
      else -> if (action != null) control(action) else stopSelf()
    }
    // Do not resurrect a killed game or replay old movement/fire intents.
    return START_NOT_STICKY
  }

  private fun startGame() {
    try {
      val channel = NotificationChannel(CHANNEL, "Doom", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Interactive Doom in an expanded notification"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(false)
      }
      manager.createNotificationChannel(channel)
      check(manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
        "The Doom notification channel is disabled."
      }
      // Silent until the game holds audio focus (see updateAudio).
      DoomEngine.setVolume(0f)
      val notification = notification("Loading Freedoom…")
      DoomNotificationRate.record()
      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(ID, notification)
      }
      // WAD extraction and native startup never block the UI/service main thread.
      startupThread = Thread({
        try {
          val destination = File(filesDir, "doom/freedoom1.wad")
          destination.parentFile!!.mkdirs()
          if (!destination.exists()) {
            val temporary = File(destination.parentFile, "freedoom1.wad.tmp")
            assets.open("doom/freedoom1.wad").use { source -> temporary.outputStream().use(source::copyTo) }
            check(temporary.renameTo(destination)) { "Could not prepare the Freedoom game data." }
          }
          synchronized(this) {
            if (destroyed) return@Thread
            check(DoomEngine.start(destination.absolutePath)) { "The Doom engine could not start." }
            nativeStarted = true
          }
          handler.post {
            if (!destroyed) {
              wadPath = destination.absolutePath
              running = true
              paused = false
              frames = 0
              lastFrameAt = SystemClock.uptimeMillis()
              nextFrameAt = lastFrameAt
              DoomController.update("state" to "playing", "framesPublished" to 0, "error" to null)
              updateAudio()
              handler.post(sampleFrame)
            }
          }
        } catch (error: Throwable) {
          handler.post { if (!destroyed) fail(error) }
        }
      }, "Noti-Doom-start").also { it.start() }
    } catch (error: Throwable) { fail(error) }
  }

  private val sampleFrame = object : Runnable {
    override fun run() {
      if (!running || destroyed || paused) return
      try {
        DoomEngine.render()?.let { pixels ->
          val frame = DoomFrames.add(this@DoomService, pixels)
          lastFrame = frame
          frameBatch.add(frame)
          val now = SystemClock.uptimeMillis()
          lastFrameAt = now
          if (flushAt != 0L && now >= flushAt) {
            // An isolated tap has had two engine tics to take effect. Drop the older frames so
            // its response is shown first rather than after them.
            frameBatch.subList(0, frameBatch.size - 1).clear()
            flushAt = 0L
            flushed = true
          }
          // While the rate budget holds a batch back, keep the newest frames.
          if (frameBatch.size > BATCH_SIZE) frameBatch.removeAt(0)
          // A tap's response, and the update that ends its still, go out as soon as the budget
          // allows. Otherwise only full batches are sent, rebuilding the headroom that lets the
          // next tap's response skip the wait.
          val ready = flushAt == 0L && (flushed || followUp || frameBatch.size == BATCH_SIZE)
          if (ready && DoomNotificationRate.delayMs() == 0L) publishBatch()
        }
        check(SystemClock.uptimeMillis() - lastFrameAt < 10_000) {
          "Doom stopped producing frames. Close and reopen the app to start a new session."
        }
        // Anchor the cadence, but do not rapidly sample duplicate frames after a UI stall.
        nextFrameAt += FRAME_INTERVAL_MS
        val now = SystemClock.uptimeMillis()
        if (nextFrameAt <= now) nextFrameAt = now + FRAME_INTERVAL_MS
        handler.postAtTime(this, nextFrameAt)
      } catch (error: Throwable) { fail(error) }
    }
  }

  private fun publishBatch() {
    post(notification("Freedoom · tap controls to play", frameBatch))
    if (Log.isLoggable("NotiDoomLatency", Log.DEBUG)) {
      Log.d("NotiDoomLatency", "publish at=${SystemClock.uptimeMillis()} count=${frameBatch.size} flush=$flushed")
    }
    followUp = flushed
    flushed = false
    frames += frameBatch.size
    DoomController.update("framesPublished" to frames)
    frameBatch.clear()
  }

  /** Every Doom update must pass through here so the rate model sees it. */
  private fun post(notification: Notification) {
    DoomNotificationRate.record()
    manager.notify(ID, notification)
  }

  private val publishPaused = object : Runnable {
    override fun run() {
      if (!paused || destroyed) return
      val wait = DoomNotificationRate.delayMs()
      if (wait > 0) {
        handler.postDelayed(this, wait)
      } else {
        post(notification("Paused · tap Resume", listOfNotNull(lastFrame)))
      }
    }
  }

  internal fun control(action: String) {
    handler.post {
      if (!running || destroyed) return@post
      try {
        if (action == "pause") {
          if (paused) {
            check(DoomEngine.start(checkNotNull(wadPath))) { "Unable to resume Doom." }
            lastFrameAt = SystemClock.uptimeMillis()
            nextFrameAt = lastFrameAt
            frameBatch.clear()
          } else {
            releaseKeys()
            DoomEngine.stop()
            frameBatch.clear()
          }
          flushAt = 0L
          followUp = false
          paused = !paused
          updateAudio()
          DoomController.update("state" to if (paused) "paused" else "playing", "lastAction" to action)
          handler.removeCallbacks(sampleFrame)
          handler.removeCallbacks(publishPaused)
          handler.post(if (paused) publishPaused else sampleFrame)
        } else if (action == "mute") {
          muted = !muted
          updateAudio()
          DoomController.update("muted" to muted, "lastAction" to action)
          // While playing, the next batch carries the new label.
          if (paused) {
            handler.removeCallbacks(publishPaused)
            handler.post(publishPaused)
          }
        } else if (!paused) {
          val key = CONTROLS[action] ?: return@post
          releases.remove(key)?.let(handler::removeCallbacks)
          DoomEngine.key(key, true)
          val now = SystemClock.uptimeMillis()
          if (Log.isLoggable("NotiDoomLatency", Log.DEBUG)) {
            Log.d("NotiDoomLatency", "input=$action at=$now")
          }
          // Repeated taps keep smooth batch playback instead of a still per tap.
          if (now - lastInputAt > INPUT_BURST_GAP_MS) flushAt = now + INPUT_SETTLE_MS
          lastInputAt = now
          val release = Runnable {
            DoomEngine.key(key, false)
            releases.remove(key)
          }
          releases[key] = release
          handler.postDelayed(release, if (action == "fire") 160L else 250L)
          DoomController.update("lastAction" to action)
        }
      } catch (error: Throwable) { fail(error) }
    }
  }

  /** Doom is heard only while playing, unmuted and holding audio focus. */
  private fun updateAudio() {
    if (muted || paused || !running || destroyed) {
      focus?.let(audio::abandonAudioFocusRequest)
      focusVolume = 0f
    } else {
      val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        )
        // Duck in the native mixer instead of letting Android pause or duck us.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(focusChange, handler)
        .build().also { focus = it }
      focusVolume = if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else 0f
    }
    DoomEngine.setVolume(if (muted) 0f else focusVolume)
  }

  private val focusChange = AudioManager.OnAudioFocusChangeListener { change ->
    focusVolume = when (change) {
      AudioManager.AUDIOFOCUS_GAIN -> 1f
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> DUCK_VOLUME
      else -> 0f
    }
    DoomEngine.setVolume(if (muted) 0f else focusVolume)
  }

  private fun releaseKeys() {
    releases.forEach { (key, callback) ->
      handler.removeCallbacks(callback)
      DoomEngine.key(key, false)
    }
    releases.clear()
  }

  private fun fail(error: Throwable) {
    Log.e("NotiDoom", "Doom service failed", error)
    DoomController.update("state" to "error", "error" to (error.message ?: error.javaClass.simpleName))
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    synchronized(this) {
      destroyed = true
      if (nativeStarted) {
        releaseKeys()
        DoomEngine.stop()
        nativeStarted = false
      }
    }
    handler.removeCallbacksAndMessages(null)
    focus?.let(audio::abandonAudioFocusRequest)
    frameBatch.clear()
    lastFrame = null
    DoomFrames.clear()
    running = false
    if (DoomController.service === this) {
      DoomController.service = null
      if (DoomController.status()["state"] != "error") DoomController.update("state" to "stopped")
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  private fun pending(action: String): PendingIntent = PendingIntent.getService(
    this, action.hashCode(), Intent(this, DoomService::class.java).setAction(action),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

  private fun notification(status: String, batch: List<Uri> = emptyList()): Notification {
    val expanded = RemoteViews(packageName, R.layout.nm_doom).apply {
      removeAllViews(R.id.nm_doom_frame)
      // The flipper loops. Padding a short batch to a full one, plus a few frames, makes it hold
      // its last image until the next update instead of jumping back to its first frame.
      val padding = if (batch.size > 1) BATCH_SIZE - batch.size + HOLD_FRAMES else 0
      (batch + List(padding) { batch.last() }).forEach { frame ->
        addView(R.id.nm_doom_frame, RemoteViews(packageName, R.layout.nm_doom_frame).apply {
          setImageViewUri(R.id.nm_doom_frame_image, frame)
        })
      }
      setInt(R.id.nm_doom_frame, "setFlipInterval", FRAME_INTERVAL_MS.toInt())
      setDisplayedChild(R.id.nm_doom_frame, 0)
      setTextViewText(R.id.nm_doom_status, status)
      mapOf(
        R.id.nm_doom_forward to "forward", R.id.nm_doom_backward to "backward",
        R.id.nm_doom_left to "left", R.id.nm_doom_right to "right",
        R.id.nm_doom_fire to "fire", R.id.nm_doom_use to "use",
      ).forEach { (id, action) -> setOnClickPendingIntent(id, pending(action)) }
    }
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.nm_small_icon)
      .setContentTitle("Doom · Noti")
      .setContentText(status)
      .setStyle(Notification.DecoratedCustomViewStyle())
      .setCustomBigContentView(expanded)
      .setOnlyAlertOnce(true)
      .setSound(null)
      .setVibrate(null)
      .setDefaults(0)
      .setShowWhen(false)
      .setOngoing(true)
      .setDeleteIntent(pending(STOP))
      .setCategory(Notification.CATEGORY_SERVICE)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(Notification.Action.Builder(null as Icon?, if (paused) "Resume" else "Pause", pending("pause")).build())
      .addAction(Notification.Action.Builder(null as Icon?, if (muted) "Sound on" else "Sound off", pending("mute")).build())
      .addAction(Notification.Action.Builder(null as Icon?, "Stop Doom", pending(STOP)).build())
      .build()
  }

  companion object {
    const val ID = 16035
    const val CHANNEL = "noti-doom"
    const val START = "noti.doom.start"
    const val STOP = "noti.doom.stop"
    private const val FRAME_INTERVAL_MS = 33L
    // Idle batches: 15 × 33 ms, 2 updates/s.
    private const val BATCH_SIZE = 15
    private const val HOLD_FRAMES = 3
    private const val INPUT_SETTLE_MS = 60L
    private const val INPUT_BURST_GAP_MS = 350L
    // While another app briefly speaks over the game (e.g. navigation).
    private const val DUCK_VOLUME = 0.25f
    val CONTROLS = mapOf("forward" to 0xAD, "backward" to 0xAF, "left" to 0xAC, "right" to 0xAE, "fire" to 0xA3, "use" to 0xA2)
  }
}
