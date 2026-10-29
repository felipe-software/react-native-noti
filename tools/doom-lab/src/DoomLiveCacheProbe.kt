package expo.modules.notificationmotion

import android.app.Activity
import android.app.Notification
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Black-box reads through the real proxy descriptor, including a producer update mid-frame. */
class DoomLiveCacheTestActivity : Activity() {
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    DoomLabTrace.initialize(this)
    val label = TextView(this).apply { textSize = 18f; setPadding(24, 88, 24, 24) }
    setContentView(label)
    val passed = mutableListOf<String>()
    var descriptor: ParcelFileDescriptor? = null
    var session: DoomLiveSession? = null
    fun uri(notification: Notification): Uri {
      val message = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)!!.first() as Bundle
      return checkNotNull(message.getParcelable("uri", Uri::class.java))
    }
    fun read(fd: ParcelFileDescriptor, offset: Long, count: Int): ByteArray {
      val bytes = ByteArray(count); var at = 0
      while (at < count) {
        val n = Os.pread(fd.fileDescriptor, bytes, at, count - at, offset + at)
        check(n > 0); at += n
      }
      return bytes
    }
    try {
      val red = IntArray(64000) { Color.RED }
      val green = IntArray(64000) { Color.GREEN }
      val yellow = IntArray(64000) { Color.YELLOW }
      val live = DoomLiveFrames.create(this) { throw AssertionError(it) }
      session = live
      live.push(red)
      val encodedRed = checkNotNull(DoomGifEncoder.encode(red, 65536))
      val liveUri = uri(checkNotNull(live.notification(false)))
      val fd = DoomLiveFrames.open(this, liveUri); descriptor = fd
      val headerSize = (fd.statSize - encodedRed.size.toLong() * 4 - 1).toInt()
      check(headerSize > 0)
      val half = encodedRed.size / 2
      val first = read(fd, headerSize.toLong(), half)
      live.push(green)
      val second = read(fd, headerSize.toLong() + half, encodedRed.size - half)
      check((first + second).contentEquals(encodedRed))
      passed += "A producer update between partial reads cannot mix one frame"
      val encodedGreen = checkNotNull(DoomGifEncoder.encode(green, 65536))
      check(read(fd, headerSize.toLong(), encodedGreen.size).contentEquals(encodedGreen))
      passed += "A new read pass receives the newest complete frame"
      val slotOne = headerSize.toLong() + encodedGreen.size
      val before = read(fd, slotOne, half)
      live.push(yellow)
      val after = read(fd, slotOne + half, encodedGreen.size - half)
      check((before + after).contentEquals(encodedGreen))
      passed += "Each animation slot keeps one snapshot for all of its parts"
      check(Os.pread(fd.fileDescriptor, ByteArray(1), 0, 1, fd.statSize) == 0)
      passed += "Reads at end of file return EOF"
      val pausedUri = uri(checkNotNull(live.notification(true)))
      live.push(IntArray(64000) { Color.CYAN })
      DoomLiveFrames.open(this, pausedUri).use { paused ->
        val file = File(filesDir, "paused-cache-test.gif")
        file.writeBytes(read(paused, 0, paused.statSize.toInt()))
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        check(bitmap.width == DoomGifEncoder.WIDTH && bitmap.height == DoomGifEncoder.HEIGHT && bitmap.getPixel(300, 200) == Color.YELLOW)
        bitmap.recycle()
      }
      passed += "A paused image remains frozen after new captures"
      live.close()
      try { DoomLiveFrames.open(this, liveUri); error("Closed stream reopened") } catch (_: FileNotFoundException) { }
      passed += "Closing a session rejects new opens of its URI"
      val finalFrame = checkNotNull(DoomGifEncoder.encode(IntArray(64000) { Color.CYAN }, 65536))
      check(read(fd, headerSize.toLong(), finalFrame.size).contentEquals(finalFrame))
      passed += "An existing reader can finish safely after the session closes"
      File(filesDir, "doom-live-cache-test.json").writeText(JSONObject().put("complete", true).put("passed", JSONArray(passed)).toString(2))
      label.text = "Live Doom cache · ${passed.size} checks passed\n\n" + passed.joinToString("\n\n")
    } catch (error: Throwable) {
      File(filesDir, "doom-live-cache-test.json").writeText(JSONObject().put("complete", false).put("passed", JSONArray(passed)).put("error", error.toString()).toString(2))
      label.text = "Live Doom cache failed: $error"
    } finally { descriptor?.close(); session?.close(); DoomLabTrace.flush() }
  }
}
