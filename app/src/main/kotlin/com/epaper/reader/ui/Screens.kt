package com.epaper.reader.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.epaper.core.ExtractionConfig
import com.epaper.reader.data.AppSettings
import com.epaper.reader.data.ReaderLayout
import com.epaper.reader.data.ThemeMode
import com.epaper.reader.reader.formatBytes
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val GRID = GridCells.Adaptive(minSize = 360.dp)
private val PAD = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
private val longDate = DateTimeFormatter.ofPattern("EEEE, d MMMM")

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.cards(list: List<PaperUi>, resolving: String?, actions: CardActions, showDate: Boolean = true) =
    items(list, key = { it.id }) { PaperCard(it, resolving == it.id, actions, showDate = showDate) }

// ---------------------------------------------------------------- Home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    papers: List<PaperUi>?, refresh: RefreshState, resolving: String?, actions: CardActions,
    onRefresh: () -> Unit, onSearch: () -> Unit, onEdition: (LocalDate) -> Unit,
    pending: List<PaperUi> = emptyList(), onFetchAll: () -> Unit = {},
) {
    PullToRefreshBox(isRefreshing = refresh.loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        val all = papers.orEmpty()
        val latestEdition = all.firstOrNull()?.editionId
        val today = all.filter { it.editionId == latestEdition }.sortedByDescending { it.favorite }
        val continueReading = all.filter { it.inProgressReading }.sortedByDescending { it.lastOpenedAt }.take(10)
        val recent = all.filter { it.lastOpenedAt != null }.sortedByDescending { it.lastOpenedAt }.take(12)
        val editions = all.distinctBy { it.editionId }.map { it.date }.drop(1).take(10)

        LazyVerticalGrid(GRID, contentPadding = PAD, horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            full {
                Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Paperstand", style = MaterialTheme.typography.headlineMedium)
                            Text(LocalDate.now().format(longDate), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(onClick = onSearch)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Search newspapers", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    refresh.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (papers == null || (all.isEmpty() && refresh.loading)) {
                full { Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { LinearProgressIndicator() } }
                return@LazyVerticalGrid
            }
            if (all.isEmpty()) {
                full { EmptyState("No editions yet", refresh.error ?: "Pull down to check for today's papers.") }
                return@LazyVerticalGrid
            }
            if (pending.isNotEmpty()) full { FetchBanner(pending, onFetchAll, Modifier.padding(top = 12.dp)) }
            if (continueReading.isNotEmpty()) {
                full { SectionHeader("Continue reading") }
                full { TileRow(continueReading, actions) }
            }
            full {
                val ready = today.count { it.canRead }
                SectionHeader(if (today.first().date == LocalDate.now()) "Today's papers" else "Latest papers",
                    "${today.first().date.short()} · $ready of ${today.size} available")
            }
            cards(today, resolving, actions, showDate = false)
            if (recent.isNotEmpty()) {
                full { SectionHeader("Recently opened") }
                full { TileRow(recent, actions) }
            }
            if (editions.isNotEmpty()) {
                full { SectionHeader("Earlier editions") }
                full {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(editions) { d -> FilterChip(false, onClick = { onEdition(d) }, label = { Text(d.short()) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileRow(list: List<PaperUi>, actions: CardActions) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { p -> PaperTile(p) { actions.onRead(p) } }
    }
}

// ---------------------------------------------------------------- Discover

enum class CategoryFilter(val label: String) { ALL("All"), NEWSPAPER("Newspapers"), EDITORIAL("Editorials") }

/** Pure filtering so it can be unit tested. */
fun filterPapers(
    all: List<PaperUi>, query: String, category: CategoryFilter, date: LocalDate?, availableOnly: Boolean, title: String?,
): List<PaperUi> {
    val q = query.trim()
    return all.filter { p ->
        (category == CategoryFilter.ALL || p.category == category.name.lowercase()) &&
            (date == null || p.date == date) &&
            (!availableOnly || p.canRead) &&
            (title == null || p.title == title) &&
            (q.isEmpty() || p.title.contains(q, true) || p.code.contains(q, true) || p.subtitle.contains(q, true) ||
                p.language?.contains(q, true) == true)
    }
}

@Composable
fun DiscoverScreen(
    papers: List<PaperUi>?, resolving: String?, actions: CardActions, initialDate: LocalDate?, focusSearch: Boolean,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(CategoryFilter.ALL) }
    var dateEpoch by rememberSaveable { mutableLongStateOf(initialDate?.toEpochDay() ?: Long.MIN_VALUE) }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf<String?>(null) }
    var dateMenu by remember { mutableStateOf(false) }
    val date = dateEpoch.takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
    val all = papers.orEmpty()
    val dates = remember(all) { all.map { it.date }.distinct() }
    val titles = remember(all) { all.map { it.title }.distinct().sorted() }
    val results = remember(all, query, category, date, availableOnly, title) {
        filterPapers(all, query, category, date, availableOnly, title)
    }

    LazyVerticalGrid(GRID, contentPadding = PAD, horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        full {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                Text("Discover", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                val focus = remember { FocusRequester() }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    placeholder = { Text("Title, code (TH, IE…), language") },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LaunchedEffect(focusSearch) { if (focusSearch) runCatching { focus.requestFocus() } }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CategoryFilter.entries) { c -> FilterChip(category == c, { category = c }, { Text(c.label) }) }
                    item {
                        Box {
                            FilterChip(date != null, { dateMenu = true }, { Text(date?.short() ?: "Any date") })
                            DropdownMenu(dateMenu, { dateMenu = false }) {
                                DropdownMenuItem({ Text("Any date") }, { dateEpoch = Long.MIN_VALUE; dateMenu = false })
                                dates.forEach { d -> DropdownMenuItem({ Text(d.short()) }, { dateEpoch = d.toEpochDay(); dateMenu = false }) }
                            }
                        }
                    }
                    item { FilterChip(availableOnly, { availableOnly = !availableOnly }, { Text("Readable only") }) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    items(titles) { t -> FilterChip(title == t, { title = if (title == t) null else t }, { Text(t) }) }
                }
                Text("${results.size} papers", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (papers != null && results.isEmpty()) full { EmptyState("Nothing matches", "Try a different search or clear the filters.") }
        results.groupBy { it.date }.forEach { (d, list) ->
            full { SectionHeader(d.short(), "${list.count { it.canRead }} of ${list.size} available") }
            cards(list, resolving, actions, showDate = false)
        }
    }
}

// ---------------------------------------------------------------- Downloads

@Composable
fun DownloadsScreen(
    papers: List<PaperUi>?, storageBytes: Long, onOpen: (PaperUi) -> Unit,
    onPause: (PaperUi) -> Unit, onResume: (PaperUi) -> Unit, onDelete: (PaperUi) -> Unit,
    pending: List<PaperUi> = emptyList(), onFetchAll: () -> Unit = {},
) {
    val list = papers.orEmpty().filter { it.download != DownloadUi.None }
    var confirm by remember { mutableStateOf<PaperUi?>(null) }
    LazyVerticalGrid(GRID, contentPadding = PAD, horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        full {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium)
                Text("${list.count { it.download is DownloadUi.Done }} saved · ${formatBytes(storageBytes)} on this device",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (pending.isNotEmpty()) full { FetchBanner(pending, onFetchAll, Modifier.padding(top = 4.dp)) }
        if (list.isEmpty() && pending.isEmpty()) full { EmptyState("No downloads", "New papers are saved here automatically for offline reading.") }
        items(list, key = { it.id }) { p ->
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(p) }) {
                Column {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(p, 44.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.date.short(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            StatusLine(p)
                        }
                        when (p.download) {
                            is DownloadUi.Active -> IconButton(onClick = { onPause(p) }) { Icon(Icons.Filled.Pause, "Pause") }
                            is DownloadUi.Paused, is DownloadUi.Failed -> IconButton(onClick = { onResume(p) }) { Icon(Icons.Filled.PlayArrow, "Resume") }
                            else -> {}
                        }
                        IconButton(onClick = { if (p.download is DownloadUi.Done) confirm = p else onDelete(p) }) {
                            Icon(if (p.download is DownloadUi.Done) Icons.Filled.Delete else Icons.Filled.Close,
                                if (p.download is DownloadUi.Done) "Delete" else "Cancel")
                        }
                    }
                    (p.download as? DownloadUi.Active)?.let { d ->
                        if (d.fraction != null) LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth().height(2.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    }
                }
            }
        }
    }
    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Delete ${p.title}?") },
            text = { Text("The saved copy from ${p.date.short()} will be removed from this device.") },
            confirmButton = { TextButton(onClick = { onDelete(p); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } })
    }
}

// ---------------------------------------------------------------- Library (bookmarks, favourites, history)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(papers: List<PaperUi>?, resolving: String?, actions: CardActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val all = papers.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text("Library", style = MaterialTheme.typography.headlineMedium)
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Bookmarks", "Favourites", "History").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        val list = when (tab) {
            0 -> all.filter { it.bookmarked }
            1 -> all.filter { it.favorite }.distinctBy { it.title }.let { latest ->
                // newest issue of each favourite publication, plus the readable back issues
                latest + all.filter { it.favorite && it.canRead && it !in latest }
            }
            else -> all.filter { it.lastOpenedAt != null }.sortedByDescending { it.lastOpenedAt }
        }
        LazyVerticalGrid(GRID, contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            if (list.isEmpty()) full {
                when (tab) {
                    0 -> EmptyState("No bookmarks", "Tap the bookmark on any paper to keep it here.")
                    1 -> EmptyState("No favourites", "Use ⋮ → Favourite on a paper to follow that publication; its new issues appear first on Home.")
                    else -> EmptyState("No reading history", "Papers you open appear here. History never leaves your device.")
                }
            }
            if (tab == 1 && list.isNotEmpty()) full {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(" ${list.map { it.title }.distinct().joinToString()}", style = MaterialTheme.typography.labelLarge)
                }
            }
            cards(list, resolving, actions)
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(
    settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, storageBytes: Long, config: ExtractionConfig,
    onDeleteAll: () -> Unit, onClearHistory: () -> Unit, onOpenSource: () -> Unit,
    publications: List<String> = emptyList(),
) {
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
        }
        item { Group("Appearance") }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(settings.theme == m, { update { it.copy(theme = m) } },
                            SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) {
                            Text(m.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) item {
            Toggle("Wallpaper colours", "Use Material You colours from your wallpaper", settings.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }
        }
        item { Group("Reading") }
        item {
            Toggle("Page by page", "Swipe horizontally between pages instead of scrolling", settings.readerLayout == ReaderLayout.HORIZONTAL) { v ->
                update { it.copy(readerLayout = if (v) ReaderLayout.HORIZONTAL else ReaderLayout.VERTICAL) }
            }
        }
        item { Toggle("Fit whole page", "Otherwise pages fit the screen width", settings.fitPage) { v -> update { it.copy(fitPage = v) } } }
        item { Toggle("Dark pages", "Invert page colours for night reading", settings.readerDark) { v -> update { it.copy(readerDark = v) } } }
        item { Toggle("Keep screen on while reading", null, settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } } }
        item { Group("Auto-download") }
        item {
            Toggle("Download new papers automatically", "The two newest editions are kept on this device. Papers you stop or delete are not fetched again.",
                settings.autoDownload) { v -> update { it.copy(autoDownload = v) } }
        }
        if (settings.autoDownload) items(publications) { title ->
            val on = title !in settings.excludedTitles
            Toggle(title, if (on) null else "Paused", on) { v ->
                update { it.copy(excludedTitles = if (v) it.excludedTitles - title else it.excludedTitles + title) }
            }
        }
        item { Group("Updates") }
        item { Toggle("Check for new editions in the background", "Every few hours, on any network", settings.autoSync) { v -> update { it.copy(autoSync = v) } } }
        item { Group("Storage & privacy") }
        item {
            ListItem(headlineContent = { Text("Downloaded papers") }, supportingContent = { Text("${formatBytes(storageBytes)} used") },
                trailingContent = { TextButton(onClick = { confirmDelete = true }) { Text("Delete all") } })
        }
        item {
            ListItem(headlineContent = { Text("Reading history") },
                supportingContent = { Text("Stored only on this device. No account, analytics or tracking.") },
                trailingContent = { TextButton(onClick = onClearHistory) { Text("Clear") } })
        }
        item { Group("About") }
        item {
            ListItem(
                headlineContent = { Text("Source") },
                supportingContent = {
                    Text("Editions are listed from ${config.blogBaseUrl.removePrefix("https://")}. Newspapers belong to their " +
                        "publishers; files stay on this device and are not redistributed.")
                },
                trailingContent = { TextButton(onClick = onOpenSource) { Text("Open") } },
            )
        }
        item {
            ListItem(headlineContent = { Text("Extraction rules") },
                supportingContent = { Text("Version ${config.version} · ${config.rules.joinToString { it.name }}") })
        }
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete all downloads?") },
            text = { Text("Saved papers will need to be downloaded again to read them.") },
            confirmButton = { TextButton(onClick = { onDeleteAll(); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
}

@Composable
private fun Group(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
