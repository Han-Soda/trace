package io.github.hansoda.trace.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.hansoda.trace.render.PhotoSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(3))
    private val preview = frameCache()
    private val export = frameCache()
    private val thumbnails = LruCache<String, Bitmap>(THUMBNAILS)
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _version = MutableStateFlow(0)

    /** Increases whenever a frame arrives for the preview, so it can redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /**
     * For the preview: frames load in the background and appear when ready. A clip's next
     * frames load ahead of time, and until one is there the latest loaded one stays up, so a
     * clip plays on rather than stutter back to its start.
     */
    val live: PhotoSource = object : PhotoSource {
        override fun frame(id: String, index: Int, frames: Int): Bitmap? {
            val wanted = preview.get(key(id, index))
            for (k in index until minOf(frames, index + AHEAD)) load(id, k, frames)
            if (wanted != null) return wanted
            for (k in index - 1 downTo max(0, index - BEHIND)) preview.get(key(id, k))?.let { return it }
            return null
        }

        override fun thumbnail(id: String): Bitmap? = thumbnailNow(id)
    }

    /** For exports: every frame is there when asked for, at full size. */
    val now: PhotoSource = object : PhotoSource {
        override fun frame(id: String, index: Int, frames: Int): Bitmap? {
            val key = key(id, index)
            return export.get(key) ?: decode(library.frameFile(id, index).path, 1)?.also { export.put(key, it) }
        }

        override fun thumbnail(id: String): Bitmap? = thumbnailNow(id)
    }

    /** Clips' sound, for exports. */
    val sounds = SoundSource { id -> Sound.read(library.soundFile(id)) }

    fun thumbnailNow(id: String): Bitmap? =
        thumbnails.get(id) ?: decode(library.thumbnailFile(id).path, 1)?.also { thumbnails.put(id, it) }

    /** Lets go of a removed photo or clip, or one whose frames changed. */
    fun forget(id: String) {
        thumbnails.remove(id)
        for (cache in listOf(preview, export)) {
            for (key in cache.snapshot().keys) if (key.startsWith("$id/")) cache.remove(key)
        }
    }

    private fun load(id: String, index: Int, frames: Int) {
        val key = key(id, index)
        if (preview.get(key) != null || !loading.add(key)) return
        scope.launch {
            try {
                // A photo is drawn smaller in the preview than in a video; half its size is plenty.
                decode(library.frameFile(id, index).path, if (frames == 1) 2 else 1)?.let {
                    preview.put(key, it)
                    _version.update { version -> version + 1 }
                }
            } finally {
                loading.remove(key)
            }
        }
    }

    private fun decode(path: String, sample: Int): Bitmap? =
        runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()

    private fun key(id: String, index: Int) = "$id/$index"

    private companion object {
        const val THUMBNAILS = 200

        /** Clip frames loaded ahead of the one on screen, and looked back over while one is missing. */
        const val AHEAD = 12
        const val BEHIND = 8

        fun frameCache() = object : LruCache<String, Bitmap>(memoryBudgetKb()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }

        fun memoryBudgetKb(): Int = (minOf(Runtime.getRuntime().maxMemory() / 8, 48L shl 20) / 1024).toInt()
    }
}
