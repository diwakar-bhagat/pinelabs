package com.bohemian.odoo.pos.ui

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bohemian.odoo.pos.core.PosLog
import com.bohemian.odoo.pos.odoo.OdooLane
import com.bohemian.odoo.pos.odoo.Product
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Product photos from Odoo (`image_256`), cached on disk per product and version
 * (`write_date`), so the grid works offline and a photo changed in Odoo is fetched again.
 * A product without a photo gets an empty marker file, so it is not asked for again until
 * the product changes. Never blocks selling: any failure just leaves the placeholder.
 */
class ProductImages(
    private val dir: File,
    /** Runs the batched downloads, so a tile scrolled away does not cancel its neighbours' photos. */
    private val scope: CoroutineScope,
    private val fetch: suspend (ids: List<Int>) -> Map<Int, String>,
) {
    /** Decoded photos, capped by memory used (not by count: a 256 px photo is ~256 KB decoded). */
    private val memory = object : LruCache<Int, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: Int, value: ImageBitmap) = value.width * value.height * 4
    }
    private val network = Semaphore(3)

    /** Tiles waiting for a photo; collected for [BATCH_WINDOW_MS], then fetched [BATCH] at a time. */
    private val waiting = LinkedHashMap<Int, Pair<Product, CompletableDeferred<Unit>>>()
    private val waitingLock = Mutex()
    private var flushScheduled = false

    /** Already decoded, for the first frame of a tile. */
    fun cached(p: Product): ImageBitmap? = memory.get(p.id)

    /** Photo of [p] from memory, disk or Odoo; null when it has none or cannot be loaded now. */
    suspend fun load(p: Product): ImageBitmap? {
        memory.get(p.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = photoFile(p)
            if (!file.exists() && !noneFile(p).exists()) downloadBatched(p)
            if (file.exists()) decode(file)?.also { memory.put(p.id, it) } else null
        }
    }

    /**
     * Fetch, in batches, the photos of [menu] not on disk yet (the menu tabs, right after a
     * catalogue load), and delete files that belong to no product of [catalogue] any more.
     */
    suspend fun prefetch(menu: List<Product>, catalogue: List<Product>) = withContext(Dispatchers.IO) {
        menu.filter { !photoFile(it).exists() && !noneFile(it).exists() }
            .chunked(BATCH).forEach { batch -> network.withPermit { download(batch) } }
        prune(catalogue)
    }

    /**
     * Scrolling shows many tiles at once: their photos are asked for in one read of up to [BATCH]
     * ids instead of one call per tile.
     */
    private suspend fun downloadBatched(p: Product) {
        var startFlush = false
        val done = waitingLock.withLock {
            if (!flushScheduled) { flushScheduled = true; startFlush = true }
            (waiting[p.id] ?: (p to CompletableDeferred<Unit>()).also { waiting[p.id] = it }).second
        }
        if (startFlush) scope.launch(Dispatchers.IO) { delay(BATCH_WINDOW_MS); flush() }
        done.await()
    }

    private suspend fun flush() {
        val batch = waitingLock.withLock { waiting.values.toList().also { waiting.clear(); flushScheduled = false } }
        batch.chunked(BATCH).forEach { chunk ->
            try { network.withPermit { download(chunk.map { it.first }) } } finally { chunk.forEach { it.second.complete(Unit) } }
        }
    }

    private suspend fun download(batch: List<Product>) {
        val got = try { withContext(OdooLane.BACKGROUND) { fetch(batch.map { it.id }) } } catch (e: Exception) {
            PosLog.w("IMAGE_FETCH_FAILED", "count" to batch.size, "err" to e.message); return
        }
        dir.mkdirs()
        for (p in batch) {
            val b64 = got[p.id]
            if (b64 == null) { noneFile(p).writeBytes(ByteArray(0)); continue }
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
            val tmp = File(dir, photoFile(p).name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(photoFile(p))
        }
    }

    /** Remove files of older versions and of products no longer in the catalogue. */
    private fun prune(current: List<Product>) {
        val keep = current.flatMap { listOf(photoFile(it).name, noneFile(it).name) }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    private fun decode(f: File): ImageBitmap? =
        runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()

    private fun photoFile(p: Product) = File(dir, fileName(p, "img"))
    private fun noneFile(p: Product) = File(dir, fileName(p, "none"))

    companion object {
        private const val BATCH = 40
        private const val BATCH_WINDOW_MS = 50L
        private const val MEMORY_BYTES = 24 * 1024 * 1024

        /** `<id>-<write_date digits>.<ext>`: a new write_date in Odoo means a new file. */
        fun fileName(p: Product, ext: String): String =
            "${p.id}-${p.writeDate?.filter { it.isDigit() }.orEmpty().ifEmpty { "0" }}.$ext"
    }
}
