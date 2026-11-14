package com.yomu.reader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.yomu.reader.data.BackupWorker
import com.yomu.reader.data.Bookmarks
import com.yomu.reader.data.CrashLog
import com.yomu.reader.data.DownloadService
import com.yomu.reader.data.Downloads
import com.yomu.reader.data.Library
import com.yomu.reader.data.Prefs
import com.yomu.reader.data.UpdateWorker
import com.yomu.reader.extension.Extensions
import com.yomu.reader.net.Http
import com.yomu.reader.tachi.TachiExtensions
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

class YomuApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
        Prefs.init(this)
        setupTachiyomiApi()
        TachiExtensions.init(this)
        Extensions.init(this) // avant Library : les séries des extensions doivent retrouver leur source
        Library.init(this)
        Bookmarks.init(this)
        Downloads.init(this)
        UpdateWorker.createChannel(this)
        runCatching { UpdateWorker.schedule(this) }
        DownloadService.createChannel(this)
        runCatching { BackupWorker.schedule(this) }
    }

    /** Objets que les extensions Tachiyomi/Mihon demandent à l'appli (via Injekt). */
    @OptIn(ExperimentalSerializationApi::class)
    private fun setupTachiyomiApi() {
        val app = this
        Injekt.addSingleton<Application>(app)
        Injekt.addSingleton<Context>(app)
        Injekt.addSingletonFactory { NetworkHelper(app) }
        Injekt.addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }

    companion object {
        lateinit var instance: YomuApplication
            private set
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Http.client)
            .crossfade(150)
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(768L * 1024 * 1024)
                    .build()
            }
            .build()
}
