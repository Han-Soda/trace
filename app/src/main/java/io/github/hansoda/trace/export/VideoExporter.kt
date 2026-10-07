package io.github.hansoda.trace.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import io.github.hansoda.trace.media.AudioMix
import io.github.hansoda.trace.media.SoundSource
import io.github.hansoda.trace.motion.Plan
import io.github.hansoda.trace.render.FrameRenderer
import io.github.hansoda.trace.render.Look
import io.github.hansoda.trace.render.Overlay
import io.github.hansoda.trace.render.PhotoSource
import io.github.hansoda.trace.render.TileKey
import io.github.hansoda.trace.render.TileMath
import io.github.hansoda.trace.render.TileSource
import io.github.hansoda.trace.tiles.TileStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Renders a [Plan] to an MP4 video or a PNG image and saves it to the shared folders. */
class VideoExporter(private val context: Context, private val tiles: TileStore, private val photos: PhotoSource? = null) {
    sealed interface Progress {
        /** Downloading map tiles. */
        data class Map(val done: Int, val total: Int) : Progress

        /** Drawing and encoding frames. */
        data class Frames(val done: Int, val total: Int) : Progress
    }

    /** Renders [plan] to an MP4; with [sounds], clips play with their own sound. */
    suspend fun video(
        plan: Plan, width: Int, height: Int, look: Look, overlay: Overlay, name: String,
        sounds: SoundSource? = null,
        onProgress: (Progress) -> Unit,
    ): Uri = withContext(Dispatchers.Default) {
        downloadTiles(plan, 0 until plan.frameCount, width, height, look, onProgress)
        // A video whose sound can't be made is still worth having, silent.
        val audio = sounds?.let { source -> runCatching { AudioMix.mix(plan, source)?.let(AacEncoder::encode) }.getOrNull() }
        ensureActive()
        val file = File(context.cacheDir, "export.mp4")
        file.delete()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val renderer = FrameRenderer()
            val source = TileSource { tiles.tileNow(it) }
            AvcEncoder(width, height, plan.fps, VideoSizes.bitRate(width, height, plan.fps), file, audio).use { encoder ->
                for (frame in 0 until plan.frameCount) {
                    ensureActive()
                    renderer.draw(canvas, width, height, plan, frame, look, overlay, source, photos)
                    encoder.encode(bitmap)
                    if (frame % 3 == 0 || frame == plan.frameCount - 1) onProgress(Progress.Frames(frame + 1, plan.frameCount))
                }
                encoder.finish()
            }
            ensureActive()
            MediaSaver.saveVideo(context, file, name)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    /** Saves one [frame] as an image: by default the closing overview, the whole route at once. */
    suspend fun image(
        plan: Plan, width: Int, height: Int, look: Look, overlay: Overlay, name: String,
        frame: Int = plan.frameCount - 1,
        onProgress: (Progress) -> Unit,
    ): Uri = withContext(Dispatchers.Default) {
        downloadTiles(plan, frame..frame, width, height, look, onProgress)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            FrameRenderer().draw(Canvas(bitmap), width, height, plan, frame, look, overlay, { tiles.tileNow(it) }, photos)
            ensureActive()
            MediaSaver.saveImage(context, name) { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun downloadTiles(
        plan: Plan, frames: IntRange, width: Int, height: Int, look: Look,
        onProgress: (Progress) -> Unit,
    ) {
        val set = FrameRenderer.tileSet(look, plan)
        // A turning map covers a square around the frame, as the renderer draws it.
        val mapWidth = if (plan.turns) FrameRenderer.turningSide(width, height) else width
        val mapHeight = if (plan.turns) mapWidth else height
        val aspect = mapWidth.toDouble() / mapHeight
        val keys = LinkedHashSet<TileKey>()
        for (f in frames) {
            val cameraWidth = plan.cameraWidth[f] * mapWidth / width
            val zoom = TileMath.zoom(cameraWidth, mapWidth)
            for ((z, _) in TileMath.levels(zoom)) {
                TileMath.forEachTile(z, plan.cameraX[f], plan.cameraY[f], cameraWidth, aspect) { _, tileY, wrappedX ->
                    keys += TileKey(set, z, wrappedX, tileY)
                }
            }
        }
        val failed = tiles.prefetch(keys) { done, total -> onProgress(Progress.Map(done, total)) }
        // The free styles fall back to coastlines; CARTO styles would be blank.
        if (look.map.usesCarto && keys.isNotEmpty() && failed == keys.size) {
            throw IOException("Couldn't download the map. Check the connection, or choose Paper, Ink or Streets.")
        }
    }
}
