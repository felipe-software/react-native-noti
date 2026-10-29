package expo.modules.notificationmotion

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

class DoomCacheTestActivity : Activity() {
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    DoomLabTrace.initialize(this)
    val label = TextView(this).apply { textSize = 18f; setPadding(24, 88, 24, 24) }
    setContentView(label)
    val passed = mutableListOf<String>()
    fun capture(color: Int) = DoomFrames.add(this, IntArray(320 * 200) { color })
    fun bytes(uri: android.net.Uri) = checkNotNull(DoomFrames.bmp(checkNotNull(uri.lastPathSegment)))
    fun color(uri: android.net.Uri, expected: Int) {
      val data = bytes(uri)
      val bitmap = checkNotNull(android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size))
      check(bitmap.width == 320 && bitmap.height == 200)
      listOf(15 to 15, 80 to 80, 200 to 120, 319 to 199).forEach { (x, y) -> check(bitmap.getPixel(x, y) == expected) }
      bitmap.recycle()
    }
    try {
      DoomFrames.clear()
      val red = capture(android.graphics.Color.RED)
      val first = DoomFrames.defer(this, 2)
      capture(android.graphics.Color.BLUE); capture(android.graphics.Color.GREEN)
      // Read the last URI first, just as parallel ImageView decoding is allowed to do.
      color(first[1], android.graphics.Color.GREEN); color(first[0], android.graphics.Color.BLUE)
      passed += "Late read selects newer frames; every index shares one snapshot"
      val frozen = first.map { bytes(it).clone() }
      capture(android.graphics.Color.WHITE)
      check(first.indices.all { bytes(first[it]).contentEquals(frozen[it]) })
      passed += "Repeated reads remain byte-identical after new captures"
      val second = DoomFrames.defer(this, 2)
      capture(android.graphics.Color.YELLOW)
      color(second[0], android.graphics.Color.WHITE); color(second[1], android.graphics.Color.YELLOW)
      passed += "Next deck advances beyond the previous sealed deck"
      val held = DoomFrames.defer(this, 2)
      color(held[0], android.graphics.Color.YELLOW); color(held[1], android.graphics.Color.YELLOW)
      passed += "No new captures holds the latest image without rewinding"
      color(red, android.graphics.Color.RED)
      passed += "Captured URIs remain frozen for pause"
      val expired = DoomFrames.defer(this, 1).first()
      repeat(8) { DoomFrames.defer(this, 1) }
      check(DoomFrames.bmp(checkNotNull(expired.lastPathSegment)) == null)
      passed += "Deferred retention is bounded"
      DoomFrames.clear()
      check(DoomFrames.bmp(checkNotNull(first[0].lastPathSegment)) == null)
      val empty = DoomFrames.defer(this, 1).first()
      check(DoomFrames.bmp(checkNotNull(empty.lastPathSegment)) == null)
      passed += "Clear invalidates URIs and the previous snapshot watermark"
      val result = JSONObject().put("passed", JSONArray(passed)).put("complete", true)
      File(filesDir, "doom-cache-test.json").writeText(result.toString(2))
      label.text = "Doom frame cache · ${passed.size} checks passed\n\n" + passed.joinToString("\n\n")
    } catch (error: Throwable) {
      label.text = "Doom frame cache FAILED: $error"
      File(filesDir, "doom-cache-test.json").writeText(JSONObject().put("complete", false).put("error", error.toString()).toString())
    } finally { DoomFrames.clear(); DoomLabTrace.flush() }
  }
}

class DoomLabActivity : Activity() {
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    DoomLabTrace.initialize(this)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 88, 24, 24) }
    root.addView(TextView(this).apply { text = "Doom comparison lab\nActual production engine and service; benchmark markers only."; textSize = 20f })
    root.addView(CalibrationClock(this), LinearLayout.LayoutParams(-1, 60))
    listOf("baseline", "candidate", "gif", "live", "live-fallback", "pause", "mute", "stop").forEach { action ->
      root.addView(Button(this).apply { text = action; setOnClickListener { execute(action) } })
    }
    setContentView(root)
    route(intent)
  }
  override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); route(intent) }
  private fun route(intent: Intent) { intent.data?.host?.let(::execute) }
  private fun execute(action: String) {
    if (action == "baseline" || action == "candidate" || action == "gif" || action.startsWith("live")) {
      DoomLabTrace.mode = action
      DoomGifTransport.enabled = action.startsWith("gif")
      DoomGifTransport.frameBudget = 32768
      DoomLabTrace.forceLiveFailure = action == "live-fallback"
      getSharedPreferences("lab", 0).edit().putString("mode", action).apply()
      if (action == "baseline") { BaselineDoomController.start(this) }
      else { DoomController.start(this, if (action.startsWith("live")) "live" else "flipper") }
    } else DoomLabTrace.action(this, action)
  }
}

private class CalibrationClock(context: Context) : View(context) {
  private val paint = Paint()
  private var located = false
  init { contentDescription = "Benchmark video clock"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
  override fun onWindowFocusChanged(focus: Boolean) { super.onWindowFocusChanged(focus); if (focus) invalidate() }
  override fun onDraw(canvas: Canvas) {
    val now = SystemClock.uptimeMillis() and 0xffffffffL
    var check = 0L
    for (shift in 0..30 step 5) check = check xor (now shr shift)
    val bits = intArrayOf(1, 0, 1, 1, 0) + IntArray(32) { ((now shr it) and 1).toInt() } + IntArray(5) { ((check shr it) and 1).toInt() }
    bits.forEachIndexed { index, bit -> paint.color = if (bit == 1) 0xffeeeeee.toInt() else 0xff111111.toInt(); canvas.drawRect(index * 6f, 0f, (index + 1) * 6f, 12f, paint) }
    if (!located) {
      val screen = IntArray(2); getLocationOnScreen(screen)
      DoomLabTrace.event("clock-rect", "x" to screen[0], "y" to screen[1], "cellWidth" to 6, "cellHeight" to 12)
      located = true
    }
    if (hasWindowFocus()) postInvalidateDelayed(33)
  }
}

class DoomLabReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    DoomLabTrace.initialize(context)
    val action = intent.getStringExtra("action") ?: return
    if (action == "flush") { DoomLabTrace.flush(); return }
    if (action == "mark") {
      DoomLabTrace.event("phase", "name" to intent.getStringExtra("name"), "edge" to intent.getStringExtra("edge"), "offered" to intent.getIntExtra("offered", 0))
      val status = if (DoomLabTrace.mode == "baseline") BaselineDoomController.status() else DoomController.status()
      DoomLabTrace.event("status", "state" to status["state"], "renderer" to status["renderer"], "error" to status["error"])
      DoomLabTrace.flush(); return
    }
    if (action == "script") {
      val count = intent.getIntExtra("count", 30)
      val gap = intent.getLongExtra("gap", 100)
      val control = intent.getStringExtra("control") ?: "right"
      require(count in 1..200 && gap in 50..5000)
      DoomLabTrace.event("script", "count" to count, "gapMs" to gap, "control" to control)
      val handler = Handler(Looper.getMainLooper())
      repeat(count) { index -> handler.postDelayed({
        DoomLabTrace.event("request", "index" to index, "action" to control)
        DoomLabTrace.action(context, control)
      }, 500 + index * gap) }
      handler.postDelayed({ DoomLabTrace.event("script-end"); DoomLabTrace.flush() }, 1000 + count * gap)
    } else {
      DoomLabTrace.event("request", "action" to action)
      DoomLabTrace.action(context, action)
    }
  }
}

internal object DoomLabTrace {
  var mode = "candidate"
  val frameState = LongArray(5)
  var inputId = 0L
  var forceLiveFailure = false
  private var writer: BufferedWriter? = null
  private var post = 0
  fun initialize(context: Context) {
    if (writer != null) return
    mode = context.getSharedPreferences("lab", 0).getString("mode", "candidate")!!
    writer = File(context.filesDir, "doom-events.jsonl").bufferedWriter()
    event("start", "sdk" to android.os.Build.VERSION.SDK_INT, "fingerprint" to android.os.Build.FINGERPRINT)
  }
  @Synchronized fun event(kind: String, vararg fields: Pair<String, Any?>) {
    val row = JSONObject().put("kind", kind).put("mode", mode).put("uptimeMs", SystemClock.uptimeMillis())
    fields.forEach { (key, value) -> row.put(key, value ?: JSONObject.NULL) }
    writer?.apply { write(row.toString()); newLine() }
  }
  @Synchronized fun flush() { writer?.flush() }
  fun action(context: Context, action: String) {
    if (action == "stop") {
      if (mode == "baseline") BaselineDoomController.stop(context) else DoomController.stop(context)
      event("stop"); flush()
    } else {
      if (mode == "baseline") BaselineDoomController.action(action) else DoomController.action(action)
    }
  }
  fun frame(strategy: String, pixels: IntArray, id: Long) {
    // A lab-only binary strip identifies the actual image in captured video. Native framebuffer
    // state is untouched: JNI already returned this copy. Both variants receive identical marks.
    val value = id.toInt() and 0xfffff
    val check = (value xor (value shr 5) xor (value shr 10) xor (value shr 15)) and 31
    val bits = intArrayOf(1, 0, 1, 1, 0) + IntArray(20) { (value shr it) and 1 } + IntArray(5) { (check shr it) and 1 }
    bits.forEachIndexed { cell, bit ->
      val color = if (bit == 1) 0xffeeeeee.toInt() else 0xff111111.toInt()
      for (y in 0 until 6) for (x in cell * 6 until (cell + 1) * 6) pixels[y * 320 + x] = color
    }
    event("frame", "strategy" to strategy, "frame" to id, "nativeFrame" to frameState[0],
      "appliedInput" to frameState[1], "discardedInput" to frameState[2], "gameTic" to frameState[3], "renderedAtNs" to frameState[4])
  }
  fun nativeKey(key: Int, pressed: Boolean): Long = DoomEngine.keyTracked(key, pressed).also { inputId = it }
  fun imageId(pixels: IntArray): Int = (0 until 20).sumOf { bit -> if ((pixels[3 * 320 + (bit + 5) * 6 + 3] and 255) > 120) 1 shl bit else 0 }
  fun batch(strategy: String, frames: List<android.net.Uri>, flush: Boolean, lastInput: Long) {
    event("batch", "strategy" to strategy, "frames" to JSONArray(frames.map { it.lastPathSegment }),
      "flush" to flush, "lastInputAt" to lastInput)
  }
  fun notify(strategy: String, notification: android.app.Notification, id: Int) {
    val now = SystemClock.uptimeMillis()
    notification.extras.putInt("doomlabSequence", ++post)
    notification.extras.putLong("doomlabSentUptime", now)
    event("notify", "strategy" to strategy, "sequence" to post, "sentUptimeMs" to now, "id" to id)
    flush()
  }
}
