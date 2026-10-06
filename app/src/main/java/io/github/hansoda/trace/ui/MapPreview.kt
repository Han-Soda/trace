package io.github.hansoda.trace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import io.github.hansoda.trace.motion.Plan
import io.github.hansoda.trace.render.FrameRenderer
import io.github.hansoda.trace.render.Look
import io.github.hansoda.trace.render.Overlay
import io.github.hansoda.trace.tiles.TileStore

/** Draws one frame of the video with the same renderer the export uses. */
@Composable
fun MapPreview(plan: Plan, look: Look, overlay: Overlay, tiles: TileStore, seconds: Float, modifier: Modifier = Modifier) {
    val renderer = remember { FrameRenderer() }
    val tileVersion by tiles.version.collectAsState()
    Canvas(modifier) {
        // Reading the version here redraws the frame whenever a tile arrives.
        if (tileVersion < 0) return@Canvas
        drawIntoCanvas { canvas ->
            renderer.draw(
                canvas.nativeCanvas, size.width.toInt(), size.height.toInt(),
                plan, plan.frameAt(seconds.toDouble()), look, overlay, tiles,
            )
        }
    }
}

/** A canvas that draws with android.graphics, like the renderer, in view pixels. */
@Composable
fun NativeCanvas(modifier: Modifier, draw: (canvas: android.graphics.Canvas, width: Int, height: Int) -> Unit) {
    Canvas(modifier) {
        drawIntoCanvas { canvas -> draw(canvas.nativeCanvas, size.width.toInt(), size.height.toInt()) }
    }
}
