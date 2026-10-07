package io.github.hansoda.trace.tiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import io.github.hansoda.trace.render.LandShapes
import io.github.hansoda.trace.render.MapStyle
import io.github.hansoda.trace.render.PlainTiles
import io.github.hansoda.trace.render.TileKey
import io.github.hansoda.trace.render.TileSource
import io.github.hansoda.trace.render.VectorPainter
import io.github.hansoda.trace.render.VectorTile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Map tiles for preview and export. CARTO tiles are cached on disk as they come. The free
 * styles are drawn on the device from OpenFreeMap vector tiles, cached on disk too, or from the
 * bundled coastlines when those can't be had. The preview never waits; missing tiles load in
 * the background and bump [version] so the preview redraws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileStore(private val context: Context) : TileSource {
    private val root = File(context.cacheDir, "tiles")
    private val io = Dispatchers.IO.limitedParallelism(6)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val loading: MutableSet<TileKey> = ConcurrentHashMap.newKeySet()
    private val failedAt = ConcurrentHashMap<TileKey, Long>()
    private val memory = object : LruCache<TileKey, Bitmap>(memoryBudgetKb()) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.byteCount / 1024
    }
    private val land by lazy {
        runCatching { context.assets.open("land.bin").use { LandShapes.decode(it.readBytes()) } }.getOrNull()
    }
    private val coastlines = PlainTiles { land }
    private val openFreeMap = OpenFreeMap(context)
    private val vectors = LruCache<TileKey, VectorTile>(VECTOR_TILES)
    private val vectorLocks = ConcurrentHashMap<TileKey, Any>()
    private val _version = MutableStateFlow(0)

    /** Increases whenever a tile arrives, so whoever draws can refresh. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** CARTO basemap key; tiles fetched without one carry an "API KEY REQUIRED" watermark. */
    @Volatile
    var apiKey: String = ""
        set(value) {
            val trimmed = value.trim()
            if (trimmed == field) return
            field = trimmed
            memory.evictAll()
            failedAt.clear()
            _version.update { it + 1 }
        }

    override fun tile(key: TileKey): Bitmap? {
        memory.get(key)?.let { return it }
        val vector = MapStyle.isVectorSet(key.set)
        val recentFailure = failedAt[if (vector) source(key) else key]
        if (recentFailure != null && SystemClock.elapsedRealtime() - recentFailure < RETRY_MS) {
            return if (vector) coastlines.tile(key) else null
        }
        if (loading.add(key)) {
            scope.launch {
                try {
                    if (load(key, allowNetwork = true) != null) _version.update { it + 1 }
                } finally {
                    loading.remove(key)
                }
            }
        }
        return null
    }

    /**
     * Loads a tile on the calling thread without touching the network; for exports after
     * [prefetch]. Free styles fall back to coastlines rather than leave a gap.
     */
    fun tileNow(key: TileKey): Bitmap? =
        memory.get(key) ?: load(key, allowNetwork = false) ?: if (MapStyle.isVectorSet(key.set)) coastlines.tile(key) else null

    /**
     * Downloads every tile behind [keys] that isn't cached yet.
     *
     * @return how many tiles couldn't be downloaded.
     */
    suspend fun prefetch(keys: Collection<TileKey>, onProgress: (done: Int, total: Int) -> Unit): Int = withContext(io) {
        val remote = keys.map { if (MapStyle.isVectorSet(it.set)) source(it) else it }.distinct()
        val missing = remote.filter { !fileFor(it).exists() }
        var done = remote.size - missing.size
        var failed = 0
        onProgress(done, remote.size)
        val gate = Semaphore(6)
        coroutineScope {
            missing.map { key ->
                async {
                    gate.withPermit {
                        ensureActive()
                        val ok = download(key, fileFor(key))
                        synchronized(this@TileStore) {
                            done++
                            if (!ok) failed++
                            onProgress(done, remote.size)
                        }
                    }
                }
            }.awaitAll()
        }
        failed
    }

    /** Downloads a fresh sample tile with the current key, so the person can see what it gives. */
    suspend fun sample(set: String): Bitmap? = withContext(io) {
        val key = TileKey(set, 5, 16, 10)
        val file = File(context.cacheDir, "sample.png")
        file.delete()
        if (download(key, file)) BitmapFactory.decodeFile(file.path).also { file.delete() } else null
    }

    fun cacheBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Draws the free maps again, for place names in a newly chosen language. */
    fun forgetDrawn() {
        memory.evictAll()
        _version.update { it + 1 }
    }

    fun clear() {
        root.deleteRecursively()
        memory.evictAll()
        vectors.evictAll()
        failedAt.clear()
        _version.update { it + 1 }
    }

    /** Deletes the oldest cached tiles once the cache grows past [maxBytes]. */
    fun trim(maxBytes: Long = 400L shl 20, targetBytes: Long = 250L shl 20) {
        val files = root.walkBottomUp().filter { it.isFile }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= targetBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private fun load(key: TileKey, allowNetwork: Boolean): Bitmap? {
        MapStyle.vectorSet(key.set)?.let { (style, labels) ->
            val source = source(key)
            val data = vectorTile(source, allowNetwork) ?: return null
            val bitmap = VectorPainter(Locale.getDefault().language).paint(data, key, source.z, style.palette!!, labels)
            memory.put(key, bitmap)
            return bitmap
        }
        val file = fileFor(key)
        if (!file.exists() && (!allowNetwork || !download(key, file))) return null
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null) {
            file.delete()
            return null
        }
        memory.put(key, bitmap)
        return bitmap
    }

    /** The OpenFreeMap tile holding [key]; past their deepest zoom, a parent tile. */
    private fun source(key: TileKey): TileKey {
        val z = minOf(key.z, openFreeMap.maxZoom)
        val shift = key.z - z
        return TileKey(OPENFREEMAP, z, key.x shr shift, key.y shr shift)
    }

    private fun vectorTile(source: TileKey, allowNetwork: Boolean): VectorTile? {
        vectors.get(source)?.let { return it }
        // Neighbouring views share source tiles; read each one once.
        synchronized(vectorLocks.getOrPut(source) { Any() }) {
            vectors.get(source)?.let { return it }
            val file = fileFor(source)
            if (!file.exists() && (!allowNetwork || !download(source, file))) return null
            val tile = try {
                VectorTile.decode(file.readBytes())
            } catch (_: Exception) {
                file.delete()
                // Coastlines until the retry, rather than downloading it again for every frame.
                failedAt[source] = SystemClock.elapsedRealtime()
                return null
            }
            vectors.put(source, tile)
            return tile
        }
    }

    private fun download(key: TileKey, file: File): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val vector = key.set == OPENFREEMAP
            val url = if (vector) openFreeMap.url(key.z, key.x, key.y) ?: throw IOException("No tile address") else key.url(apiKey)
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", OpenFreeMap.USER_AGENT)
            }
            val code = connection.responseCode
            if (vector && (code == HttpURLConnection.HTTP_NOT_FOUND || code == 410)) openFreeMap.stale()
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            file.parentFile?.mkdirs()
            val partial = File(file.path + ".part")
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (!partial.renameTo(file)) throw IOException("Couldn't save tile")
            failedAt.remove(key)
            true
        } catch (error: IOException) {
            failedAt[key] = SystemClock.elapsedRealtime()
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun fileFor(key: TileKey): File {
        if (key.set == OPENFREEMAP) return File(root, "openfreemap/${key.z}/${key.x}_${key.y}.pbf")
        val keyFolder = if (apiKey.isEmpty()) "public" else hash(apiKey)
        return File(root, "$keyFolder/${key.set.replace('/', '_')}/${key.z}/${key.x}_${key.y}.png")
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private companion object {
        const val RETRY_MS = 15_000L
        const val OPENFREEMAP = "openfreemap"
        const val VECTOR_TILES = 12

        fun memoryBudgetKb(): Int = (minOf(Runtime.getRuntime().maxMemory() / 5, 96L shl 20) / 1024).toInt()
    }
}
