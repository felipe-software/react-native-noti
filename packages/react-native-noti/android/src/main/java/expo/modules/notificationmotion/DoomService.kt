package expo.modules.notificationmotion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    "renderer" to "flipper",
  )
  fun status(): Map<String, Any?> = snapshot
  @Synchronized fun update(vararg values: Pair<String, Any?>) { snapshot = snapshot + values }

  fun start(context: Context, renderer: String = "flipper"): Map<String, Any?> {
    require(renderer == "flipper" || renderer == "live") { "Unknown Doom renderer: $renderer" }
    if (snapshot["state"] in listOf("starting", "playing", "paused")) {
      check(snapshot["renderer"] == renderer) { "Stop Doom before changing its renderer." }
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    check(manager.areNotificationsEnabled()) { "Enable notifications before starting Doom." }
    check(manager.getNotificationChannel(DoomService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
      "The Doom notification channel is disabled. Enable it in Android settings."
    }
    check(manager.getNotificationChannel(DoomService.CONTROLS_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) {
      "The Doom controls channel is disabled. Enable it in Android settings."
    }
    // Fail in the caller rather than starting a service with missing game data.
    context.assets.open("doom/freedoom1.wad").use { }
    if (snapshot["state"] == "starting" || snapshot["state"] == "playing") return status()
    update("state" to "starting", "error" to null, "renderer" to renderer)
    try {
      context.startForegroundService(Intent(context, DoomService::class.java).setAction(DoomService.START)
        .putExtra("renderer", renderer))
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
    context.getSystemService(NotificationManager::class.java).cancel(DoomService.CONTROLS_ID)
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
  private const val DEFERRED_BATCH_CAPACITY = 8
  private val session = SystemClock.elapsedRealtime().toString(36)
  private var next = 0L
  private var nextBatch = 0L
  private data class CapturedFrame(val id: Long, val bytes: ByteArray)
  private class DeferredBatch(val count: Int) { var sealed: List<ByteArray>? = null }
  private val frames = object : LinkedHashMap<String, CapturedFrame>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CapturedFrame>?) = size > CAPACITY
  }
  private val batches = object : LinkedHashMap<String, DeferredBatch>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DeferredBatch>?) = size > DEFERRED_BATCH_CAPACITY
  }
  private var lastSealed: CapturedFrame? = null

  fun add(context: Context, pixels: IntArray): Uri {
    val frame = encode(pixels)
    val name = synchronized(this) { "$session-$next.bmp".also { frames[it] = CapturedFrame(next++, frame) } }
    return uri(context, name)
  }

  private fun uri(context: Context, name: String): Uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
    .authority("${context.packageName}$AUTHORITY_SUFFIX").appendPath(name).build()

  /** A unique deck is frozen once, when the host first reads any of its images. */
  @Synchronized fun defer(context: Context, count: Int): List<Uri> {
    require(count in 1..CAPACITY)
    val name = "$session-b${nextBatch++}"
    batches[name] = DeferredBatch(count)
    return List(count) { index -> uri(context, "$name-$index.bmp") }
  }

  @Synchronized fun bmp(name: String): ByteArray? {
    frames[name]?.let { return it.bytes }
    val split = name.lastIndexOf('-')
    if (split < 0 || !name.endsWith(".bmp")) return null
    val batch = batches[name.substring(0, split)] ?: return null
    val index = name.substring(split + 1, name.length - 4).toIntOrNull() ?: return null
    if (index !in 0 until batch.count) return null
    val sealed = batch.sealed ?: run {
      // Copy references to already encoded frames; never wait for the renderer on a provider
      // thread. A shared anchor keeps all images in a deck consistent even if read out of order.
      val newer = frames.values.filter { it.id > (lastSealed?.id ?: -1L) }.takeLast(batch.count)
      val chosen = newer.ifEmpty { listOfNotNull(lastSealed ?: frames.values.lastOrNull()) }
      if (chosen.isEmpty()) return null
      val snapshot = chosen.map { it.bytes } + List(batch.count - chosen.size) { chosen.last().bytes }
      lastSealed = chosen.last()
      batch.sealed = snapshot
      snapshot
    }
    return sealed[index]
  }

  @Synchronized fun clear() {
    frames.clear()
    batches.clear()
    lastSealed = null
  }

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
  private val frameBatch = ArrayList<Uri>(IDLE_BATCH_SIZE + 1)
  private var batchSize = IDLE_BATCH_SIZE
  private var interactiveUntil = 0L
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
  private val frameState = LongArray(5)
  private var pendingInput = 0L
  private var renderer = "flipper"
  private val controlsWhen = System.currentTimeMillis()
  private var liveFrames: DoomLiveSession? = null
  private var flushed = false
  private var followUp = false
  private var startupThread: Thread? = null
  @Volatile private var destroyed = false
  private val screenOn = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == Intent.ACTION_SCREEN_OFF && running && !destroyed) {
        handler.post {
          releaseKeys()
        }
      }
      if (intent?.action == Intent.ACTION_SCREEN_ON && running && !destroyed) {
        liveFrames?.invalidatePublication()
        if (paused) {
          handler.removeCallbacks(publishPaused)
          handler.post(publishPaused)
        }
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    DoomController.service = this
    val screenFilter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, screenFilter, Context.RECEIVER_NOT_EXPORTED)
    else registerReceiver(screenOn, screenFilter)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<String>) {
    writer.println(org.json.JSONObject(DoomController.status())
      .put("pendingKeyReleases", releases.size).toString())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (val action = intent?.action) {
      START -> if (paused) control("pause") else if (!running && startupThread == null) {
        renderer = intent.getStringExtra("renderer") ?: "flipper"
        startGame()
      }
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
      val controlsChannel = NotificationChannel(CONTROLS_CHANNEL, "Doom controls", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Doom controls and session actions"
        setSound(null, null)
        enableVibration(false)
        setShowBadge(false)
      }
      manager.createNotificationChannels(listOf(channel, controlsChannel))
      check(manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
        "The Doom notification channel is disabled."
      }
      check(manager.getNotificationChannel(CONTROLS_CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
        "The Doom controls channel is disabled."
      }
      // Silent until the game holds audio focus (see updateAudio).
      DoomEngine.setVolume(0f)
      // The stable controls are the service-owned notification. If the process dies, Android
      // removes them; the separately refreshed video has a timeout so it cannot be orphaned.
      val notification = controlsNotification()
      DoomNotificationRate.record()
      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(CONTROLS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(CONTROLS_ID, notification)
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
              if (renderer == "live") {
                liveFrames = DoomLiveFrames.create(this) { error -> handler.post { useFlipper(error) } }
              }
              DoomController.update("state" to "playing", "framesPublished" to 0, "error" to null)
              updateAudio()
              handler.post(publishControls)
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
        DoomEngine.renderState(frameState)?.let { pixels ->
          val frame = DoomFrames.add(this@DoomService, pixels)
          lastFrame = frame
          lastFrameAt = SystemClock.uptimeMillis()
          liveFrames?.let { live ->
            try { live.push(pixels) } catch (error: Exception) { useFlipper(error) }
            if (liveFrames === live) publishLive()
          }
          if (liveFrames != null) {
            frames++
            DoomController.update("framesPublished" to frames)
          } else {
            frameBatch.add(frame)
            if (pendingInput != 0L) {
              if (frameState[2] >= pendingInput) {
                // Pause or queue overflow invalidated this request; a later ID cannot ack it.
                pendingInput = 0L
                flushed = false
              } else if (frameState[1] >= pendingInput) {
                // These pixels include a simulated command containing the input, not merely a
                // key read from the queue. No fixed two-tic settling interval is needed.
                pendingInput = 0L
                flushed = true
              }
            }
            if (flushed) {
              // Keep the response current even when the rate budget delays its publication.
              frameBatch.subList(0, frameBatch.size - 1).clear()
            }
            // While the rate budget holds a batch back, keep the newest frames.
            while (frameBatch.size > batchSize) frameBatch.removeAt(0)
            // A tap's response, and the update that ends its still, go out as soon as the budget
            // allows. Sustained input uses shorter decks; idle decks rebuild the headroom that
            // lets the next isolated tap's response skip the wait.
            val ready = pendingInput == 0L && (flushed || followUp || frameBatch.size == batchSize)
            if (ready && DoomNotificationRate.delayMs() == 0L) publishBatch()
          }
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

  private fun publishLive() {
    val live = liveFrames ?: return
    if (DoomNotificationRate.delayMs() != 0L) return
    val notification = live.notification(paused) ?: return
    post(notification)
    live.didPublish()
  }

  private fun useFlipper(error: Exception) {
    if (liveFrames == null || destroyed || !running) return
    Log.w("NotiDoom", "Live images unavailable; continuing with ViewFlipper", error)
    liveFrames?.close()
    liveFrames = null
    renderer = "flipper"
    frameBatch.clear()
    pendingInput = 0L
    flushed = false
    followUp = false
    DoomController.update("renderer" to "flipper")
    if (paused) { handler.removeCallbacks(publishPaused); handler.post(publishPaused) }
  }

  private fun publishBatch() {
    // Change back to idle at a publication boundary, not halfway through collecting a short
    // deck. Pad this deck for the NEXT collection period so the transition cannot replay it.
    val nextBatchSize = if (SystemClock.uptimeMillis() < interactiveUntil) INTERACTIVE_BATCH_SIZE else IDLE_BATCH_SIZE
    // URI lookup happens after Android's delivery/assistant delay. Seal a fresh, consistent
    // deck then, rather than adding that delay to images frozen here. Paused images use their
    // original captured URI, and every sealed URI remains immutable for later inflations.
    post(notification("Freedoom · tap controls to play", DoomFrames.defer(this, frameBatch.size), nextBatchSize))
    if (Log.isLoggable("NotiDoomLatency", Log.DEBUG)) {
      Log.d("NotiDoomLatency", "publish at=${SystemClock.uptimeMillis()} count=${frameBatch.size} flush=$flushed")
    }
    followUp = flushed
    flushed = false
    frames += frameBatch.size
    DoomController.update("framesPublished" to frames)
    frameBatch.clear()
    batchSize = nextBatchSize
  }

  /** Every Doom update must pass through here so the rate model sees it. */
  private fun post(notification: Notification, id: Int = ID) {
    DoomNotificationRate.record()
    manager.notify(id, notification)
  }

  // Video updates reinflate their decorated row. Keep the buttons in a different notification
  // so an in-flight press is not canceled each time a frame deck arrives.
  private val publishControls = object : Runnable {
    override fun run() {
      if (!running || destroyed) return
      val wait = DoomNotificationRate.delayMs()
      if (wait > 0) handler.postDelayed(this, wait)
      else post(controlsNotification(), CONTROLS_ID)
    }
  }

  private val publishPaused = object : Runnable {
    override fun run() {
      if (!paused || destroyed) return
      val wait = DoomNotificationRate.delayMs()
      if (wait > 0) {
        handler.postDelayed(this, wait)
      } else {
        if (liveFrames != null) publishLive()
        else post(notification("Paused · tap Resume", listOfNotNull(lastFrame)))
        // Keep the paused image available while retaining a finite lifetime if this process
        // dies. Handler time does not wake the device; screen-on restores an expired image.
        handler.postDelayed(this, VIDEO_KEEPALIVE_MS)
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
          pendingInput = 0L
          flushed = false
          followUp = false
          batchSize = IDLE_BATCH_SIZE
          interactiveUntil = 0L
          lastInputAt = 0L
          paused = !paused
          updateAudio()
          DoomController.update("state" to if (paused) "paused" else "playing", "lastAction" to action)
          handler.removeCallbacks(sampleFrame)
          handler.removeCallbacks(publishPaused)
          handler.removeCallbacks(publishControls)
          handler.post(publishControls)
          handler.post(if (paused) publishPaused else sampleFrame)
        } else if (action == "mute") {
          muted = !muted
          updateAudio()
          DoomController.update("muted" to muted, "lastAction" to action)
          handler.removeCallbacks(publishControls)
          handler.post(publishControls)
        } else if (!paused) {
          val key = CONTROLS[action] ?: return@post
          releases.remove(key)?.let(handler::removeCallbacks)
          val input = DoomEngine.keyTracked(key, true)
          check(input != 0L) { "Doom could not accept the control." }
          val now = SystemClock.uptimeMillis()
          if (Log.isLoggable("NotiDoomLatency", Log.DEBUG)) {
            Log.d("NotiDoomLatency", "input=$action at=$now")
          }
          // Preserve the isolated-tap fast path. During a burst, trim older history without
          // increasing the frame interval or bypassing the notification rate budget.
          if (lastInputAt == 0L || now - lastInputAt > INPUT_BURST_GAP_MS) {
            pendingInput = input
          } else {
            interactiveUntil = now + INTERACTIVE_IDLE_MS
            batchSize = INTERACTIVE_BATCH_SIZE
          }
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
    manager.cancel(ID)
    manager.cancel(CONTROLS_ID)
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
    liveFrames?.close()
    liveFrames = null
    unregisterReceiver(screenOn)
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
    manager.cancel(ID)
    manager.cancel(CONTROLS_ID)
    super.onDestroy()
  }

  private fun pending(action: String): PendingIntent = PendingIntent.getService(
    this, action.hashCode(), Intent(this, DoomService::class.java).setAction(action),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

  private fun notification(status: String, batch: List<Uri> = emptyList(), nextBatchSize: Int = IDLE_BATCH_SIZE): Notification {
    val expanded = RemoteViews(packageName, R.layout.nm_doom).apply {
      removeAllViews(R.id.nm_doom_frame)
      // The flipper loops. Hold the last image for the next deck's collection period, including
      // the longer period when sustained input ends. A single frame already holds indefinitely.
      val padding = if (batch.size > 1) max(0, nextBatchSize - batch.size) + HOLD_FRAMES else 0
      (batch + List(padding) { batch.last() }).forEach { frame ->
        addView(R.id.nm_doom_frame, RemoteViews(packageName, R.layout.nm_doom_frame).apply {
          setImageViewUri(R.id.nm_doom_frame_image, frame)
        })
      }
      setInt(R.id.nm_doom_frame, "setFlipInterval", FRAME_INTERVAL_MS.toInt())
      setDisplayedChild(R.id.nm_doom_frame, 0)
      setTextViewText(R.id.nm_doom_status, status)
    }
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.nm_small_icon)
      .setContentTitle("Doom · Noti")
      .setSubText(if (paused) "Doom · paused" else "Doom")
      .setContentText(status)
      .setStyle(Notification.DecoratedCustomViewStyle())
      .setCustomBigContentView(expanded)
      .setOnlyAlertOnce(true)
      .setSound(null)
      .setVibrate(null)
      .setDefaults(0)
      .setWhen(controlsWhen + 1L)
      .setShowWhen(false)
      .setOngoing(true)
      .setDeleteIntent(pending(STOP))
      .setCategory(Notification.CATEGORY_SERVICE)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
      .setTimeoutAfter(VIDEO_TIMEOUT_MS)
      .build()
  }

  private fun controlsNotification(): Notification {
    val controls = RemoteViews(packageName, R.layout.nm_doom_controls).apply {
      mapOf(
        R.id.nm_doom_forward to "forward", R.id.nm_doom_backward to "backward",
        R.id.nm_doom_left to "left", R.id.nm_doom_right to "right",
        R.id.nm_doom_fire to "fire", R.id.nm_doom_use to "use",
      ).forEach { (id, action) ->
        setOnClickPendingIntent(id, pending(action))
        setBoolean(id, "setEnabled", running && !paused)
      }
    }
    return Notification.Builder(this, CONTROLS_CHANNEL)
      .setSmallIcon(R.drawable.nm_small_icon)
      .setContentTitle("Doom controls")
      .setSubText(if (!running) "Doom · loading" else if (paused) "Doom controls · paused" else "Doom controls")
      .setStyle(Notification.DecoratedCustomViewStyle())
      .setCustomContentView(controls)
      .setCustomBigContentView(controls)
      .setOnlyAlertOnce(true)
      // setShowWhen hides the clock; it does not freeze the ranking timestamp.
      .setWhen(controlsWhen)
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
    const val CONTROLS_ID = 16036
    const val CHANNEL = "noti-doom"
    const val CONTROLS_CHANNEL = "noti-doom-controls"
    const val START = "noti.doom.start"
    const val STOP = "noti.doom.stop"
    private const val FRAME_INTERVAL_MS = 33L
    // Idle batches: 15 × 33 ms, 2 updates/s.
    private const val IDLE_BATCH_SIZE = 15
    // 10 × 33 ms stays above the conservative limiter's ~317 ms steady-state interval.
    private const val INTERACTIVE_BATCH_SIZE = 10
    private const val INTERACTIVE_IDLE_MS = 750L
    private const val VIDEO_TIMEOUT_MS = 10_000L
    private const val VIDEO_KEEPALIVE_MS = 5_000L
    private const val HOLD_FRAMES = 3
    private const val INPUT_BURST_GAP_MS = 350L
    // While another app briefly speaks over the game (e.g. navigation).
    private const val DUCK_VOLUME = 0.25f
    val CONTROLS = mapOf("forward" to 0xAD, "backward" to 0xAF, "left" to 0xAC, "right" to 0xAE, "fire" to 0xA3, "use" to 0xA2)
  }
}
