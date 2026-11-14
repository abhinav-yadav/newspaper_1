package com.epaper.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epaper.core.Resolution
import com.epaper.reader.AppContainer
import com.epaper.reader.browser.CapturedDownload
import com.epaper.reader.data.AppSettings
import com.epaper.reader.data.DownloadState
import com.epaper.reader.data.LinkStatus
import com.epaper.reader.data.PaperRow
import com.epaper.reader.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.LocalDate

sealed interface Availability {
    data object Ready : Availability
    data object NotUploaded : Availability
    data class Off(val reason: String) : Availability
    data class Removed(val reason: String) : Availability
}

sealed interface DownloadUi {
    data object None : DownloadUi
    data class Active(val fraction: Float?, val bytes: Long, val note: String?) : DownloadUi
    data class Paused(val bytes: Long, val total: Long) : DownloadUi
    data class Done(val bytes: Long) : DownloadUi
    data class Failed(val message: String) : DownloadUi
}

data class PaperUi(
    val id: String,
    val title: String,
    val code: String,
    val subtitle: String,
    val category: String,
    val language: String?,
    val date: LocalDate,
    val editionId: String,
    val postUrl: String,
    val availability: Availability,
    val download: DownloadUi,
    val bookmarked: Boolean,
    val favorite: Boolean,
    val lastOpenedAt: Long?,
    val lastPage: Int,
    val pageCount: Int,
    val thumb: File?,
) {
    val canRead get() = availability == Availability.Ready || download is DownloadUi.Done ||
        download is DownloadUi.Active || download is DownloadUi.Paused
    val inProgressReading get() = lastOpenedAt != null && pageCount > 1 && lastPage in 1 until pageCount - 1
}

sealed interface UiEvent {
    data class OpenReader(val paperId: String) : UiEvent
    data class OpenBrowser(val paperId: String, val url: String, val subtitle: String?) : UiEvent
    data class Message(val text: String, val postUrl: String? = null) : UiEvent
}

data class RefreshState(val loading: Boolean = false, val error: String? = null, val lastNew: Int? = null)

class MainViewModel(private val c: AppContainer, autoRefresh: Boolean = true) : ViewModel() {

    val settings: StateFlow<AppSettings> = c.settings.state

    val papers: StateFlow<List<PaperUi>?> = c.repository.observeAll()
        .map { rows -> rows.map(::toUi) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _refresh = MutableStateFlow(RefreshState())
    val refresh: StateFlow<RefreshState> = _refresh.asStateFlow()

    /** Paper currently being resolved (shows a spinner on its card). */
    private val _resolving = MutableStateFlow<String?>(null)
    val resolving: StateFlow<String?> = _resolving.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { if (autoRefresh) refresh() }

    fun refresh() {
        if (_refresh.value.loading) return
        viewModelScope.launch {
            _refresh.value = _refresh.value.copy(loading = true, error = null)
            _refresh.value = try {
                RefreshState(lastNew = c.repository.refresh())
            } catch (e: IOException) {
                RefreshState(error = if (c.repository.hasData()) "Offline — showing saved editions" else "Couldn't reach the newspaper site. Check your connection.")
            } catch (e: Exception) {
                RefreshState(error = "The site's layout may have changed: ${e.message}")
            }
        }
    }

    /** Read: open a local copy, or resolve the link and get the document. */
    fun open(paperId: String, openAfterDownload: Boolean = true) {
        if (_resolving.value != null) return
        viewModelScope.launch {
            val paper = c.repository.paper(paperId) ?: return@launch
            val dl = c.db.downloads().get(paperId)
            if (dl != null && dl.state != DownloadState.FAILED) {
                if (dl.state == DownloadState.PAUSED) c.downloads.resume(paperId)
                if (openAfterDownload) _events.send(UiEvent.OpenReader(paperId))
                return@launch
            }
            if (paper.status != LinkStatus.AVAILABLE) {
                _events.send(UiEvent.Message(paper.offReason ?: "${paper.title} hasn't been uploaded yet. Pull to refresh later."))
                return@launch
            }
            _resolving.value = paperId
            try {
                when (val r = c.repository.resolve(paper)) {
                    is Resolution.Document -> {
                        c.downloads.enqueue(paperId, r.url, r.headers)
                        _events.send(if (openAfterDownload) UiEvent.OpenReader(paperId) else UiEvent.Message("Downloading ${paper.title}"))
                    }
                    is Resolution.NeedsBrowser -> _events.send(
                        UiEvent.OpenBrowser(paperId, r.url, listOfNotNull(r.fileName, r.sizeLabel).joinToString(" · ").ifEmpty { null })
                    )
                    is Resolution.Unavailable -> _events.send(UiEvent.Message(r.message, paper.postUrl))
                }
            } finally {
                _resolving.value = null
            }
        }
    }

    fun onCaptured(paperId: String, d: CapturedDownload) = viewModelScope.launch {
        c.downloads.enqueue(paperId, d.url, d.headers)
    }

    fun toggleBookmark(id: String) = viewModelScope.launch { c.repository.toggleBookmark(id) }
    fun toggleFavorite(title: String) = viewModelScope.launch { c.repository.toggleFavorite(title) }
    fun pause(id: String) = viewModelScope.launch { c.downloads.pause(id) }
    fun resume(id: String) = viewModelScope.launch { c.downloads.resume(id) }
    fun deleteDownload(id: String) = viewModelScope.launch { c.downloads.delete(id) }
    fun deleteAllDownloads() = viewModelScope.launch { c.downloads.deleteAll() }
    fun clearHistory() = viewModelScope.launch { c.repository.clearHistory() }
    fun storageBytes() = c.files.totalBytes()

    fun updateSettings(t: (AppSettings) -> AppSettings) {
        val before = c.settings.state.value
        c.settings.update(t)
        val after = c.settings.state.value
        if (before.autoSync != after.autoSync) SyncWorker.schedule(c.context, after.autoSync)
    }

    private fun toUi(r: PaperRow): PaperUi {
        val p = r.paper
        val availability = when {
            p.status == LinkStatus.OFF -> Availability.Off(p.offReason ?: "Not published")
            p.status == LinkStatus.PENDING -> Availability.NotUploaded
            p.removedReason != null -> Availability.Removed(p.removedReason)
            else -> Availability.Ready
        }
        val download = when (r.dlState) {
            null -> DownloadUi.None
            DownloadState.QUEUED, DownloadState.RUNNING -> DownloadUi.Active(
                (r.dlTotal ?: -1).let { t -> if (t > 0) (r.dlBytes ?: 0).toFloat() / t else null }, r.dlBytes ?: 0,
                r.dlError ?: if (r.dlState == DownloadState.QUEUED) "Waiting…" else null,
            )
            DownloadState.PAUSED -> DownloadUi.Paused(r.dlBytes ?: 0, r.dlTotal ?: -1)
            DownloadState.COMPLETE -> DownloadUi.Done(r.dlBytes ?: 0)
            DownloadState.FAILED -> DownloadUi.Failed(r.dlError ?: "Download failed")
        }
        return PaperUi(
            id = p.id, title = p.title, code = p.code, subtitle = p.subtitle, category = p.category, language = p.language,
            date = LocalDate.ofEpochDay(p.dateEpochDay), editionId = p.editionId, postUrl = p.postUrl,
            availability = availability, download = download,
            bookmarked = r.bookmarked == true, favorite = r.favorite,
            lastOpenedAt = r.lastOpenedAt, lastPage = r.lastPage ?: 0, pageCount = r.pageCount ?: 0,
            thumb = if (r.dlState == DownloadState.COMPLETE) c.files.thumb(p.id).takeIf { it.exists() } else null,
        )
    }
}
