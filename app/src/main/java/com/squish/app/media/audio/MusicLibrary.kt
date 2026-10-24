package com.squish.app.media.audio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A song on the phone, as the music browser lists it. */
data class PhoneTrack(val uri: Uri, val title: String, val artist: String?, val durationMs: Long)

/**
 * One entry of the music browser as it is remembered - starred, or used
 * lately - whatever list it came from. [key] says which: `orig:<id>` for a
 * Squish original, `sfx:<id>` for a sound effect, otherwise a song on the
 * phone by its address. Title and subtitle are kept with it so the list can
 * be drawn without asking the media store again.
 */
data class MusicPick(val key: String, val title: String, val subtitle: String) {
    val isOriginal: Boolean get() = key.startsWith("orig:")
    val isEffect: Boolean get() = key.startsWith("sfx:")
    val id: String get() = key.substringAfter(':')
}

/**
 * Where music comes from: the tracks Squish composes itself, its sound
 * effects, and the music already on the phone - and which of them were
 * starred or used lately. Nothing here touches the network.
 */
object MusicLibrary {

    private fun dir(context: Context) = File(context.filesDir, "music").apply { mkdirs() }

    /** Whether [style] has already been rendered, so the list can say so. */
    fun isReady(context: Context, style: MusicSynth.Style): Boolean = madeFile(context, style) != null

    /**
     * The track's file if it has been made: the AAC kept since 18 October,
     * or the WAV an earlier build wrote - which a project made then names by
     * that address, so it stays and is used as it is.
     */
    private fun madeFile(context: Context, style: MusicSynth.Style): File? =
        File(dir(context), "${style.id}.wav").takeIf { it.length() > 0L }
            ?: File(dir(context), "${style.id}.m4a").takeIf { it.length() > 0L }

    private val making = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /**
     * The track's file, composed if it has not been yet. Kept in app
     * storage, so a project that uses it can always find it again.
     *
     * Composed as a WAV and kept as AAC: the WAV of a fifty-second track is
     * 6.5 MB and the AAC under 1.3, which is what lets [prepare] make them
     * of them ahead of time - so Listen and Add on an original are a file
     * read, where they used to wait a second on the synthesis.
     */
    suspend fun original(
        context: Context,
        style: MusicSynth.Style,
        on: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
    ): Uri? = withContext(on) {
        madeFile(context, style)?.let { return@withContext Uri.fromFile(it) }
        // One composition per track at a time: a tap while [prepare] is on
        // the same track waits for that one rather than starting a second.
        making.getOrPut(style.id) { kotlinx.coroutines.sync.Mutex() }.withLock {
            madeFile(context, style)?.let { return@withLock Uri.fromFile(it) }
            val wav = File(context.cacheDir, "compose-${style.id}.wav")
            val aac = File(dir(context), "${style.id}.m4a")
            // A full phone can refuse the write: no track, rather than a crash in the panel.
            runCatching {
                val t0 = android.os.SystemClock.elapsedRealtime()
                MusicSynth.render(style, wav)
                val t1 = android.os.SystemClock.elapsedRealtime()
                val encoded = AacWriter.encode(wav, aac)
                com.squish.app.av.Perf.took("synth", t1 - t0, style.id)
                com.squish.app.av.Perf.took("aac", android.os.SystemClock.elapsedRealtime() - t1, style.id)
                if (encoded) {
                    wav.delete()
                    Uri.fromFile(aac)
                } else {
                    // No AAC encoder would have it: the WAV, as before.
                    val kept = File(dir(context), "${style.id}.wav")
                    if (!wav.renameTo(kept)) wav.copyTo(kept, overwrite = true).also { wav.delete() }
                    Uri.fromFile(kept)
                }
            }.getOrElse { wav.delete(); aac.delete(); null }
        }
    }

    /**
     * Makes each of [styles] not made yet, one after another at low priority,
     * so none of them waits on a tap. Called with the category the Music
     * panel has open - about a second of composing each and 1.2 MB kept.
     * It made every original when there were twenty-two; at thirty-seven
     * that was 45 MB written for opening the sheet once.
     */
    suspend fun prepare(context: Context, styles: List<MusicSynth.Style>) = withContext(Dispatchers.Default) {
        for (style in styles) {
            if (madeFile(context, style) != null) continue
            val t0 = android.os.SystemClock.elapsedRealtime()
            // At background priority (BackgroundWork): it was at the preview's own,
            // beside the first auditions and the first play after Add.
            original(context, style, com.squish.app.util.BackgroundWork.dispatcher)
            com.squish.app.av.Perf.took("compose", android.os.SystemClock.elapsedRealtime() - t0, style.id)
        }
    }

    /**
     * Which build of the synth the effects on disk were made by.
     *
     * Raise it when [MusicSynth.renderEffect] changes in a way a listener would
     * hear. 2: the three-millisecond release that stops a sound clicking where
     * it meets the cut it is laid against.
     */
    private const val EFFECT_BUILD = 2

    /**
     * A sound effect's file, made the first time it is asked for, like a track -
     * and made again when the synth has changed since it was written.
     *
     * Rewritten in place rather than renamed or deleted: a project that used
     * this effect holds the file's address in the clip, so a new name would be
     * a missing sound in every draft that had one, and a delete would be the
     * same until something asked for it again. An effect is a second of
     * arithmetic, so remaking all of them costs nothing worth measuring.
     */
    suspend fun effect(context: Context, effect: MusicSynth.Effect): Uri? = withContext(Dispatchers.Default) {
        remakeEffectsIfStale(context)
        val file = File(dir(context), "${effect.id}.wav")
        runCatching { if (!file.exists()) MusicSynth.renderEffect(effect, file); Uri.fromFile(file) }
            .getOrElse { file.delete(); null }
    }

    /**
     * Under a lock: two sounds asked for at once would otherwise both find the
     * stamp stale and write the same WAVs over each other, and a half-written
     * file is a sound that does not play.
     */
    /** Remakes the effects on disk now if the synth has changed since, so the first one asked for does not wait on all of them. */
    fun warmUp(context: Context) = remakeEffectsIfStale(context)

    @Synchronized
    private fun remakeEffectsIfStale(context: Context) {
        val stamp = File(dir(context), "effects.build")
        val made = runCatching { stamp.readText().trim().toInt() }.getOrDefault(0)
        if (made >= EFFECT_BUILD) return
        MusicSynth.effects.forEach { e ->
            val file = File(dir(context), "${e.id}.wav")
            if (file.exists()) runCatching { MusicSynth.renderEffect(e, file) }
        }
        runCatching { stamp.writeText(EFFECT_BUILD.toString()) }
    }

    /**
     * A remembered pick's file: an original or effect rendered as needed, a
     * phone song by its address. Null for an original or effect that no
     * longer exists in this build.
     */
    suspend fun resolve(context: Context, pick: MusicPick): Uri? = when {
        pick.isOriginal -> MusicSynth.byId(pick.id)?.let { original(context, it) }
        pick.isEffect -> MusicSynth.effectById(pick.id)?.let { effect(context, it) }
        else -> Uri.parse(pick.key)
    }

    // ---- Starred and recent ----------------------------------------------------

    private const val PREFS = "music"
    private const val KEY_FAVOURITES = "favourites"
    private const val KEY_RECENTS = "recents"
    private const val MAX_RECENTS = 20

    fun favourites(context: Context): List<MusicPick> = readPicks(context, KEY_FAVOURITES)
    fun recents(context: Context): List<MusicPick> = readPicks(context, KEY_RECENTS)

    fun isFavourite(context: Context, key: String): Boolean = favourites(context).any { it.key == key }

    /** Stars [pick], or unstars it if it was. Returns whether it is starred now. */
    fun toggleFavourite(context: Context, pick: MusicPick): Boolean {
        val current = favourites(context)
        val now = if (current.any { it.key == pick.key }) current.filterNot { it.key == pick.key } else current + pick
        writePicks(context, KEY_FAVOURITES, now)
        return now.any { it.key == pick.key }
    }

    /** Notes that [pick] was added to an edit: it goes to the top of the recent list. */
    fun noteUsed(context: Context, pick: MusicPick) {
        val now = listOf(pick) + recents(context).filterNot { it.key == pick.key }
        writePicks(context, KEY_RECENTS, now.take(MAX_RECENTS))
    }

    private fun readPicks(context: Context, key: String): List<MusicPick> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MusicPick(k, o.optString("title", "Untitled"), o.optString("subtitle"))
            }
        }.getOrDefault(emptyList())
    }

    private fun writePicks(context: Context, key: String, picks: List<MusicPick>) {
        val array = JSONArray()
        picks.forEach { p ->
            array.put(JSONObject().apply {
                put("key", p.key)
                put("title", p.title)
                put("subtitle", p.subtitle)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, array.toString()).apply()
    }

    /**
     * Music on the phone, newest first, optionally filtered by [query] across
     * title, artist and album. Needs the audio read permission; without it the
     * list is empty rather than an error.
     */
    suspend fun phoneTracks(context: Context, query: String = ""): List<PhoneTrack> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val q = query.trim()
        val selection = buildString {
            append("${MediaStore.Audio.Media.DURATION} >= 5000")
            if (q.isNotEmpty()) {
                append(" AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?")
                append(" OR ${MediaStore.Audio.Media.ALBUM} LIKE ?)")
            }
        }
        val args = if (q.isNotEmpty()) Array(3) { "%$q%" } else null
        val out = ArrayList<PhoneTrack>()
        runCatching {
            context.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext() && out.size < MAX_RESULTS) {
                    val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    out.add(
                        PhoneTrack(
                            uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                            title = c.getString(titleCol) ?: "Untitled",
                            artist = artist,
                            durationMs = c.getLong(durCol)
                        )
                    )
                }
            }
        }
        out
    }

    private const val MAX_RESULTS = 300
}
