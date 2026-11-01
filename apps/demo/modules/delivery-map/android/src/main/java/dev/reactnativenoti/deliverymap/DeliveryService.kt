package dev.reactnativenoti.deliverymap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import com.mapbox.bindgen.Value
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.LayerPosition
import com.mapbox.maps.MapSnapshotOptions
import com.mapbox.maps.Size
import com.mapbox.maps.SnapshotOverlayOptions
import com.mapbox.maps.SnapshotStyleListener
import com.mapbox.maps.Snapshotter
import com.mapbox.maps.Style
import org.json.JSONArray
import org.json.JSONObject

/** Service-owned state, so the card keeps working while React is suspended. */
internal object DeliveryController {
  @Volatile private var snapshot: Map<String, Any?> = mapOf(
    "state" to "idle", "headline" to null, "detail" to null, "progress" to 0.0, "error" to null,
  )

  fun status(): Map<String, Any?> = snapshot
  @Synchronized fun update(vararg values: Pair<String, Any?>) { snapshot = snapshot + values }

  fun start(context: Context, options: String): Map<String, Any?> {
    val manager = context.getSystemService(NotificationManager::class.java)
    check(manager.areNotificationsEnabled()) { "Enable notifications to track a delivery." }
    if (snapshot["state"] == "starting" || snapshot["state"] == "enroute") return status()
    update("state" to "starting", "error" to null, "progress" to 0.0, "headline" to null, "detail" to null)
    try {
      context.startForegroundService(
        Intent(context, DeliveryService::class.java).setAction(DeliveryService.START).putExtra("options", options),
      )
    } catch (error: Exception) {
      update("state" to "error", "error" to error.message)
      throw error
    }
    return status()
  }

  fun stop(context: Context): Map<String, Any?> {
    update("state" to "stopped")
    context.stopService(Intent(context, DeliveryService::class.java))
    context.getSystemService(NotificationManager::class.java).cancel(DeliveryService.ID)
    return status()
  }
}

/**
 * Tracks one simulated delivery in a custom notification. Mapbox renders off-screen only when the
 * camera is about to outgrow the last render. Every few seconds [DeliveryDeck] publishes a new set
 * of frames that move that render and the markers, and SystemUI flips through them by itself.
 */
class DeliveryService : Service() {
  private val main = Handler(Looper.getMainLooper())
  private val manager get() = getSystemService(NotificationManager::class.java)
  private val power get() = getSystemService(PowerManager::class.java)
  private val workerThread = HandlerThread("Delivery-deck")
  private lateinit var worker: Handler
  private var snapshotter: Snapshotter? = null
  private var timeline: DeliveryTimeline? = null
  private var details: DeliveryDetails? = null
  private var deck: DeliveryDeck? = null
  private var viewport: MapViewport? = null
  private var accent = 0
  @Volatile private var startedAt = 0L
  @Volatile private var running = false
  @Volatile private var destroyed = false
  private var delivered = false
  private var started = false
  private var mapBusy = false
  private var requestedPlan = -1
  private var nextDeckAt = 0L
  private val sentAt = System.currentTimeMillis()
  @Volatile private var mapRenders = 0
  @Volatile private var decks = 0
  @Volatile private var deckBuildMs = 0.0

  override fun onCreate() {
    super.onCreate()
    workerThread.start()
    worker = Handler(workerThread.looper)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<String>) {
    writer.println(
      JSONObject(
        DeliveryController.status() + mapOf(
          "mapRenders" to mapRenders,
          "mapRendersPlanned" to (timeline?.plans?.size ?: 0),
          "decks" to decks,
          "framesPerDeck" to DeliveryDeck.FRAMES_PER_DECK,
          "deckBuildMs" to deckBuildMs,
        ),
      ).toString(),
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      START -> if (timeline == null) begin(intent.getStringExtra("options"))
      STOP -> DeliveryController.stop(this)
      else -> stopSelf()
    }
    // A killed delivery should not resurrect itself mid-route.
    return START_NOT_STICKY
  }

  private fun begin(options: String?) {
    try {
      accent = getColor(R.color.delivery_accent)
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL, "Delivery", NotificationManager.IMPORTANCE_DEFAULT).apply {
          description = "Live delivery tracking"
          setSound(null, null)
          enableVibration(false)
          setShowBadge(false)
        },
      )
      val parsed = JSONObject(checkNotNull(options) { "Missing delivery options." })
      val route = parsed.getJSONArray("route")
      val coordinates = DoubleArray(route.length() * 2) { index ->
        route.getJSONArray(index / 2).getDouble(index % 2)
      }
      val details = DeliveryDetails(parsed.getString("courier"), parsed.getString("store"), parsed.getDouble("etaSeconds"))
      this.details = details
      val metrics = resources.displayMetrics
      val viewport = MapViewport(
        (metrics.widthPixels - MAP_INSET_DP * metrics.density).toDouble(),
        resources.getDimensionPixelSize(R.dimen.delivery_map_height).toDouble(),
        metrics.density.toDouble(),
      )
      this.viewport = viewport
      val timeline = DeliveryTimeline(coordinates, parsed.getLong("durationMs"), viewport, DeliveryDeck.DECK_MS)
      this.timeline = timeline
      Log.i(TAG, "Route ${timeline.totalMeters.toInt()} m, ${timeline.plans.size} map renders planned")
      val deck = DeliveryDeck(this, timeline, details, viewport)
      this.deck = deck
      val notification = card(
        deck.placeholder(R.layout.delivery_collapsed), deck.placeholder(R.layout.delivery_expanded),
        "Finding ${details.courier}", details.store,
      ).build()
      if (Build.VERSION.SDK_INT >= 34) {
        startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(ID, notification)
      }
      val dark = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
      MapboxOptions.accessToken = parsed.getString("accessToken")
      snapshotter = createSnapshotter(parsed.getString(if (dark) "darkMapStyle" else "mapStyle"), route, dark, viewport)
      renderMap(0)
      main.postDelayed(firstMapTimeout, FIRST_MAP_TIMEOUT_MS)
    } catch (error: Throwable) {
      fail(error)
    }
  }

  private val firstMapTimeout = Runnable {
    fail(IllegalStateException("The map took too long to load. Check the connection and try again."))
  }

  private fun createSnapshotter(styleUri: String, route: JSONArray, dark: Boolean, viewport: MapViewport): Snapshotter {
    val options = MapSnapshotOptions.Builder()
      // Size is in output pixels here; the ratio only scales roads and labels inside that image.
      .size(Size(viewport.renderWidth.toFloat(), viewport.renderHeight.toFloat()))
      .pixelRatio(viewport.renderRatio.toFloat())
      .build()
    // The card draws the credits itself, because this image gets scaled and cropped.
    return Snapshotter(this, options, SnapshotOverlayOptions(showLogo = false, showAttributes = false)).apply {
      setStyleListener(object : SnapshotStyleListener {
        override fun onDidFinishLoadingStyle(style: Style) {
          for (layer in style.styleLayers) {
            if (HIDDEN_LAYERS.any(layer.id::contains)) style.removeStyleLayer(layer.id)
          }
          addRoute(style, route, dark)
        }
      })
      setStyleUri(styleUri)
    }
  }

  /** Drawn by Mapbox above the roads and under the labels, so a street name on the route stays readable. */
  private fun addRoute(style: Style, route: JSONArray, dark: Boolean) {
    fun value(json: String) = checkNotNull(Value.fromJson(json).value) { "Invalid map layer: $json" }
    fun color(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)
    val casing = if (dark) "#14171B" else "#FFFFFF"
    // Some symbol layers sit among the roads, so the labels only start after the last other layer.
    var above = style.styleLayers.lastOrNull { it.type != "symbol" }?.id
    style.addStyleSource(
      "delivery-route",
      value("""{"type":"geojson","data":{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":$route}}}"""),
    )
    style.addStyleSource(
      "delivery-store",
      value("""{"type":"geojson","data":{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":${route.getJSONArray(0)}}}}"""),
    )
    for ((id, lineColor, width) in listOf(Triple("delivery-casing", casing, 8), Triple("delivery-line", color(accent), 4))) {
      style.addStyleLayer(
        value("""{"id":"$id","type":"line","source":"delivery-route","layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"$lineColor","line-width":$width}}"""),
        LayerPosition(above, null, null),
      )
      above = id
    }
    style.addStyleLayer(
      value("""{"id":"delivery-store","type":"circle","source":"delivery-store","paint":{"circle-radius":5,"circle-color":"$casing","circle-stroke-color":"${color(accent)}","circle-stroke-width":3}}"""),
      LayerPosition(null, null, null),
    )
  }

  private fun renderMap(index: Int) {
    val plan = timeline?.plans?.getOrNull(index) ?: return
    val snapshotter = snapshotter ?: return
    mapBusy = true
    requestedPlan = index
    val startedRender = SystemClock.elapsedRealtime()
    snapshotter.setCamera(
      CameraOptions.Builder()
        .center(Point.fromLngLat(plan.longitude, plan.latitude))
        .zoom(plan.zoom)
        .bearing(0.0)
        .pitch(0.0)
        .build(),
    )
    snapshotter.start(null) { bitmap: Bitmap?, error: String? ->
      mapBusy = false
      if (destroyed) return@start
      if (bitmap == null) {
        Log.w(TAG, "Map render failed: $error")
        if (!started) fail(IllegalStateException(error ?: "The map could not be rendered."))
        return@start
      }
      mapRenders++
      Log.i(TAG, "Map $index z=${"%.2f".format(plan.zoom)} ${bitmap.width}x${bitmap.height} in ${SystemClock.elapsedRealtime() - startedRender} ms")
      val first = !started
      started = true
      worker.post {
        if (destroyed) return@post
        deck?.addMap(bitmap, plan)
        if (first) startDelivery()
      }
    }
  }

  /** Runs on the worker once the first map exists, so the card never shows an empty map. */
  private fun startDelivery() {
    startedAt = SystemClock.elapsedRealtime() + PUBLISH_LATENCY_MS
    running = true
    nextDeckAt = 0L
    main.post {
      if (destroyed) return@post
      main.removeCallbacks(firstMapTimeout)
      DeliveryController.update("state" to "enroute")
      main.post(tick)
    }
    publishDeck.run()
  }

  /** Publishes ahead of each deck's start, because Android delays every update before showing it. */
  private val publishDeck = object : Runnable {
    override fun run() {
      val deck = deck
      val timeline = timeline
      val details = details
      if (destroyed || !running || deck == null || timeline == null || details == null) return
      val deckAt = nextDeckAt
      // Flippers do not run with the screen off, so there is nobody to draw for.
      if (power.isInteractive) {
        val building = SystemClock.elapsedRealtimeNanos()
        val expanded = deck.expanded(deckAt)
        val text = deliveryText(details, timeline, deckAt)
        manager.notify(ID, card(deck.collapsed(deckAt), expanded, text.title, text.subtitle).build())
        deckBuildMs = (SystemClock.elapsedRealtimeNanos() - building) / 1e6
        decks++
      }
      nextDeckAt = deckAt + DeliveryDeck.DECK_MS
      worker.postDelayed(this, startedAt + nextDeckAt - PUBLISH_LATENCY_MS - SystemClock.elapsedRealtime())
    }
  }

  private val tick = object : Runnable {
    override fun run() {
      val timeline = timeline
      val details = details
      if (!running || destroyed || timeline == null || details == null) return
      val elapsed = SystemClock.elapsedRealtime() - startedAt
      if (elapsed >= timeline.durationMs + ARRIVAL_HOLD_MS) {
        finish()
        return
      }
      if (power.isInteractive && !mapBusy) {
        val index = timeline.planIndexAt(elapsed + MAP_LEAD_MS)
        if (index > requestedPlan) renderMap(index)
      }
      val text = deliveryText(details, timeline, elapsed)
      DeliveryController.update(
        "headline" to text.title,
        "detail" to text.subtitle,
        "progress" to timeline.progress(elapsed).coerceIn(0.0, 1.0),
      )
      main.postDelayed(this, TICK_MS)
    }
  }

  private fun finish() {
    running = false
    worker.post {
      val deck = deck
      val collapsed = deck?.collapsedDelivered()
      val expanded = deck?.delivered()
      main.post { if (!destroyed) showDelivered(collapsed, expanded) }
    }
  }

  /**
   * ActivityManager keeps its own copy of the last notification under a foreground service's id, and
   * can post it again just after detaching, over anything newer. So update the card while attached,
   * making that copy the final one, and repost once detached so the card can be dismissed.
   */
  private fun showDelivered(collapsed: RemoteViews?, expanded: RemoteViews?) {
    val details = details
    delivered = true
    if (collapsed == null || expanded == null || details == null) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      manager.cancel(ID)
      finishDelivery()
      return
    }
    val notification = {
      card(collapsed, expanded, "Delivered", "Your ${details.store} order is here")
        .setOngoing(false).setAutoCancel(true).setDeleteIntent(null).build()
    }
    manager.notify(ID, notification())
    stopForeground(STOP_FOREGROUND_DETACH)
    main.postDelayed({
      manager.notify(ID, notification())
      finishDelivery()
    }, DETACH_SETTLE_MS)
  }

  private fun finishDelivery() {
    DeliveryController.update(
      "state" to "delivered", "headline" to "Delivered", "detail" to details?.store, "progress" to 1.0,
    )
    stopSelf()
  }

  private fun fail(error: Throwable) {
    Log.e(TAG, "Delivery failed", error)
    DeliveryController.update("state" to "error", "error" to (error.message ?: error.javaClass.simpleName))
    stopForeground(STOP_FOREGROUND_REMOVE)
    manager.cancel(ID)
    stopSelf()
  }

  override fun onDestroy() {
    destroyed = true
    running = false
    main.removeCallbacksAndMessages(null)
    worker.removeCallbacksAndMessages(null)
    snapshotter?.cancel()
    snapshotter?.destroy()
    snapshotter = null
    workerThread.quitSafely()
    if (!delivered) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      manager.cancel(ID)
      if (DeliveryController.status()["state"] !in listOf("error", "stopped")) {
        DeliveryController.update("state" to "stopped")
      }
    }
    super.onDestroy()
  }

  private fun card(collapsed: RemoteViews, expanded: RemoteViews, title: String, text: String): Notification.Builder {
    val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
      PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.delivery_glyph_scooter)
      .setColor(accent)
      .setStyle(Notification.DecoratedCustomViewStyle())
      .setCustomContentView(collapsed)
      .setCustomBigContentView(expanded)
      .setContentTitle(title)
      .setContentText(text)
      .setSubText(details?.store)
      .setOnlyAlertOnce(true)
      .setWhen(sentAt)
      .setShowWhen(false)
      .setOngoing(true)
      .setContentIntent(open)
      .setDeleteIntent(
        PendingIntent.getService(
          this, 0, Intent(this, DeliveryService::class.java).setAction(STOP),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
      )
      .setCategory(Notification.CATEGORY_STATUS)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
  }

  companion object {
    const val ID = 16040
    const val START = "noti.delivery.start"
    const val STOP = "noti.delivery.stop"
    private const val CHANNEL = "noti-delivery"
    private const val TAG = "DeliveryMap"
    private const val TICK_MS = 250L
    /** The custom content's width is the display minus the card margins and the app icon column. */
    private const val MAP_INSET_DP = 100
    /** Roughly how long notify() takes to reach the screen. A wrong guess only shifts the card slightly. */
    private const val PUBLISH_LATENCY_MS = 250L
    /** Ask for each map early enough to cover the render and the deck that will first use it. */
    private const val MAP_LEAD_MS = DeliveryDeck.DECK_MS + PUBLISH_LATENCY_MS + 1500L
    private const val FIRST_MAP_TIMEOUT_MS = 30_000L
    private const val ARRIVAL_HOLD_MS = 2500L
    private const val DETACH_SETTLE_MS = 500L
    /** Business labels and traffic would compete with the route. */
    private val HIDDEN_LAYERS = listOf("traffic", "incident", "poi-label", "transit-label", "airport-label", "natural-point-label")
  }
}
