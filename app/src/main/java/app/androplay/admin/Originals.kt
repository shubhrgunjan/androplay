package app.androplay.admin

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/**
 * Opens [uri] for reading, asking MediaStore for the unredacted original (EXIF GPS etc.)
 * when it is a `content://media/...` uri and ACCESS_MEDIA_LOCATION is granted.
 * Falls back to the plain uri if the original can't be opened.
 */
fun Context.openOriginal(uri: Uri): ParcelFileDescriptor {
    val cr = contentResolver
    val original = originalUri(uri)
    if (original != uri) {
        try {
            cr.openFileDescriptor(original, "r")?.let { return it }
        } catch (_: Exception) {
            // SecurityException / UnsupportedOperationException: use the regular uri.
        }
    }
    return cr.openFileDescriptor(uri, "r") ?: error("Cannot open $uri")
}

/** [MediaStore.setRequireOriginal] for media-store uris (API 29+, needs ACCESS_MEDIA_LOCATION). */
fun Context.originalUri(uri: Uri): Uri {
    if (Build.VERSION.SDK_INT < 29) return uri
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) return uri
    if (checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) return uri
    return runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
}
