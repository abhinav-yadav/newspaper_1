package com.epaper.reader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper over the platform [PdfRenderer] (no third-party PDF engine needed).
 * PdfRenderer allows one open page at a time, so all access is serialised on a single thread.
 */
class PdfDocument private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Page sizes in PDF points. */
    val pageSizes: List<Pair<Int, Int>>,
) : Closeable {

    val pageCount get() = pageSizes.size
    private val lock = Mutex()
    private var closed = false

    /** Bitmap cache sized by bytes: roughly a few full-width pages. */
    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(page: Int, width: Int): Bitmap? = cache.get(key(page, width))

    /** Renders [page] at [width] pixels wide. Width is capped to keep memory bounded on huge broadsheet pages. */
    suspend fun render(page: Int, width: Int): Bitmap? {
        val w = width.coerceIn(16, MAX_WIDTH)
        cache.get(key(page, w))?.let { return it }
        return withContext(renderThread) {
            lock.withLock {
                if (closed) return@withLock null
                cache.get(key(page, w))?.let { return@withLock it }
                renderer.openPage(page).use { p ->
                    val h = (w.toLong() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key(page, w), bmp)
                    bmp
                }
            }
        }
    }

    override fun close() {
        closed = true
        // Close on the render thread so an in-flight render can't touch a closed renderer.
        Thread {
            kotlinx.coroutines.runBlocking(renderThread) {
                lock.withLock { runCatching { renderer.close() }; runCatching { fd.close() } }
            }
            cache.evictAll()
        }.start()
    }

    private fun key(page: Int, width: Int) = "$page@$width"

    companion object {
        const val MAX_WIDTH = 2600

        @OptIn(ExperimentalCoroutinesApi::class)
        private val renderThread = Dispatchers.IO.limitedParallelism(1)

        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        suspend fun open(file: File): PdfDocument = withContext(renderThread) {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(fd)
                val sizes = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width to it.height } }
                PdfDocument(fd, renderer, sizes)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }
    }
}
