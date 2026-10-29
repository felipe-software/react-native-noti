package expo.modules.notificationmotion

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/** Read-only animation data; the manifest restricts access to SystemUI's signature permission. */
class DoomLiveFrameProvider : ContentProvider() {
  override fun onCreate() = true
  override fun getType(uri: Uri) = "image/gif"
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    require(mode == "r") { "Doom images are read-only." }
    return DoomLiveFrames.open(requireNotNull(context), uri)
  }
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
