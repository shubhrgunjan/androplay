package app.androplay.admin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.androplay.AndroPlayApp
import app.androplay.core.Backend
import app.androplay.core.Content
import app.androplay.core.RedisError
import app.androplay.core.S3
import app.androplay.protocol.ContentType
import app.androplay.protocol.Origin
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Drains the upload queue: resumable multipart upload to object storage, thumbnail, then
 * marks the content ready in Redis, which wakes every board to download it.
 * Network errors → retry with backoff; the queue and part progress survive app restarts.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as AndroPlayApp
    private val repo get() = app.admin

    override suspend fun doWork(): Result {
        if (!app.settings.config.isComplete) return Result.success()
        runCatching { setForeground(foreground("Uploading to AndroPlay")) }
        val backend = try {
            app.newBackend()
        } catch (e: Exception) {
            return Result.failure()
        }
        val s3 = app.newS3()
        try {
            while (true) {
                val u = repo.nextPending() ?: break
                try {
                    upload(backend, s3, u)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FileNotFoundException) {
                    repo.update(u.id) { it.copy(state = UploadState.FAILED, error = "File is no longer available on this phone") }
                } catch (e: SecurityException) {
                    repo.update(u.id) { it.copy(state = UploadState.FAILED, error = "Lost permission to read this file") }
                } catch (e: S3.S3Error) {
                    if (e.code in 400..499 && e.code != 404 && e.code != 408 && e.code != 429) {
                        repo.update(u.id) { it.copy(state = UploadState.FAILED, error = "Storage rejected upload (${e.code})") }
                    } else {
                        repo.update(u.id) { it.copy(state = UploadState.QUEUED) }
                        return Result.retry()
                    }
                } catch (e: RedisError) {
                    repo.update(u.id) { it.copy(state = UploadState.FAILED, error = "Redis: ${e.message}") }
                } catch (e: IOException) {
                    repo.update(u.id) { it.copy(state = UploadState.QUEUED) }
                    return Result.retry()
                }
            }
        } finally {
            backend.close()
        }
        return Result.success()
    }

    private suspend fun upload(backend: Backend, s3: S3, start: Upload) {
        var u = start
        val existing = backend.content(u.id)
        if (existing?.ready == true) {
            repo.finished(u.id)
            return
        }
        if (existing == null) {
            backend.putContent(
                Content(
                    id = u.id, handle = backend.newHandle(), name = u.name, type = u.type, mime = u.mime, size = u.size,
                    ready = false, origin = Origin.UPLOAD, addedBy = app.settings.deviceName, addedAt = u.addedAt,
                ),
            )
        }
        val key = "content/${u.id}"
        val uri = Uri.parse(u.uri)
        applicationContext.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                if (u.size <= PART) {
                    s3.putObject(key, read(ch, 0, u.size.toInt()), u.mime)
                } else {
                    val uploadId = u.uploadId ?: s3.createMultipart(key, u.mime).also { id ->
                        repo.update(u.id) { it.copy(uploadId = id, parts = emptyList()) }
                    }
                    u = u.copy(uploadId = uploadId)
                    val total = ((u.size + PART - 1) / PART).toInt()
                    val parts = u.parts.toMutableList()
                    for (n in parts.size + 1..total) {
                        val offset = (n - 1).toLong() * PART
                        val bytes = read(ch, offset, min(PART.toLong(), u.size - offset).toInt())
                        val etag = try {
                            s3.uploadPart(key, uploadId, n, bytes)
                        } catch (e: S3.S3Error) {
                            if (e.code == 404) repo.update(u.id) { it.copy(uploadId = null, parts = emptyList(), uploaded = 0) }
                            throw e // expired multipart upload: next attempt starts fresh
                        }
                        parts += PartTag(n, etag)
                        val done = min(n.toLong() * PART, u.size)
                        repo.update(u.id) { it.copy(state = UploadState.UPLOADING, parts = parts.toList(), uploaded = done) }
                        setProgress(u.name, done, u.size)
                    }
                    s3.completeMultipart(key, uploadId, parts.map { it.n to it.etag })
                }
            }
        }
        val thumb = runCatching { thumbnail(uri, u.type) }.getOrNull()?.let { jpeg ->
            runCatching { s3.putObject("thumbs/${u.id}.jpg", jpeg, "image/jpeg") }.isSuccess
        } ?: false
        backend.markReady(u.id, key, thumb)
        repo.finished(u.id)
    }

    private fun read(ch: FileChannel, offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n < 0) throw IOException("File shrank during upload")
            pos += n
        }
        return buf.array()
    }

    private fun thumbnail(uri: Uri, type: ContentType): ByteArray? {
        val cr = applicationContext.contentResolver
        val bitmap: Bitmap = when (type) {
            ContentType.IMAGE -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
                val s = min(1f, 480f / info.size.width)
                d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            ContentType.VIDEO, ContentType.AUDIO -> MediaMetadataRetriever().let { r ->
                try {
                    r.setDataSource(applicationContext, uri)
                    if (type == ContentType.AUDIO) {
                        r.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                    } else {
                        r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                    }
                } finally {
                    r.release()
                }
            } ?: return null
            ContentType.PDF -> PdfRenderer(cr.openFileDescriptor(uri, "r") ?: return null).use { r ->
                r.openPage(0).use { p ->
                    Bitmap.createBitmap(480, (480 * p.height / p.width.toFloat()).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        .also {
                            it.eraseColor(Color.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                }
            }
            else -> return null
        }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
            it.toByteArray()
        }
    }

    private suspend fun setProgress(name: String, done: Long, total: Long) {
        runCatching { setForeground(foreground("Uploading $name", (done * 100 / total).toInt())) }
    }

    private fun foreground(title: String, percent: Int = -1): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setOngoing(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val PART = 8 * 1024 * 1024
        private const val CHANNEL = "uploads"
        private const val NOTIFICATION_ID = 2

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("androplay-upload", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
