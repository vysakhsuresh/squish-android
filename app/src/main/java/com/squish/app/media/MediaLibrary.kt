package com.squish.app.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The phone's own photos and videos, read straight out of MediaStore.
 *
 * The app used to open the system photo picker for everything, and the
 * first person to cut a real event on it came back saying "some of the
 * videos I took were not showing". The picker is a curated view: it lists
 * what the phone's media provider chooses to list, and on different phones
 * that leaves out files in folders it does not count as galleries, files
 * whose type it does not recognise, cloud-only items, and whatever the OEM's
 * build of it decided. InShot, CapCut and every editor people compare this
 * one to draw their own grid from MediaStore, which is the index itself:
 * every video and photo the phone has scanned, with the folder it is in.
 * That is what this reads. The system picker and the file browser stay as
 * other ways in - the picker for what it is good at (cloud photos, no
 * permission), the browser for an MKV in Downloads or a folder on a card.
 *
 * Pure reading. Nothing here writes to or deletes from the gallery.
 */
object MediaLibrary {

    enum class Kind { Video, Photo }

    /** One file as the index knows it - enough to draw a tile and to open it. */
    data class Item(
        val uri: Uri,
        val name: String,
        val kind: Kind,
        /** Zero for a photo, and for a video the index has not measured. */
        val durationMs: Long,
        val width: Int,
        val height: Int,
        /** Seconds since the epoch, as MediaStore keeps it. */
        val addedAtS: Long,
        /** The folder's display name - "Camera", "WhatsApp Video", "Download". */
        val album: String,
        val mimeType: String,
        val sizeBytes: Long
    )

    /** How much of the gallery the app may read. */
    enum class Access {
        /** Everything the index holds. */
        Full,
        /** Android 14's "Select photos": the ones the user chose, and no more until asked again. */
        Partial,
        None
    }

    /**
     * What to ask for on this phone. On 14 and later the visual-user-selected
     * permission is what a "Select photos" answer grants, and it is asked
     * alongside the full ones so that answer is accepted rather than read as a
     * refusal; on 13 the two media permissions; before that the old storage one.
     */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): Access {
        fun held(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> when {
                held(Manifest.permission.READ_MEDIA_IMAGES) || held(Manifest.permission.READ_MEDIA_VIDEO) -> Access.Full
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    held(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Partial
                else -> Access.None
            }
            held(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.Full
            else -> Access.None
        }
    }

    /**
     * Every photo and video in the index, newest first. One query over the
     * files table rather than one each over images and videos, so the order
     * is one order. Items the index knows nothing about beyond a name still
     * come back - a video with no duration measured is still a video.
     */
    suspend fun load(context: Context, kinds: Set<Kind> = setOf(Kind.Video, Kind.Photo)): List<Item> =
        withContext(Dispatchers.IO) {
            val types = buildList {
                if (Kind.Video in kinds) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                if (Kind.Photo in kinds) add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            }
            if (types.isEmpty()) return@withContext emptyList()
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.DURATION,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE
            )
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${types.joinToString(",")})"
            val items = ArrayList<Item>()
            runCatching {
                context.contentResolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    projection, selection, null,
                    "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val name = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val type = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    val duration = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
                    val width = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                    val height = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                    val added = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                    val bucket = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                    val mime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                    val size = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    while (c.moveToNext()) {
                        val video = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val rowId = c.getLong(id)
                        // The typed collection's URI, which is what the rest of
                        // the app has always been handed by the picker, so a
                        // draft keeps the same kind of link either way.
                        val uri = ContentUris.withAppendedId(
                            if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            rowId
                        )
                        items += Item(
                            uri = uri,
                            name = c.getString(name) ?: (if (video) "Video" else "Photo"),
                            kind = if (video) Kind.Video else Kind.Photo,
                            durationMs = if (c.isNull(duration)) 0L else c.getLong(duration),
                            width = if (c.isNull(width)) 0 else c.getInt(width),
                            height = if (c.isNull(height)) 0 else c.getInt(height),
                            addedAtS = if (c.isNull(added)) 0L else c.getLong(added),
                            album = c.getString(bucket)?.takeIf { it.isNotBlank() } ?: "Other",
                            mimeType = c.getString(mime) ?: if (video) "video/*" else "image/*",
                            sizeBytes = if (c.isNull(size)) 0L else c.getLong(size)
                        )
                    }
                }
            }
            items
        }

    /**
     * The albums worth a chip: by how many items each holds, the biggest
     * first, so "Camera" and "WhatsApp Video" are at hand and a folder with
     * one screenshot in it is at the end. Pure, for the check suite.
     */
    fun albums(items: List<Item>): List<Pair<String, Int>> =
        items.groupingBy { it.album }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key to it.value }

    /** A video's length as its tile shows it: m:ss, or h:mm:ss past an hour. */
    fun durationLabel(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ---- Tiles ----------------------------------------------------------------

    private const val TILE_PX = 320

    private val tiles = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val misses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val decoders = Semaphore(3)

    private fun ownTile(context: Context, uri: Uri): Bitmap? = runCatching {
        if (com.squish.app.av.AvImages.isPicture(context, uri)) {
            com.squish.app.av.AvImages.thumbnail(context, uri, TILE_PX)
        } else {
            com.squish.app.av.AvRetriever().use { r ->
                r.setDataSource(context, uri)
                val lengthMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                // A second in, as the provider's own thumbnails are, past a fade from black.
                r.getScaledFrameAtTime(minOf(1_000L, lengthMs / 2) * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TILE_PX, TILE_PX)
            }
        }
    }.getOrNull()

    /** The tile as last loaded, for a grid that scrolls back over it. */
    fun tile(uri: Uri): Bitmap? = tiles.get(uri.toString())

    /**
     * A small picture for a tile, through the media provider's own thumbnail
     * store - the one the gallery draws from, so it is usually already made
     * and costs a file read. Three at a time: a grid composing thirty tiles
     * at once would otherwise open thirty decoders against the same storage.
     * A file the provider cannot picture is remembered as such, so the grid
     * draws its placeholder once rather than asking again on every scroll.
     */
    suspend fun loadTile(context: Context, uri: Uri): Bitmap? {
        val key = uri.toString()
        tiles.get(key)?.let { return it }
        if (key in misses) return null
        return withContext(Dispatchers.IO) {
            decoders.withPermit {
                tiles.get(key) ?: runCatching {
                    context.contentResolver.loadThumbnail(uri, Size(TILE_PX, TILE_PX), null)
                }.getOrNull()
                    // The provider pictures what the phone decodes; a RAW, a
                    // WMV or a ProRes clip it leaves blank is pictured by the
                    // app's own decoders.
                    ?: ownTile(context, uri)
                        ?.also { tiles.put(key, it) } ?: run { misses += key; null }
            }
        }
    }
}
