package com.epaper.core

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkResolverTest {
    private val server = MockWebServer()
    private val pdf = "%PDF-1.7\n" + "x".repeat(2000) + "\n%%EOF"

    private fun config() = ExtractionConfig.default()

    /** Treats the mock server as a captcha-gated file host like DevUploads. */
    private fun landingConfig(): ExtractionConfig {
        val base = ExtractionConfig.default()
        val devUploads = base.rules.first { it.name == "DevUploads" }
        return base.copy(rules = listOf(devUploads.copy(hosts = listOf(server.hostName))))
    }

    private fun serve(routes: Map<String, MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                routes[request.path] ?: MockResponse().setResponseCode(404)
        }
    }

    private fun resolver(cfg: ExtractionConfig = config()) = LinkResolver(defaultHttpClient(), cfg)
    private fun url(path: String) = server.url(path).toString()

    @After fun tearDown() = server.shutdown()

    @Test fun `direct pdf behind redirects is returned`() = runTest {
        serve(mapOf(
            "/a" to MockResponse().setResponseCode(302).setHeader("Location", "/b"),
            "/b" to MockResponse().setResponseCode(301).setHeader("Location", "/paper.pdf"),
            "/paper.pdf" to MockResponse().setBody(pdf).setHeader("Content-Type", "application/octet-stream"),
        ))
        val r = resolver().resolve(url("/a"))
        assertTrue(r is Resolution.Document)
        assertEquals(url("/paper.pdf"), (r as Resolution.Document).url)
        assertEquals("paper.pdf", r.fileName)
    }

    @Test fun `redirect loop is detected`() = runTest {
        serve(mapOf(
            "/a" to MockResponse().setResponseCode(302).setHeader("Location", "/b"),
            "/b" to MockResponse().setResponseCode(302).setHeader("Location", "/a"),
        ))
        val r = resolver().resolve(url("/a")) as Resolution.Unavailable
        assertEquals(Resolution.Reason.REDIRECT_LOOP, r.reason)
    }

    @Test fun `endless redirect chain is capped`() = runTest {
        serve((0..20).associate { "/r$it" to MockResponse().setResponseCode(302).setHeader("Location", "/r${it + 1}") })
        val r = resolver().resolve(url("/r0")) as Resolution.Unavailable
        assertEquals(Resolution.Reason.REDIRECT_LOOP, r.reason)
    }

    @Test fun `html pretending to be a pdf is rejected`() = runTest {
        serve(mapOf("/fake.pdf" to MockResponse().setBody("<html>Ad page</html>").setHeader("Content-Type", "application/pdf")))
        val r = resolver().resolve(url("/fake.pdf"))
        assertTrue(r !is Resolution.Document)
    }

    @Test fun `interstitial page linking to the pdf is skipped`() = runTest {
        serve(mapOf(
            "/post" to MockResponse().setBody("<html><a href='/ads'>Ad</a><a href='/files/today.pdf'>Download</a></html>")
                .setHeader("Content-Type", "text/html"),
            "/files/today.pdf" to MockResponse().setBody(pdf),
        ))
        val r = resolver().resolve(url("/post")) as Resolution.Document
        assertTrue(r.url.endsWith("/files/today.pdf"))
    }

    @Test fun `unknown html page falls back to browser`() = runTest {
        serve(mapOf("/gate" to MockResponse().setBody("<html><button>Generate link</button></html>").setHeader("Content-Type", "text/html")))
        assertTrue(resolver().resolve(url("/gate")) is Resolution.NeedsBrowser)
    }

    @Test fun `dead link is reported as removed`() = runTest {
        serve(emptyMap())
        val r = resolver().resolve(url("/gone")) as Resolution.Unavailable
        assertEquals(Resolution.Reason.REMOVED, r.reason)
    }

    @Test fun `server errors are reported as network failures, not documents`() = runTest {
        serve(mapOf("/x" to MockResponse().setResponseCode(503)))
        val r = resolver().resolve(url("/x"))
        assertTrue(r is Resolution.Unavailable)
    }

    @Test fun `landing page - live file yields name and size for the browser hand-off`() = runTest {
        serve(mapOf("/w1t9dhovwi6w" to MockResponse().setBody(resource("/devuploads_live.html")).setHeader("Content-Type", "text/html")))
        val r = resolver(landingConfig()).resolve(url("/w1t9dhovwi6w")) as Resolution.NeedsBrowser
        assertEquals("13_nov_bl.pdf", r.fileName)
        assertEquals("32.8 MB", r.sizeLabel)
        assertEquals("DevUploads", r.rule?.name)
    }

    @Test fun `landing page - deleted file is removed`() = runTest {
        serve(mapOf("/dead" to MockResponse().setBody(resource("/devuploads_dead.html")).setHeader("Content-Type", "text/html")))
        val r = resolver(landingConfig()).resolve(url("/dead")) as Resolution.Unavailable
        assertEquals(Resolution.Reason.REMOVED, r.reason)
    }

    @Test fun `unresolvable host is a network error, not a crash`() = runTest {
        val r = resolver().resolve("https://no-such-host.invalid/file.pdf") as Resolution.Unavailable
        assertEquals(Resolution.Reason.NETWORK, r.reason)
    }

    @Test fun `drive ids are extracted from all link shapes`() {
        assertEquals("1976kuzmTbB-pJys-CzY7JvVUAwHWJvEs",
            LinkResolver.driveFileId("https://drive.google.com/file/d/1976kuzmTbB-pJys-CzY7JvVUAwHWJvEs/view?usp=drivesdk"))
        assertEquals("1976kuzmTbB-pJys", LinkResolver.driveFileId("https://drive.google.com/open?id=1976kuzmTbB-pJys"))
        assertEquals("1976kuzmTbB-pJys", LinkResolver.driveFileId("https://drive.google.com/uc?export=download&id=1976kuzmTbB-pJys"))
    }
}
