package com.squish.app.media.audio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
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
    fun isReady(context: Context, style: MusicSynth.Style): Boolean =
        File(dir(context), "${style.id}.wav").exists()

    /**
     * The track's file, composed the first time it is asked for. Kept in app
     * storage, so a project that uses it can always find it again.
     */
    suspend fun original(context: Context, style: MusicSynth.Style): Uri? = withContext(Dispatchers.Default) {
        val file = File(dir(context), "${style.id}.wav")
        // A full phone can refuse the write: no track, rather than a crash in the panel.
        runCatching { if (!file.exists()) MusicSynth.render(style, file); Uri.fromFile(file) }
            .getOrElse { file.delete(); null }
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
