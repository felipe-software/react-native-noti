package expo.modules.notificationmotion

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor

/** Lab-only live encoded animation. Every slot is valid at all times; no URI rebinding. */
internal object DoomGifTransport {
  init { System.loadLibrary("doom_gif_probe") }
  var enabled = false
  var frameBudget = 32768
  private var memory: FileDescriptor? = null
  private var frameBytes = 0
  private var published = false
  private var name = ""
  private const val FRAMES = 4
  private var headerBytes = 0
  private data class EncodedFrame(val bytes: ByteArray, val id: Int)
  @Volatile private var latest: EncodedFrame? = null
  private var headerData = byteArrayOf()
  private val reads = HandlerThread("doom-gif-reads").apply { start() }
  private external fun encode(pixels: IntArray, budget: Int): ByteArray
  private external fun direct(fd: Int): Int
  external fun sampleStep(): Int
  external fun encodedSize(): Int

  @Synchronized fun push(context: Context, pixels: IntArray) {
    if (!enabled) return
    val began = SystemClock.elapsedRealtimeNanos()
    // A second lab-only marker detects rows read from different producer frames.
    repeat(6) { row -> System.arraycopy(pixels, row * 320, pixels, (194 + row) * 320, 180) }
    val frame = encode(pixels, frameBudget)
    if (memory == null) {
      val header = ByteArrayOutputStream()
      fun bytes(vararg values: Int) = values.forEach { header.write(it) }
      header.write("GIF89a".toByteArray()); bytes(0x80,2,0x90,1,0xf7,0,0)
      repeat(256) { color -> bytes(((color shr 5) and 7) * 255 / 7, ((color shr 2) and 7) * 255 / 7, (color and 3) * 255 / 3) }
      bytes(0x21,0xff,11); header.write("NETSCAPE2.0".toByteArray()); bytes(3,1,0,0,0)
      val data = header.toByteArray(); headerData = data; headerBytes = data.size; frameBytes = frame.size
      memory = Os.memfd_create("doom-live-animation", OsConstants.MFD_CLOEXEC)
      name = SystemClock.elapsedRealtime().toString(36) + ".gif"
      Os.ftruncate(memory, (data.size + frame.size * FRAMES + 1).toLong())
      write(data, 0)
      write(byteArrayOf(0x3b), (data.size + frame.size * FRAMES).toLong())
    }
    check(frame.size == frameBytes)
    latest = EncodedFrame(frame, frameId(pixels))
    DoomLabTrace.event("gif-write", "frame" to frameId(pixels), "sampleStep" to sampleStep(), "encodedBytes" to encodedSize(), "durationNs" to SystemClock.elapsedRealtimeNanos() - began)
  }
  private fun frameId(pixels: IntArray): Int = (0 until 20).sumOf { bit -> if ((pixels[3 * 320 + (bit + 5) * 6 + 3] and 255) > 120) 1 shl bit else 0 }
  private fun write(data: ByteArray, offset: Long) {
    var at = 0
    while (at < data.size) at += Os.pwrite(checkNotNull(memory), data, at, data.size - at, offset + at)
  }
  @Synchronized fun open(context: Context, uri: Uri): ParcelFileDescriptor {
    require(uri.lastPathSegment == name)
    DoomLabTrace.event("gif-open")
    val header = headerData
    val bytesPerFrame = frameBytes
    val total = header.size.toLong() + bytesPerFrame.toLong() * FRAMES + 1
    val frozen = arrayOfNulls<EncodedFrame>(FRAMES)
    var previous = -1L
    var readCount = 0
    val descriptor = context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
      ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
        override fun onGetSize() = total
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
          readCount++
          if (readCount <= 8 || readCount % 100 == 0) DoomLabTrace.event("gif-read", "count" to readCount, "offset" to offset, "size" to size)
          if (offset < previous) frozen.fill(null)
          previous = offset
          val count = minOf(size.toLong(), total - offset).toInt().coerceAtLeast(0)
          var copied = 0
          while (copied < count) {
            val position = offset + copied
            if (position < header.size) {
              val length = minOf(count - copied, header.size - position.toInt())
              System.arraycopy(header, position.toInt(), data, copied, length); copied += length
            } else if (position == total - 1) {
              data[copied++] = 0x3b
            } else {
              val index = ((position - header.size) / bytesPerFrame).toInt()
              val inFrame = ((position - header.size) % bytesPerFrame).toInt()
              val frame = frozen[index] ?: checkNotNull(latest).also {
                frozen[index] = it
                DoomLabTrace.event("gif-latch", "index" to index, "frame" to it.id)
              }
              val length = minOf(count - copied, bytesPerFrame - inFrame)
              System.arraycopy(frame.bytes, inFrame, data, copied, length); copied += length
            }
          }
          return count
        }
        override fun onRelease() { DoomLabTrace.event("gif-release") }
      }, Handler(reads.looper))
    val flags = direct(descriptor.fd)
    check(flags >= 0) { "Direct animation reads failed: $flags" }
    DoomLabTrace.event("gif-direct", "flags" to flags)
    return descriptor
  }
  @Synchronized fun show(context: Context) {
    if (published || memory == null) return
    val uri = Uri.parse("content://${context.packageName}.livegif/$name")
    val style = Notification.MessagingStyle(Person.Builder().setName("Player").build())
      .setConversationTitle("Doom live animation · lab")
      .addMessage(Notification.MessagingStyle.Message("Live Doom gameplay", System.currentTimeMillis(), Person.Builder().setName("Doom").build()).setData("image/gif", uri))
    val notification = Notification.Builder(context, DoomService.CHANNEL).setSmallIcon(R.drawable.nm_small_icon)
      .setStyle(style).setOnlyAlertOnce(true).setShowWhen(false).setOngoing(true).setTimeoutAfter(120_000).build()
    DoomLabTrace.notify("gif", notification, DoomService.ID)
    DoomNotificationRate.record()
    context.getSystemService(NotificationManager::class.java).notify(DoomService.ID, notification)
    published = true
  }
  @Synchronized fun close() {
    memory?.let(Os::close); memory = null; frameBytes = 0; published = false
  }
}

class DoomGifProbeProvider : ContentProvider() {
  override fun onCreate() = true
  override fun getType(uri: Uri) = "image/gif"
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    require(mode == "r")
    return DoomGifTransport.open(requireNotNull(context), uri)
  }
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
