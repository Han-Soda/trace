package io.github.hansoda.trace.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.hansoda.trace.render.PhotoSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Decodes the kept photos and clip frames for drawing, keeping the recent ones in memory. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoStore(private val library: MediaLibrary) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val frames = object : LruCache<String, Bitmap>(memoryBudgetKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val thumbnails = LruCache<String, Bitmap>(THUMBNAILS)
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _version = MutableStateFlow(0)

    /** Increases whenever a frame arrives for the preview, so it can redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** For the preview: frames load in the background and appear when ready. */
    val live: PhotoSource = object : PhotoSource {
        override fun frame(id: String, index: Int): Bitmap? {
            val key = key(id, index)
            frames.get(key)?.let { return it }
            if (loading.add(key)) {
                scope.launch {
                    try {
                        decode(library.frameFile(id, index).path)?.let {
                            frames.put(key, it)
                            _version.update { version -> version + 1 }
                        }
                    } finally {
                        loading.remove(key)
                    }
                }
            }
            return null
        }

        override fun thumbnail(id: String): Bitmap? = thumbnailNow(id)
    }

    /** For exports: every frame is there when asked for. */
    val now: PhotoSource = object : PhotoSource {
        override fun frame(id: String, index: Int): Bitmap? {
            val key = key(id, index)
            return frames.get(key) ?: decode(library.frameFile(id, index).path)?.also { frames.put(key, it) }
        }

        override fun thumbnail(id: String): Bitmap? = thumbnailNow(id)
    }

    fun thumbnailNow(id: String): Bitmap? =
        thumbnails.get(id) ?: decode(library.thumbnailFile(id).path)?.also { thumbnails.put(id, it) }

    /** Lets go of a removed photo or clip. */
    fun forget(id: String) {
        thumbnails.remove(id)
        for (key in frames.snapshot().keys) if (key.startsWith("$id/")) frames.remove(key)
    }

    private fun decode(path: String): Bitmap? = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()

    private fun key(id: String, index: Int) = "$id/$index"

    private companion object {
        const val THUMBNAILS = 200

        fun memoryBudgetKb(): Int = (minOf(Runtime.getRuntime().maxMemory() / 8, 48L shl 20) / 1024).toInt()
    }
}
