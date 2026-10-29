package expo.modules.notificationmotion

import android.app.Notification
import android.app.Person
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Native encoder for the optional live image transport; simulation remains in DoomEngine. */
internal object DoomGifEncoder {
  const val WIDTH = 952
  const val HEIGHT = 595
  init { System.loadLibrary("noti_doom") }
  external fun encode(pixels: IntArray, budget: Int): ByteArray?
  external fun direct(fd: Int): Int
  external fun sampleStep(): Int
  external fun encodedSize(): Int
}

internal object DoomLiveFrames {
  const val AUTHORITY_SUFFIX = ".notificationmotion.doom.live"
  @Volatile private var current: DoomLiveSession? = null

  fun create(context: Context, onFailure: (Exception) -> Unit): DoomLiveSession {
    current?.close()
    return DoomLiveSession(context.applicationContext, onFailure).also { current = it }
  }

  fun open(context: Context, uri: Uri): ParcelFileDescriptor {
    require(uri.authority == context.packageName + AUTHORITY_SUFFIX) { "Unknown Doom stream authority." }
    return (current ?: throw FileNotFoundException("Doom stream ended.")).open(uri)
  }

  fun remove(session: DoomLiveSession) { if (current === session) current = null }
}

/**
 * A seekable encoded animation. Each reader freezes a complete frame until its next pass.
 * Direct reads avoid retaining the first animation forever in the filesystem page cache.
 */
internal class DoomLiveSession(private val context: Context, onFailure: (Exception) -> Unit) {
  private data class EncodedFrame(val bytes: ByteArray)
  private val key = SystemClock.elapsedRealtimeNanos().toString(36)
  private val sentAt = System.currentTimeMillis()
  private val header = makeHeader()
  private val closed = AtomicBoolean(false)
  private val readers = AtomicInteger(0)
  @Volatile private var failure: ((Exception) -> Unit)? = onFailure
  private val io = HandlerThread("Noti-Doom-image-reads").apply { start() }
  @Volatile private var latest: EncodedFrame? = null
  private var frameBytes = 0
  private var mode: Boolean? = null
  private var pauseSequence = 0L
  private var lastPublishedAt = 0L
  private val pauses = object : LinkedHashMap<String, ByteArray>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 2
  }

  fun push(pixels: IntArray) {
    check(!closed.get()) { "Doom stream ended." }
    val encoded = checkNotNull(DoomGifEncoder.encode(pixels, FRAME_BUDGET)) { "Doom image encoding failed." }
    if (frameBytes == 0) frameBytes = encoded.size
    check(encoded.size == frameBytes) { "Doom stream frame size changed." }
    latest = EncodedFrame(encoded)
  }

  fun invalidatePublication() { lastPublishedAt = 0L }

  /** Called on the service thread, only after obtaining notification rate budget. */
  fun notification(paused: Boolean): Notification? {
    val frame = latest ?: return null
    val now = SystemClock.elapsedRealtime()
    if (mode == paused && lastPublishedAt != 0L && now - lastPublishedAt < KEEPALIVE_MS) return null
    if (mode != paused) {
      if (paused) {
        val snapshot = header + frame.bytes + byteArrayOf(0x3b)
        synchronized(pauses) { pauses["paused-${++pauseSequence}.gif"] = snapshot }
      }
      mode = paused
    }
    val name = if (paused) "paused-$pauseSequence.gif" else "live.gif"
    val uri = Uri.Builder().scheme("content").authority(context.packageName + DoomLiveFrames.AUTHORITY_SUFFIX)
      .appendPath(key).appendPath(name).build()
    val style = Notification.MessagingStyle(Person.Builder().setName("Player").build())
      .setConversationTitle(if (paused) "Doom · paused" else "Doom")
      .addMessage(Notification.MessagingStyle.Message("Live Doom gameplay", sentAt,
        Person.Builder().setName("Freedoom").build()).setData("image/gif", uri))
    return Notification.Builder(context, DoomService.CHANNEL)
      .setSmallIcon(R.drawable.nm_small_icon).setStyle(style)
      .setOnlyAlertOnce(true).setWhen(sentAt).setShowWhen(false).setOngoing(true)
      .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE)
      .setDeleteIntent(PendingIntent.getService(context, DoomService.STOP.hashCode(),
        Intent(context, DoomService::class.java).setAction(DoomService.STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
      .setTimeoutAfter(TIMEOUT_MS).build()
  }

  fun didPublish() { lastPublishedAt = SystemClock.elapsedRealtime() }

  fun open(uri: Uri): ParcelFileDescriptor {
    if (closed.get() || uri.pathSegments.size != 2 || uri.pathSegments[0] != key) {
      throw FileNotFoundException("Doom stream expired.")
    }
    val name = uri.pathSegments[1]
    if (name != "live.gif") {
      val snapshot = synchronized(pauses) { pauses[name] } ?: throw FileNotFoundException("Doom snapshot expired.")
      return immutableFile(snapshot)
    }
    val initial = latest ?: throw FileNotFoundException("Doom image is not ready.")
    val bytesPerFrame = initial.bytes.size
    val total = header.size.toLong() + bytesPerFrame.toLong() * FRAME_COUNT + 1
    val frozen = arrayOfNulls<EncodedFrame>(FRAME_COUNT)
    var previous = -1L
    val released = AtomicBoolean(false)
    readers.incrementAndGet()
    fun release() {
      if (released.compareAndSet(false, true) && readers.decrementAndGet() == 0 && closed.get()) io.quitSafely()
    }
    var descriptor: ParcelFileDescriptor? = null
    try {
      descriptor = context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
        ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
          override fun onGetSize() = total
          override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (offset < 0) throw ErrnoException("Doom image read", OsConstants.EINVAL)
            if (offset >= total) return 0
            if (offset < previous) frozen.fill(null)
            previous = offset
            val count = minOf(size.toLong(), total - offset).toInt().coerceAtLeast(0)
            var copied = 0
            while (copied < count) {
              val position = offset + copied
              if (position < header.size) {
                val length = minOf(count - copied, header.size - position.toInt())
                System.arraycopy(header, position.toInt(), data, copied, length)
                copied += length
              } else if (position == total - 1) {
                data[copied++] = 0x3b
              } else {
                val index = ((position - header.size) / bytesPerFrame).toInt()
                val inFrame = ((position - header.size) % bytesPerFrame).toInt()
                val frame = frozen[index] ?: (latest ?: initial).also { frozen[index] = it }
                val length = minOf(count - copied, bytesPerFrame - inFrame)
                System.arraycopy(frame.bytes, inFrame, data, copied, length)
                copied += length
              }
            }
            return count
          }
          override fun onRelease() { release() }
        }, Handler(io.looper))
      val flags = DoomGifEncoder.direct(descriptor.fd)
      if (flags < 0) throw IOException("Direct Doom image reads are unavailable ($flags).")
      return descriptor
    } catch (error: Exception) {
      descriptor?.close()
      release()
      failure?.invoke(error)
      throw FileNotFoundException("Doom live image unavailable.").apply { initCause(error) }
    }
  }

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    failure = null
    DoomLiveFrames.remove(this)
    synchronized(pauses) { pauses.clear() }
    // Existing readers retain an immutable final frame until SystemUI releases their files.
    if (readers.get() == 0) io.quitSafely()
  }

  private fun immutableFile(data: ByteArray): ParcelFileDescriptor {
    val file = Os.memfd_create("doom-paused-image", OsConstants.MFD_CLOEXEC)
    try {
      var at = 0
      while (at < data.size) at += Os.write(file, data, at, data.size - at)
      Os.lseek(file, 0, OsConstants.SEEK_SET)
      return ParcelFileDescriptor.dup(file)
    } finally { Os.close(file) }
  }

  private fun makeHeader(): ByteArray {
    val out = ByteArrayOutputStream()
    fun bytes(vararg values: Int) = values.forEach { out.write(it) }
    out.write("GIF89a".toByteArray())
    bytes(DoomGifEncoder.WIDTH and 255, DoomGifEncoder.WIDTH shr 8,
      DoomGifEncoder.HEIGHT and 255, DoomGifEncoder.HEIGHT shr 8, 0xf7,0,0)
    repeat(256) { color -> bytes(((color shr 5) and 7) * 255 / 7, ((color shr 2) and 7) * 255 / 7, (color and 3) * 255 / 3) }
    bytes(0x21,0xff,11); out.write("NETSCAPE2.0".toByteArray()); bytes(3,1,0,0,0)
    return out.toByteArray()
  }

  companion object {
    private const val FRAME_BUDGET = 65536
    private const val FRAME_COUNT = 4
    private const val KEEPALIVE_MS = 5_000L
    private const val TIMEOUT_MS = 10_000L
  }
}
