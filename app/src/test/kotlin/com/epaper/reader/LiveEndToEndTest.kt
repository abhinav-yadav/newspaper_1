package com.epaper.reader

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.epaper.core.PdfValidator
import com.epaper.core.Resolution
import com.epaper.reader.data.DownloadEntity
import com.epaper.reader.data.DownloadState
import com.epaper.reader.data.LinkStatus
import com.epaper.reader.storage.DownloadController
import com.epaper.reader.storage.DownloadWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Discovery → resolution → download → validation against the real website, through the app's own
 * repository, database and download worker. `LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveEndToEnd*'`
 */
@RunWith(AndroidJUnit4::class)
class LiveEndToEndTest {
    private val app = ApplicationProvider.getApplicationContext<EpaperApp>()
    private val c = app.container

    @Test fun workflow() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        c.repository.refresh(limit = 100)
        val rows = c.repository.observeAll().first()
        val editions = rows.map { it.paper.editionId }.distinct()
        println("Stored ${editions.size} editions / ${rows.size} papers")
        assertTrue(editions.size >= 10)

        // Newest edition: every available paper must be classified explicitly.
        val newest = rows.filter { it.paper.editionId == editions.first() }
        newest.filter { it.paper.status == LinkStatus.AVAILABLE }.forEach { r ->
            val res = c.repository.resolve(r.paper)
            println("  ${r.paper.code}: $res".take(200))
            assertTrue(res is Resolution.NeedsBrowser || res is Resolution.Document || res is Resolution.Unavailable)
            if (res is Resolution.NeedsBrowser) assertTrue(res.fileName?.endsWith(".pdf", true) == true)
        }

        // Fully automatic path (Google Drive): resolve, download with the real worker, validate.
        val drive = rows.firstOrNull { it.paper.link?.contains("drive.google.com") == true } ?: return@runBlocking
        val res = c.repository.resolve(drive.paper)
        println("Drive paper ${drive.paper.title} ${drive.paper.dateEpochDay}: $res")
        if (res !is Resolution.Document) return@runBlocking // links expire; explicit Unavailable is the correct outcome
        c.db.downloads().upsert(DownloadEntity(drive.paper.id, res.url, DownloadController.encodeHeaders(res.headers), DownloadState.QUEUED))
        val result = TestListenableWorkerBuilder<DownloadWorker>(app)
            .setInputData(workDataOf(DownloadWorker.KEY_ID to drive.paper.id)).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        val f = c.files.pdf(drive.paper.id)
        println("Downloaded ${f.length()} bytes -> valid=${PdfValidator.isCompletePdf(f)}")
        assertTrue(PdfValidator.isCompletePdf(f))
    }
}
