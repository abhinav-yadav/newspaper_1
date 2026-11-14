package com.epaper.core

import java.time.LocalDate

/** One Blogger post = one day's set of papers. */
data class Edition(
    val id: String,
    val title: String,
    val publishedAtMillis: Long,
    /** Calendar date of the papers in the publisher's time zone (posts go up the evening before, US time). */
    val date: LocalDate,
    val url: String,
    val papers: List<Paper>,
)

data class Paper(
    /** Stable across refreshes: `<postId>-<position>`. */
    val id: String,
    val editionId: String,
    val position: Int,
    val title: String,
    val code: String,
    val subtitle: String,
    /** `newspaper` or `editorial` (site's data-category), lower-case. */
    val category: String,
    val language: String?,
    val status: PaperStatus,
)

sealed interface PaperStatus {
    data class Available(val url: String) : PaperStatus
    /** The post exists but this paper's link has not been filled in yet. */
    data object Pending : PaperStatus
    /** Deliberately not published, e.g. "Sunday Off". */
    data class Off(val reason: String) : PaperStatus
}

/** Outcome of turning a paper link into something readable. Never a guess. */
sealed interface Resolution {
    /** A URL verified to start with `%PDF-`. [headers] must be sent when downloading it. */
    data class Document(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val fileName: String? = null,
    ) : Resolution

    /** The host requires a real browser (captcha/proof-of-work); the app opens it in its sandboxed browser. */
    data class NeedsBrowser(
        val url: String,
        val rule: HostRule?,
        val fileName: String? = null,
        val sizeLabel: String? = null,
    ) : Resolution

    data class Unavailable(val reason: Reason, val message: String) : Resolution

    enum class Reason { REMOVED, NOT_A_PDF, NETWORK, REDIRECT_LOOP, NOT_PUBLISHED }
}
