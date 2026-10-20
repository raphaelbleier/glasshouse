package io.github.raphaelbleier.glasshouse

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class TvApp : Application(), ImageLoaderFactory {
    // Plex transcode responses aren't cacheable by their headers, so cache them regardless
    override fun newImageLoader() = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.3).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(400L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()
}
