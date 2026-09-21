package expo.modules.notificationmotion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min
import org.json.JSONObject

internal data class FramePlaybackConfig(
  val serialized: String,
  val assetDirectory: String,
  val frameCount: Int,
  val sourceFps: Int,
  val requestedFps: Int,
  val filenamePrefix: String,
  val filenameDigits: Int,
  val filenameStartIndex: Int,
  val filenameExtension: String,
  val batchSize: Int,
  val notificationId: Int,
  val title: String,
  val body: String,
  val channelId: String,
  val channelName: String,
  val smallIcon: String,
  val loop: Boolean,
  val heightDp: Int,
) {
  // ViewFlipper only accepts whole milliseconds. Floor keeps every selected mode at or above its
  // requested host tick rate; wall-clock source selection prevents the small excess from speeding
  // up or shortening the video.
  val frameIntervalMs: Int get() = 1_000 / requestedFps
  val hostFps: Double get() = 1_000.0 / frameIntervalMs
  val uniqueFrameFps: Double get() = min(sourceFps.toDouble(), requestedFps.toDouble())
  val duplicatesSourceFrames: Boolean get() = hostFps > sourceFps
  val durationMs: Long get() = ceil(frameCount * 1_000.0 / sourceFps).toLong()

  fun frameNumber(sourceFrameIndex: Int): Int = filenameStartIndex + sourceFrameIndex

  fun filename(sourceFrameIndex: Int): String =
    filenamePrefix + frameNumber(sourceFrameIndex).toString().padStart(filenameDigits, '0') + "." + filenameExtension

  fun assetPath(sourceFrameIndex: Int): String = "$assetDirectory/${filename(sourceFrameIndex)}"

  fun frameUri(context: Context, sourceFrameIndex: Int): Uri {
    val builder = Uri.Builder()
      .scheme(ContentResolver.SCHEME_CONTENT)
      .authority("${context.packageName}.notificationmotion.frames")
      .appendPath("asset")
    assetDirectory.split('/').forEach(builder::appendPath)
    return builder.appendPath(filename(sourceFrameIndex)).build()
  }

  companion object {
    private val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")
    private val SAFE_PREFIX = Regex("[A-Za-z0-9._-]*")
    private val SAFE_RESOURCE = Regex("[a-z][a-z0-9_]*")
    private val SAFE_CHANNEL = Regex("[A-Za-z0-9._-]{1,100}")
    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    fun parse(context: Context, serialized: String, validateAssets: Boolean): FramePlaybackConfig {
      val value = JSONObject(serialized)
      val assetDirectory = value.getString("assetDirectory").trim('/')
      require(assetDirectory.isNotEmpty() && assetDirectory.split('/').all { it.matches(SAFE_SEGMENT) && it != "." && it != ".." }) {
        "assetDirectory must be a safe relative Android asset directory."
      }
      val frameCount = value.getInt("frameCount")
      require(frameCount in 1..100_000) { "frameCount must be between 1 and 100000." }
      val sourceFps = value.optInt("sourceFps", 30)
      require(sourceFps in 1..240) { "sourceFps must be between 1 and 240." }
      val requestedFps = value.optInt("fps", 30)
      require(requestedFps == 15 || requestedFps == 30 || requestedFps == 60) { "fps must be 15, 30, or 60." }
      val prefix = value.optString("filenamePrefix", "frame_")
      require(prefix.matches(SAFE_PREFIX)) { "filenamePrefix may only contain letters, numbers, '.', '_' and '-'." }
      val digits = value.optInt("filenameDigits", 5)
      require(digits in 1..9) { "filenameDigits must be between 1 and 9." }
      val startIndex = value.optInt("filenameStartIndex", 1)
      require(startIndex >= 0 && startIndex.toLong() + frameCount - 1 <= Int.MAX_VALUE) {
        "filenameStartIndex and frameCount exceed the supported range."
      }
      require((startIndex.toLong() + frameCount - 1).toString().length <= digits) {
        "filenameDigits is too small for the final frame number."
      }
      val extension = value.optString("filenameExtension", "png").lowercase()
      require(extension in EXTENSIONS) { "filenameExtension must be png, jpg, jpeg, or webp." }
      val batchSize = value.optInt("batchSize", 60)
      require(batchSize in 2..60) { "batchSize must be between 2 and 60." }
      val notificationIdValue = value.optLong("notificationId", DEFAULT_NOTIFICATION_ID.toLong())
      require(notificationIdValue != 0L && notificationIdValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "notificationId must be a non-zero 32-bit Android notification id."
      }
      val notificationId = notificationIdValue.toInt()
      val title = value.optString("title", "Frame playback")
      require(title.isNotBlank() && title.length <= 120) { "title must contain 1–120 characters." }
      val body = value.optString("body", "Playing silently in the notification shade")
      require(body.length <= 240) { "body must contain at most 240 characters." }
      val channelId = value.optString("channelId", "noti-frame-playback")
      require(channelId.matches(SAFE_CHANNEL)) { "channelId must be 1–100 letters, numbers, '.', '_' or '-'." }
      val channelName = value.optString("channelName", "Frame playback")
      require(channelName.isNotBlank() && channelName.length <= 80) { "channelName must contain 1–80 characters." }
      val smallIcon = value.optString("smallIcon", "nm_small_icon")
      require(smallIcon.matches(SAFE_RESOURCE)) { "smallIcon must name a valid Android drawable resource." }
      val icon = context.resources.getIdentifier(smallIcon, "drawable", context.packageName).takeIf { it != 0 }
        ?: context.resources.getIdentifier(smallIcon, "mipmap", context.packageName)
      require(icon != 0) { "smallIcon must name an existing Android drawable or mipmap resource." }
      val height = value.optInt("height", 120)
      require(height in 48..240) { "height must be between 48 and 240 dp." }

      val config = FramePlaybackConfig(
        serialized = serialized,
        assetDirectory = assetDirectory,
        frameCount = frameCount,
        sourceFps = sourceFps,
        requestedFps = requestedFps,
        filenamePrefix = prefix,
        filenameDigits = digits,
        filenameStartIndex = startIndex,
        filenameExtension = extension,
        batchSize = batchSize,
        notificationId = notificationId,
        title = title,
        body = body,
        channelId = channelId,
        channelName = channelName,
        smallIcon = smallIcon,
        loop = value.optBoolean("loop", false),
        heightDp = height,
      )
      if (validateAssets) config.validateAssets(context)
      return config
    }
  }

  private fun validateAssets(context: Context) {
    val available = requireNotNull(context.assets.list(assetDirectory)) {
      "Android asset directory '$assetDirectory' does not exist."
    }.toHashSet()
    val missing = (0 until frameCount).firstOrNull { filename(it) !in available }
    require(missing == null) {
      "Missing frame asset '${missing?.let(::assetPath)}'. Expected $frameCount sequential frames."
    }
  }
}

internal data class FramePlaybackRuntime(
  val token: String,
  val config: FramePlaybackConfig,
  val startedElapsedMs: Long,
  val startedAtEpochMs: Long,
  var state: String,
  var frozenElapsedMs: Long = 0,
  var batchIndex: Int = 0,
  var batchesPublished: Int = 0,
  var lastBatchFrameCount: Int = 0,
  var error: String? = null,
)

/** Shared state survives React instance teardown; persisted diagnostics also survive process recreation. */
internal object FramePlaybackController {
  private const val PREFERENCES = "notification-motion-frame-playback"
  private const val KEY_CONFIG = "config"
  private const val KEY_STATUS = "status"
  private val lock = Any()
  @Volatile private var runtime: FramePlaybackRuntime? = null
  @Volatile private var activeService: FramePlaybackService? = null

  fun play(context: Context, serialized: String): Map<String, Any?> {
    check(Build.VERSION.SDK_INT >= 31) { "Frame playback requires Android 12 / API 31+." }
    val appContext = context.applicationContext
    val config = FramePlaybackConfig.parse(appContext, serialized, validateAssets = true)
    ensureNotificationsEnabled(appContext, config.channelId)
    val nowElapsed = SystemClock.elapsedRealtime()
    val nowEpoch = System.currentTimeMillis()
    val next = FramePlaybackRuntime(
      token = UUID.randomUUID().toString(),
      config = config,
      startedElapsedMs = nowElapsed,
      startedAtEpochMs = nowEpoch,
      state = "starting",
    )
    synchronized(lock) {
      runtime = next
      persist(appContext, next)
    }
    val intent = Intent(appContext, FramePlaybackService::class.java)
      .setAction(FramePlaybackService.ACTION_PLAY)
      .putExtra(FramePlaybackService.EXTRA_CONFIG, serialized)
      .putExtra(FramePlaybackService.EXTRA_TOKEN, next.token)
      .putExtra(FramePlaybackService.EXTRA_STARTED_AT, nowEpoch)
    try {
      appContext.startForegroundService(intent)
    } catch (error: Throwable) {
      fail(appContext, next.token, error)
      throw error
    }
    return status(appContext)
  }

  fun stop(context: Context): Map<String, Any?> {
    val appContext = context.applicationContext
    val service: FramePlaybackService?
    val notificationId: Int?
    synchronized(lock) {
      val current = runtime ?: restore(appContext)
      notificationId = current?.config?.notificationId
      current?.let {
        it.frozenElapsedMs = elapsed(it, SystemClock.elapsedRealtime())
        it.state = "stopped"
        persist(appContext, it)
      }
      service = activeService
    }
    if (service != null) {
      service.stopFromController(removeNotification = true)
    } else {
      notificationId?.let { appContext.getSystemService(NotificationManager::class.java).cancel(it) }
      appContext.stopService(Intent(appContext, FramePlaybackService::class.java))
    }
    return status(appContext)
  }

  fun status(context: Context): Map<String, Any?> = synchronized(lock) {
    val current = runtime ?: restore(context.applicationContext)
    current?.let { statusMap(it, SystemClock.elapsedRealtime()) } ?: idleStatus()
  }

  fun attach(service: FramePlaybackService) {
    activeService = service
  }

  fun detach(service: FramePlaybackService) {
    if (activeService === service) activeService = null
  }

  fun prepareService(
    context: Context,
    serialized: String,
    token: String,
    startedAtEpochMs: Long,
  ): FramePlaybackRuntime = synchronized(lock) {
    (runtime ?: restore(context))?.takeIf { it.token == token }?.also {
      it.state = "playing"
      persist(context, it)
      return it
    }
    val config = FramePlaybackConfig.parse(context, serialized, validateAssets = false)
    val wallElapsed = (System.currentTimeMillis() - startedAtEpochMs).coerceAtLeast(0)
    FramePlaybackRuntime(
      token = token,
      config = config,
      startedElapsedMs = SystemClock.elapsedRealtime() - wallElapsed,
      startedAtEpochMs = startedAtEpochMs,
      state = "playing",
    ).also {
      runtime = it
      persist(context, it)
    }
  }

  fun isPlaying(token: String): Boolean = synchronized(lock) {
    runtime?.let { it.token == token && (it.state == "playing" || it.state == "starting") } == true
  }

  fun batchPublished(context: Context, token: String, batchIndex: Int, frameCount: Int) {
    synchronized(lock) {
      runtime?.takeIf { it.token == token }?.let {
        it.state = "playing"
        it.batchIndex = batchIndex
        it.batchesPublished += 1
        it.lastBatchFrameCount = frameCount
        persist(context, it)
      }
    }
  }

  fun complete(context: Context, token: String) {
    synchronized(lock) {
      runtime?.takeIf { it.token == token }?.let {
        it.frozenElapsedMs = it.config.durationMs
        it.state = "completed"
        persist(context, it)
      }
    }
  }

  fun markStopped(context: Context, token: String) {
    synchronized(lock) {
      runtime?.takeIf { it.token == token }?.let {
        it.frozenElapsedMs = elapsed(it, SystemClock.elapsedRealtime())
        it.state = "stopped"
        persist(context, it)
      }
    }
  }

  fun fail(context: Context, token: String, error: Throwable) {
    synchronized(lock) {
      runtime?.takeIf { it.token == token }?.let {
        it.frozenElapsedMs = elapsed(it, SystemClock.elapsedRealtime())
        it.state = "error"
        it.error = error.message ?: error.javaClass.simpleName
        persist(context, it)
      }
    }
  }

  private fun ensureNotificationsEnabled(context: Context, channelId: String) {
    val manager = context.getSystemService(NotificationManager::class.java)
    check(manager.areNotificationsEnabled()) {
      "Notification permission is disabled. Call requestPermission() before playFrames()."
    }
    check(manager.getNotificationChannel(channelId)?.importance != NotificationManager.IMPORTANCE_NONE) {
      "The frame playback notification channel is disabled."
    }
  }

  private fun elapsed(value: FramePlaybackRuntime, nowElapsedMs: Long): Long =
    if (value.state == "playing" || value.state == "starting") {
      (nowElapsedMs - value.startedElapsedMs).coerceAtLeast(0)
    } else {
      value.frozenElapsedMs.coerceAtLeast(0)
    }

  private fun statusMap(value: FramePlaybackRuntime, nowElapsedMs: Long): Map<String, Any?> {
    val config = value.config
    val totalElapsed = elapsed(value, nowElapsedMs)
    val timelineElapsed = when {
      value.state == "completed" -> config.durationMs
      config.loop && config.durationMs > 0 -> totalElapsed % config.durationMs
      else -> totalElapsed.coerceAtMost(config.durationMs)
    }
    val frameElapsed = timelineElapsed.coerceAtMost((config.durationMs - 1).coerceAtLeast(0))
    val sourceIndex = ((frameElapsed * config.sourceFps) / 1_000L).toInt().coerceIn(0, config.frameCount - 1)
    val outputIndex = (totalElapsed / config.frameIntervalMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val progress = if (value.state == "completed") 1.0 else timelineElapsed.toDouble() / config.durationMs
    return linkedMapOf(
      "state" to value.state,
      "notificationId" to config.notificationId,
      "requestedFps" to config.requestedFps,
      "hostFrameIntervalMs" to config.frameIntervalMs,
      "hostFps" to config.hostFps,
      "sourceFps" to config.sourceFps,
      "uniqueFrameFps" to config.uniqueFrameFps,
      "duplicatesSourceFrames" to config.duplicatesSourceFrames,
      "sourceFrameIndex" to sourceIndex,
      "sourceFrameNumber" to config.frameNumber(sourceIndex),
      "outputFrameIndex" to outputIndex,
      "elapsedMs" to totalElapsed,
      "durationMs" to config.durationMs,
      "progress" to progress.coerceIn(0.0, 1.0),
      "batchSize" to config.batchSize,
      "batchIndex" to value.batchIndex,
      "batchesPublished" to value.batchesPublished,
      "lastBatchFrameCount" to value.lastBatchFrameCount,
      "startedAt" to value.startedAtEpochMs,
      "error" to value.error,
    )
  }

  private fun idleStatus(): Map<String, Any?> = linkedMapOf(
    "state" to "idle",
    "notificationId" to null,
    "requestedFps" to null,
    "hostFrameIntervalMs" to null,
    "hostFps" to null,
    "sourceFps" to null,
    "uniqueFrameFps" to null,
    "duplicatesSourceFrames" to false,
    "sourceFrameIndex" to 0,
    "sourceFrameNumber" to 0,
    "outputFrameIndex" to 0,
    "elapsedMs" to 0L,
    "durationMs" to 0L,
    "progress" to 0.0,
    "batchSize" to null,
    "batchIndex" to 0,
    "batchesPublished" to 0,
    "lastBatchFrameCount" to 0,
    "startedAt" to null,
    "error" to null,
  )

  private fun persist(context: Context, value: FramePlaybackRuntime) {
    val status = JSONObject(statusMap(value, SystemClock.elapsedRealtime())).put("token", value.token)
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString(KEY_CONFIG, value.config.serialized)
      .putString(KEY_STATUS, status.toString())
      .apply()
  }

  private fun restore(context: Context): FramePlaybackRuntime? {
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val serialized = preferences.getString(KEY_CONFIG, null) ?: return null
    val status = preferences.getString(KEY_STATUS, null)?.let(::JSONObject) ?: return null
    return runCatching {
      val config = FramePlaybackConfig.parse(context, serialized, validateAssets = false)
      val startedAt = status.optLong("startedAt", System.currentTimeMillis())
      val savedElapsed = status.optLong("elapsedMs", 0).coerceAtLeast(0)
      val liveElapsed = if (status.optString("state") == "playing" || status.optString("state") == "starting") {
        (System.currentTimeMillis() - startedAt).coerceAtLeast(savedElapsed)
      } else {
        savedElapsed
      }
      FramePlaybackRuntime(
        token = status.optString("token", UUID.randomUUID().toString()),
        config = config,
        startedElapsedMs = SystemClock.elapsedRealtime() - liveElapsed,
        startedAtEpochMs = startedAt,
        state = status.optString("state", "idle"),
        frozenElapsedMs = liveElapsed,
        batchIndex = status.optInt("batchIndex", 0),
        batchesPublished = status.optInt("batchesPublished", 0),
        lastBatchFrameCount = status.optInt("lastBatchFrameCount", 0),
        error = status.optString("error").ifEmpty { null },
      )
    }.getOrNull()?.also { runtime = it }
  }
}

class FramePlaybackService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private val manager get() = getSystemService(NotificationManager::class.java)
  private var current: FramePlaybackRuntime? = null
  private var removeNotificationOnDestroy = false

  override fun onCreate() {
    super.onCreate()
    FramePlaybackController.attach(this)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> FramePlaybackController.stop(applicationContext)
      ACTION_PLAY -> startPlayback(intent)
      else -> stopSelf()
    }
    return START_REDELIVER_INTENT
  }

  private fun startPlayback(intent: Intent) {
    handler.removeCallbacksAndMessages(null)
    removeNotificationOnDestroy = false
    val oldId = current?.config?.notificationId
    val serialized = requireNotNull(intent.getStringExtra(EXTRA_CONFIG)) { "Missing frame playback config." }
    val token = requireNotNull(intent.getStringExtra(EXTRA_TOKEN)) { "Missing frame playback token." }
    val startedAt = intent.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
    try {
      val playback = FramePlaybackController.prepareService(applicationContext, serialized, token, startedAt)
      current = playback
      if (oldId != null && oldId != playback.config.notificationId) manager.cancel(oldId)
      createChannel(playback.config)
      publishBatch(playback, first = true)
    } catch (error: Throwable) {
      FramePlaybackController.fail(applicationContext, token, error)
      current?.config?.notificationId?.let(manager::cancel)
      stopSelf()
    }
  }

  private fun publishBatch(playback: FramePlaybackRuntime, first: Boolean) {
    if (!FramePlaybackController.isPlaying(playback.token)) return
    val config = playback.config
    val now = SystemClock.elapsedRealtime()
    val totalElapsed = (now - playback.startedElapsedMs).coerceAtLeast(0)
    if (!config.loop && totalElapsed >= config.durationMs) {
      complete(playback)
      return
    }
    val baseTick = totalElapsed / config.frameIntervalMs
    val uris = (0 until config.batchSize).map { offset ->
      val tickElapsed = (baseTick + offset) * config.frameIntervalMs
      val timelineElapsed = if (config.loop) tickElapsed % config.durationMs else tickElapsed.coerceAtMost(config.durationMs - 1)
      val sourceIndex = ((timelineElapsed * config.sourceFps) / 1_000L).toInt().coerceIn(0, config.frameCount - 1)
      config.frameUri(this, sourceIndex)
    }
    val notification = buildNotification(config, uris, animated = true, ongoing = true)
    if (first) {
      startForeground(config.notificationId, notification)
    } else {
      manager.notify(config.notificationId, notification)
    }
    val batchIndex = (baseTick / config.batchSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    FramePlaybackController.batchPublished(applicationContext, playback.token, batchIndex, uris.size)

    val nextAt = playback.startedElapsedMs + (baseTick + config.batchSize) * config.frameIntervalMs
    val completionAt = playback.startedElapsedMs + config.durationMs
    // Handler uses uptime; convert our elapsed-realtime deadline to a delay.
    val deadline = if (config.loop) nextAt else min(nextAt, completionAt)
    handler.postDelayed(
      {
        try {
          publishBatch(playback, first = false)
        } catch (error: Throwable) {
          failPlayback(playback, error)
        }
      },
      (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0),
    )
  }

  private fun failPlayback(playback: FramePlaybackRuntime, error: Throwable) {
    handler.removeCallbacksAndMessages(null)
    FramePlaybackController.fail(applicationContext, playback.token, error)
    removeNotificationOnDestroy = true
    manager.cancel(playback.config.notificationId)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun complete(playback: FramePlaybackRuntime) {
    if (!FramePlaybackController.isPlaying(playback.token)) return
    handler.removeCallbacksAndMessages(null)
    val config = playback.config
    val finalUri = config.frameUri(this, config.frameCount - 1)
    manager.notify(config.notificationId, buildNotification(config, listOf(finalUri), animated = false, ongoing = false))
    FramePlaybackController.complete(applicationContext, playback.token)
    stopForeground(STOP_FOREGROUND_DETACH)
    stopSelf()
  }

  internal fun stopFromController(removeNotification: Boolean) {
    handler.post {
      handler.removeCallbacksAndMessages(null)
      removeNotificationOnDestroy = removeNotification
      current?.let { FramePlaybackController.markStopped(applicationContext, it.token) }
      if (removeNotification) current?.config?.notificationId?.let(manager::cancel)
      stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
      stopSelf()
    }
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    if (removeNotificationOnDestroy) current?.config?.notificationId?.let(manager::cancel)
    FramePlaybackController.detach(this)
    super.onDestroy()
  }

  private fun createChannel(config: FramePlaybackConfig) {
    val channel = NotificationChannel(config.channelId, config.channelName, NotificationManager.IMPORTANCE_LOW).apply {
      description = "Silent frame-sequence playback"
      setSound(null, null)
      enableVibration(false)
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
    check(manager.getNotificationChannel(config.channelId)?.importance != NotificationManager.IMPORTANCE_NONE) {
      "The frame playback notification channel is disabled."
    }
  }

  private fun buildNotification(
    config: FramePlaybackConfig,
    frames: List<Uri>,
    animated: Boolean,
    ongoing: Boolean,
  ): Notification {
    val icon = resources.getIdentifier(config.smallIcon, "drawable", packageName).takeIf { it != 0 }
      ?: resources.getIdentifier(config.smallIcon, "mipmap", packageName)
    val collapsed = staticFrame(config, frames.first(), COLLAPSED_HEIGHT_DP)
    val expanded = if (animated) frameBatch(config, frames) else staticFrame(config, frames.first(), config.heightDp)
    val builder = Notification.Builder(this, config.channelId)
      .setSmallIcon(icon)
      .setContentTitle(config.title)
      .setContentText(config.body)
      .setCategory(Notification.CATEGORY_SERVICE)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setOnlyAlertOnce(true)
      .setSound(null as Uri?)
      .setVibrate(null)
      .setDefaults(0)
      .setShowWhen(false)
      .setOngoing(ongoing)
      .setStyle(Notification.DecoratedCustomViewStyle())
      .setCustomContentView(collapsed)
      .setCustomBigContentView(expanded)
      .setCustomHeadsUpContentView(collapsed)
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
      builder.setContentIntent(
        PendingIntent.getActivity(
          this,
          config.notificationId,
          launchIntent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
      )
    }
    if (ongoing) {
      val stopIntent = Intent(this, FramePlaybackService::class.java).setAction(ACTION_STOP)
      builder.addAction(
        Notification.Action.Builder(
          null as Icon?,
          "Stop",
          PendingIntent.getService(
            this,
            config.notificationId,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          ),
        ).build(),
      )
    }
    return builder.build()
  }

  private fun staticFrame(config: FramePlaybackConfig, uri: Uri, heightDp: Int): RemoteViews =
    RemoteViews(packageName, R.layout.nm_frame_sequence_image).also { views ->
      views.setViewLayoutHeight(
        R.id.nm_frame_sequence_image_root,
        heightDp.toFloat(),
        TypedValue.COMPLEX_UNIT_DIP,
      )
      views.setImageViewUri(R.id.nm_frame_sequence_image, uri)
      views.setContentDescription(
        R.id.nm_frame_sequence_image,
        "${config.title} ${uri.lastPathSegment ?: "video frame"}",
      )
    }

  private fun frameBatch(config: FramePlaybackConfig, frames: List<Uri>): RemoteViews =
    RemoteViews(packageName, R.layout.nm_frame_sequence).also { views ->
      views.setViewLayoutHeight(
        R.id.nm_frame_sequence_root,
        config.heightDp.toFloat(),
        TypedValue.COMPLEX_UNIT_DIP,
      )
      views.removeAllViews(R.id.nm_frame_sequence_content)
      frames.forEach { uri ->
        val frame = RemoteViews(packageName, R.layout.nm_frame_sequence_image)
        frame.setImageViewUri(R.id.nm_frame_sequence_image, uri)
        frame.setViewVisibility(R.id.nm_frame_sequence_image, View.VISIBLE)
        frame.setContentDescription(
          R.id.nm_frame_sequence_image,
          "${config.title} ${uri.lastPathSegment ?: "video frame"}",
        )
        views.addView(R.id.nm_frame_sequence_content, frame)
      }
      views.setInt(R.id.nm_frame_sequence_content, "setFlipInterval", config.frameIntervalMs)
      views.setDisplayedChild(R.id.nm_frame_sequence_content, 0)
      views.setContentDescription(R.id.nm_frame_sequence_content, "${config.title} frame sequence")
    }

  companion object {
    internal const val ACTION_PLAY = "expo.modules.notificationmotion.action.PLAY_FRAMES"
    internal const val ACTION_STOP = "expo.modules.notificationmotion.action.STOP_FRAMES"
    internal const val EXTRA_CONFIG = "framePlaybackConfig"
    internal const val EXTRA_TOKEN = "framePlaybackToken"
    internal const val EXTRA_STARTED_AT = "framePlaybackStartedAt"
    private const val COLLAPSED_HEIGHT_DP = 48
  }
}

private const val DEFAULT_NOTIFICATION_ID = 90_731
