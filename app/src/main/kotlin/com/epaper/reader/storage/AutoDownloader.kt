package com.epaper.reader.storage

import com.epaper.core.ExtractionConfig
import com.epaper.core.Resolution
import com.epaper.core.Strategy
import com.epaper.reader.data.AppSettings
import com.epaper.reader.data.DownloadDao
import com.epaper.reader.data.LinkStatus
import com.epaper.reader.data.PaperEntity
import com.epaper.reader.data.PaperRepository

/**
 * Keeps the newest editions on the device without being asked.
 *
 * Links that resolve to a document (Google Drive, direct PDFs) are queued straight away. Links on hosts
 * that gate files behind a security check (DevUploads) cannot be fetched in the background, because the
 * check and countdown only run on a visible page; those are returned so the UI can offer a single
 * "Fetch all" run.
 */
class AutoDownloader(
    private val repo: PaperRepository,
    private val downloads: DownloadController,
    private val downloadDao: DownloadDao,
    private val config: ExtractionConfig,
) {
    /** Papers that automatic downloading should get, in order. */
    suspend fun candidates(settings: AppSettings): List<PaperEntity> {
        if (!settings.autoDownload) return emptyList()
        return repo.recentPapers(EDITIONS).filter { p ->
            p.status == LinkStatus.AVAILABLE && p.link != null && p.removedReason == null &&
                p.title !in settings.excludedTitles &&
                downloadDao.get(p.id) == null && // already saved, running, paused or failed: leave it to the user
                repo.library(p.id)?.autoSkip != true
        }
    }

    fun needsBrowser(p: PaperEntity) = p.link?.let { config.ruleFor(it)?.strategy } == Strategy.BROWSER_ASSISTED

    /** Queues every candidate that can be fetched in the background. Returns how many were queued. */
    suspend fun runBackground(settings: AppSettings): Int {
        var queued = 0
        for (p in candidates(settings)) {
            if (needsBrowser(p)) continue
            val r = repo.resolve(p)
            if (r is Resolution.Document) { downloads.enqueue(p.id, r.url, r.headers); queued++ }
        }
        return queued
    }

    companion object {
        /** The blog keeps files for ~48 h, so only the two newest editions are worth fetching. */
        const val EDITIONS = 2
    }
}
