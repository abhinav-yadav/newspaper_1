package com.epaper.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.time.OffsetDateTime
import java.time.ZoneId

/** A post as it comes out of the Blogger feed, before its body is interpreted. */
data class FeedPost(
    val id: String,
    val title: String,
    val publishedIso: String,
    val url: String,
    val html: String,
)

data class FeedPage(val posts: List<FeedPost>, val totalResults: Int)

class EditionParser(private val config: ExtractionConfig) {

    private val zone = ZoneId.of(config.timeZone)
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses Blogger's `?alt=json` feed. */
    fun parseFeed(text: String): FeedPage {
        val feed = json.parseToJsonElement(text).jsonObject["feed"]?.jsonObject
            ?: throw ParseException("Not a Blogger feed")
        val total = feed.str("openSearch\$totalResults")?.toIntOrNull() ?: 0
        val entries = (feed["entry"] as? JsonArray).orEmpty()
        val posts = entries.mapNotNull { el ->
            val e = el.jsonObject
            val rawId = e.str("id") ?: return@mapNotNull null
            val url = e["link"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["rel"]?.jsonPrimitive?.content == "alternate" }
                ?.get("href")?.jsonPrimitive?.content
                ?: return@mapNotNull null
            FeedPost(
                id = rawId.substringAfterLast("post-"),
                title = e.str("title").orEmpty().trim(),
                publishedIso = e.str("published") ?: return@mapNotNull null,
                url = url,
                html = e.str("content") ?: e.str("summary").orEmpty(),
            )
        }
        return FeedPage(posts, total)
    }

    /** Returns null for posts that are not editions (articles, notification popups). */
    fun parseEdition(post: FeedPost, html: String = post.html): Edition? {
        val papers = parsePapers(post.id, html)
        if (papers.isEmpty()) return null
        val published = OffsetDateTime.parse(post.publishedIso)
        return Edition(
            id = post.id,
            title = post.title,
            publishedAtMillis = published.toInstant().toEpochMilli(),
            date = published.atZoneSameInstant(zone).toLocalDate(),
            url = post.url,
            papers = papers,
        )
    }

    /** Extracts the papers from a post body or from a full post page. */
    fun parsePapers(postId: String, html: String): List<Paper> {
        val s = config.selectors
        val doc = Jsoup.parse(html)
        return doc.select(s.item).mapIndexedNotNull { index, item ->
            val title = item.selectFirst(s.title)?.text()?.trim().orEmpty()
            val code = item.selectFirst(s.code)?.text()?.trim().orEmpty()
            if (title.isEmpty() && code.isEmpty()) return@mapIndexedNotNull null
            val subtitle = item.selectFirst(s.subtitle)?.text()?.trim().orEmpty()
            val href = item.selectFirst(s.link)?.attr("href")?.trim().orEmpty()
            Paper(
                id = "$postId-$index",
                editionId = postId,
                position = index,
                title = title.ifEmpty { code },
                code = code,
                subtitle = subtitle,
                category = item.attr(s.categoryAttr).trim().lowercase().ifEmpty { "newspaper" },
                language = languageOf(title, subtitle),
                status = statusOf(href),
            )
        }
    }

    /** Mirrors the site's own `epUpdateAvailability()` logic. */
    fun statusOf(href: String): PaperStatus {
        val h = href.trim()
        val off = Regex("^${Regex.escape(config.offPrefix)}(?::(.*))?$", RegexOption.IGNORE_CASE).find(h)
        if (off != null) return PaperStatus.Off(off.groupValues[1].trim().ifEmpty { "Not available" })
        if (config.placeholderLinks.any { it.equals(h, ignoreCase = true) }) return PaperStatus.Pending
        val scheme = h.substringBefore(':', "").lowercase()
        return if (scheme == "http" || scheme == "https") PaperStatus.Available(h) else PaperStatus.Pending
    }

    private fun languageOf(title: String, subtitle: String): String? {
        val known = listOf("English", "Hindi", "Marathi", "Gujarati", "Bengali", "Tamil", "Telugu", "Urdu", "Kannada", "Malayalam")
        val first = subtitle.split('·').first().trim()
        known.firstOrNull { it.equals(first, ignoreCase = true) }?.let { return it }
        return known.firstOrNull { title.contains(it, ignoreCase = true) }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonObject)?.get("\$t")?.jsonPrimitive?.content

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}

class ParseException(message: String) : Exception(message)
