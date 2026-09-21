package expo.modules.notificationmotion

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException

/**
 * Read-only bridge between APK assets and RemoteViews inflated in System UI.
 *
 * NotificationManager grants each URI referenced by a RemoteViews action. Keeping frames as
 * content URIs prevents their decoded pixels from being flattened into Binder transactions.
 */
class FrameAssetProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun getType(uri: Uri): String = when (assetPath(uri).substringAfterLast('.').lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    require(mode == "r") { "Frame assets are read-only." }
    val path = assetPath(uri)
    val appContext = requireNotNull(context).applicationContext
    // openPipeHelper streams compressed APK assets too; AssetManager.openFd only supports
    // uncompressed entries and therefore is not suitable for PNG frame sequences.
    return openPipeHelper(uri, getType(uri), null, path) { output, _, _, _, asset ->
      try {
        appContext.assets.open(requireNotNull(asset)).use { input ->
          ParcelFileDescriptor.AutoCloseOutputStream(output).use { sink -> input.copyTo(sink) }
        }
      } catch (_: IOException) {
        // SystemUI can cancel an in-flight read when the notification is replaced.
      }
    }
  }

  private fun assetPath(uri: Uri): String {
    require(uri.authority == "${requireNotNull(context).packageName}.notificationmotion.frames") {
      "Unknown frame asset authority."
    }
    val segments = uri.pathSegments
    require(segments.size >= 2 && segments.first() == "asset") { "Invalid frame asset URI." }
    require(segments.drop(1).all(::safeSegment)) { "Unsafe frame asset path." }
    return segments.drop(1).joinToString("/")
  }

  private fun safeSegment(value: String): Boolean =
    value.isNotEmpty() && value != "." && value != ".." && value.matches(Regex("[A-Za-z0-9._-]+"))

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? =
    throw UnsupportedOperationException("Frame assets are read-only.")

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = throw UnsupportedOperationException("Frame assets are read-only.")

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
    throw UnsupportedOperationException("Frame assets are read-only.")
}
