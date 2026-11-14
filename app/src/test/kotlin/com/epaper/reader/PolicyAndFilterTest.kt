package com.epaper.reader

import com.epaper.core.ExtractionConfig
import com.epaper.reader.browser.BrowserPolicy
import com.epaper.reader.browser.BrowserPolicy.Nav
import com.epaper.reader.ui.Availability
import com.epaper.reader.ui.CategoryFilter
import com.epaper.reader.ui.DownloadUi
import com.epaper.reader.ui.PaperUi
import com.epaper.reader.ui.filterPapers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PolicyAndFilterTest {
    private val config = ExtractionConfig.default()
    private val policy = BrowserPolicy(config, config.ruleFor("https://devuploads.com/abc"))

    @Test fun `ad and tracker resources are blocked, captcha providers are not`() {
        assertTrue(policy.blocksResource("https://sads.adsboosters.xyz/x.js"))
        assertTrue(policy.blocksResource("https://pagead2.googlesyndication.com/a"))
        assertFalse(policy.blocksResource("https://challenges.cloudflare.com/turnstile/v0/api.js"))
        assertFalse(policy.blocksResource("https://js.hcaptcha.com/1/api.js"))
        assertFalse(policy.blocksResource("https://devuploads.com/static/app.js"))
    }

    @Test fun `navigation is restricted`() {
        assertEquals(Nav.ALLOW, policy.navigation("https://devuploads.com/next/abc", hasGesture = false))
        assertEquals(Nav.ALLOW, policy.navigation("https://du2.devuploads.com/x", hasGesture = false))
        assertEquals(Nav.BLOCK, policy.navigation("intent://scan#Intent;end", hasGesture = true))
        assertEquals(Nav.BLOCK, policy.navigation("https://www.doubleclick.net/click", hasGesture = true))
        // Script-driven jump to an unknown site: only followed if it turns out to be the document.
        assertEquals(Nav.CHECK, policy.navigation("https://random-offer.example/win", hasGesture = false))
        assertEquals(Nav.ALLOW, policy.navigation("https://random-site.example/", hasGesture = true))
        assertEquals(Nav.CHECK, policy.navigation("https://cdn.example/files/13_nov_bl.pdf?t=1", hasGesture = false))
    }

    @Test fun `page script presses only the configured button`() {
        val js = policy.pageScript()
        assertTrue(js.contains("input[name=method_free]"))
        assertFalse(js.contains("method_premium']"))
        assertTrue(js.contains(".dl-media"))
    }

    private fun paper(id: String, title: String, cat: String, date: LocalDate, a: Availability = Availability.Ready) = PaperUi(
        id, title, title.take(2).uppercase(), "English · National", cat, "English", date, "e", "u", a, DownloadUi.None,
        false, false, null, 0, 0, null,
    )

    @Test fun `search and filters combine`() {
        val d1 = LocalDate.of(2026, 11, 13); val d2 = d1.minusDays(1)
        val all = listOf(
            paper("1", "The H. Edition", "newspaper", d1),
            paper("2", "All Hindi Editorials", "editorial", d1, Availability.NotUploaded),
            paper("3", "The H. Edition", "newspaper", d2, Availability.Removed("gone")),
        )
        assertEquals(listOf("1", "3"), filterPapers(all, "h. ed", CategoryFilter.ALL, null, false, null).map { it.id })
        assertEquals(listOf("2"), filterPapers(all, "", CategoryFilter.EDITORIAL, null, false, null).map { it.id })
        assertEquals(listOf("1"), filterPapers(all, "", CategoryFilter.ALL, null, true, null).map { it.id })
        assertEquals(listOf("3"), filterPapers(all, "", CategoryFilter.ALL, d2, false, "The H. Edition").map { it.id })
        assertEquals(listOf("1", "2", "3"), filterPapers(all, "english", CategoryFilter.ALL, null, false, null).map { it.id })
    }
}
