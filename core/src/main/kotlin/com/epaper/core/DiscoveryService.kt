package com.epaper.core

import okhttp3.OkHttpClient

/** Finds editions via the Blogger JSON feed, falling back to the post page when the feed body is truncated. */
class DiscoveryService(
    private val http: OkHttpClient,
    private val config: ExtractionConfig = ExtractionConfig.default(),
    private val parser: EditionParser = EditionParser(config),
) {
    /**
     * Fetches up to [limit] most recent posts and returns those that are editions, newest first,
     * de-duplicated by post id.
     */
    suspend fun fetchEditions(limit: Int = 60, pageSize: Int = 25): List<Edition> {
        val editions = LinkedHashMap<String, Edition>()
        var start = 1
        while (start <= limit) {
            val size = minOf(pageSize, limit - start + 1)
            val page = parser.parseFeed(http.getText(feedUrl(start, size)))
            for (post in page.posts) {
                if (post.id in editions) continue
                val edition = parser.parseEdition(post) ?: fallback(post) ?: continue
                editions[post.id] = edition
            }
            if (page.posts.size < size) break
            start += size
        }
        return editions.values.sortedByDescending { it.publishedAtMillis }
    }

    /** Feed set to "short" truncates bodies; read the actual post page in that case. */
    private suspend fun fallback(post: FeedPost): Edition? {
        if (post.html.contains("ep-resource", ignoreCase = true)) return null // had markup but no papers
        if (post.html.length > 2_000) return null // full body that simply is not an edition (article)
        val page = runCatching { http.getText(post.url) }.getOrNull() ?: return null
        return parser.parseEdition(post, page)
    }

    private fun feedUrl(start: Int, size: Int) =
        "${config.blogBaseUrl}${config.feedPath}&start-index=$start&max-results=$size"
}
