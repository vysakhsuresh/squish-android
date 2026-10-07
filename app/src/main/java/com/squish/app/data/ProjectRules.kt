package com.squish.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * The decisions about a project as a thing in a list, kept free of Android so
 * they are executed on the JVM (tools/jvm/ProjectRulesChecks.kt).
 *
 * A project is its own id, not its first video. Keyed by the video's URI, two
 * cuts of the same footage could not coexist, and reopening a clip meant a
 * banner asking which edit this was; keyed by an id handed out when the
 * project is made, the grid on the dashboard is the list of projects, each
 * with a name, a cover and a date of its own, as CapCut's is.
 */
object ProjectRules {

    /** A slot no other project has: the ids are what the files are named after. */
    fun newId(): String = "j" + UUID.randomUUID().toString().replace("-", "")

    /**
     * The name a duplicate takes: "Holiday copy", then "Holiday copy 2" when
     * that one is taken too, never "Holiday copy copy". [taken] is every name
     * already on the list.
     */
    fun copyName(original: String, taken: Collection<String>): String {
        val base = original.trim().ifBlank { "Untitled" }.removeSuffix(" copy").replace(COPY_SUFFIX, "")
        val first = "$base copy"
        if (first !in taken) return first
        var n = 2
        while ("$base copy $n" in taken) n += 1
        return "$base copy $n"
    }

    /**
     * The moment a cover frame is taken from: a little way into the first shot,
     * not its very first frame - a clip's first frame is often black or a
     * hand over the lens - and never past its end. A still is the same picture
     * throughout, so any moment of it will do.
     */
    fun coverTimeMs(sourceInMs: Long, sourceOutMs: Long): Long {
        val start = sourceInMs.coerceAtLeast(0L)
        val span = (sourceOutMs - start).coerceAtLeast(0L)
        return start + minOf(span / 3, COVER_LEAD_MS)
    }

    /**
     * Which of a purged project's files the app may let go of its right to
     * read: those no other project on disk - live or in the bin - still names.
     * Releasing a grant another project relies on would blank that one.
     */
    fun releasable(purged: Set<String>, stillNamed: Set<String>): Set<String> = purged - stillNamed

    /**
     * A clip's kept stretch as read from a draft: in order, and never before
     * zero.
     *
     * The two numbers come out of a JSON object with `optLong`, which answers
     * zero for a key that is not there - so a half-written object, or one a
     * build with other names wrote, can hand back an out-point before its
     * in-point. Everything that then clamps a moment into the window calls
     * `coerceIn(in, out)`, and `coerceIn` on an inverted range *throws* rather
     * than returning anything: sampling a frame, starting a Track, or
     * auto-reframing such a clip would take the editor down. A draft this app
     * wrote is always in order, so this costs nothing and is only ever the
     * difference between a strange edit and no edit at all.
     *
     * The two numbers are swapped rather than one of them discarded: both are
     * in the file, and the only thing wrong with them is which way round.
     */
    fun window(readInMs: Long, readOutMs: Long): Pair<Long, Long> {
        val lo = minOf(readInMs, readOutMs).coerceAtLeast(0L)
        val hi = maxOf(readInMs, readOutMs).coerceAtLeast(lo)
        return lo to hi
    }

    /**
     * The files a project's own bytes are counted over: what it made itself
     * (stills, reversed renders, voice takes, spoken lines) and keeps under the
     * app's storage. The footage picked from the gallery is the phone's, not
     * the project's, so a file:// under the app is the one kind that counts.
     */
    fun ownedFile(uri: String, filesDir: String): Boolean =
        uri.startsWith("file://") && uri.removePrefix("file://").startsWith(filesDir)

    /** "0 B", "1.2 MB", "3.4 GB" - the size on a project card. */
    fun sizeLabel(bytes: Long): String = when {
        bytes < 1_000L -> "$bytes B"
        // The tiers end where the rounding would carry, not at the round
        // number: at 999,999 bytes the old boundaries printed "1000 KB", and
        // at 999,999,999 "1000.0 MB", which is a unit the eye reads twice.
        bytes < 999_500L -> "%.0f KB".format(bytes / 1_000.0)
        bytes < 999_950_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.2f GB".format(bytes / 1_000_000_000.0)
    }

    /**
     * When a project was started, for the sidecar a save is about to write.
     * [storedCreated] and [storedSaved] are what the sidecar on disk holds (0
     * for a key that is not there) and [now] is the moment this save carries.
     *
     * The middle step is the whole of it. `createdAtMillis` is newer than the
     * app, so every project made before it exists only as a `savedAtMillis` -
     * and the readers already know that, falling back to it in four places to
     * decide what an unnamed project is called ("Edit · 29 Sep") and how the
     * grid is sorted. The writer did not: with no stored creation date it took
     * the moment it was writing, so the **first save by a build that knows the
     * field** stamped a project as made today and renamed it after today.
     *
     * Seen on the owner's phone, 7 October: a project from 29 September,
     * opened and left without a change, came back as "Edit · 7 Oct, 5:32 AM"
     * at the top of the grid. The save itself was right - the codec had
     * changed, so the edit encoded differently and the fingerprint moved - but
     * a re-save is not a re-creation, and a project's age is not something a
     * save may invent. Falling back to the stored save time first makes the
     * writer agree with the readers: the oldest moment the project can be
     * shown to have existed.
     */
    fun createdAt(storedCreated: Long, storedSaved: Long, now: Long): Long = when {
        storedCreated > 0L -> storedCreated
        storedSaved > 0L -> storedSaved
        else -> now
    }

    /** How far into a shot a cover is taken, at most. */
    const val COVER_LEAD_MS = 1_000L

    /**
     * What an unnamed project is called on its card. A file someone named -
     * "Beach day.mp4" - keeps its name, less the extension. A name a camera or
     * the gallery made up - "1001319240.jpg", "VID-20260926-WA0104.mp4",
     * "PXL_20260901_101112" - says nothing, so the project is called after
     * the day it was started instead: "Edit · 29 Sep". With no [prefix] - a
     * list of exports, several a day - the moment itself: "29 Sep, 11:47 PM".
     */
    fun displayTitle(label: String?, createdAtMillis: Long, zone: TimeZone = TimeZone.getDefault(), prefix: String? = "Edit"): String {
        val stem = label?.trim()?.substringBeforeLast('.')?.trim().orEmpty()
        if (saysSomething(stem)) return stem
        if (createdAtMillis <= 0L) return "Untitled edit"
        val format = SimpleDateFormat(if (prefix == null) "d MMM, h:mm a" else "d MMM", Locale.getDefault()).apply { timeZone = zone }
        val day = format.format(Date(createdAtMillis))
        return if (prefix == null) day else "$prefix · $day"
    }

    /**
     * A file's name fit to show a person - "Beach day" from "Beach day.mp4" -
     * or null when the name was made up by a camera or the gallery
     * ("1001323287.mp4", "VID-20260926-WA0104") and says nothing.
     */
    fun readableName(label: String?): String? {
        val stem = label?.trim()?.substringBeforeLast('.')?.trim().orEmpty()
        return stem.takeIf { saysSomething(it) }
    }

    /**
     * Whether a file's stem is a name somebody gave rather than one a camera
     * or the gallery made up.
     *
     * Three letters, or one in a script where a word is routinely one or two
     * characters. The floor used to be three letters flat, counted over UTF-16
     * chars - so a whole name in Chinese, Japanese, Korean or Hebrew, which is
     * usually two characters (海滩, 旅行, 바다, ים), was taken for a camera's
     * and thrown away for "Edit · 6 Oct"; the Replace sheet then read "the clip
     * you picked" and Track "the overlay" instead of naming the file. The
     * codebase had already met this once and written it down for captions -
     * TextEdits.translateCaptions, "every one-or-two-character line in Chinese
     * or Japanese, which is a whole sentence" - and the lesson had not reached
     * here.
     *
     * The floor stays at three for the Latin and Cyrillic scripts, where it is
     * what refuses a GoPro's "GH010123" (two letters wrapped round six digits);
     * dropping it to two outright would have let that through.
     *
     * Counted by code point, so a name in a plane past the first is counted at
     * all: a surrogate half is not a letter.
     */
    private fun saysSomething(stem: String): Boolean {
        if (stem.isEmpty() || MADE_UP_NAME.matches(stem) || COPY_NAME.matches(stem)) return false
        var letters = 0
        var compact = false
        var i = 0
        while (i < stem.length) {
            val cp = stem.codePointAt(i)
            if (Character.isLetter(cp)) {
                letters++
                if (isCompactScript(cp)) compact = true
            }
            i += Character.charCount(cp)
        }
        return letters >= 3 || (compact && letters >= 1)
    }

    /** A script whose words are routinely one or two characters. */
    private fun isCompactScript(codePoint: Int): Boolean =
        when (runCatching { Character.UnicodeScript.of(codePoint) }.getOrNull()) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.HEBREW,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER -> true
            else -> false
        }

    /**
     * Titles told apart. Two projects started the same day with no name were
     * both "Edit · 2 Oct" on the dashboard, and which was which took opening
     * them. An unnamed project whose title another shares gets the time it
     * was started ("Edit · 2 Oct, 1:24 PM"), and a number after that if even
     * the minute is shared. A name someone gave is never changed. [entries]
     * are (title, named, createdAtMillis); the result is in the same order.
     */
    fun distinctTitles(entries: List<Triple<String, Boolean, Long>>, zone: TimeZone = TimeZone.getDefault()): List<String> {
        val counts = entries.groupingBy { it.first }.eachCount()
        val clock = SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = zone }
        val timed = entries.map { (title, named, created) ->
            if (named || (counts[title] ?: 0) < 2 || created <= 0L) title else "$title, ${clock.format(Date(created))}"
        }
        val seen = HashMap<String, Int>()
        val again = timed.groupingBy { it }.eachCount()
        return timed.mapIndexed { i, t ->
            if ((again[t] ?: 0) < 2 || entries[i].second) t
            else {
                val n = (seen[t] ?: 0) + 1
                seen[t] = n
                if (n == 1) t else "$t ($n)"
            }
        }
    }

    /**
     * The name a shared file's copy is kept under (MediaAccess.importCopy):
     * sixteen bytes of hex now, or a UUID on anything copied before 7 October.
     * Either says nothing about what is in the file, so neither may become a
     * project's name.
     */
    private val COPY_NAME = Regex(
        "(?i)^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32})$"
    )

    /**
     * What a shared file's copy is called, so that the *same* file opened twice
     * is copied once.
     *
     * Found on the owner's phone, 7 October: **three gigabytes of
     * files/imports**, and most of it the same handful of videos over and over
     * - five copies of one 262 MB file (three of them made in half an hour of
     * testing, by opening the same clip three times), five of another at 8.6 MB,
     * and one 1.9 GB copy of an eight-hour recording. A share whose grant cannot
     * be persisted has to be copied or the draft dies with the process
     * (MediaAccess.importCopy says why); copying it *again* on the next open
     * buys nothing.
     *
     * The name is a digest of what identifies the file to us - what it is called
     * and how long it is - so the second open finds the first copy already
     * there. The caller still checks the copy's length against the source's
     * before trusting it, which is what makes a collision harmless rather than
     * wrong: two different files would have to share a name *and* a byte count
     * to be confused, and then be confused with each other rather than with
     * anything of the user's.
     *
     * Null when the size is unknown, because then there is nothing to check a
     * found copy against and a fresh name is the honest answer.
     */
    fun importCopyName(displayName: String?, sizeBytes: Long, extension: String): String? {
        if (sizeBytes <= 0L) return null
        val key = "${displayName.orEmpty()}/$sizeBytes"
        // A 128-bit digest written as the 32 hex characters COPY_NAME knows.
        // Not a hashCode: 32 bits over a phone's library is a birthday
        // collision waiting to happen, and the cost here is a copy of the wrong
        // film.
        val digest = java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val hex = StringBuilder(32)
        digest.forEach { b -> hex.append("%02x".format(java.util.Locale.ROOT, b)) }
        return "$hex.$extension"
    }

    private val MADE_UP_NAME = Regex(
        "(?i)^(vid|img|pxl|mvimg|dsc|dscn|dcim|mov|video|photo|image|picture|screenshot|screen[ _-]?record\\w*|record\\w*|wa|signal|snapchat|inshot|capcut|squish|squish_trim|overlay|voice|still|rec|take|dji|gopro)[ _-]*[0-9].*"
    )

    private val COPY_SUFFIX = Regex(" copy \\d+$")
}
