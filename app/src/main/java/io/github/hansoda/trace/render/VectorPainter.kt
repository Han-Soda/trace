package io.github.hansoda.trace.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colours of a map drawn from vector tiles. Roads without a casing colour have no outline. */
class VectorPalette(
    val land: Int,
    val water: Int,
    val park: Int,
    val wood: Int,
    val building: Int,
    val minorRoad: Int,
    val majorRoad: Int,
    val motorway: Int,
    val casing: Int?,
    val majorCasing: Int?,
    val rail: Int,
    val border: Int,
    val label: Int,
    val minorLabel: Int,
    val waterLabel: Int,
    val halo: Int,
) {
    companion object {
        /** Warm paper, white streets, grey water. */
        val PAPER = VectorPalette(
            land = 0xFFF4F1EA.toInt(), water = 0xFFDFE3E3.toInt(), park = 0xFFE9EADF.toInt(), wood = 0xFFE5E8DA.toInt(),
            building = 0xFFEAE5DB.toInt(), minorRoad = 0xFFFFFFFF.toInt(), majorRoad = 0xFFFFFFFF.toInt(), motorway = 0xFFFFFDF8.toInt(),
            casing = null, majorCasing = 0xFFE6E0D4.toInt(), rail = 0xFFDAD4C9.toInt(), border = 0xFFB9B2A6.toInt(),
            label = 0xFF57534C.toInt(), minorLabel = 0xFF7D786F.toInt(), waterLabel = 0xFF8D9A9C.toInt(), halo = 0xFFF4F1EA.toInt(),
        )

        /** Near-black land, darker water, streets a few shades up. */
        val INK = VectorPalette(
            land = 0xFF17181B.toInt(), water = 0xFF0A0B0D.toInt(), park = 0xFF191C1B.toInt(), wood = 0xFF191C1A.toInt(),
            building = 0xFF1E1F23.toInt(), minorRoad = 0xFF2A2C31.toInt(), majorRoad = 0xFF34373D.toInt(), motorway = 0xFF3D4047.toInt(),
            casing = null, majorCasing = null, rail = 0xFF292B30.toInt(), border = 0xFF4C4F57.toInt(),
            label = 0xFF9A9DA4.toInt(), minorLabel = 0xFF767980.toInt(), waterLabel = 0xFF505A63.toInt(), halo = 0xFF17181B.toInt(),
        )

        /** Soft colour: blue water, green parks, white streets and warm main roads. */
        val STREETS = VectorPalette(
            land = 0xFFF2EFE9.toInt(), water = 0xFFAACFE4.toInt(), park = 0xFFD6E8C6.toInt(), wood = 0xFFC9E0B6.toInt(),
            building = 0xFFE4DFD7.toInt(), minorRoad = 0xFFFFFFFF.toInt(), majorRoad = 0xFFFDE6B5.toInt(), motorway = 0xFFF8C98D.toInt(),
            casing = 0xFFDCD6CC.toInt(), majorCasing = 0xFFE5C189.toInt(), rail = 0xFFC2BCB2.toInt(), border = 0xFFA79BB8.toInt(),
            label = 0xFF333333.toInt(), minorLabel = 0xFF555555.toInt(), waterLabel = 0xFF4E7FA2.toInt(), halo = 0xFFFFFFFF.toInt(),
        )
    }
}

/**
 * Draws one 512-pixel map tile from an OpenMapTiles vector tile, possibly a zoomed-in quarter
 * of a lower-zoom source tile. Not thread-safe: use one painter per thread.
 */
class VectorPainter(private val language: String) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** Where the output tile sits in the source tile, in tile units, and how to get to pixels. */
    private var scale = 1f
    private var left = 0f
    private var top = 0f
    private var zoom = 0.0
    private var unitsPerPixel = 1f

    /**
     * @param key the tile to draw.
     * @param sourceZ zoom of [tile]; the output tile lies inside it.
     */
    fun paint(tile: VectorTile, key: TileKey, sourceZ: Int, palette: VectorPalette, labels: Boolean): Bitmap {
        val size = TileMath.TILE_PIXELS
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.land)
        val depth = key.z - sourceZ
        val pieces = 1 shl depth
        zoom = key.z.toDouble()

        fun place(extent: Int) {
            scale = size.toFloat() * pieces / extent
            left = (key.x and (pieces - 1)) * extent.toFloat() / pieces
            top = (key.y and (pieces - 1)) * extent.toFloat() / pieces
            unitsPerPixel = 1f / scale
        }

        tile.layer("landcover")?.let { layer ->
            place(layer.extent)
            fillAll(canvas, layer, palette.wood) { it.string("class") == "wood" }
            // Grassland covers whole regions in the countryside; only tint it near towns.
            if (zoom >= 11) fillAll(canvas, layer, palette.park) { it.string("class") == "grass" }
        }
        tile.layer("park")?.let { layer ->
            place(layer.extent)
            fillAll(canvas, layer, palette.park) { true }
        }
        tile.layer("landuse")?.let { layer ->
            place(layer.extent)
            fillAll(canvas, layer, palette.park) { it.string("class") in GREEN_LANDUSE }
        }
        tile.layer("water")?.let { layer ->
            place(layer.extent)
            fillAll(canvas, layer, palette.water) { it.string("brunnel") != "tunnel" }
        }
        tile.layer("waterway")?.let { layer ->
            place(layer.extent)
            for (feature in layer.features) {
                if (feature.string("brunnel") == "tunnel") continue
                val base = when (feature.string("class")) {
                    "river" -> 2.4f
                    "canal" -> 1.8f
                    else -> 0.9f
                }
                strokeFeature(canvas, feature, palette.water, roadWidth(base))
            }
        }
        if (zoom >= 14) {
            tile.layer("building")?.let { layer ->
                place(layer.extent)
                fillAll(canvas, layer, palette.building) { true }
            }
        }
        tile.layer("transportation")?.let { layer ->
            place(layer.extent)
            drawRoads(canvas, layer, palette)
        }
        tile.layer("boundary")?.let { layer ->
            place(layer.extent)
            drawBorders(canvas, layer, palette)
        }
        if (labels) {
            val placed = ArrayList<FloatArray>()
            tile.layer("place")?.let { layer ->
                place(layer.extent)
                drawPlaces(canvas, layer, palette, placed)
            }
            tile.layer("water_name")?.let { layer ->
                place(layer.extent)
                drawWaterNames(canvas, layer, palette, placed)
            }
        }
        return bitmap
    }

    // region Shapes

    private fun visible(feature: VectorTile.Feature, margin: Float): Boolean {
        val size = TileMath.TILE_PIXELS / scale
        val m = margin * unitsPerPixel
        return feature.maxX >= left - m && feature.minX <= left + size + m && feature.maxY >= top - m && feature.minY <= top + size + m
    }

    private inline fun fillAll(canvas: Canvas, layer: VectorTile.Layer, color: Int, include: (VectorTile.Feature) -> Boolean) {
        fill.color = color
        for (feature in layer.features) {
            if (feature.type != VectorTile.POLYGON || !include(feature) || !visible(feature, 1f)) continue
            if (build(feature, close = true)) canvas.drawPath(path, fill)
        }
    }

    private fun strokeFeature(canvas: Canvas, feature: VectorTile.Feature, color: Int, width: Float) {
        if (feature.type != VectorTile.LINE || !visible(feature, width)) return
        stroke.color = color
        stroke.strokeWidth = width
        if (build(feature, close = false)) canvas.drawPath(path, stroke)
    }

    /** Turns a feature into [path], skipping steps under half a pixel. */
    private fun build(feature: VectorTile.Feature, close: Boolean): Boolean {
        path.rewind()
        val coordinates = feature.coordinates
        val parts = feature.parts
        var drawn = false
        for (p in 0 until parts.size - 1) {
            val from = parts[p]
            val to = parts[p + 1]
            if (to - from < (if (close) 3 else 2)) continue
            var lastX = 0f
            var lastY = 0f
            for (i in from until to) {
                val x = (coordinates[i * 2] - left) * scale
                val y = (coordinates[i * 2 + 1] - top) * scale
                if (i == from) {
                    path.moveTo(x, y)
                } else if (abs(x - lastX) + abs(y - lastY) < 0.5f && i < to - 1) {
                    continue
                } else {
                    path.lineTo(x, y)
                }
                lastX = x
                lastY = y
            }
            if (close) path.close()
            drawn = true
        }
        return drawn
    }

    // endregion

    // region Roads and borders

    /** Width in pixels of a line [base] pixels wide at zoom 14, at the tile's zoom. */
    private fun roadWidth(base: Float): Float {
        val exponent = if (zoom < 14) 0.6 else 0.75
        return (base * 2.0.pow((zoom - 14) * exponent)).toFloat().coerceIn(0.5f, 48f)
    }

    private fun drawRoads(canvas: Canvas, layer: VectorTile.Layer, palette: VectorPalette) {
        val roads = layer.features.filter { it.type == VectorTile.LINE }
        // Railways first, quietly, under the streets.
        for (feature in roads) {
            val kind = feature.string("class")
            if (kind != "rail" && kind != "transit") continue
            if (feature.string("brunnel") == "tunnel") continue
            strokeFeature(canvas, feature, palette.rail, roadWidth(1.1f))
        }
        val streets = roads.mapNotNull { feature -> ROADS[feature.string("class")]?.let { feature to it } }
            .filter { (feature, _) -> feature.string("brunnel") != "tunnel" || zoom >= 15 }
            .sortedBy { it.second.order }
        for ((feature, road) in streets) {
            val casing = if (road.order >= MAJOR) palette.majorCasing else palette.casing
            if (casing != null && zoom >= 11) strokeFeature(canvas, feature, casing, roadWidth(road.width) + roadWidth(1.2f).coerceAtMost(2.5f))
        }
        for ((feature, road) in streets) {
            val color = when {
                road.order >= MOTORWAY -> palette.motorway
                road.order >= MAJOR -> palette.majorRoad
                else -> palette.minorRoad
            }
            strokeFeature(canvas, feature, color, roadWidth(road.width))
        }
    }

    private fun drawBorders(canvas: Canvas, layer: VectorTile.Layer, palette: VectorPalette) {
        for (feature in layer.features) {
            if (feature.type != VectorTile.LINE || feature.number("maritime") == 1.0) continue
            val level = feature.number("admin_level")?.toInt() ?: continue
            if (level > 4 || (level > 2 && zoom < 5)) continue
            val width = if (level <= 2) 1.4f else 0.9f
            stroke.pathEffect = DashPathEffect(floatArrayOf(width * 4f, width * 2.5f), 0f)
            stroke.strokeCap = Paint.Cap.BUTT
            strokeFeature(canvas, feature, if (level <= 2) palette.border else withAlpha(palette.border, 0.6f), width)
            stroke.pathEffect = null
            stroke.strokeCap = Paint.Cap.ROUND
        }
    }

    // endregion

    // region Labels

    private fun name(feature: VectorTile.Feature): String? =
        feature.string("name:$language") ?: (if (language == "en") feature.string("name_en") ?: feature.string("name:en") else null)
            ?: feature.string("name")

    private fun drawPlaces(canvas: Canvas, layer: VectorTile.Layer, palette: VectorPalette, placed: MutableList<FloatArray>) {
        class Candidate(val feature: VectorTile.Feature, val style: PlaceStyle, val rank: Double)

        val candidates = ArrayList<Candidate>()
        for (feature in layer.features) {
            if (feature.type != VectorTile.POINT) continue
            val style = PLACES[feature.string("class")] ?: continue
            if (zoom < style.minZoom || zoom > style.maxZoom) continue
            val rank = feature.number("rank") ?: 10.0
            if (style.order == CITY && rank > cityRankLimit()) continue
            candidates += Candidate(feature, style, rank)
        }
        candidates.sortWith(compareBy({ it.style.order }, { it.rank }))
        for (candidate in candidates) {
            val label = name(candidate.feature) ?: continue
            val coordinates = candidate.feature.coordinates
            if (coordinates.size < 2) continue
            val x = (coordinates[0] - left) * scale
            val y = (coordinates[1] - top) * scale
            val color = if (candidate.style.order <= CITY) palette.label else palette.minorLabel
            drawLabel(canvas, label, x, y, candidate.style.size, candidate.style.bold, italic = false, color, palette.halo, placed)
        }
    }

    private fun drawWaterNames(canvas: Canvas, layer: VectorTile.Layer, palette: VectorPalette, placed: MutableList<FloatArray>) {
        for (feature in layer.features) {
            if (feature.type != VectorTile.POINT) continue
            val kind = feature.string("class")
            val show = when (kind) {
                "ocean" -> zoom in 1.0..5.0
                "sea", "bay", "strait" -> zoom in 4.0..9.0
                "lake" -> zoom >= 11
                else -> false
            }
            if (!show) continue
            val label = name(feature) ?: continue
            val coordinates = feature.coordinates
            if (coordinates.size < 2) continue
            val x = (coordinates[0] - left) * scale
            val y = (coordinates[1] - top) * scale
            drawLabel(canvas, label, x, y, if (kind == "lake") 17f else 19f, bold = false, italic = true, palette.waterLabel, palette.halo, placed)
        }
    }

    /** At lower zooms only the bigger cities get a name. */
    private fun cityRankLimit(): Double = when {
        zoom < 5 -> 2.0
        zoom < 7 -> 4.0
        zoom < 9 -> 7.0
        else -> 99.0
    }

    /** Draws a label centred on a point, if it fits in the tile and clear of others. */
    private fun drawLabel(
        canvas: Canvas, label: String, x: Float, y: Float, size: Float, bold: Boolean, italic: Boolean,
        color: Int, halo: Int, placed: MutableList<FloatArray>,
    ) {
        text.typeface = when {
            italic -> ITALIC
            bold -> MEDIUM
            else -> REGULAR
        }
        text.textSize = size
        val width = text.measureText(label)
        val box = floatArrayOf(x - width / 2 - 4, y - size * 0.8f, x + width / 2 + 4, y + size * 0.45f)
        val edge = TileMath.TILE_PIXELS - 2f
        if (box[0] < 2f || box[1] < 2f || box[2] > edge || box[3] > edge) return
        if (placed.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) return
        placed += box
        val baseline = y + size * 0.32f
        text.style = Paint.Style.STROKE
        text.strokeWidth = max(2.5f, size * 0.22f)
        text.strokeJoin = Paint.Join.ROUND
        text.color = withAlpha(halo, 0.9f)
        canvas.drawText(label, x - width / 2, baseline, text)
        text.style = Paint.Style.FILL
        text.color = color
        canvas.drawText(label, x - width / 2, baseline, text)
    }

    // endregion

    private class Road(val order: Int, val width: Float)

    private class PlaceStyle(val order: Int, val size: Float, val bold: Boolean, val minZoom: Double, val maxZoom: Double)

    private companion object {
        const val MAJOR = 5
        const val MOTORWAY = 7
        const val CITY = 1

        val ROADS = mapOf(
            "path" to Road(0, 0.8f),
            "track" to Road(1, 1.0f),
            "service" to Road(2, 1.3f),
            "minor" to Road(3, 2.2f),
            "tertiary" to Road(4, 2.9f),
            "secondary" to Road(5, 3.4f),
            "primary" to Road(6, 4.0f),
            "trunk" to Road(7, 4.4f),
            "motorway" to Road(8, 4.8f),
        )

        val PLACES = mapOf(
            "country" to PlaceStyle(0, 20f, true, 2.0, 6.5),
            "city" to PlaceStyle(1, 22f, true, 4.0, 15.0),
            "town" to PlaceStyle(2, 19f, false, 9.0, 16.0),
            "village" to PlaceStyle(3, 17f, false, 12.0, 17.0),
            "suburb" to PlaceStyle(4, 17f, false, 12.5, 16.5),
            "quarter" to PlaceStyle(5, 16f, false, 14.0, 17.5),
            "neighbourhood" to PlaceStyle(6, 16f, false, 15.0, 18.0),
        )

        val GREEN_LANDUSE = setOf("park", "cemetery", "recreation_ground", "garden", "playground", "pitch")

        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val ITALIC: Typeface = Typeface.create("sans-serif", Typeface.ITALIC)

        fun withAlpha(color: Int, alpha: Float): Int =
            ((((color ushr 24) * alpha).toInt().coerceIn(0, 255)) shl 24) or (color and 0x00FFFFFF)
    }
}
