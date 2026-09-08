package expo.modules.notificationmotion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import kotlin.math.roundToInt

internal class MotionRenderer(
  private val context: Context,
  private val images: MotionImages,
  private val target: MotionTarget,
) {
  private val bitmapKeys = mutableSetOf<String>()
  private var bitmapBytes = 0L
  private var nodes = 0

  fun render(node: JSONObject, previous: JSONObject? = null): RemoteViews = build(node, previous, 0, true)

  private fun resource(name: String): Int {
    val result = context.resources.getIdentifier(name, "layout", context.packageName)
    require(result != 0) { "Unknown animation/layout $name. Add build-time animations to the Expo plugin and rebuild." }
    return result
  }

  private fun anchor(style: JSONObject): String {
    val explicit = when (style.optString("anchor")) {
      "topLeft" -> "tl"
      "topCenter" -> "tc"
      "topRight" -> "tr"
      "centerLeft" -> "cl"
      "center" -> "cc"
      "centerRight" -> "cr"
      "bottomLeft" -> "bl"
      "bottomCenter" -> "bc"
      "bottomRight" -> "br"
      else -> null
    }
    if (explicit != null) return explicit
    return (if (style.has("bottom") && !style.has("top")) "b" else "t") +
      (if (style.has("right") && !style.has("left")) "r" else "l")
  }

  private fun build(
    node: JSONObject,
    old: JSONObject?,
    depth: Int,
    interactive: Boolean,
    animate: Boolean = true,
  ): RemoteViews {
    require(++nodes <= 512 && depth <= 12) { "Notification hierarchy is too complex." }
    val type = node.getString("type")
    val style = node.optJSONObject("style") ?: JSONObject()
    val changed = old == null || node.toString() != old.toString()
    val scrollAnimation = if (type == "scroll" && old != null && node.optInt("index") != old.optInt("index")) {
      "scroll_${if (node.optInt("index") > old.optInt("index")) "up" else "down"}_${node.getInt("windowSize")}"
    } else null
    val current = body(node, old, depth, interactive, style)
    if (
      !animate ||
      !changed ||
      type in setOf("flipper", "loop", "presence", "crossfade") ||
      (type == "scroll" && node.optBoolean("autoPlay"))
    ) return current
    val enter = scrollAnimation ?: node.optString("enter", node.optString("animation"))
    val exit = scrollAnimation ?: old?.optString("exit", old.optString("animation", enter)).orEmpty()
    if (enter.isEmpty() && (old == null || exit.isEmpty())) return current
    return transition(current, old, style, enter, exit, depth)
  }

  private fun transition(
    current: RemoteViews,
    old: JSONObject?,
    style: JSONObject,
    enter: String,
    exit: String,
    depth: Int,
  ): RemoteViews {
    val stage = RemoteViews(context.packageName, resource("nm_view_overlay_${anchor(style)}"))
    applyStyle(stage, style, geometryOnly = true)
    fillContainer(stage, style)
    stage.removeAllViews(R.id.nm_content)
    stage.removeAllViews(R.id.nm_overlay)
    clearGeometry(current)
    stage.addView(R.id.nm_content, if (enter.isEmpty()) current else animatedSingle(current, enter, false))
    if (old != null) {
      val outgoing = build(old, null, depth + 1, false, false)
      clearGeometry(outgoing)
      stage.addView(R.id.nm_content, if (exit.isEmpty()) outgoing else animatedSingle(outgoing, exit, true))
    }
    return stage
  }

  private fun animatedSingle(
    content: RemoteViews,
    animation: String,
    outgoing: Boolean,
    fillParent: Boolean = false,
  ): RemoteViews {
    require(animation.matches(Regex("[a-z][a-zA-Z0-9_]*"))) { "Invalid animation name." }
    val wrapper = RemoteViews(context.packageName, resource("nm_motion_${animation.lowercase()}_tl"))
    listOf(R.id.nm_in_a, R.id.nm_in_b, R.id.nm_out_a, R.id.nm_out_b).forEach { wrapper.removeAllViews(it) }
    val visible = if (outgoing) R.id.nm_out else R.id.nm_in
    val hidden = if (outgoing) R.id.nm_in else R.id.nm_out
    val slot = if (outgoing) R.id.nm_out_b else R.id.nm_in_b
    wrapper.setViewVisibility(hidden, View.GONE)
    wrapper.setViewVisibility(visible, View.VISIBLE)
    if (fillParent) {
      listOf(R.id.nm_node, R.id.nm_in, R.id.nm_out, R.id.nm_in_a, R.id.nm_in_b, R.id.nm_out_a, R.id.nm_out_b).forEach { id ->
        wrapper.setViewLayoutWidth(id, -1f, TypedValue.COMPLEX_UNIT_PX)
        wrapper.setViewLayoutHeight(id, -1f, TypedValue.COMPLEX_UNIT_PX)
      }
    }
    wrapper.addView(slot, content)
    trigger(wrapper, visible)
    return wrapper
  }

  private fun trigger(views: RemoteViews, id: Int) {
    views.setDisplayedChild(id, 0)
    views.setDisplayedChild(id, 1)
  }

  private fun body(
    node: JSONObject,
    old: JSONObject?,
    depth: Int,
    interactive: Boolean,
    style: JSONObject,
  ): RemoteViews {
    val type = node.getString("type")
    if (type == "loop") return runtimeLoop(node, depth, interactive, style)
    if (type == "flipper") return viewFlipper(node, old, depth, interactive, style)
    if (type == "presence" || type == "crossfade") return presence(node, old, depth, interactive, style)
    if (type == "scroll" && node.optBoolean("autoPlay")) return autoScroll(node, depth, interactive, style)
    if (type == "gif") return renderGif(node, style)
    val template = when (type) {
      "view" -> "view_${node.optString("direction", "column")}"
      "grid" -> "grid"
      "scroll" -> "view_column"
      "text", "button" -> "text"
      "image" -> "image_${node.optString("resizeMode", "cover")}"
      "progress" -> "progress"
      "chronometer" -> "chronometer"
      "spacer" -> "view_overlay"
      else -> error("Unsupported notification component: $type")
    }
    val views = RemoteViews(context.packageName, resource("nm_${template}_${anchor(style)}"))
    applyStyle(views, style, defaultWidth = if (type == "text" || type == "button") "auto" else "100%")
    if (node.has("accessibilityLabel")) {
      views.setContentDescription(R.id.nm_node, node.getString("accessibilityLabel"))
    }
    if (interactive && node.has("onPress")) views.setOnClickPendingIntent(R.id.nm_node, actionIntent(node))
    when (type) {
      "text", "button" -> renderText(views, node, style)
      "image" -> renderImage(views, node)
      "progress" -> renderProgress(views, node)
      "chronometer" -> renderChronometer(views, node, style)
      "spacer" -> {
        views.removeAllViews(R.id.nm_content)
        views.removeAllViews(R.id.nm_overlay)
      }
      else -> renderContainer(views, node, old, depth, interactive, style)
    }
    return views
  }

  private fun renderText(views: RemoteViews, node: JSONObject, style: JSONObject) {
    if (style.has("height") && style.opt("height") != "auto") {
      views.setViewLayoutHeight(R.id.nm_content, -1f, TypedValue.COMPLEX_UNIT_PX)
    }
    views.setTextViewText(R.id.nm_content, node.optString("text"))
    views.setInt(R.id.nm_content, "setMaxLines", node.optInt("numberOfLines", 1))
    views.setTextColor(R.id.nm_content, Color.parseColor(style.optString("color", "#ffffff")))
    views.setTextViewTextSize(
      R.id.nm_content,
      TypedValue.COMPLEX_UNIT_SP,
      style.optDouble("fontSize", 14.0).toFloat(),
    )
    val horizontal = when (style.optString("textAlign")) {
      "center" -> Gravity.CENTER_HORIZONTAL
      "right" -> Gravity.RIGHT
      else -> Gravity.LEFT
    }
    val vertical = when (style.optString("verticalAlign")) {
      "center" -> Gravity.CENTER_VERTICAL
      "bottom" -> Gravity.BOTTOM
      else -> Gravity.TOP
    }
    views.setInt(R.id.nm_content, "setGravity", horizontal or vertical)
    if (style.optString("fontWeight") == "bold") {
      val text = android.text.SpannableString(node.optString("text"))
      text.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, text.length, 0)
      views.setTextViewText(R.id.nm_content, text)
    }
  }

  private fun renderImage(views: RemoteViews, node: JSONObject) {
    val source = node.getString("source")
    val bitmap = images.decode(source)
    if (bitmapKeys.add(source)) bitmapBytes += bitmap.allocationByteCount
    require(bitmapBytes <= 3_500_000) {
      "Notification images exceed the 3.5 MB decoded budget, including animation snapshots."
    }
    views.setImageViewBitmap(R.id.nm_content, bitmap)
  }

  private fun renderGif(node: JSONObject, style: JSONObject): RemoteViews {
    val source = node.getString("source")
    val width = style.opt("width").let { if (it is Number) it.toDouble() else null }
    val height = style.opt("height").let { if (it is Number) it.toDouble() else null }
    val gif = runCatching {
      images.decodeGif(
        source,
        node.getInt("maxFrames"),
        node.getInt("maxBytes"),
        node.getString("optimization"),
        width,
        height,
      )
    }.getOrElse { error ->
      val fallback = node.optString("fallbackSource")
      if (fallback.isEmpty()) throw error
      val fallbackNode = JSONObject(node.toString()).put("source", fallback)
      return RemoteViews(
        context.packageName,
        resource("nm_image_${node.optString("resizeMode", "cover")}_${anchor(style)}"),
      ).also { views ->
        applyStyle(views, style)
        renderImage(views, fallbackNode)
      }
    }
    val views = RemoteViews(context.packageName, resource("nm_runtime_${anchor(style)}"))
    applyStyle(views, style)
    fillContainer(views, style)
    views.removeAllViews(R.id.nm_content)
    val frameStyle = JSONObject().put("width", "100%").put("height", "100%")
    gif.frames.forEachIndexed { index, bitmap ->
      if (bitmapKeys.add("$source#${System.identityHashCode(bitmap)}")) bitmapBytes += bitmap.allocationByteCount
      require(bitmapBytes <= 3_500_000) {
        "Notification images exceed the 3.5 MB decoded budget, including GIF frames."
      }
      val frame = RemoteViews(
        context.packageName,
        resource("nm_image_${node.optString("resizeMode", "cover")}_tl"),
      )
      applyStyle(frame, frameStyle)
      frame.setImageViewBitmap(R.id.nm_content, bitmap)
      views.addView(R.id.nm_content, frame)
    }
    if (node.has("accessibilityLabel")) {
      views.setContentDescription(R.id.nm_node, node.getString("accessibilityLabel"))
    }
    val interval = if (node.has("interval")) {
      node.getInt("interval")
    } else {
      (gif.duration / gif.frames.size).coerceIn(20, 60_000)
    }
    views.setInt(R.id.nm_content, "setFlipInterval", interval)
    views.setDisplayedChild(R.id.nm_content, 0)
    return views
  }

  private fun renderProgress(views: RemoteViews, node: JSONObject) {
    views.setProgressBar(
      R.id.nm_content,
      node.getInt("max"),
      node.getInt("value"),
      node.optBoolean("indeterminate"),
    )
    if (node.has("color")) {
      views.setColorStateList(
        R.id.nm_content,
        "setProgressTintList",
        ColorStateList.valueOf(Color.parseColor(node.getString("color"))),
      )
    }
    if (node.has("trackColor")) {
      views.setColorStateList(
        R.id.nm_content,
        "setProgressBackgroundTintList",
        ColorStateList.valueOf(Color.parseColor(node.getString("trackColor"))),
      )
    }
  }

  private fun renderChronometer(views: RemoteViews, node: JSONObject, style: JSONObject) {
    val epochBase = node.getLong("base")
    val elapsedBase = SystemClock.elapsedRealtime() + epochBase - System.currentTimeMillis()
    views.setChronometer(
      R.id.nm_content,
      elapsedBase,
      node.optString("format").ifEmpty { null },
      node.optBoolean("started", true),
    )
    views.setChronometerCountDown(R.id.nm_content, node.optBoolean("countDown"))
    views.setTextColor(R.id.nm_content, Color.parseColor(style.optString("color", "#ffffff")))
    views.setTextViewTextSize(
      R.id.nm_content,
      TypedValue.COMPLEX_UNIT_SP,
      style.optDouble("fontSize", 14.0).toFloat(),
    )
    val horizontal = when (style.optString("textAlign")) {
      "center" -> Gravity.CENTER_HORIZONTAL
      "right" -> Gravity.RIGHT
      else -> Gravity.LEFT
    }
    val vertical = when (style.optString("verticalAlign")) {
      "center" -> Gravity.CENTER_VERTICAL
      "bottom" -> Gravity.BOTTOM
      else -> Gravity.TOP
    }
    views.setInt(R.id.nm_content, "setGravity", horizontal or vertical)
  }

  private fun renderContainer(
    views: RemoteViews,
    node: JSONObject,
    old: JSONObject?,
    depth: Int,
    interactive: Boolean,
    style: JSONObject,
  ) {
    views.removeAllViews(R.id.nm_content)
    views.removeAllViews(R.id.nm_overlay)
    fillContainer(views, style)
    val type = node.getString("type")
    val direction = node.optString("direction", "column")
    if (type == "grid") {
      views.setInt(R.id.nm_content, "setColumnCount", node.getInt("columns"))
      if (node.has("rows")) views.setInt(R.id.nm_content, "setRowCount", node.getInt("rows"))
    } else if (direction != "overlay") {
      views.setInt(R.id.nm_content, "setGravity", containerGravity(direction, style))
    }
    val currentChildren = children(node)
    val oldChildren = old?.let(::children).orEmpty().associateBy { it.getString("key") }
    val start = if (type == "scroll") node.optInt("index") else 0
    val size = if (type == "scroll") node.getInt("windowSize") else currentChildren.size
    require(start >= 0 && start <= maxOf(0, currentChildren.size - size)) {
      "Scroll index outside the content window."
    }
    currentChildren.drop(start).take(size).forEachIndexed { index, child ->
      val preparedChild = if (type == "scroll") scrollRow(child, node.getDouble("itemHeight")) else child
      val alignedChild = inheritCrossAxisAnchor(preparedChild, direction, style)
      val childStyle = alignedChild.optJSONObject("style") ?: JSONObject()
      val absolute = childStyle.optString("position") == "absolute"
      val previous = oldChildren[child.getString("key")]?.let {
        val prepared = if (type == "scroll") scrollRow(it, node.getDouble("itemHeight")) else it
        inheritCrossAxisAnchor(prepared, direction, style)
      }
      val rendered = build(alignedChild, previous, depth + 1, interactive, interactive)
      if (type == "scroll") {
        rendered.setViewLayoutHeight(
          R.id.nm_node,
          node.getDouble("itemHeight").toFloat(),
          TypedValue.COMPLEX_UNIT_DIP,
        )
      } else {
        applyGap(rendered, type, direction, style, index, node.optInt("columns", 1), absolute)
      }
      views.addView(if (absolute && direction != "overlay") R.id.nm_overlay else R.id.nm_content, rendered)
    }
  }

  private fun inheritCrossAxisAnchor(
    child: JSONObject,
    direction: String,
    containerStyle: JSONObject,
  ): JSONObject {
    if (direction == "overlay") return child
    val childStyle = child.optJSONObject("style") ?: JSONObject()
    if (childStyle.has("anchor") || childStyle.optString("position") == "absolute") return child
    val alignment = containerStyle.optString("alignItems")
    if (alignment != "center" && alignment != "end") return child
    val inherited = JSONObject(child.toString())
    val inheritedStyle = inherited.optJSONObject("style") ?: JSONObject().also {
      inherited.put("style", it)
    }
    inheritedStyle.put(
      "anchor",
      if (direction == "row") {
        if (alignment == "center") "centerLeft" else "bottomLeft"
      } else {
        if (alignment == "center") "topCenter" else "topRight"
      },
    )
    return inherited
  }

  private fun scrollRow(child: JSONObject, itemHeight: Double): JSONObject {
    val row = JSONObject(child.toString())
    val rowStyle = row.optJSONObject("style") ?: JSONObject().also { row.put("style", it) }
    if (!rowStyle.has("width")) rowStyle.put("width", "100%")
    rowStyle.put("height", itemHeight)
    return row
  }

  private fun applyGap(
    child: RemoteViews,
    type: String,
    direction: String,
    style: JSONObject,
    index: Int,
    columns: Int,
    absolute: Boolean,
  ) {
    if (absolute || index == 0) return
    val gap = style.optDouble("gap", 0.0).toFloat()
    if (type == "grid") {
      if (index % columns != 0) {
        child.setViewLayoutMargin(R.id.nm_node, RemoteViews.MARGIN_LEFT, gap, TypedValue.COMPLEX_UNIT_DIP)
      }
      if (index >= columns) {
        child.setViewLayoutMargin(R.id.nm_node, RemoteViews.MARGIN_TOP, gap, TypedValue.COMPLEX_UNIT_DIP)
      }
    } else {
      child.setViewLayoutMargin(
        R.id.nm_node,
        if (direction == "row") RemoteViews.MARGIN_LEFT else RemoteViews.MARGIN_TOP,
        gap,
        TypedValue.COMPLEX_UNIT_DIP,
      )
    }
  }

  private fun viewFlipper(
    node: JSONObject,
    old: JSONObject?,
    depth: Int,
    interactive: Boolean,
    style: JSONObject,
  ): RemoteViews {
    if (!node.optBoolean("autoPlay", true)) {
      val selected = children(node)[node.getInt("index")]
      val views = RemoteViews(context.packageName, resource("nm_view_overlay_${anchor(style)}"))
      applyStyle(views, style)
      fillContainer(views, style)
      views.removeAllViews(R.id.nm_content)
      views.removeAllViews(R.id.nm_overlay)
      val rendered = build(
        selected,
        old?.let(::children)?.getOrNull(node.getInt("index")),
        depth + 1,
        interactive,
      )
      clearGeometry(rendered)
      views.addView(R.id.nm_content, rendered)
      return views
    }
    val animation = node.optString("animation", "fade")
    require(animation.matches(Regex("[a-z][a-zA-Z0-9_]*"))) { "Invalid animation name." }
    val views = RemoteViews(
      context.packageName,
      resource("nm_flipper_${animation.lowercase()}_${anchor(style)}"),
    )
    applyStyle(views, style)
    fillContainer(views, style)
    views.removeAllViews(R.id.nm_content)
    val oldChildren = old?.let(::children).orEmpty().associateBy { it.getString("key") }
    children(node).forEach { child ->
      val rendered = build(child, oldChildren[child.getString("key")], depth + 1, interactive, false)
      clearGeometry(rendered)
      views.addView(R.id.nm_content, rendered)
    }
    views.setInt(R.id.nm_content, "setFlipInterval", node.getInt("interval"))
    views.setDisplayedChild(R.id.nm_content, node.getInt("index"))
    return views
  }

  private fun runtimeLoop(
    node: JSONObject,
    depth: Int,
    interactive: Boolean,
    style: JSONObject,
  ): RemoteViews {
    val views = RemoteViews(context.packageName, resource("nm_runtime_${anchor(style)}"))
    applyStyle(views, style)
    fillContainer(views, style)
    views.removeAllViews(R.id.nm_content)
    val child = children(node).single()
    val frames = node.getJSONArray("runtimeFrames")
    require(frames.length() in 2..94) { "Runtime loops support 2–94 serialized frames." }
    for (index in 0 until frames.length()) {
      val rendered = build(child, null, depth + 1, interactive, false)
      clearGeometry(rendered)
      applyRuntimeFrame(rendered, frames.getJSONObject(index))
      views.addView(R.id.nm_content, rendered)
    }
    views.setInt(R.id.nm_content, "setFlipInterval", node.getInt("interval"))
    views.setDisplayedChild(R.id.nm_content, 0)
    return views
  }

  private fun autoScroll(
    node: JSONObject,
    depth: Int,
    interactive: Boolean,
    style: JSONObject,
  ): RemoteViews {
    val windowSize = node.getInt("windowSize")
    val rows = children(node)
    require(rows.size in (windowSize + 1)..12) {
      "Auto-playing scroll requires windowSize + 1 to 12 rows."
    }
    val views = RemoteViews(
      context.packageName,
      resource("nm_flipper_scroll_up_${windowSize}_${anchor(style)}"),
    )
    applyStyle(views, style)
    fillContainer(views, style)
    views.removeAllViews(R.id.nm_content)
    for (start in rows.indices) {
      val page = JSONObject()
        .put("type", "view")
        .put("key", "${node.getString("key")}-window-$start")
        .put("direction", "column")
        .put("style", JSONObject().put("width", "100%").put("height", "100%"))
        .put(
          "children",
          org.json.JSONArray(
            (0 until windowSize).map { offset ->
              scrollRow(rows[(start + offset) % rows.size], node.getDouble("itemHeight"))
            },
          ),
        )
      val rendered = build(page, null, depth + 1, interactive, false)
      clearGeometry(rendered)
      views.addView(R.id.nm_content, rendered)
    }
    views.setInt(R.id.nm_content, "setFlipInterval", node.getInt("interval"))
    views.setDisplayedChild(R.id.nm_content, node.getInt("index"))
    return views
  }

  private fun presence(
    node: JSONObject,
    old: JSONObject?,
    depth: Int,
    interactive: Boolean,
    style: JSONObject,
  ): RemoteViews {
    val defaultAnimation = if (node.getString("type") == "crossfade") "crossfade" else "fade"
    val fillParent = node.getString("type") == "crossfade"
    val views = RemoteViews(context.packageName, resource("nm_view_overlay_${anchor(style)}"))
    applyStyle(views, style)
    fillContainer(views, style)
    views.removeAllViews(R.id.nm_content)
    views.removeAllViews(R.id.nm_overlay)
    val oldChildren = old?.let(::children).orEmpty()
    val oldByKey = oldChildren.associateBy { it.getString("key") }
    val current = children(node)
    val currentKeys = current.map { it.getString("key") }.toSet()
    current.forEach { child ->
      val previous = oldByKey[child.getString("key")]
      val rendered = if (previous == null && old != null) {
        val content = build(child, null, depth + 1, interactive, false)
        val enter = child.optString("enter", node.optString("enter", defaultAnimation))
        if (enter.isEmpty()) content else animatedSingle(content, enter, false, fillParent)
      } else {
        build(child, previous, depth + 1, interactive)
      }
      views.addView(R.id.nm_content, rendered)
    }
    if (old != null) {
      oldChildren.filter { it.getString("key") !in currentKeys }.forEach { removed ->
        val content = build(removed, null, depth + 1, false, false)
        val exit = removed.optString("exit", old.optString("exit", defaultAnimation))
        if (exit.isNotEmpty()) {
          views.addView(R.id.nm_content, animatedSingle(content, exit, true, fillParent))
        }
      }
    }
    return views
  }

  private fun containerGravity(direction: String, style: JSONObject): Int {
    fun horizontal(value: String): Int = when (value) {
      "center" -> Gravity.CENTER_HORIZONTAL
      "end" -> Gravity.RIGHT
      else -> Gravity.LEFT
    }
    fun vertical(value: String): Int = when (value) {
      "center" -> Gravity.CENTER_VERTICAL
      "end" -> Gravity.BOTTOM
      else -> Gravity.TOP
    }
    return if (direction == "row") {
      horizontal(style.optString("justifyContent")) or vertical(style.optString("alignItems"))
    } else {
      horizontal(style.optString("alignItems")) or vertical(style.optString("justifyContent"))
    }
  }

  private fun fillContainer(views: RemoteViews, style: JSONObject) {
    if (style.has("width") && style.opt("width") != "auto") {
      views.setViewLayoutWidth(R.id.nm_content, -1f, TypedValue.COMPLEX_UNIT_PX)
    }
    if (style.has("height") && style.opt("height") != "auto") {
      views.setViewLayoutHeight(R.id.nm_content, -1f, TypedValue.COMPLEX_UNIT_PX)
    }
  }

  private fun children(node: JSONObject): List<JSONObject> {
    val items = node.optJSONArray("children") ?: return emptyList()
    return (0 until items.length()).map(items::getJSONObject)
  }

  private fun applyStyle(
    views: RemoteViews,
    style: JSONObject,
    geometryOnly: Boolean = false,
    defaultWidth: String = "100%",
  ) {
    fun dimension(name: String, fallback: String): Float = when (val value = style.opt(name) ?: fallback) {
      "auto" -> -2f
      "100%" -> -1f
      is Number -> value.toFloat()
      else -> error("Unsupported dimension $name")
    }
    val width = dimension("width", defaultWidth)
    val height = dimension("height", "auto")
    views.setViewLayoutWidth(
      R.id.nm_node,
      width,
      if (width < 0) TypedValue.COMPLEX_UNIT_PX else TypedValue.COMPLEX_UNIT_DIP,
    )
    views.setViewLayoutHeight(
      R.id.nm_node,
      height,
      if (height < 0) TypedValue.COMPLEX_UNIT_PX else TypedValue.COMPLEX_UNIT_DIP,
    )
    listOf(
      "left" to RemoteViews.MARGIN_LEFT,
      "right" to RemoteViews.MARGIN_RIGHT,
      "top" to RemoteViews.MARGIN_TOP,
      "bottom" to RemoteViews.MARGIN_BOTTOM,
    ).forEach { (name, margin) ->
      views.setViewLayoutMargin(
        R.id.nm_node,
        margin,
        style.optDouble(name, 0.0).toFloat(),
        TypedValue.COMPLEX_UNIT_DIP,
      )
    }
    if (geometryOnly) return
    val density = context.resources.displayMetrics.density
    val horizontal = style.optDouble("paddingHorizontal", style.optDouble("padding", 0.0))
    val vertical = style.optDouble("paddingVertical", style.optDouble("padding", 0.0))
    val top = style.optDouble("paddingTop", vertical)
    val right = style.optDouble("paddingRight", horizontal)
    val bottom = style.optDouble("paddingBottom", vertical)
    val left = style.optDouble("paddingLeft", horizontal)
    views.setViewPadding(
      R.id.nm_node,
      (left * density).roundToInt(),
      (top * density).roundToInt(),
      (right * density).roundToInt(),
      (bottom * density).roundToInt(),
    )
    if (style.has("backgroundColor")) {
      views.setInt(R.id.nm_node, "setBackgroundColor", Color.parseColor(style.getString("backgroundColor")))
    }
    if (style.has("borderRadius")) {
      views.setViewOutlinePreferredRadius(
        R.id.nm_node,
        style.getDouble("borderRadius").toFloat(),
        TypedValue.COMPLEX_UNIT_DIP,
      )
    }
    views.setFloat(R.id.nm_node, "setAlpha", style.optDouble("opacity", 1.0).toFloat())
    views.setFloat(R.id.nm_node, "setScaleX", style.optDouble("scaleX", style.optDouble("scale", 1.0)).toFloat())
    views.setFloat(R.id.nm_node, "setScaleY", style.optDouble("scaleY", style.optDouble("scale", 1.0)).toFloat())
    views.setFloat(R.id.nm_node, "setRotation", style.optDouble("rotate", 0.0).toFloat())
    views.setFloat(R.id.nm_node, "setTranslationX", style.optDouble("translateX", 0.0).toFloat() * density)
    views.setFloat(R.id.nm_node, "setTranslationY", style.optDouble("translateY", 0.0).toFloat() * density)
    views.setFloat(R.id.nm_node, "setElevation", style.optDouble("elevation", 0.0).toFloat() * density)
  }

  private fun applyRuntimeFrame(views: RemoteViews, frame: JSONObject) {
    val density = context.resources.displayMetrics.density
    val scale = frame.optDouble("scale", 1.0)
    views.setFloat(R.id.nm_node, "setAlpha", frame.optDouble("opacity", 1.0).toFloat())
    views.setFloat(R.id.nm_node, "setScaleX", frame.optDouble("scaleX", scale).toFloat())
    views.setFloat(R.id.nm_node, "setScaleY", frame.optDouble("scaleY", scale).toFloat())
    views.setFloat(R.id.nm_node, "setRotation", frame.optDouble("rotate", 0.0).toFloat())
    views.setFloat(R.id.nm_node, "setTranslationX", frame.optDouble("translateX", 0.0).toFloat() * density)
    views.setFloat(R.id.nm_node, "setTranslationY", frame.optDouble("translateY", 0.0).toFloat() * density)
    if (frame.has("backgroundColor")) {
      views.setInt(R.id.nm_node, "setBackgroundColor", Color.parseColor(frame.getString("backgroundColor")))
    }
  }

  private fun clearGeometry(views: RemoteViews) {
    listOf(
      RemoteViews.MARGIN_LEFT,
      RemoteViews.MARGIN_RIGHT,
      RemoteViews.MARGIN_TOP,
      RemoteViews.MARGIN_BOTTOM,
    ).forEach {
      views.setViewLayoutMargin(R.id.nm_node, it, 0f, TypedValue.COMPLEX_UNIT_DIP)
    }
  }

  private fun actionIntent(node: JSONObject): PendingIntent {
    val nodeId = node.optString("id", node.getString("key"))
    val action = node.getString("onPress")
    val uri = Uri.Builder().scheme("notification-motion").authority(context.packageName)
    val intent = Intent(context, MotionActionReceiver::class.java)
      .putExtra("nodeId", nodeId)
      .putExtra("motionAction", action)
    when (target) {
      is MotionTarget.Notification -> {
        uri.appendPath("notification").appendPath(target.id.toString()).appendPath(target.tag ?: "")
        intent.putExtra("notificationId", target.id).putExtra("notificationTag", target.tag)
      }
      is MotionTarget.Widget -> {
        uri.appendPath("widget").appendPath(target.id.toString())
        intent.putExtra("widgetId", target.id)
      }
    }
    intent.setData(uri.appendPath(nodeId).appendPath(action).build())
    return PendingIntent.getBroadcast(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}

internal sealed interface MotionTarget {
  data class Notification(val id: Int, val tag: String?) : MotionTarget
  data class Widget(val id: Int) : MotionTarget
}
