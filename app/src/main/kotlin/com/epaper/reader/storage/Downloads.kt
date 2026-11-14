package com.epaper.reader.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.epaper.core.PdfValidator
import com.epaper.reader.EpaperApp
import com.epaper.reader.data.DownloadDao
import com.epaper.reader.data.DownloadEntity
import com.epaper.reader.data.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Files on disk for a paper. Everything lives in app-private storage; nothing is shared. */
class PaperFiles(context: Context) {
    private val papers = File(context.filesDir, "papers").apply { mkdirs() }
    private val thumbs = File(context.filesDir, "thumbs").apply { mkdirs() }
    fun pdf(id: String) = File(papers, "$id.pdf")
    fun part(id: String) = File(papers, "$id.pdf.part")
    fun thumb(id: String) = File(thumbs, "$id.png")
    fun totalBytes(): Long = papers.listFiles()?.sumOf { it.length() } ?: 0
}

class DownloadController(
    private val context: Context,
    private val dao: DownloadDao,
    private val files: PaperFiles,
) {
    private val wm get() = WorkManager.getInstance(context)

    /** Queues a download unless a valid copy is already on disk (no duplicate downloads). */
    suspend fun enqueue(paperId: String, url: String, headers: Map<String, String> = emptyMap()) {
        val existing = dao.get(paperId)
        if (existing?.state == DownloadState.COMPLETE && PdfValidator.isCompletePdf(files.pdf(paperId))) return
        val sameSource = existing?.url == url
        if (sameSource && existing?.state in setOf(DownloadState.QUEUED, DownloadState.RUNNING)) return
        if (!sameSource) files.part(paperId).delete() // a partial file from another URL can't be resumed
        dao.upsert(
            DownloadEntity(
                paperId = paperId, url = url, headersJson = encodeHeaders(headers), state = DownloadState.QUEUED,
                downloadedBytes = if (sameSource) existing?.downloadedBytes ?: 0 else 0,
            )
        )
        schedule(paperId)
    }

    suspend fun pause(paperId: String) {
        val d = dao.get(paperId) ?: return
        if (d.state != DownloadState.RUNNING && d.state != DownloadState.QUEUED) return
        wm.cancelUniqueWork(work(paperId)).await() // stop the worker first so it can't overwrite the state
        val latest = dao.get(paperId) ?: return
        dao.progress(paperId, DownloadState.PAUSED, latest.downloadedBytes, latest.totalBytes)
    }

    suspend fun resume(paperId: String) {
        val d = dao.get(paperId) ?: return
        dao.progress(paperId, DownloadState.QUEUED, d.downloadedBytes, d.totalBytes)
        schedule(paperId)
    }

    /** Stops and removes a download and any file it produced. */
    suspend fun delete(paperId: String) {
        wm.cancelUniqueWork(work(paperId))
        dao.delete(paperId)
        files.part(paperId).delete()
        files.pdf(paperId).delete()
        files.thumb(paperId).delete()
    }

    suspend fun deleteAll() = dao.all().forEach { delete(it.paperId) }

    private fun schedule(paperId: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to paperId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniqueWork(work(paperId), ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        fun work(id: String) = "download-$id"
        fun encodeHeaders(h: Map<String, String>) = JsonObject(h.mapValues { JsonPrimitive(it.value) }).toString()
        fun decodeHeaders(s: String): Map<String, String> =
            runCatching { Json.parseToJsonElement(s) as JsonObject }.getOrNull()
                ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as EpaperApp).container
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val dao = c.db.downloads()
        val d = dao.get(id) ?: return Result.failure()
        return try {
            download(c.downloadHttp, dao, c.files, d)
            Result.success()
        } catch (e: CancellationException) {
            throw e // paused or cancelled; partial file is kept for resuming
        } catch (e: InvalidDocumentException) {
            c.files.part(id).delete()
            dao.progress(id, DownloadState.FAILED, 0, -1, e.message)
            Result.failure()
        } catch (e: IOException) {
            val cur = dao.get(id) ?: return Result.failure()
            if (runAttemptCount < 2) {
                dao.progress(id, DownloadState.QUEUED, cur.downloadedBytes, cur.totalBytes, "Connection lost, retrying…")
                Result.retry()
            } else {
                dao.progress(id, DownloadState.FAILED, cur.downloadedBytes, cur.totalBytes, "Download failed: ${e.message}")
                Result.failure()
            }
        }
    }

    private suspend fun download(http: OkHttpClient, dao: DownloadDao, files: PaperFiles, d: DownloadEntity) =
        withContext(Dispatchers.IO) {
            val part = files.part(d.paperId)
            val offset = if (part.exists()) part.length() else 0L
            val request = Request.Builder().url(d.url).apply {
                DownloadController.decodeHeaders(d.headersJson).forEach { (k, v) -> header(k, v) }
                if (offset > 0) header("Range", "bytes=$offset-")
            }.build()
            http.newCall(request).execute().use { r ->
                if (r.code == 416 && offset > 0) return@use // already fully downloaded
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val append = offset > 0 && r.code == 206
                val body = r.body ?: throw IOException("Empty response")
                val start = if (append) offset else 0L
                val total = body.contentLength().let { if (it >= 0) it + start else -1L }
                var done = start
                var lastReport = 0L
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (done == 0L && !PdfValidator.hasPdfHeader(buf.copyOf(n))) {
                                throw InvalidDocumentException("The file host sent a web page instead of the newspaper PDF")
                            }
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 400) {
                                dao.progress(d.paperId, DownloadState.RUNNING, done, total)
                                lastReport = now
                            }
                        }
                    }
                }
            }
            if (!PdfValidator.isCompletePdf(part)) {
                throw InvalidDocumentException("The downloaded file is not a complete PDF")
            }
            val pdf = files.pdf(d.paperId)
            pdf.delete()
            if (!part.renameTo(pdf)) throw IOException("Could not save the file")
            dao.complete(d.paperId, pdf.absolutePath, pdf.length())
            runCatching { renderCover(pdf, files.thumb(d.paperId)) }
        }

    companion object {
        const val KEY_ID = "paperId"

        /** First page as a small PNG, used as the card thumbnail. */
        fun renderCover(pdf: File, out: File, width: Int = 320) {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val h = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        bmp.recycle()
                    }
                }
            }
        }
    }
}

class InvalidDocumentException(message: String) : IOException(message)
