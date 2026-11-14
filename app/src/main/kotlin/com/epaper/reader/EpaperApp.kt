package com.epaper.reader

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.epaper.core.DiscoveryService
import com.epaper.core.ExtractionConfig
import com.epaper.core.LinkResolver
import com.epaper.core.defaultHttpClient
import com.epaper.reader.data.AppDatabase
import com.epaper.reader.data.PaperRepository
import com.epaper.reader.data.SettingsStore
import com.epaper.reader.storage.DownloadController
import com.epaper.reader.storage.PaperFiles
import com.epaper.reader.sync.SyncWorker
import okhttp3.Cache
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency wiring; the graph is small enough that a DI framework would only add weight. */
class AppContainer(context: Context) {
    val context: Context = context.applicationContext
    val config = ExtractionConfig.default()
    val http = defaultHttpClient().newBuilder()
        .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024))
        .build()
    /** Downloads follow redirects (CDNs) and may take long; the resolver has already vetted the URL. */
    val downloadHttp = http.newBuilder()
        .followRedirects(true).followSslRedirects(true)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
        .build()
    val db = AppDatabase.create(context)
    val settings = SettingsStore(context)
    val files = PaperFiles(context)
    val resolver = LinkResolver(http, config)
    val repository = PaperRepository(db, DiscoveryService(http, config), resolver)
    val downloads = DownloadController(context, db.downloads(), files)
}

class EpaperApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedule(this, container.settings.state.value.autoSync)
    }
}
