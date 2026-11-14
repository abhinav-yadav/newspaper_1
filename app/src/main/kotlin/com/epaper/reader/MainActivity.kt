package com.epaper.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.epaper.reader.browser.AssistedBrowserScreen
import com.epaper.reader.reader.ReaderScreen
import com.epaper.reader.reader.ReaderViewModel
import com.epaper.reader.ui.CardActions
import com.epaper.reader.ui.DiscoverScreen
import com.epaper.reader.ui.DownloadsScreen
import com.epaper.reader.ui.EpaperTheme
import com.epaper.reader.ui.HomeScreen
import com.epaper.reader.ui.LibraryScreen
import com.epaper.reader.ui.MainViewModel
import com.epaper.reader.ui.SettingsScreen
import com.epaper.reader.ui.UiEvent
import java.net.URLEncoder
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EpaperApp).container
        setContent { App(container) }
    }
}

private inline fun <reified T : ViewModel> factory(crossinline create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Newspaper),
    DISCOVER("discover", "Discover", Icons.Filled.Explore),
    DOWNLOADS("downloads", "Downloads", Icons.Filled.Download),
    LIBRARY("library", "Bookmarks", Icons.Filled.Bookmarks),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

@Composable
fun App(container: AppContainer, autoRefresh: Boolean = true) {
    val vm: MainViewModel = viewModel(factory = factory { MainViewModel(container, autoRefresh) })
    val settings by vm.settings.collectAsState()
    EpaperTheme(settings.theme, settings.dynamicColor) {
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val context = LocalContext.current
        val papers by vm.papers.collectAsState()
        val refresh by vm.refresh.collectAsState()
        val resolving by vm.resolving.collectAsState()
        val pending by vm.pendingFetch.collectAsState()
        val fetch by vm.fetch.collectAsState()

        fun openExternal(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

        LaunchedEffect(Unit) {
            vm.events.collect { e ->
                when (e) {
                    is UiEvent.OpenReader -> nav.navigate("reader/${e.paperId}")
                    is UiEvent.OpenBrowser -> nav.navigate("browser/${e.paperId}?url=${enc(e.url)}&sub=${enc(e.subtitle ?: "")}")
                    UiEvent.FetchFinished -> {
                        if (nav.currentDestination?.route == "fetch") nav.popBackStack()
                        nav.goTab(Tab.DOWNLOADS)
                    }
                    is UiEvent.Message -> {
                        val r = snackbar.showSnackbar(e.text, actionLabel = if (e.postUrl != null) "Open post" else null,
                            withDismissAction = true, duration = SnackbarDuration.Long)
                        if (r == SnackbarResult.ActionPerformed && e.postUrl != null) {
                            nav.navigate("browser/none?url=${enc(e.postUrl)}&sub=${enc("Original post")}")
                        }
                    }
                }
            }
        }

        val actions = CardActions(
            onRead = { vm.open(it.id) },
            onBookmark = { vm.toggleBookmark(it.id) },
            onFavorite = { vm.toggleFavorite(it.title) },
            onDownload = { vm.open(it.id, openAfterDownload = false) },
            onDelete = { vm.deleteDownload(it.id) },
            onOriginal = { nav.navigate("browser/${it.id}?url=${enc(it.postUrl)}&sub=${enc("Original post")}") },
            onToggleAuto = { vm.toggleAutoDownload(it.title) },
            isAutoPaused = { it in settings.excludedTitles },
        )
        val fetchAll = { vm.startFetchAll(); nav.navigate("fetch") }

        val backStack by nav.currentBackStackEntryAsState()
        val route = backStack?.destination?.route.orEmpty()
        val tab = Tab.entries.firstOrNull { route.startsWith(it.route) }
        val wide = LocalConfiguration.current.screenWidthDp >= 600

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (tab != null && !wide) BottomBar(nav, tab) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
                if (tab != null && wide) Rail(nav, tab)
                NavHost(nav, startDestination = Tab.HOME.route, modifier = Modifier.weight(1f)) {
                    composable(Tab.HOME.route) {
                        HomeScreen(papers, refresh, resolving, actions, onRefresh = vm::refresh,
                            onSearch = { nav.navigate("discover?focus=true") { launchSingleTop = true } },
                            onEdition = { d -> nav.navigate("discover?date=${d.toEpochDay()}") { launchSingleTop = true } },
                            pending = pending, onFetchAll = fetchAll)
                    }
                    composable(
                        "discover?date={date}&focus={focus}",
                        arguments = listOf(
                            navArgument("date") { type = NavType.LongType; defaultValue = Long.MIN_VALUE },
                            navArgument("focus") { type = NavType.BoolType; defaultValue = false },
                        ),
                    ) { entry ->
                        val d = entry.arguments?.getLong("date")?.takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
                        DiscoverScreen(papers, resolving, actions, d, entry.arguments?.getBoolean("focus") == true)
                    }
                    composable(Tab.DOWNLOADS.route) {
                        DownloadsScreen(papers, remember(papers) { vm.storageBytes() },
                            onOpen = { vm.open(it.id) }, onPause = { vm.pause(it.id) }, onResume = { if (it.download is com.epaper.reader.ui.DownloadUi.Failed) vm.open(it.id, false) else vm.resume(it.id) },
                            onDelete = { vm.deleteDownload(it.id) }, pending = pending, onFetchAll = fetchAll)
                    }
                    composable(Tab.LIBRARY.route) { LibraryScreen(papers, resolving, actions) }
                    composable(Tab.SETTINGS.route) {
                        SettingsScreen(settings, vm::updateSettings, remember(papers) { vm.storageBytes() }, container.config,
                            onDeleteAll = vm::deleteAllDownloads, onClearHistory = vm::clearHistory,
                            onOpenSource = { openExternal(container.config.blogBaseUrl) },
                            publications = remember(papers) { papers.orEmpty().map { it.title }.distinct().sorted() })
                    }
                    composable("fetch") {
                        val f = fetch
                        if (f == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else if (f.url == null) {
                            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text("Checking ${f.title} (${f.index + 1} of ${f.total})…", Modifier.padding(top = 16.dp))
                                TextButton(onClick = { vm.stopFetch(); nav.popBackStack() }) { Text("Stop") }
                            }
                        } else key(f.paperId) {
                            AssistedBrowserScreen(
                                startUrl = f.url, title = f.title, subtitle = f.subtitle,
                                config = container.config, http = container.http,
                                onCaptured = { d -> vm.fetchCaptured(f.paperId, d) },
                                onClose = { vm.stopFetch(); nav.popBackStack() },
                                queueLabel = "${f.index + 1} of ${f.total}",
                                onSkip = { vm.skipFetch() },
                            )
                        }
                    }
                    composable("reader/{id}") { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val rvm: ReaderViewModel = viewModel(key = "reader-$id", factory = factory { ReaderViewModel(id, container) })
                        ReaderScreen(rvm, settings, vm::updateSettings, onBack = { nav.popBackStack() },
                            onOpenOriginal = { url -> nav.navigate("browser/$id?url=${enc(url)}&sub=${enc("Original post")}") })
                    }
                    composable(
                        "browser/{id}?url={url}&sub={sub}",
                        arguments = listOf(
                            navArgument("url") { type = NavType.StringType },
                            navArgument("sub") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val url = entry.arguments?.getString("url").orEmpty()
                        val title = papers?.firstOrNull { it.id == id }?.title ?: "Original post"
                        AssistedBrowserScreen(
                            startUrl = url, title = title, subtitle = entry.arguments?.getString("sub")?.ifEmpty { null },
                            config = container.config, http = container.http,
                            onCaptured = { d ->
                                if (id == "none") return@AssistedBrowserScreen
                                vm.onCaptured(id, d)
                                nav.navigate("reader/$id") { popUpTo(entry.destination.id) { inclusive = true } }
                            },
                            onClose = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

private fun NavHostController.goTab(t: Tab) = navigate(t.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(nav: NavHostController, current: Tab) {
    NavigationBar {
        Tab.entries.forEach { t ->
            NavigationBarItem(current == t, { nav.goTab(t) }, { Icon(t.icon, null) }, label = { Text(t.label, maxLines = 1, softWrap = false) })
        }
    }
}

@Composable
private fun Rail(nav: NavHostController, current: Tab) {
    NavigationRail {
        Tab.entries.forEach { t ->
            NavigationRailItem(current == t, { nav.goTab(t) }, { Icon(t.icon, null) }, label = { Text(t.label) })
        }
    }
}
