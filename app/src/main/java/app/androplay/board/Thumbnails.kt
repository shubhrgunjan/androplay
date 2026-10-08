package app.androplay.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.util.Size
import app.androplay.protocol.ContentType
import app.androplay.protocol.Item
import java.io.ByteArrayOutputStream
import java.io.File

/** 480x270 JPEG previews: cached for the board's grid and uploaded for admins. */
class Thumbnails(ctx: Context, private val store: LocalStore) {
    private val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    fun file(item: Item): File? {
        val out = File(dir, "${item.id}.jpg")
        if (out.exists()) return out
        val src = store.fileOf(item.id) ?: return null
        val jpeg = render(src, item.type) ?: return null
        val tmp = File(dir, "${item.id}.${Thread.currentThread().id}.tmp")
        tmp.writeBytes(jpeg)
        tmp.renameTo(out)
        return out
    }

    fun evict(id: String) {
        File(dir, "$id.jpg").delete()
    }

    fun render(src: File, type: ContentType): ByteArray? {
        val bitmap = runCatching { bitmap(src, type) }.getOrNull() ?: return null
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private fun bitmap(src: File, type: ContentType): Bitmap? = when (type) {
        ContentType.VIDEO -> ThumbnailUtils.createVideoThumbnail(src, SIZE, null)
        ContentType.IMAGE -> ThumbnailUtils.createImageThumbnail(src, SIZE, null)
        ContentType.AUDIO -> ThumbnailUtils.createAudioThumbnail(src, SIZE, null)
        ContentType.PDF -> PdfRenderer(ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { page ->
                val w = SIZE.width
                val h = (w * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        else -> null
    }

    private companion object {
        val SIZE = Size(480, 270)
    }
}
