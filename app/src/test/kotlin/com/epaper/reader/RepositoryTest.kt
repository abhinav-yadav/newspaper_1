package com.epaper.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.epaper.core.DiscoveryService
import com.epaper.core.ExtractionConfig
import com.epaper.core.LinkResolver
import com.epaper.core.Resolution
import com.epaper.core.defaultHttpClient
import com.epaper.reader.data.LinkStatus
import com.epaper.reader.data.PaperRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private val server = MockWebServer()
    private val db = memoryDb()
    private var feed = String(res("/feed.json"))

    private val repo: PaperRepository by lazy {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/feeds/") ->
                    // one page only: the fixture has 5 posts
                    if (request.path!!.contains("start-index=1&")) MockResponse().setBody(feed)
                    else MockResponse().setBody("""{"feed":{"entry":[]}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val config = ExtractionConfig.default().copy(blogBaseUrl = server.url("").toString().trimEnd('/'))
        val http = defaultHttpClient()
        PaperRepository(db, DiscoveryService(http, config), LinkResolver(http, config), now = { 1000L })
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    @Test fun `refresh stores editions newest first and skips non-edition posts`() = runBlocking {
        assertEquals(4, repo.refresh())
        val rows = repo.observeAll().first()
        assertEquals(40, rows.size)
        assertEquals("13/11", rows.first().editionTitle)
        assertTrue(rows.zipWithNext().all { (a, b) -> a.paper.publishedAt >= b.paper.publishedAt })
        assertEquals(LinkStatus.PENDING, rows[2].paper.status)
        assertTrue(rows.any { it.paper.status == LinkStatus.OFF && it.paper.offReason == "Sunday Off" })
        // A second refresh finds nothing new and does not duplicate.
        assertEquals(0, repo.refresh())
        assertEquals(40, repo.observeAll().first().size)
    }

    @Test fun `user state survives refreshes`() = runBlocking {
        repo.refresh()
        val id = repo.observeAll().first().first().paper.id
        repo.toggleBookmark(id)
        repo.markOpened(id)
        repo.savePosition(id, 5, 20)
        repo.toggleFavorite("The H. Edition")
        repo.refresh()
        val row = repo.observeAll().first().first { it.paper.id == id }
        assertEquals(true, row.bookmarked)
        assertEquals(1000L, row.lastOpenedAt)
        assertEquals(5, row.lastPage)
        assertTrue(row.favorite)
        repo.clearHistory()
        assertNull(repo.observeAll().first().first { it.paper.id == id }.lastOpenedAt)
    }

    @Test fun `dead links are remembered until the blog changes the link`() = runBlocking {
        repo.refresh()
        // Point the first paper at a dead URL on the mock server.
        val first = repo.observeAll().first().first().paper
        val deadLink = server.url("/gone.pdf").toString()
        db.papers().upsertPapers(listOf(first.copy(link = deadLink)))
        val r = repo.resolve(repo.paper(first.id)!!)
        assertTrue(r is Resolution.Unavailable && r.reason == Resolution.Reason.REMOVED)
        assertTrue(repo.paper(first.id)!!.removedReason != null)
        // The refresh brings back the original (different) link, so the verdict no longer applies.
        repo.refresh()
        assertNull(repo.paper(first.id)!!.removedReason)
    }

    @Test fun `unpublished papers resolve to a clear reason without network`() = runBlocking {
        repo.refresh()
        val off = repo.observeAll().first().first { it.paper.status == LinkStatus.OFF }.paper
        val r = repo.resolve(off) as Resolution.Unavailable
        assertEquals(Resolution.Reason.NOT_PUBLISHED, r.reason)
        assertEquals("Sunday Off", r.message)
    }
}
