package expo.modules.notificationmotion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import pl.droidsonroids.gif.GifDrawable

internal data class MotionGif(
  val frames: List<Bitmap>,
  val duration: Int,
)

internal class MotionImages(private val context: Context) {
  private val bitmapCache = object : LruCache<String, Bitmap>(4_000_000) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
  }
  private val bytesCache = object : LruCache<String, ByteArray>(4_000_000) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size
  }
  private val gifCache = object : LruCache<String, MotionGif>(8_000_000) {
    override fun sizeOf(key: String, value: MotionGif): Int = allocationBytes(value.frames)
  }

  fun decode(source: String): Bitmap {
    bitmapCache.get(source)?.let { return it }
    val bytes = read(source)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    validateDimensions(bounds.outWidth, bounds.outHeight)
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
      "Could not decode notification image."
    }
    bitmapCache.put(source, bitmap)
    return bitmap
  }

  fun decodeGif(
    source: String,
    maxFrames: Int,
    maxBytes: Int,
    optimization: String,
    widthDp: Double?,
    heightDp: Double?,
  ): MotionGif {
    val key = "$source#$maxFrames#$maxBytes#$optimization#$widthDp#$heightDp"
    gifCache.get(key)?.let { return it }
    val drawable = GifDrawable(read(source))
    return try {
      validateDimensions(drawable.intrinsicWidth, drawable.intrinsicHeight)
      val durations = (0 until drawable.numberOfFrames).map { drawable.getFrameDuration(it).coerceAtLeast(20) }
      val indices = selectFrameIndices(durations, maxFrames)
      require(indices.isNotEmpty()) { "GIF has no decodable frames." }
      val decoded = indices.distinct().associateWith { index ->
        optimizeFrame(drawable.seekToFrameAndGet(index), widthDp, heightDp)
      }
      val frames = optimizeGif(indices.map(decoded::getValue), maxBytes, optimization)
      MotionGif(frames, durations.sum()).also { gifCache.put(key, it) }
    } finally {
      drawable.recycle()
    }
  }

  private fun selectFrameIndices(durations: List<Int>, maxFrames: Int): List<Int> {
    val duration = durations.sum()
    val slots = min(
      maxFrames,
      maxOf(durations.size, ceil(duration.toDouble() / durations.min()).toInt()),
    )
    if (slots == durations.size) return durations.indices.toList()
    var frame = 0
    var boundary = durations.first()
    return (0 until slots).map { slot ->
      val time = slot * duration / slots
      while (frame < durations.lastIndex && time >= boundary) {
        frame += 1
        boundary += durations[frame]
      }
      frame
    }
  }

  private fun optimizeFrame(bitmap: Bitmap, widthDp: Double?, heightDp: Double?): Bitmap {
    val density = context.resources.displayMetrics.density
    val widthLimit = widthDp?.let { (it * density).roundToInt().coerceAtLeast(1) }
    val heightLimit = heightDp?.let { (it * density).roundToInt().coerceAtLeast(1) }
    val scale = min(
      1.0,
      min(
        widthLimit?.let { it.toDouble() / bitmap.width } ?: 1.0,
        heightLimit?.let { it.toDouble() / bitmap.height } ?: 1.0,
      ),
    )
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    val scaled = if (width == bitmap.width && height == bitmap.height) bitmap else {
      Bitmap.createScaledBitmap(bitmap, width, height, true).also { bitmap.recycle() }
    }
    if (scaled.hasAlpha()) return scaled
    return scaled.copy(Bitmap.Config.RGB_565, false).also { scaled.recycle() }
  }

  private fun optimizeGif(input: List<Bitmap>, maxBytes: Int, strategy: String): List<Bitmap> {
    val bytes = allocationBytes(input)
    if (bytes <= maxBytes) return input
    require(strategy != "none") {
      "GIF frames use $bytes bytes, above maxBytes=$maxBytes. Choose an optimization strategy."
    }
    val ratio = maxBytes.toDouble() / bytes
    val frameRatio = when (strategy) {
      "quality" -> ratio
      "automatic" -> ratio.pow(0.35)
      else -> 1.0
    }
    val frameCount = floor(input.size * frameRatio).toInt().coerceIn(2, input.size)
    val sampled = if (frameCount == input.size) input else sampleFrames(input, frameCount)
    val sampledBytes = allocationBytes(sampled)
    if (sampledBytes <= maxBytes) return sampled
    require(strategy != "quality") {
      "GIF cannot fit maxBytes=$maxBytes without reducing image dimensions. Use automatic or smoothness optimization."
    }
    val scale = (sqrt(maxBytes.toDouble() / sampledBytes) * 0.98).coerceAtMost(1.0)
    val resized = IdentityHashMap<Bitmap, Bitmap>()
    val output = sampled.map { bitmap ->
      resized.getOrPut(bitmap) {
        Bitmap.createScaledBitmap(
          bitmap,
          (bitmap.width * scale).roundToInt().coerceAtLeast(1),
          (bitmap.height * scale).roundToInt().coerceAtLeast(1),
          true,
        )
      }
    }
    require(allocationBytes(output) <= maxBytes) {
      "GIF frames could not be reduced below maxBytes=$maxBytes."
    }
    return output
  }

  private fun sampleFrames(frames: List<Bitmap>, count: Int): List<Bitmap> =
    (0 until count).map { index ->
      frames[(index.toLong() * frames.size / count).toInt().coerceAtMost(frames.lastIndex)]
    }

  private fun allocationBytes(frames: List<Bitmap>): Int {
    val unique = java.util.Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
    return frames.sumOf { if (unique.add(it)) it.allocationByteCount else 0 }
  }

  private fun validateDimensions(width: Int, height: Int) {
    require(width in 1..1024 && height in 1..1024 && width.toLong() * height <= 524288) {
      "Images must be at most 1024 per side and 524288 samples in total. Resize before publishing."
    }
  }

  private fun read(source: String): ByteArray {
    bytesCache.get(source)?.let { return it }
    val bytes = when {
      source.startsWith("data:image/") -> {
        require(source.length <= 2_800_000) { "Image data URI is too large." }
        Base64.decode(source.substringAfter(";base64,", ""), Base64.DEFAULT)
      }
      source.startsWith("https://") -> download(source)
      else -> {
        val uri = Uri.parse(source)
        require(uri.scheme == "file" || uri.scheme == "content") {
          "Use an HTTPS URL, local image URI or base64 data URI."
        }
        requireNotNull(context.contentResolver.openInputStream(uri)).use(::readBounded)
      }
    }
    require(bytes.size <= MAX_SOURCE_BYTES) { "Image source exceeds 2 MB." }
    bytesCache.put(source, bytes)
    return bytes
  }

  private fun download(source: String): ByteArray {
    val connection = URL(source).openConnection() as HttpURLConnection
    return try {
      connection.connectTimeout = 5_000
      connection.readTimeout = 8_000
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("Accept", "image/gif,image/*")
      connection.setRequestProperty("User-Agent", "Noti/0.1")
      require(connection.responseCode in 200..299) {
        "Image download failed with HTTP ${connection.responseCode}."
      }
      val length = connection.contentLengthLong
      require(length < 0 || length <= MAX_SOURCE_BYTES) { "Image source exceeds 2 MB." }
      connection.inputStream.use(::readBounded)
    } finally {
      connection.disconnect()
    }
  }

  private fun readBounded(stream: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var count = stream.read(buffer)
    while (count != -1) {
      require(output.size() + count <= MAX_SOURCE_BYTES) { "Image source exceeds 2 MB." }
      output.write(buffer, 0, count)
      count = stream.read(buffer)
    }
    return output.toByteArray()
  }

  private companion object {
    const val MAX_SOURCE_BYTES = 2_000_000
    const val MAX_DECODED_BYTES = 3_500_000
  }
}
