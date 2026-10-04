package expo.modules.notificationmotion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import java.io.File

/** Service-owned state and controls continue to work while React is suspended. */
internal object DoomController {
  @Volatile var service: DoomService? = null
  @Volatile private var snapshot: Map<String, Any?> = mapOf(
    "state" to "idle", "framesPublished" to 0, "notificationFps" to 30,
    "engineTickRate" to 35, "lastAction" to null, "error" to null,
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
    require(action in DoomService.CONTROLS || action == "pause") { "Unknown Doom control: $action" }
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

class DoomService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private val manager get() = getSystemService(NotificationManager::class.java)
  private val releases = mutableMapOf<Int, Runnable>()
  private var bitmap: Bitmap? = null
  private val frameBatch = ArrayList<Bitmap>(BATCH_SIZE)
  private var paused = false
  private var running = false
  private var nativeStarted = false
  private var frames = 0
  private var wadPath: String? = null
  private var lastFrameAt = 0L
  private var nextFrameAt = 0L
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
      val notification = notification("Loading Freedoom…")
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
          check(pixels.size == 320 * 200) { "Unexpected Doom frame size." }
          // Keep each snapshot immutable until its RemoteViews transaction has been sent.
          bitmap = compactFrame(pixels)
          frameBatch.add(checkNotNull(bitmap))
          lastFrameAt = SystemClock.uptimeMillis()
          if (frameBatch.size == BATCH_SIZE) publishBatch()
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

  private fun compactFrame(pixels: IntArray): Bitmap {
    val source = Bitmap.createBitmap(pixels, 320, 200, Bitmap.Config.ARGB_8888)
    return try {
      Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.RGB_565).also { target ->
        Canvas(target).drawBitmap(
          source, Rect(0, 0, 320, 200), Rect(0, 0, FRAME_WIDTH, FRAME_HEIGHT),
          Paint().apply { isFilterBitmap = false },
        )
      }
    } finally {
      source.recycle()
    }
  }

  private fun publishBatch() {
    manager.notify(ID, notification("Freedoom · tap controls to play", frameBatch))
    frames += frameBatch.size
    DoomController.update("framesPublished" to frames)
    frameBatch.clear()
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
          paused = !paused
          DoomController.update("state" to if (paused) "paused" else "playing", "lastAction" to action)
          handler.removeCallbacks(sampleFrame)
          if (paused) {
            manager.notify(ID, notification("Paused · tap Resume", bitmap?.let { listOf(it) } ?: emptyList()))
          } else {
            handler.post(sampleFrame)
          }
        } else if (!paused) {
          val key = CONTROLS[action] ?: return@post
          releases.remove(key)?.let(handler::removeCallbacks)
          DoomEngine.key(key, true)
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
    frameBatch.clear()
    bitmap = null
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

  private fun notification(status: String, batch: List<Bitmap> = emptyList()): Notification {
    val expanded = RemoteViews(packageName, R.layout.nm_doom).apply {
      removeAllViews(R.id.nm_doom_frame)
      batch.forEach { frame ->
        addView(R.id.nm_doom_frame, RemoteViews(packageName, R.layout.nm_doom_frame).apply {
          setImageViewBitmap(R.id.nm_doom_frame_image, frame)
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
      .addAction(Notification.Action.Builder(null as Icon?, "Stop Doom", pending(STOP)).build())
      .build()
  }

  companion object {
    const val ID = 16035
    const val CHANNEL = "noti-doom"
    const val START = "noti.doom.start"
    const val STOP = "noti.doom.stop"
    private const val FRAME_INTERVAL_MS = 33L
    private const val BATCH_SIZE = 8
    private const val FRAME_WIDTH = 240
    private const val FRAME_HEIGHT = 150
    val CONTROLS = mapOf("forward" to 0xAD, "backward" to 0xAF, "left" to 0xAC, "right" to 0xAE, "fire" to 0xA3, "use" to 0xA2)
  }
}
