package com.yomu.reader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.yomu.reader.data.Downloads
import com.yomu.reader.data.Library
import com.yomu.reader.data.Prefs
import com.yomu.reader.data.UpdateWorker
import com.yomu.reader.net.Http

class YomuApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Library.init(this)
        Downloads.init(this)
        UpdateWorker.createChannel(this)
        runCatching { UpdateWorker.schedule(this) }
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
