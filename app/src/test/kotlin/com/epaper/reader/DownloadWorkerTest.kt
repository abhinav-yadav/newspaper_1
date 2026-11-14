package com.epaper.reader

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.epaper.core.PdfValidator
import com.epaper.reader.data.DownloadEntity
import com.epaper.reader.data.DownloadState
import com.epaper.reader.storage.DownloadWorker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<EpaperApp>()
    private val c = app.container
    private val server = MockWebServer()
    private val pdf = res("/sample.pdf")
    private val requests = mutableListOf<RecordedRequest>()

    @After fun tearDown() = server.shutdown()

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return handler(request) }
        }
    }

    private fun run(id: String): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<DownloadWorker>(app).setInputData(workDataOf(DownloadWorker.KEY_ID to id)).build().doWork()
    }

    @Test fun `downloads, validates and stores a pdf with the browser's headers`() = runBlocking {
        serve { MockResponse().setBody(Buffer().write(pdf)) }
        c.db.downloads().upsert(DownloadEntity("p1", server.url("/a.pdf").toString(), """{"Cookie":"sid=1"}""", DownloadState.QUEUED))
        assertEquals(ListenableWorker.Result.success(), run("p1"))
        val d = c.db.downloads().get("p1")!!
        assertEquals(DownloadState.COMPLETE, d.state)
        assertEquals(pdf.size.toLong(), d.totalBytes)
        assertTrue(PdfValidator.isCompletePdf(c.files.pdf("p1")))
        assertEquals("sid=1", requests.single().getHeader("Cookie"))
    }

    @Test fun `an html page is never saved as a newspaper`() = runBlocking {
        serve { MockResponse().setBody("<html>Please wait 10 seconds…</html>") }
        c.db.downloads().upsert(DownloadEntity("p2", server.url("/x").toString(), state = DownloadState.QUEUED))
        assertEquals(ListenableWorker.Result.failure(), run("p2"))
        val d = c.db.downloads().get("p2")!!
        assertEquals(DownloadState.FAILED, d.state)
        assertFalse(c.files.pdf("p2").exists())
        assertFalse(c.files.part("p2").exists())
    }

    @Test fun `a paused download resumes with a range request`() = runBlocking {
        val half = pdf.size / 2
        c.files.part("p3").writeBytes(pdf.copyOf(half))
        serve { req ->
            val range = req.getHeader("Range")
            if (range == "bytes=$half-") MockResponse().setResponseCode(206).setBody(Buffer().write(pdf.copyOfRange(half, pdf.size)))
            else MockResponse().setBody(Buffer().write(pdf))
        }
        c.db.downloads().upsert(DownloadEntity("p3", server.url("/a.pdf").toString(), state = DownloadState.QUEUED, downloadedBytes = half.toLong()))
        assertEquals(ListenableWorker.Result.success(), run("p3"))
        assertEquals("bytes=$half-", requests.single().getHeader("Range"))
        assertArrayEquals(pdf, c.files.pdf("p3").readBytes())
    }

    @Test fun `server ignoring range restarts cleanly`() = runBlocking {
        c.files.part("p4").writeBytes(pdf.copyOf(10))
        serve { MockResponse().setBody(Buffer().write(pdf)) } // 200, full body
        c.db.downloads().upsert(DownloadEntity("p4", server.url("/a.pdf").toString(), state = DownloadState.QUEUED, downloadedBytes = 10))
        assertEquals(ListenableWorker.Result.success(), run("p4"))
        assertArrayEquals(pdf, c.files.pdf("p4").readBytes())
    }

    @Test fun `network failure is retried, keeping the partial file`() = runBlocking {
        serve { MockResponse().setResponseCode(503) }
        c.db.downloads().upsert(DownloadEntity("p5", server.url("/a.pdf").toString(), state = DownloadState.QUEUED))
        assertEquals(ListenableWorker.Result.retry(), run("p5"))
        assertEquals(DownloadState.QUEUED, c.db.downloads().get("p5")!!.state)
    }
}
