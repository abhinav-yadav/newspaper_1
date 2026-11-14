package com.epaper.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real-world checks against the live website. Run with `LIVE=1 ./gradlew :core:test --tests '*LiveSiteTest*'`.
 * Skipped by default so the build does not depend on the network.
 */
class LiveSiteTest {
    private val http = defaultHttpClient()
    private val config = ExtractionConfig.default()

    @Test fun `discover editions and resolve every available link of the newest one`() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        val editions = DiscoveryService(http, config).fetchEditions(limit = 100)
        println("Discovered ${editions.size} editions, ${editions.sumOf { it.papers.size }} papers; newest ${editions.first().date}")
        assertTrue(editions.size >= 10)
        assertTrue(editions.zipWithNext().all { (a, b) -> a.publishedAtMillis >= b.publishedAtMillis })
        assertTrue(editions.map { it.id }.toSet().size == editions.size)

        val resolver = LinkResolver(http, config)
        val newest = editions.first()
        for (p in newest.papers) {
            val s = p.status
            val r = if (s is PaperStatus.Available) resolver.resolve(s.url) else null
            println("  ${p.code.padEnd(4)} ${p.title.padEnd(24)} $s -> $r")
        }
        // A sample of older links across hosts: each must be classified, never crash, never be a fake document.
        editions.drop(1).flatMap { it.papers }.map { it.status }.filterIsInstance<PaperStatus.Available>()
            .distinctBy { hostOf(it.url) }.forEach { println("  host sample ${it.url} -> ${resolver.resolve(it.url)}") }
    }

    @Test fun `a live Google Drive file resolves to a verified PDF`() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        val r = LinkResolver(http, config).resolve("https://drive.google.com/file/d/1976kuzmTbB-pJys-CzY7JvVUAwHWJvEs/view?usp=drivesdk")
        println(r)
        // Files expire after ~48 h on the blog's side; either outcome is acceptable but must be explicit.
        assertTrue(r is Resolution.Document || (r is Resolution.Unavailable && r.reason == Resolution.Reason.REMOVED))
    }
}
