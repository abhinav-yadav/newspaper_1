package com.epaper.reader

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.epaper.core.EditionParser
import com.epaper.core.ExtractionConfig
import com.epaper.reader.data.DownloadEntity
import com.epaper.reader.data.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Compose's frame dispatcher is static and binds to the first test's Choreographer, so only one UI test
 * per JVM is reliable under Robolectric. Each scenario is therefore its own class (Gradle forks per class).
 *
 * Renders the real app (real database, real navigation) with fixture data on a phone and a tablet,
 * checks key content is present, and writes PNGs to app/build/screenshots for review.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotBase {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected val c = ApplicationProvider.getApplicationContext<EpaperApp>().container

    @Before fun seed() = runBlocking {
        val parser = EditionParser(ExtractionConfig.default())
        val feed = parser.parseFeed(String(res("/feed.json")))
        c.repository.store(feed.posts.mapNotNull { parser.parseEdition(it) })
        val rows = c.repository.observeAll().first()
        val th = rows[0].paper.id; val ie = rows[1].paper.id; val bl = rows[3].paper.id
        c.db.downloads().upsert(DownloadEntity(th, "https://x/a.pdf", state = DownloadState.COMPLETE, downloadedBytes = 18_400_000, totalBytes = 18_400_000))
        c.db.downloads().upsert(DownloadEntity(ie, "https://x/b.pdf", state = DownloadState.RUNNING, downloadedBytes = 9_000_000, totalBytes = 21_000_000))
        c.repository.toggleBookmark(bl)
        c.repository.markOpened(th); c.repository.savePosition(th, 6, 22)
        c.repository.toggleFavorite("Financial E. Paper")
    }

    /** Real-time wait: Room emits on its own executor threads, so let them run and drain the main looper. */
    protected fun awaitText(text: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isEmpty()) {
            if (System.currentTimeMillis() > deadline) { shot("debug_missing_" + text.filter(Char::isLetter))
                error("'$text' never appeared") }
            Thread.sleep(50)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            compose.waitForIdle()
        }
    }

    protected fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@RunWith(AndroidJUnit4::class)
class PhoneScreenshotTest : ScreenshotBase() {
    @Test @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun phone() {
        compose.setContent { App(c, autoRefresh = false) }
        awaitText("Continue reading")
        shot("phone_1_home")
        compose.onAllNodesWithText("Not uploaded yet").onFirst().assertExists()
        compose.onNodeWithText("Discover").performClick(); shot("phone_2_discover")
        compose.onNodeWithText("Downloads").performClick()
        awaitText("Saved · 18.4 MB")
        shot("phone_3_downloads")
        compose.onNodeWithText("Bookmarks").performClick(); shot("phone_4_library")
        compose.onNodeWithText("Settings").performClick(); shot("phone_5_settings")
    }
}

@RunWith(AndroidJUnit4::class)
class TabletScreenshotTest : ScreenshotBase() {
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tablet() {
        compose.setContent { App(c, autoRefresh = false) }
        awaitText("Latest papers") // fixture's newest edition is not today's
        shot("tablet_home")
    }
}

@RunWith(AndroidJUnit4::class)
class DarkPhoneScreenshotTest : ScreenshotBase() {
    @Test @Config(qualifiers = "w400dp-h860dp-night-xhdpi")
    fun phoneDark() {
        compose.setContent { App(c, autoRefresh = false) }
        awaitText("Continue reading")
        shot("phone_home_dark")
    }
}
