package com.epaper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EditionParserTest {
    private val parser = EditionParser(ExtractionConfig.default())
    private val feed = parser.parseFeed(resource("/feed.json"))

    @Test fun `feed lists posts with ids, urls and dates`() {
        assertEquals(5, feed.posts.size)
        assertEquals(91, feed.totalResults)
        val first = feed.posts.first()
        assertEquals("13/11", first.title)
        assertEquals("https://epaperwala11.blogspot.com/2026/11/1311.html", first.url)
        assertTrue(first.id.all { it.isDigit() })
    }

    @Test fun `current template - ten papers with metadata and mixed statuses`() {
        val e = parser.parseEdition(feed.posts[0])!!
        // Posted 17:48 US-Pacific on the 12th = morning of the 13th in India.
        assertEquals(LocalDate.of(2026, 11, 13), e.date)
        assertEquals(10, e.papers.size)
        val th = e.papers[0]
        assertEquals("The H. Edition", th.title)
        assertEquals("TH", th.code)
        assertEquals("English · National", th.subtitle)
        assertEquals("English", th.language)
        assertEquals("newspaper", th.category)
        assertEquals(PaperStatus.Available("https://devuploads.com/w2ttkxpgonwq").javaClass, th.status.javaClass)
        assertEquals(PaperStatus.Pending, e.papers[2].status) // YOUR_LINK_HERE
        assertEquals("editorial", e.papers[9].category)
        assertEquals("Hindi", e.papers[9].language)
        assertEquals("${e.id}-3", e.papers[3].id)
    }

    @Test fun `off days carry their reason`() {
        val e = parser.parseEdition(feed.posts[1])!!
        val off = e.papers.map { it.status }.filterIsInstance<PaperStatus.Off>()
        assertEquals(2, off.size)
        assertEquals("Sunday Off", off.first().reason)
    }

    @Test fun `old template and drive links parse`() {
        val drive = parser.parseEdition(feed.posts[2])!!
        assertTrue(drive.papers.any { (it.status as? PaperStatus.Available)?.url?.contains("drive.google.com") == true })
        val old = parser.parseEdition(feed.posts[3])!!
        assertEquals("[TH]", old.papers[0].subtitle)
        assertNull(old.papers[0].language)
    }

    @Test fun `non-edition posts are skipped`() {
        assertNull(parser.parseEdition(feed.posts[4]))
    }

    @Test fun `status mirrors the site's availability rules`() {
        assertEquals(PaperStatus.Pending, parser.statusOf("YOUR_LINK_HERE"))
        assertEquals(PaperStatus.Pending, parser.statusOf(""))
        assertEquals(PaperStatus.Pending, parser.statusOf("javascript:alert(1)"))
        assertEquals(PaperStatus.Off("Not available"), parser.statusOf("OFF"))
        assertEquals(PaperStatus.Off("Diwali"), parser.statusOf("off: Diwali"))
        assertEquals(PaperStatus.Available("https://x.com/a"), parser.statusOf(" https://x.com/a "))
    }

    @Test fun `markup changes degrade to no papers, not a crash`() {
        assertTrue(parser.parsePapers("1", "<div class='renamed'><a href='https://x'>Open</a></div>").isEmpty())
    }
}

fun resource(path: String): String =
    EditionParserTest::class.java.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
