package com.epaper.reader.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epaper.reader.reader.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM")
fun LocalDate.short(): String = when (this) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> format(dayFmt)
}

/** Stable, muted colour per publication code so each paper is recognisable at a glance. */
private val swatches = listOf(
    0xFF1F3A5F, 0xFF8C2F2B, 0xFF2E6B4F, 0xFF6B4E1F, 0xFF4B3B6E, 0xFF1F5A63, 0xFF7A3E5C, 0xFF3F4A2A, 0xFF5C4A3D, 0xFF2D4F7C,
)
fun swatch(code: String) = Color(swatches[(code.hashCode() and 0x7fffffff) % swatches.size])

@Composable
fun Cover(paper: PaperUi, width: Dp, modifier: Modifier = Modifier) {
    val thumb by produceState<ImageBitmap?>(null, paper.thumb) {
        value = paper.thumb?.let { f -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path)?.asImageBitmap() } }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.size(width, width * 1.32f).clip(shape).background(swatch(paper.code)), contentAlignment = Alignment.Center) {
        val t = thumb
        if (t != null) {
            Image(t, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(paper.code.ifEmpty { paper.title.take(2) }, color = Color.White, fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold, fontSize = (width.value / 3.2f).sp, maxLines = 1)
                Box(Modifier.padding(top = 4.dp).width(width * 0.5f).height(1.dp).background(Color.White.copy(alpha = .5f)))
            }
        }
    }
}

@Composable
fun StatusLine(paper: PaperUi) {
    val (text, color) = when (val d = paper.download) {
        is DownloadUi.Done -> "Saved · ${formatBytes(d.bytes)}" to MaterialTheme.colorScheme.tertiary
        is DownloadUi.Active -> (d.note ?: (d.fraction?.let { "Downloading ${(it * 100).toInt()}%" } ?: "Downloading ${formatBytes(d.bytes)}")) to MaterialTheme.colorScheme.primary
        is DownloadUi.Paused -> "Paused · ${formatBytes(d.bytes)}" to MaterialTheme.colorScheme.onSurfaceVariant
        is DownloadUi.Failed -> d.message to MaterialTheme.colorScheme.error
        DownloadUi.None -> when (val a = paper.availability) {
            Availability.Ready -> "Available" to MaterialTheme.colorScheme.tertiary
            Availability.NotUploaded -> "Not uploaded yet" to MaterialTheme.colorScheme.onSurfaceVariant
            is Availability.Off -> a.reason to MaterialTheme.colorScheme.onSurfaceVariant
            is Availability.Removed -> "Removed from host" to MaterialTheme.colorScheme.error
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (paper.download is DownloadUi.Done) Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp).padding(end = 2.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

data class CardActions(
    val onRead: (PaperUi) -> Unit,
    val onBookmark: (PaperUi) -> Unit,
    val onFavorite: (PaperUi) -> Unit,
    val onDownload: (PaperUi) -> Unit,
    val onDelete: (PaperUi) -> Unit,
    val onOriginal: (PaperUi) -> Unit,
    val onToggleAuto: (PaperUi) -> Unit = {},
    val isAutoPaused: (String) -> Boolean = { false },
)

@Composable
fun PaperCard(paper: PaperUi, resolving: Boolean, actions: CardActions, modifier: Modifier = Modifier, showDate: Boolean = true) {
    val dim = !paper.canRead
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = paper.canRead && !resolving) { actions.onRead(paper) },
    ) {
        Column {
            Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Cover(paper, 46.dp)
                    if (dim) Box(Modifier.size(46.dp, 46.dp * 1.32f).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .55f)))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(paper.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (paper.favorite) Icon(Icons.Filled.Star, "Favourite", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                    }
                    Text(
                        listOfNotNull(paper.subtitle.ifEmpty { null }, if (showDate) paper.date.short() else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    StatusLine(paper)
                }
                IconButton(onClick = { actions.onBookmark(paper) }, modifier = Modifier.size(40.dp)) {
                    Icon(if (paper.bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        if (paper.bookmarked) "Remove bookmark" else "Bookmark",
                        tint = if (paper.bookmarked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (resolving) {
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                } else {
                    FilledTonalButton(
                        onClick = { actions.onRead(paper) }, enabled = paper.canRead,
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) { Text("Read") }
                }
                CardMenu(paper, actions)
            }
            (paper.download as? DownloadUi.Active)?.let { d ->
                if (d.fraction != null) LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth().height(2.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
}

@Composable
private fun CardMenu(paper: PaperUi, actions: CardActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.width(32.dp)) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (paper.download == DownloadUi.None && paper.availability == Availability.Ready) {
                DropdownMenuItem({ Text("Download for offline") }, onClick = { open = false; actions.onDownload(paper) })
            }
            if (paper.download != DownloadUi.None) {
                DropdownMenuItem({ Text("Delete download") }, onClick = { open = false; actions.onDelete(paper) })
            }
            DropdownMenuItem({ Text(if (paper.favorite) "Unfavourite ${paper.title}" else "Favourite ${paper.title}") },
                onClick = { open = false; actions.onFavorite(paper) })
            DropdownMenuItem(
                { Text(if (actions.isAutoPaused(paper.title)) "Resume auto-download of ${paper.title}" else "Pause auto-download of ${paper.title}") },
                onClick = { open = false; actions.onToggleAuto(paper) },
            )
            DropdownMenuItem({ Text("Open original post") }, onClick = { open = false; actions.onOriginal(paper) })
        }
    }
}

/** Compact tile for horizontal "continue reading" / "recent" rows. */
@Composable
fun PaperTile(paper: PaperUi, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Cover(paper, 104.dp)
        Spacer(Modifier.height(6.dp))
        Text(paper.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (paper.inProgressReading) "p. ${paper.lastPage + 1} of ${paper.pageCount}" else paper.date.short(),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (paper.inProgressReading) {
            LinearProgressIndicator(progress = { (paper.lastPage + 1f) / paper.pageCount },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)))
        }
    }
}

/** Shown while new papers wait on a file host that needs its check run on screen. */
@Composable
fun FetchBanner(pending: List<PaperUi>, onFetchAll: () -> Unit, modifier: Modifier = Modifier) {
    if (pending.isEmpty()) return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${pending.size} new ${if (pending.size == 1) "paper" else "papers"} to save",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(pending.map { it.code.ifEmpty { it.title } }.distinct().joinToString() +
                    " · the file host runs a short check on screen, then everything saves by itself",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Button(onClick = onFetchAll) { Text("Fetch all") }
        }
    }
}

@Composable
fun SectionHeader(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { Spacer(Modifier.height(16.dp)); it() }
    }
}
