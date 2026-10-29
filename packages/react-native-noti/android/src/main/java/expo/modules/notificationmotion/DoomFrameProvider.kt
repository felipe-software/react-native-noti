package expo.modules.notificationmotion

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException

/**
 * Serves live Doom frames from [DoomFrames] to the notification's RemoteViews in SystemUI.
 *
 * Captured images have immutable URIs. Live decks have unique URIs and seal their shared
 * snapshot on first read, after delivery delay; later reads return that same snapshot.
 * NotificationManager revokes the grant for a URI once an update drops it, so the
 * manifest instead lets SystemUI read with STATUS_BAR_SERVICE, a permission only it holds.
 */
class DoomFrameProvider : ContentProvider() {
  override fun onCreate(): Boolean = true

  override fun getType(uri: Uri): String = "image/bmp"

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    require(mode == "r") { "Doom frames are read-only." }
    require(uri.authority == "${requireNotNull(context).packageName}${DoomFrames.AUTHORITY_SUFFIX}") {
      "Unknown Doom frame authority."
    }
    val name = uri.pathSegments.singleOrNull() ?: throw FileNotFoundException("Invalid Doom frame URI.")
    // An expired frame fails as an IOException, which ImageView tolerates.
    val frame = DoomFrames.bmp(name) ?: throw FileNotFoundException("Doom frame expired.")
    // A seekable in-memory file lets ImageDecoder read natively. Through a pipe it falls back to
    // a Java stream, and SystemUI took ~350 ms instead of ~85 ms to show each update.
    val memory = Os.memfd_create("doom-frame", OsConstants.MFD_CLOEXEC)
    try {
      var written = 0
      while (written < frame.size) written += Os.write(memory, frame, written, frame.size - written)
      Os.lseek(memory, 0, OsConstants.SEEK_SET)
      return ParcelFileDescriptor.dup(memory)
    } finally {
      Os.close(memory)
    }
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? =
    throw UnsupportedOperationException("Doom frames are read-only.")

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = throw UnsupportedOperationException("Doom frames are read-only.")

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
    throw UnsupportedOperationException("Doom frames are read-only.")
}
