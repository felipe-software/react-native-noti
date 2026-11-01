package dev.reactnativenoti.deliverymap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The visible map in device pixels. A notification's width is only known inside SystemUI, so it is
 * estimated from the screen. Everything is positioned from the center, so the estimate only affects
 * framing, never alignment.
 */
internal class MapViewport(val widthPx: Double, val heightPx: Double, val density: Double) {
  /** Under 1 dp per map point, so more streets fit the small card while labels stay readable. */
  val pixelsPerPoint = density * POINT_SCALE

  /** Below 1 when needed to keep two map bitmaps inside the notification budget. */
  val renderScale = min(1.0, sqrt(MAX_RENDER_PIXELS / (widthPx * heightPx * OVERSCAN * OVERSCAN)))
  val renderWidth = widthPx * OVERSCAN * renderScale
  val renderHeight = heightPx * OVERSCAN * renderScale
  val renderRatio = pixelsPerPoint * renderScale

  fun scaleAt(zoom: Double) = 512.0 * 2.0.pow(zoom) * pixelsPerPoint

  companion object {
    /** Map renders are larger than the view, so the camera can drift and zoom within one. */
    const val OVERSCAN = 1.35
    private const val POINT_SCALE = 0.8
    /** ARGB_8888 at 4 bytes per pixel: two of these stay under the 5 MB custom view limit. */
    private const val MAX_RENDER_PIXELS = 600_000.0
  }
}

/** A Web Mercator view of the map region. World coordinates run from 0 to 1. */
internal class Camera(val x: Double, val y: Double, val zoom: Double, viewport: MapViewport) {
  val scale = viewport.scaleAt(zoom)
  fun offsetX(worldX: Double) = ((worldX - x) * scale).toFloat()
  fun offsetY(worldY: Double) = ((worldY - y) * scale).toFloat()
}

/** One off-screen map render: it stays sharp and in frame for every camera until the next plan. */
internal class MapPlan(val index: Int, val activeFrom: Long, val x: Double, val y: Double, val zoom: Double) {
  val longitude get() = x * 360.0 - 180.0
  val latitude get() = atan(sinh(PI * (1.0 - 2.0 * y))) * 180.0 / PI
}

/**
 * The whole delivery as pure functions of time. Knowing the future camera lets the map be rendered
 * ahead of time, every few seconds, while the frames between renders only move the result.
 */
internal class DeliveryTimeline(
  coordinates: DoubleArray,
  val durationMs: Long,
  private val viewport: MapViewport,
  private val planStepMs: Long,
) {
  val pointCount = coordinates.size / 2
  val xs = DoubleArray(pointCount) { (coordinates[it * 2] + 180.0) / 360.0 }
  val ys = DoubleArray(pointCount) {
    val latitude = coordinates[it * 2 + 1] * PI / 180.0
    (1.0 - ln(tan(PI / 4.0 + latitude / 2.0)) / PI) / 2.0
  }
  /** Meters from the store to each route point. */
  val along = DoubleArray(pointCount)
  val totalMeters: Double
  /** The courier stops short of the door so both markers stay readable on arrival. */
  val arrivalMeters: Double
  private val traveled: DoubleArray
  private val cameraX: DoubleArray
  private val cameraY: DoubleArray
  private val cameraZoom: DoubleArray
  val plans: List<MapPlan>

  init {
    require(pointCount >= 2) { "A delivery route needs at least two points." }
    for (i in 1 until pointCount) {
      along[i] = along[i - 1] + meters(
        coordinates[i * 2 - 2], coordinates[i * 2 - 1], coordinates[i * 2], coordinates[i * 2 + 1],
      )
    }
    totalMeters = along.last()
    arrivalMeters = max(totalMeters - DOOR_STANDOFF_METERS, totalMeters * 0.9)
    val samples = (durationMs / STEP_MS).toInt() + 1
    traveled = integrateTravel(samples)
    val fitX = DoubleArray(samples)
    val fitY = DoubleArray(samples)
    val fitZoom = DoubleArray(samples)
    for (i in 0 until samples) fit(traveled[i], fitX, fitY, fitZoom, i)
    // The remaining route only shrinks, so never zoom back out. Lagging behind the fit is safe, as
    // it just shows more map.
    val maxZoomStep = MAX_ZOOM_PER_SECOND * STEP_MS / 1000.0
    for (i in 1 until samples) {
      fitZoom[i] = max(fitZoom[i - 1], min(fitZoom[i], fitZoom[i - 1] + maxZoomStep))
    }
    cameraX = smooth(fitX)
    cameraY = smooth(fitY)
    cameraZoom = smooth(fitZoom)
    plans = planMaps()
  }

  fun meters(timeMs: Long): Double = lookup(traveled, timeMs)
  fun progress(timeMs: Long) = meters(timeMs) / arrivalMeters
  fun remainingMeters(timeMs: Long) = arrivalMeters - meters(timeMs)
  fun camera(timeMs: Long) =
    Camera(lookup(cameraX, timeMs), lookup(cameraY, timeMs), lookup(cameraZoom, timeMs), viewport)

  /** 0 while far away, rising to 1 at the door. */
  fun nearness(timeMs: Long) = (1.0 - remainingMeters(timeMs) / NEAR_METERS).coerceIn(0.0, 1.0)

  /** How fast the courier is moving, from 0 when stopped to 1 at full speed. */
  fun motion(timeMs: Long) = speed(timeMs.toDouble())

  fun worldX(meters: Double): Double = interpolate(xs, meters)
  fun worldY(meters: Double): Double = interpolate(ys, meters)

  /** Direction of travel in world coordinates, averaged over a short distance to soften corners. */
  fun heading(meters: Double): Double {
    val back = max(meters - HEADING_SPAN_METERS, 0.0)
    val ahead = min(meters + HEADING_SPAN_METERS, totalMeters)
    return atan2(worldY(ahead) - worldY(back), worldX(ahead) - worldX(back))
  }

  fun planIndexAt(timeMs: Long): Int {
    var index = 0
    while (index + 1 < plans.size && plans[index + 1].activeFrom <= timeMs) index++
    return index
  }

  private fun interpolate(values: DoubleArray, meters: Double): Double {
    var low = 0
    var high = pointCount - 1
    while (high - low > 1) {
      val middle = (low + high) / 2
      if (along[middle] <= meters) low = middle else high = middle
    }
    val span = along[high] - along[low]
    val mix = if (span > 0) ((meters - along[low]) / span).coerceIn(0.0, 1.0) else 0.0
    return values[low] + (values[high] - values[low]) * mix
  }

  private fun lookup(values: DoubleArray, timeMs: Long): Double {
    val position = timeMs.coerceIn(0L, durationMs).toDouble() / STEP_MS
    val low = position.toInt().coerceAtMost(values.size - 1)
    val high = min(low + 1, values.size - 1)
    return values[low] + (values[high] - values[low]) * (position - low)
  }

  private fun ramp(distanceMs: Double): Double {
    val x = (distanceMs / RAMP_MS).coerceIn(0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)
  }

  private fun speed(timeMs: Double): Double {
    var result = min(ramp(timeMs), ramp(durationMs - timeMs))
    for (fraction in STOP_FRACTIONS) {
      val from = durationMs * fraction
      val until = from + STOP_MS
      if (timeMs in from..until) return 0.0
      result = min(result, ramp(if (timeMs < from) from - timeMs else timeMs - until))
    }
    return result
  }

  private fun integrateTravel(samples: Int): DoubleArray {
    val distance = DoubleArray(samples)
    for (i in 1 until samples) distance[i] = distance[i - 1] + speed(i * STEP_MS.toDouble())
    // Slow down on the way in. The camera is zooming, so this keeps speed on screen roughly constant.
    val total = distance.last()
    return DoubleArray(samples) { arrivalMeters * (1.0 - (1.0 - distance[it] / total).pow(ARRIVAL_EASING)) }
  }

  /** Frames the rest of the route, so the map tightens exactly as the courier closes in. */
  private fun fit(meters: Double, outX: DoubleArray, outY: DoubleArray, outZoom: DoubleArray, at: Int) {
    var minX = worldX(meters)
    var maxX = minX
    var minY = worldY(meters)
    var maxY = minY
    for (i in 0 until pointCount) {
      if (along[i] <= meters) continue
      minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
      minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
    }
    val paddingX = PADDING_X_DP * viewport.density
    val paddingTop = PADDING_TOP_DP * viewport.density
    val paddingBottom = PADDING_BOTTOM_DP * viewport.density
    val scale = min(
      (viewport.widthPx - 2.0 * paddingX) / max(maxX - minX, MIN_WORLD_SPAN),
      (viewport.heightPx - paddingTop - paddingBottom) / max(maxY - minY, MIN_WORLD_SPAN),
    )
    val zoom = log2(scale / viewport.scaleAt(0.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
    outX[at] = (minX + maxX) / 2.0
    // Shift the view so asymmetric padding stays centered around the route.
    outY[at] = (minY + maxY) / 2.0 - (paddingTop - paddingBottom) / 2.0 / viewport.scaleAt(zoom)
    outZoom[at] = zoom
  }

  private fun smooth(values: DoubleArray): DoubleArray {
    val radius = (SMOOTHING_MS / STEP_MS).toInt()
    val sums = DoubleArray(values.size + 2 * radius + 1)
    for (i in 1 until sums.size) {
      sums[i] = sums[i - 1] + values[(i - 1 - radius).coerceIn(0, values.size - 1)]
    }
    return DoubleArray(values.size) { (sums[it + 2 * radius + 1] - sums[it]) / (2 * radius + 1) }
  }

  /**
   * Each render is made at the zoom it reaches last, so the camera only ever scales it down. A
   * render lasts until it would stop covering the view or become too much sharper than needed.
   */
  private fun planMaps(): List<MapPlan> {
    val result = ArrayList<MapPlan>()
    var from = 0L
    while (from < durationMs) {
      val first = camera(from)
      var minX = first.x - viewport.widthPx / 2.0 / first.scale
      var maxX = first.x + viewport.widthPx / 2.0 / first.scale
      var minY = first.y - viewport.heightPx / 2.0 / first.scale
      var maxY = first.y + viewport.heightPx / 2.0 / first.scale
      var until = from
      var zoom = first.zoom
      while (until < durationMs) {
        val next = min(until + planStepMs, durationMs)
        val camera = camera(next)
        val halfWidth = viewport.widthPx / 2.0 / camera.scale
        val halfHeight = viewport.heightPx / 2.0 / camera.scale
        val grownMinX = min(minX, camera.x - halfWidth)
        val grownMaxX = max(maxX, camera.x + halfWidth)
        val grownMinY = min(minY, camera.y - halfHeight)
        val grownMaxY = max(maxY, camera.y + halfHeight)
        val coverage = MapViewport.OVERSCAN / viewport.scaleAt(camera.zoom)
        val outgrown = camera.zoom - first.zoom > MAX_ZOOM_GAIN ||
          grownMaxX - grownMinX > viewport.widthPx * coverage ||
          grownMaxY - grownMinY > viewport.heightPx * coverage
        if (outgrown && until > from) break
        minX = grownMinX; maxX = grownMaxX; minY = grownMinY; maxY = grownMaxY
        zoom = camera.zoom
        until = next
      }
      result += MapPlan(result.size, from, (minX + maxX) / 2.0, (minY + maxY) / 2.0, zoom)
      from = until
    }
    return result
  }

  companion object {
    private const val STEP_MS = 100L
    private const val MAX_ZOOM_GAIN = 0.27
    private const val DOOR_STANDOFF_METERS = 45.0
    private const val NEAR_METERS = 420.0
    private const val HEADING_SPAN_METERS = 12.0
    private const val RAMP_MS = 1800.0
    private const val STOP_MS = 2400.0
    private val STOP_FRACTIONS = doubleArrayOf(0.34, 0.68)
    private const val ARRIVAL_EASING = 1.5
    private const val SMOOTHING_MS = 3000L
    private const val PADDING_X_DP = 40.0
    private const val PADDING_TOP_DP = 36.0
    /** Keeps the markers clear of the Mapbox logo, which has to stay visible. */
    private const val PADDING_BOTTOM_DP = 44.0
    private const val MIN_WORLD_SPAN = 1e-7
    private const val MIN_ZOOM = 11.0
    private const val MAX_ZOOM = 16.9
    private const val MAX_ZOOM_PER_SECOND = 0.09

    private fun meters(lng1: Double, lat1: Double, lng2: Double, lat2: Double): Double {
      val a = lat1 * PI / 180.0
      val b = lat2 * PI / 180.0
      val deltaLat = b - a
      val deltaLng = (lng2 - lng1) * PI / 180.0
      val h = sin(deltaLat / 2).pow(2) + cos(a) * cos(b) * sin(deltaLng / 2).pow(2)
      return abs(2.0 * 6_371_000.0 * atan2(sqrt(h), sqrt(1.0 - h)))
    }
  }
}
