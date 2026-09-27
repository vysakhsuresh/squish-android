package com.squish.app.media.audio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A song on the phone, as the music browser lists it. */
data class PhoneTrack(val uri: Uri, val title: String, val artist: String?, val durationMs: Long)

/**
 * Where music comes from: the tracks Squish composes itself, and the music
 * already on the phone. Nothing here touches the network.
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
    suspend fun original(context: Context, style: MusicSynth.Style): Uri = withContext(Dispatchers.Default) {
        val file = File(dir(context), "${style.id}.wav")
        if (!file.exists()) MusicSynth.render(style, file)
        Uri.fromFile(file)
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
