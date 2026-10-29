package expo.modules.notificationmotion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Lab only: determine whether a host re-reads a published animation's backing file. */
class AnimatedTransportProbeActivity : Activity() {
  private val handler = Handler(Looper.getMainLooper())
  private fun event(kind: String, vararg fields: Pair<String, Any>) {
    val row = JSONObject().put("kind", kind).put("uptimeMs", SystemClock.uptimeMillis())
    fields.forEach { (key, value) -> row.put(key, value) }
    File(filesDir, "gif-probe.jsonl").appendText(row.toString() + "\n")
  }
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    val file = File(filesDir, "live-probe.gif")
    File(filesDir, "gif-probe.jsonl").writeText("")
    val first = gif(false)
    file.writeBytes(first)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 88, 24, 24) }
    val label = TextView(this).apply { text = "Animated transport probe\nRed/blue initially. Backing file changes to green/white after 10 seconds. One notification only."; textSize = 18f }
    root.addView(label)
    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
    check(drawable is AnimatedImageDrawable)
    root.addView(ImageView(this).apply { setImageDrawable(drawable); adjustViewBounds = true })
    setContentView(root)
    drawable.start()
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("doom-gif-probe", "Doom animation probe", NotificationManager.IMPORTANCE_DEFAULT).apply {
      setSound(null, null); enableVibration(false); setShowBadge(false)
    })
    val uri = Uri.parse("content://$packageName.gifprobe/live-probe.gif")
    val style = Notification.MessagingStyle(Person.Builder().setName("Probe viewer").build())
      .setConversationTitle("Doom animation transport probe")
      .addMessage(Notification.MessagingStyle.Message("One publication; file changes after 10 seconds", System.currentTimeMillis(), Person.Builder().setName("Doom lab probe").build()).setData("image/gif", uri))
    manager.notify(16037, Notification.Builder(this, "doom-gif-probe")
      .setSmallIcon(R.drawable.nm_small_icon).setStyle(style).setOnlyAlertOnce(true).setShowWhen(false).setTimeoutAfter(30_000).build())
    event("published", "bytes" to first.size, "frames" to 32, "delayCentiseconds" to 3)
    handler.postDelayed({
      val next = gif(true)
      check(next.size == first.size)
      event("rewrite-begin")
      RandomAccessFile(file, "rw").use { it.seek(0); it.write(next); it.fd.sync() }
      event("rewrite-complete", "bytes" to next.size)
      label.text = "Backing file is now green/white. No second notification was published."
    }, 10_000)
  }
  override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

  private fun gif(changed: Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    fun bytes(vararg values: Int) = values.forEach { out.write(it) }
    fun word(n: Int) { bytes(n and 255, n shr 8) }
    out.write("GIF89a".toByteArray()); word(320); word(200); bytes(0x91, 0, 0)
    // Four global colors, fixed for both versions of the file.
    bytes(255,255,255, 240,32,32, 32,64,240, 32,210,64)
    bytes(0x21,0xff,11); out.write("NETSCAPE2.0".toByteArray()); bytes(3,1,0,0,0)
    repeat(32) { frame ->
      bytes(0x21,0xf9,4,4,3,0,0,0, 0x2c); word(0); word(0); word(320); word(200); bytes(0,2)
      val codes = ByteArrayOutputStream(); var accumulator = 0; var bits = 0
      fun code(value: Int) {
        accumulator = accumulator or (value shl bits); bits += 3
        while (bits >= 8) { codes.write(accumulator and 255); accumulator = accumulator ushr 8; bits -= 8 }
      }
      for (y in 0 until 200) for (x in 0 until 320) {
        val stripe = ((x / 20 + frame) and 1) == 0
        val color = if (changed) { if (stripe) 3 else 0 } else { if (stripe) 1 else 2 }
        code(4); code(color) // Clear before every literal: fixed-size valid 3-bit codes.
      }
      code(5); if (bits > 0) codes.write(accumulator)
      val encoded = codes.toByteArray(); var at = 0
      while (at < encoded.size) { val count = minOf(255, encoded.size - at); out.write(count); out.write(encoded, at, count); at += count }
      bytes(0)
    }
    bytes(0x3b); return out.toByteArray()
  }
}

class AnimatedTransportProbeProvider : ContentProvider() {
  override fun onCreate() = true
  override fun getType(uri: Uri) = "image/gif"
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    require(mode == "r" && uri.path == "/live-probe.gif")
    return ParcelFileDescriptor.open(File(requireNotNull(context).filesDir, "live-probe.gif"), ParcelFileDescriptor.MODE_READ_ONLY)
  }
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
