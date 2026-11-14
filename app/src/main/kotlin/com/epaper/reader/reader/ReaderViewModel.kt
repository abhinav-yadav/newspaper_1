package com.epaper.reader.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.epaper.reader.AppContainer
import com.epaper.reader.data.DownloadState
import com.epaper.reader.data.LibraryEntity
import com.epaper.reader.data.PaperEntity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface ReaderState {
    /** Waiting for the file; [progress] is 0..1, or null when the size is unknown. */
    data class Downloading(val progress: Float?, val bytes: Long, val message: String? = null) : ReaderState
    data class Ready(val doc: PdfDocument, val initialPage: Int) : ReaderState
    data class Failed(val message: String) : ReaderState
}

@OptIn(FlowPreview::class)
class ReaderViewModel(private val paperId: String, private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Downloading(null, 0))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    val paper: StateFlow<PaperEntity?> = c.repository.observePaper(paperId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val library: StateFlow<LibraryEntity?> = c.repository.observeLibrary(paperId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val pageChanges = MutableStateFlow<Int?>(null)
    private var doc: PdfDocument? = null

    init {
        viewModelScope.launch { c.repository.markOpened(paperId) }
        c.db.downloads().observe(paperId).onEach { d ->
            if (doc != null) return@onEach
            when (d?.state) {
                DownloadState.COMPLETE -> open(File(d.filePath ?: c.files.pdf(paperId).path))
                DownloadState.FAILED -> _state.value = ReaderState.Failed(d.error ?: "The download failed")
                DownloadState.PAUSED -> _state.value = ReaderState.Downloading(fraction(d.downloadedBytes, d.totalBytes), d.downloadedBytes, "Paused")
                DownloadState.QUEUED, DownloadState.RUNNING ->
                    _state.value = ReaderState.Downloading(fraction(d.downloadedBytes, d.totalBytes), d.downloadedBytes, d.error)
                null -> {
                    val f = c.files.pdf(paperId)
                    if (f.exists()) open(f) else _state.value = ReaderState.Failed("This paper is not downloaded")
                }
            }
        }.launchIn(viewModelScope)

        pageChanges.filterNotNull().distinctUntilChanged().debounce(400).onEach { page ->
            c.repository.savePosition(paperId, page, doc?.pageCount ?: 0)
        }.launchIn(viewModelScope)
    }

    private suspend fun open(file: File) {
        if (doc != null) return
        try {
            val d = PdfDocument.open(file)
            doc = d
            val last = c.db.library().get(paperId)?.lastPage ?: 0
            _state.value = ReaderState.Ready(d, last.coerceIn(0, (d.pageCount - 1).coerceAtLeast(0)))
        } catch (e: Exception) {
            _state.value = ReaderState.Failed("This file can't be opened as a PDF (${e.javaClass.simpleName})")
        }
    }

    fun onPage(page: Int) { pageChanges.value = page }
    fun toggleBookmark() = viewModelScope.launch { c.repository.toggleBookmark(paperId) }
    fun resume() = viewModelScope.launch { c.downloads.resume(paperId) }

    override fun onCleared() { doc?.close() }

    private fun fraction(done: Long, total: Long) = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}
