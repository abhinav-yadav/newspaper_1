package com.epaper.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Turns a paper's link into a verified PDF URL, a hand-off to the sandboxed browser, or a clear
 * "unavailable" — never into an unverified document.
 */
class LinkResolver(
    private val http: OkHttpClient,
    private val config: ExtractionConfig = ExtractionConfig.default(),
) {
    suspend fun resolve(url: String): Resolution = try {
        val rule = config.ruleFor(url)
        when (rule?.strategy) {
            Strategy.GOOGLE_DRIVE -> drive(url)
            Strategy.BROWSER_ASSISTED -> landingPage(url, rule)
            Strategy.DIRECT, null -> direct(url, rule, depth = 0)
        }
    } catch (e: RedirectLoopException) {
        Resolution.Unavailable(Resolution.Reason.REDIRECT_LOOP, e.message ?: "Too many redirects")
    } catch (e: IOException) {
        Resolution.Unavailable(Resolution.Reason.NETWORK, "Network error: ${e.message ?: e.javaClass.simpleName}")
    }

    // ---- strategies -------------------------------------------------------------------------

    private suspend fun drive(url: String): Resolution {
        val id = driveFileId(url) ?: return direct(url, null, 0)
        val download = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        val probe = probe(download)
        if (probe.isPdf) return Resolution.Document(probe.finalUrl, fileName = probe.fileName)
        if (probe.code == 404 || probe.code == 410 || probe.code == 403) return removed("The file was removed from Google Drive")
        // Large files get a "can't scan for viruses" page whose form carries the real download parameters.
        val form = probe.html?.let { Jsoup.parse(it, probe.finalUrl).selectFirst("form#download-form, form[action*=download]") }
        if (form != null) {
            val action = form.absUrl("action").toHttpUrlOrNull() ?: return notPdf()
            val target = action.newBuilder().apply {
                form.select("input[type=hidden]").forEach { setQueryParameter(it.attr("name"), it.attr("value")) }
            }.build().toString()
            val second = probe(target)
            if (second.isPdf) return Resolution.Document(second.finalUrl, fileName = second.fileName)
        }
        return notPdf()
    }

    private suspend fun landingPage(url: String, rule: HostRule): Resolution {
        val probe = probe(url)
        if (probe.isPdf) return Resolution.Document(probe.finalUrl, fileName = probe.fileName)
        if (probe.code == 404 || probe.code == 410) return removed("The file host says this file no longer exists")
        val html = probe.html ?: return notPdf()
        val doc = Jsoup.parse(html, probe.finalUrl)
        val text = doc.text()
        if (rule.deadMarkers.any { text.contains(it, ignoreCase = true) }) {
            return removed("The file host says this file no longer exists")
        }
        val fileName = rule.fileNameSelector?.let { sel ->
            doc.selectFirst(sel)?.let { it.attr("value").ifBlank { it.text() } }?.trim()?.ifBlank { null }
        }
        val size = rule.sizePattern?.let { Regex(it).find(text)?.groupValues?.getOrNull(1) }
        return Resolution.NeedsBrowser(url, rule, fileName, size)
    }

    private suspend fun direct(url: String, rule: HostRule?, depth: Int): Resolution {
        val probe = probe(url)
        if (probe.isPdf) return Resolution.Document(probe.finalUrl, fileName = probe.fileName)
        if (probe.code == 404 || probe.code == 410) return removed("The link is dead (HTTP ${probe.code})")
        val html = probe.html ?: return notPdf()
        val doc = Jsoup.parse(html, probe.finalUrl)
        val effectiveRule = rule ?: config.ruleFor(probe.finalUrl)
        if (effectiveRule?.deadMarkers?.any { doc.text().contains(it, ignoreCase = true) } == true) {
            return removed("The file host says this file no longer exists")
        }
        if (depth < 1) {
            // An interstitial page that simply links to the document: follow the most PDF-looking link.
            candidateLinks(doc).forEach { next ->
                val r = if (config.ruleFor(next)?.strategy == Strategy.GOOGLE_DRIVE) drive(next) else direct(next, null, depth + 1)
                if (r is Resolution.Document) return r
            }
        }
        if (probe.code in 200..299) return Resolution.NeedsBrowser(url, effectiveRule)
        return removed("The link returned HTTP ${probe.code}")
    }

    // ---- helpers ----------------------------------------------------------------------------

    internal fun candidateLinks(doc: org.jsoup.nodes.Document): List<String> {
        val urls = doc.select("a[href], iframe[src], embed[src], meta[http-equiv=refresh]").mapNotNull { el ->
            when (el.tagName()) {
                "meta" -> el.attr("content").substringAfter("url=", "").trim().trim('\'', '"')
                    .ifEmpty { null }?.let { resolveAgainst(doc.location(), it) }
                "a" -> el.absUrl("href")
                else -> el.absUrl("src")
            }
        }.filter { it.startsWith("http") }
        return urls.filter { u ->
            u.substringBefore('?').endsWith(".pdf", ignoreCase = true) ||
                config.ruleFor(u)?.strategy == Strategy.GOOGLE_DRIVE
        }.distinct().take(5)
    }

    private fun resolveAgainst(base: String, rel: String): String? =
        base.toHttpUrlOrNull()?.resolve(rel)?.toString()

    private class Probe(
        val finalUrl: String,
        val code: Int,
        val isPdf: Boolean,
        val html: String?,
        val fileName: String?,
    )

    /** GET following redirects manually; reads only the first bytes unless the response is HTML. */
    private suspend fun probe(start: String): Probe {
        var url = start
        val seen = HashSet<String>()
        repeat(config.maxRedirects + 1) {
            if (!seen.add(url)) throw RedirectLoopException("Redirect loop at $url")
            val response = http.newCall(Request.Builder().url(url).build()).await()
            response.use { r ->
                if (r.isRedirect) {
                    val location = r.header("Location") ?: return Probe(url, r.code, false, null, null)
                    url = r.request.url.resolve(location)?.toString() ?: return Probe(url, r.code, false, null, null)
                    return@repeat
                }
                val body = r.body ?: return Probe(url, r.code, false, null, null)
                val source = body.source()
                source.request(1024)
                val head = source.buffer.snapshot().toByteArray().let { it.copyOf(minOf(it.size, 1024)) }
                if (r.isSuccessful && PdfValidator.hasPdfHeader(head)) {
                    return Probe(url, r.code, true, null, fileNameFrom(r.header("Content-Disposition"), url))
                }
                val type = r.header("Content-Type").orEmpty().lowercase()
                val html = if (type.contains("html") || type.contains("text") || type.isEmpty()) {
                    source.request(MAX_HTML)
                    source.buffer.snapshot().let { it.substring(0, minOf(it.size, MAX_HTML.toInt())).utf8() }
                } else null
                return Probe(url, r.code, false, html, null)
            }
        }
        throw RedirectLoopException("More than ${config.maxRedirects} redirects")
    }

    private fun fileNameFrom(disposition: String?, url: String): String? {
        val fromHeader = disposition?.let {
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        return fromHeader ?: url.substringBefore('?').substringAfterLast('/').takeIf { it.endsWith(".pdf", true) }
    }

    private fun removed(msg: String) = Resolution.Unavailable(Resolution.Reason.REMOVED, msg)
    private fun notPdf() = Resolution.Unavailable(Resolution.Reason.NOT_A_PDF, "The link did not lead to a PDF document")

    companion object {
        private const val MAX_HTML = 1_000_000L

        fun driveFileId(url: String): String? {
            Regex("/file/d/([A-Za-z0-9_-]{10,})").find(url)?.let { return it.groupValues[1] }
            Regex("[?&]id=([A-Za-z0-9_-]{10,})").find(url)?.let { return it.groupValues[1] }
            return null
        }
    }
}

class RedirectLoopException(message: String) : IOException(message)
