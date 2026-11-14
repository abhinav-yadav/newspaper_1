package com.epaper.reader

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.epaper.core.DiscoveryService
import com.epaper.core.EditionParser
import com.epaper.core.ExtractionConfig
import com.epaper.core.LinkResolver
import com.epaper.core.defaultHttpClient
import com.epaper.reader.data.AppDatabase
import com.epaper.reader.data.AppSettings
import com.epaper.reader.data.DownloadEntity
import com.epaper.reader.data.DownloadState
import com.epaper.reader.data.LinkStatus
import com.epaper.reader.data.PaperRepository
import com.epaper.reader.storage.AutoDownloader
import com.epaper.reader.storage.DownloadController
import com.epaper.reader.storage.PaperFiles
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoDownloadTest {
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path!!.endsWith(".pdf")) MockResponse().setBody(Buffer().write(res("/sample.pdf")))
                else MockResponse().setResponseCode(404)
        }
    }
    private val db = memoryDb()
    private val config = ExtractionConfig.default()
    private val http = defaultHttpClient()
    private val repo = PaperRepository(db, DiscoveryService(http, config), LinkResolver(http, config))
    private val downloads = DownloadController(ApplicationProvider.getApplicationContext(), db.downloads(),
        PaperFiles(ApplicationProvider.getApplicationContext()))
    private val auto = AutoDownloader(repo, downloads, db.downloads(), config)

    @After fun tearDown() { db.close(); server.shutdown() }

    /** Fixture feed; the newest edition's TH/IE/BL/FE are DevUploads links, the rest pending. */
    private fun seed() = runBlocking {
        val parser = EditionParser(config)
        val feed = parser.parseFeed(String(res("/feed.json")))
        repo.store(feed.posts.mapNotNull { parser.parseEdition(it) })
        repo.recentPapers(2)
    }

    @Test fun `only the newest two editions, available, not stopped, not paused are candidates`() = runBlocking {
        val recent = seed()
        assertEquals(setOf(2), setOf(recent.map { it.editionId }.distinct().size))
        val all = auto.candidates(AppSettings())
        assertTrue(all.all { it.status == LinkStatus.AVAILABLE })
        assertTrue(all.all { auto.needsBrowser(it) }) // fixture links are DevUploads

        val th = all.first { it.title == "The H. Edition" }
        repo.setAutoSkip(th.id, true) // user deleted it
        val ie = all.first { it.title == "Indian E. Paper" }
        db.downloads().upsert(DownloadEntity(ie.id, "https://x/a.pdf", state = DownloadState.PAUSED)) // user paused it
        val after = auto.candidates(AppSettings(excludedTitles = setOf("Financial E. Paper"))) // user paused this publication
        assertFalse(after.any { it.id == th.id || it.id == ie.id || it.title == "Financial E. Paper" })
        assertEquals(all.size - 2 - all.count { it.title == "Financial E. Paper" }, after.size)

        assertTrue(auto.candidates(AppSettings(autoDownload = false)).isEmpty())
    }

    @Test fun `directly downloadable papers are queued in the background, gated ones are not`() = runBlocking {
        seed()
        val gated = auto.candidates(AppSettings())
        // Make one paper a plain PDF link (as Google Drive / direct hosts are).
        val direct = gated.first()
        db.papers().upsertPapers(listOf(direct.copy(link = server.url("/today.pdf").toString())))
        assertEquals(1, auto.runBackground(AppSettings()))
        assertEquals(DownloadState.QUEUED, db.downloads().get(direct.id)!!.state)
        gated.drop(1).forEach { assertEquals(null, db.downloads().get(it.id)) }
        // Running again does not duplicate.
        assertEquals(0, auto.runBackground(AppSettings()))
    }
}

/** Your installed v1 database must upgrade without losing bookmarks or history. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun `v1 to v2 keeps library rows and adds autoSkip`() {
        helper.createDatabase("m.db", 1).use {
            it.execSQL("INSERT INTO library (paperId, bookmarked, lastOpenedAt, lastPage, pageCount) VALUES ('p', 1, 5, 3, 9)")
        }
        helper.runMigrationsAndValidate("m.db", 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT bookmarked, lastPage, autoSkip FROM library WHERE paperId = 'p'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0)); assertEquals(3, c.getInt(1)); assertEquals(0, c.getInt(2))
            }
        }
    }
}
