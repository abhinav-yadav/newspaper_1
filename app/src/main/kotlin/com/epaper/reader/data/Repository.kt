package com.epaper.reader.data

import androidx.room.withTransaction
import com.epaper.core.DiscoveryService
import com.epaper.core.Edition
import com.epaper.core.LinkResolver
import com.epaper.core.PaperStatus
import com.epaper.core.Resolution
import kotlinx.coroutines.flow.Flow

/** Single source of truth for editions and per-paper user state. */
class PaperRepository(
    private val db: AppDatabase,
    private val discovery: DiscoveryService,
    private val resolver: LinkResolver,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val papers = db.papers()
    private val library = db.library()

    fun observeAll(): Flow<List<PaperRow>> = papers.observeAll()
    fun observeLibrary(paperId: String) = library.observe(paperId)
    fun observePaper(paperId: String) = papers.observePaper(paperId)
    suspend fun paper(paperId: String) = papers.paper(paperId)
    suspend fun hasData() = papers.editionCount() > 0

    /** Fetches the feed and stores it. Returns the number of editions that were not known before. */
    suspend fun refresh(limit: Int = 60): Int {
        val editions = discovery.fetchEditions(limit)
        val before = papers.editionCount()
        store(editions)
        return (papers.editionCount() - before).coerceAtLeast(0)
    }

    internal suspend fun store(editions: List<Edition>) {
        // A "removed" verdict survives refreshes only while the blog keeps the same link.
        val removed = papers.removedLinks().associateBy { it.id }
        val editionRows = editions.map {
            EditionEntity(it.id, it.title, it.date.toEpochDay(), it.publishedAtMillis, it.url)
        }
        val paperRows = editions.flatMap { e ->
            e.papers.map { p ->
                val (status, link, off) = when (val s = p.status) {
                    is PaperStatus.Available -> Triple(LinkStatus.AVAILABLE, s.url, null)
                    is PaperStatus.Off -> Triple(LinkStatus.OFF, null, s.reason)
                    PaperStatus.Pending -> Triple(LinkStatus.PENDING, null, null)
                }
                PaperEntity(
                    id = p.id, editionId = e.id, position = p.position, title = p.title, code = p.code,
                    subtitle = p.subtitle, category = p.category, language = p.language, status = status,
                    link = link, offReason = off, dateEpochDay = e.date.toEpochDay(),
                    publishedAt = e.publishedAtMillis, postUrl = e.url,
                    removedReason = removed[p.id]?.takeIf { it.link == link }?.removedReason,
                )
            }
        }
        db.withTransaction {
            papers.upsertEditions(editionRows)
            papers.upsertPapers(paperRows)
        }
    }

    /** Resolves a paper's link; remembers dead links so the UI can say so without re-checking. */
    suspend fun resolve(paper: PaperEntity): Resolution {
        val link = paper.link ?: return Resolution.Unavailable(
            Resolution.Reason.NOT_PUBLISHED,
            paper.offReason ?: "This paper has not been uploaded yet",
        )
        val r = resolver.resolve(link)
        when {
            r is Resolution.Unavailable && r.reason == Resolution.Reason.REMOVED -> papers.setRemoved(paper.id, r.message)
            r !is Resolution.Unavailable && paper.removedReason != null -> papers.setRemoved(paper.id, null)
        }
        return r
    }

    suspend fun markOpened(paperId: String) {
        val cur = library.get(paperId) ?: LibraryEntity(paperId)
        library.upsert(cur.copy(lastOpenedAt = now()))
    }

    suspend fun savePosition(paperId: String, page: Int, pageCount: Int) {
        val cur = library.get(paperId) ?: LibraryEntity(paperId)
        library.upsert(cur.copy(lastPage = page, pageCount = pageCount))
    }

    suspend fun toggleBookmark(paperId: String) {
        val cur = library.get(paperId) ?: LibraryEntity(paperId)
        library.upsert(cur.copy(bookmarked = !cur.bookmarked))
    }

    suspend fun toggleFavorite(title: String) {
        if (library.isFavorite(title)) library.removeFavorite(title) else library.addFavorite(FavoriteEntity(title))
    }

    suspend fun clearHistory() = library.clearHistory()

    suspend fun setAutoSkip(paperId: String, skip: Boolean) {
        val cur = library.get(paperId) ?: LibraryEntity(paperId)
        library.upsert(cur.copy(autoSkip = skip))
    }

    suspend fun recentPapers(editions: Int) = papers.recent(editions)
    suspend fun library(paperId: String) = library.get(paperId)
}
