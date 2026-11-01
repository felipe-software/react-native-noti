package dev.reactnativenoti.deliverymap

import android.content.Context
import android.graphics.Bitmap
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal class DeliveryDetails(val courier: String, val store: String, val etaSeconds: Double)

internal class DeliveryText(val title: String, val subtitle: String)

internal fun deliveryText(details: DeliveryDetails, timeline: DeliveryTimeline, timeMs: Long): DeliveryText {
  val progress = timeline.progress(timeMs).coerceIn(0.0, 1.0)
  val seconds = details.etaSeconds * (1.0 - progress)
  val remaining = timeline.remainingMeters(timeMs).coerceAtLeast(0.0)
  val away = when {
    remaining < 15.0 -> "at your door"
    remaining < 1000.0 -> "${(remaining / 10.0).roundToInt() * 10} m away"
    else -> String.format(Locale.US, "%.1f km away", remaining / 1000.0)
  }
  return DeliveryText(
    title = if (seconds < 45.0) "Arriving now" else "Arriving in ${ceil(seconds / 60.0).toInt()} min",
    subtitle = "${details.courier} is $away",
  )
}

/**
 * Builds the notification's custom views. A deck is one set of ViewFlipper frames, and each frame
 * only moves layers: every frame shares the same map bitmap, so the map is scaled by the GPU in
 * SystemUI and nothing is re-encoded. A deck runs a little past its period, so a late replacement
 * keeps moving instead of freezing or looping.
 */
internal class DeliveryDeck(
  private val context: Context,
  private val timeline: DeliveryTimeline,
  private val details: DeliveryDetails,
  private val viewport: MapViewport,
) {
  private class MapLayer(val bitmap: Bitmap, val plan: MapPlan)

  private val packageName = context.packageName
  private val houseX = timeline.xs.last()
  private val houseY = timeline.ys.last()
  private val pending = ArrayDeque<MapLayer>()
  private var current: MapLayer? = null
  private var previous: MapLayer? = null
  private var fadeStart = 0L
  private var facingLeft = false

  /** Called on the same thread as [expanded]. Shared memory lets every deck reuse the pixels. */
  fun addMap(bitmap: Bitmap, plan: MapPlan) {
    pending.addLast(MapLayer(bitmap.asShared(), plan))
  }

  /** Frames for [startMs] onward, using the newest map rendered for that time. */
  fun expanded(startMs: Long): RemoteViews {
    promoteMap(startMs)
    val views = base(R.layout.delivery_expanded, deliveryText(details, timeline, startMs), timeline.progress(startMs))
    views.removeAllViews(R.id.delivery_frames)
    for (index in 0 until FRAMES_PER_DECK) {
      views.addView(R.id.delivery_frames, frame(startMs + index * FRAME_STEP_MS, delivered = false))
    }
    views.setDisplayedChild(R.id.delivery_frames, 0)
    return views
  }

  fun collapsed(timeMs: Long): RemoteViews =
    base(R.layout.delivery_collapsed, deliveryText(details, timeline, timeMs), timeline.progress(timeMs))

  /** Shown until the first map has rendered, with the same layout so nothing jumps. */
  fun placeholder(layout: Int): RemoteViews =
    base(layout, DeliveryText("Finding ${details.courier}", details.store), 0.0)

  /** The finished card: one still frame, with the bitmap held by the notification itself. */
  fun delivered(): RemoteViews {
    val timeMs = timeline.durationMs
    promoteMap(timeMs + FADE_MS)
    previous = null
    return base(R.layout.delivery_expanded, deliveredText(), 1.0).apply {
      removeAllViews(R.id.delivery_frames)
      addView(R.id.delivery_frames, frame(timeMs, delivered = true))
    }
  }

  fun collapsedDelivered(): RemoteViews = base(R.layout.delivery_collapsed, deliveredText(), 1.0)

  private fun deliveredText() = DeliveryText("Delivered", "Your ${details.store} order is here")

  private fun base(layout: Int, text: DeliveryText, progress: Double): RemoteViews {
    val views = RemoteViews(packageName, layout)
    views.setTextViewText(R.id.delivery_title, text.title)
    // The collapsed layout has no subtitle, and RemoteViews skips actions on missing views.
    views.setTextViewText(R.id.delivery_subtitle, text.subtitle)
    // The courier already has the order, so the first two steps are done from the start.
    val onTheWay = ((progress / ON_THE_WAY_SHARE) * SEGMENT_MAX).toInt().coerceIn(0, SEGMENT_MAX)
    val arriving = (((progress - ON_THE_WAY_SHARE) / (1.0 - ON_THE_WAY_SHARE)) * SEGMENT_MAX).toInt().coerceIn(0, SEGMENT_MAX)
    views.setProgressBar(R.id.delivery_segment_1, SEGMENT_MAX, SEGMENT_MAX, false)
    views.setProgressBar(R.id.delivery_segment_2, SEGMENT_MAX, SEGMENT_MAX, false)
    views.setProgressBar(R.id.delivery_segment_3, SEGMENT_MAX, onTheWay, false)
    views.setProgressBar(R.id.delivery_segment_4, SEGMENT_MAX, arriving, false)
    return views
  }

  private fun promoteMap(timeMs: Long) {
    var next: MapLayer? = null
    while (true) {
      val candidate = pending.peekFirst() ?: break
      // Renders arrive ahead of time. Wait for this one's moment, unless there is nothing else to show.
      if (candidate.plan.activeFrom > timeMs && (current != null || next != null)) break
      next = pending.pollFirst()
    }
    if (next != null) {
      // After a jump the two views have nothing in common to crossfade.
      previous = current?.takeUnless { isFarBehind(it.plan, timeline.camera(timeMs)) }
      current = next
      fadeStart = timeMs
    } else if (timeMs - fadeStart >= FADE_MS) {
      previous = null
    }
  }

  private fun isFarBehind(plan: MapPlan, camera: Camera) = camera.zoom - plan.zoom > MAX_UPSCALE_ZOOM

  /**
   * Nothing renders while the screen is off, so the map can be far behind when it comes back. Until
   * the fresh render arrives, stay on the old map's own sharp view instead of stretching it.
   */
  private fun visibleCamera(timeMs: Long): Camera {
    val camera = timeline.camera(timeMs)
    val plan = current?.plan ?: return camera
    return if (isFarBehind(plan, camera)) Camera(plan.x, plan.y, plan.zoom, viewport) else camera
  }

  private fun frame(timeMs: Long, delivered: Boolean): RemoteViews {
    val views = RemoteViews(packageName, R.layout.delivery_frame)
    val camera = visibleCamera(timeMs)
    current?.let { placeMap(views, R.id.delivery_map, it, camera) }
    val under = previous
    val fade = (timeMs - fadeStart).toFloat() / FADE_MS
    if (under != null && fade < 1f) {
      placeMap(views, R.id.delivery_map_under, under, camera)
      views.setViewVisibility(R.id.delivery_map_under, View.VISIBLE)
      views.setFloat(R.id.delivery_map, "setAlpha", fade.coerceIn(0f, 1f))
    }

    val nearness = timeline.nearness(timeMs).toFloat()
    val phase = (timeMs % PULSE_MS).toFloat() / PULSE_MS
    val houseOffsetX = camera.offsetX(houseX)
    val houseOffsetY = camera.offsetY(houseY)
    // The destination grows and beats harder as the courier closes in.
    val beat = if (delivered) 0f else 0.05f * nearness * sin(phase * 2f * PI_F)
    place(views, R.id.delivery_home, houseOffsetX, houseOffsetY, 0.9f + 0.2f * nearness + beat)
    if (delivered) {
      views.setViewVisibility(R.id.delivery_ring, View.GONE)
      views.setImageViewResource(R.id.delivery_home, R.drawable.delivery_marker_done)
    } else {
      place(views, R.id.delivery_ring, houseOffsetX, houseOffsetY, 1f + phase * (0.9f + 0.9f * nearness))
      views.setFloat(R.id.delivery_ring, "setAlpha", (1f - phase).pow(2) * (0.55f + 0.35f * nearness))
    }

    val meters = timeline.meters(timeMs)
    val bob = sin(timeMs / 1000f * 2f * PI_F * BOB_HZ) * BOB_DP * viewport.density.toFloat() * timeline.motion(timeMs).toFloat()
    val across = cos(timeline.heading(meters))
    // Keep the rider's direction through north-south legs instead of flipping on noise.
    if (across < -0.2) facingLeft = true else if (across > 0.2) facingLeft = false
    views.setFloat(R.id.delivery_courier, "setTranslationX", camera.offsetX(timeline.worldX(meters)))
    views.setFloat(R.id.delivery_courier, "setTranslationY", camera.offsetY(timeline.worldY(meters)) + bob)
    // The scooter glyph faces right.
    views.setFloat(R.id.delivery_courier, "setScaleX", if (facingLeft) -1f else 1f)
    return views
  }

  private fun place(views: RemoteViews, id: Int, x: Float, y: Float, scale: Float) {
    views.setFloat(id, "setTranslationX", x)
    views.setFloat(id, "setTranslationY", y)
    views.setFloat(id, "setScaleX", scale)
    views.setFloat(id, "setScaleY", scale)
  }

  private fun placeMap(views: RemoteViews, id: Int, layer: MapLayer, camera: Camera) {
    views.setImageViewBitmap(id, layer.bitmap)
    views.setViewLayoutWidth(id, layer.bitmap.width.toFloat(), TypedValue.COMPLEX_UNIT_PX)
    views.setViewLayoutHeight(id, layer.bitmap.height.toFloat(), TypedValue.COMPLEX_UNIT_PX)
    val scale = (2.0.pow(camera.zoom - layer.plan.zoom) / viewport.renderScale).toFloat()
    place(views, id, camera.offsetX(layer.plan.x), camera.offsetY(layer.plan.y), scale)
  }

  companion object {
    /** Content time per frame. android:flipInterval is 66, and each flip also has to schedule. */
    const val FRAME_STEP_MS = 68L
    const val DECK_MS = 3000L
    private const val OVERRUN_MS = 700L
    val FRAMES_PER_DECK = ((DECK_MS + OVERRUN_MS) / FRAME_STEP_MS).toInt()
    private const val FADE_MS = 400L
    private const val MAX_UPSCALE_ZOOM = 0.35
    private const val SEGMENT_MAX = 1000
    private const val ON_THE_WAY_SHARE = 0.75
    private const val PULSE_MS = 1600L
    private const val BOB_HZ = 1.6f
    private const val BOB_DP = 1f
    private const val PI_F = Math.PI.toFloat()
  }
}
