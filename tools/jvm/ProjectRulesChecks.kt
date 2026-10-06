import com.squish.app.data.ProjectRules
import com.squish.app.settings.StorageRules
import kotlin.system.exitProcess

val problems = mutableListOf<String>()
fun check(ok: Boolean, msg: String) { if (!ok) problems += msg }

fun main() {
    // --- Ids are slots: file-name safe, and never the same twice. --------------
    run {
        val ids = List(200) { ProjectRules.newId() }
        check(ids.toSet().size == ids.size, "two projects got the same id")
        check(ids.all { it.startsWith("j") && it.length == 33 && it.all { c -> c.isLetterOrDigit() } }, "an id was not a plain file name: ${ids.first()}")
        // An old draft's slot was "p" and a hash; a new id can never collide with one.
        check(ids.none { it.startsWith("p") }, "a new id could be mistaken for an old slot")
    }

    // --- A duplicate's name never stacks "copy" on "copy". ---------------------
    run {
        check(ProjectRules.copyName("Holiday", emptyList()) == "Holiday copy", "first copy misnamed")
        check(ProjectRules.copyName("Holiday", listOf("Holiday copy")) == "Holiday copy 2", "second copy misnamed")
        check(ProjectRules.copyName("Holiday", listOf("Holiday copy", "Holiday copy 2")) == "Holiday copy 3", "third copy misnamed")
        check(ProjectRules.copyName("Holiday copy", listOf("Holiday copy")) == "Holiday copy 2", "a copy of a copy stacked the word")
        check(ProjectRules.copyName("Holiday copy 2", listOf("Holiday copy", "Holiday copy 2")) == "Holiday copy 3", "a copy of a numbered copy stacked the number")
        check(ProjectRules.copyName("  ", emptyList()) == "Untitled copy", "a blank name was not given a word")
        check(ProjectRules.copyName("Holiday copy 2", emptyList()) == "Holiday copy", "a numbered copy with nothing taken did not go back to the plain copy")
    }

    // --- An unnamed project is called what its file says, or the day. --------
    run {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val day = 1_790_690_000_000L // 29 Sep 2026, 13:53 UTC
        fun t(label: String?) = ProjectRules.displayTitle(label, day, utc)
        check(t("Beach day.mp4") == "Beach day", "a named file lost its name: ${t("Beach day.mp4")}")
        check(t("Holi 2026 final.mov") == "Holi 2026 final", "a name with digits was taken for a made-up one")
        for (made in listOf("squish_1790712584952.mp4", "photo_1790638835660.mp4", "1001319240.jpg", "VID-20260926-WA0104.mp4", "IMG_1234.JPG", "PXL_20260901_101112.mp4", "Screen_Recording_20260929.mp4", "rec_1790737512345.mp4", "5ee44925-efb7-4c1a-9d3e-2b6f0a1c9e77.mp4", "", null)) {
            check(t(made) == "Edit · 29 Sep", "a made-up name was shown: $made -> ${t(made)}")
        }
        check(ProjectRules.displayTitle("1001.jpg", 0L, utc) == "Untitled edit", "no date and no name was not Untitled")
        val moment = ProjectRules.displayTitle("squish_1790712584952.mp4", day, utc, prefix = null)
        check(moment.startsWith("29 Sep, 1:53"), "an export's made-up name was not its moment: $moment")
    }

    // --- The cover is a little way into the shot, never past its end. ----------
    run {
        check(ProjectRules.coverTimeMs(0L, 30_000L) == 1_000L, "a long shot's cover was not one second in")
        check(ProjectRules.coverTimeMs(5_000L, 35_000L) == 6_000L, "a trimmed shot's cover ignored its in point")
        check(ProjectRules.coverTimeMs(0L, 900L) == 300L, "a short shot's cover was not a third in")
        check(ProjectRules.coverTimeMs(0L, 0L) == 0L, "an empty shot's cover moved")
        check(ProjectRules.coverTimeMs(2_000L, 1_000L) == 2_000L, "an inverted window went backwards")
        check(ProjectRules.coverTimeMs(-5L, 100L) == 33L, "a negative in point was not clamped")
    }

    // --- A purge lets go of what nothing else names, and only that. -----------
    run {
        val purged = setOf("content://a", "content://b", "file:///data/x")
        val still = setOf("content://b", "content://z")
        check(ProjectRules.releasable(purged, still) == setOf("content://a", "file:///data/x"), "the release set was wrong")
        check(ProjectRules.releasable(emptySet(), still).isEmpty(), "nothing purged released something")
        check(ProjectRules.releasable(purged, purged).isEmpty(), "a file another draft names was released")
    }

    // --- Only the app's own files count towards a project's size. -------------
    run {
        val files = "/data/user/0/com.squish.app/files"
        check(ProjectRules.ownedFile("file:///data/user/0/com.squish.app/files/stills/photo_1.mp4", files), "a still was not counted")
        check(!ProjectRules.ownedFile("content://media/picker/0/com.android.providers.media.photopicker/media/1", files), "a picked video was counted")
        check(!ProjectRules.ownedFile("file:///storage/emulated/0/Download/clip.mp4", files), "a file outside the app was counted")
        check(ProjectRules.sizeLabel(0L) == "0 B", "zero mislabelled")
        check(ProjectRules.sizeLabel(12_345L) == "12 KB", "kilobytes mislabelled: ${ProjectRules.sizeLabel(12_345L)}")
        check(ProjectRules.sizeLabel(12_345_678L) == "12.3 MB", "megabytes mislabelled: ${ProjectRules.sizeLabel(12_345_678L)}")
        check(ProjectRules.sizeLabel(2_500_000_000L) == "2.50 GB", "gigabytes mislabelled: ${ProjectRules.sizeLabel(2_500_000_000L)}")
        // The tiers end where the rounding carries, so no label ever says a
        // thousand of the unit below the one it should have used.
        check(ProjectRules.sizeLabel(999_999L) == "1.0 MB", "999,999 bytes reads ${ProjectRules.sizeLabel(999_999L)}")
        check(ProjectRules.sizeLabel(999_499L) == "999 KB", "999,499 bytes reads ${ProjectRules.sizeLabel(999_499L)}")
        check(ProjectRules.sizeLabel(999_999_999L) == "1.00 GB", "999,999,999 bytes reads ${ProjectRules.sizeLabel(999_999_999L)}")
        check(ProjectRules.sizeLabel(999_000L) == "999 KB", "999,000 bytes reads ${ProjectRules.sizeLabel(999_000L)}")
        // And nothing in the ladder ever prints four digits before the point,
        // up to the largest number that can reach it. The ladder stops at
        // gigabytes on purpose: a phone's whole storage is about a terabyte and
        // one project cannot be a thousand gigabytes, so a terabyte tier would
        // be a unit nobody will see.
        var bytes = 1L
        while (bytes < 999_000_000_000L) {
            val label = ProjectRules.sizeLabel(bytes)
            val digits = label.substringBefore('.').substringBefore(' ').length
            check(digits <= 3, "$bytes bytes reads \"$label\", which is four digits of a unit")
            bytes = (bytes * 7) / 5 + 1
        }
    }

    // --- A storage clear takes only what no draft names, companions included. --
    run {
        val stills = listOf(
            "/app/files/stills/photo_1.mp4", "/app/files/stills/photo_1.jpg",
            "/app/files/stills/photo_2.mp4", "/app/files/stills/photo_2.jpg",
            "/app/files/stills/clear_16.png", "/app/files/stills/sticker_3.png"
        )
        val named = setOf("/app/files/stills/photo_1.mp4")
        val gone = StorageRules.unreferenced(stills, named)
        check("/app/files/stills/photo_1.mp4" !in gone, "a named still was cleared")
        check("/app/files/stills/photo_1.jpg" !in gone, "a named still's picture was cleared")
        check("/app/files/stills/photo_2.mp4" in gone && "/app/files/stills/photo_2.jpg" in gone, "an unnamed still stayed")
        check("/app/files/stills/clear_16.png" !in gone, "the export's gap still was cleared")
        check("/app/files/stills/sticker_3.png" in gone, "an unnamed sticker stayed")
        check(StorageRules.unreferenced(emptyList(), named).isEmpty(), "an empty folder produced something to clear")
        check(StorageRules.pathOf("file:///app/files/stills/photo_1.mp4") == "/app/files/stills/photo_1.mp4", "a file URI's path was misread")
        check(StorageRules.pathOf("content://media/x") == null, "a content URI produced a path")
        // org.json writes a path's slashes escaped; the reader unescapes them before this.
        check(StorageRules.pathOf("file:///app/files/voice/take.wav?x#y") == "/app/files/voice/take.wav", "a query or fragment reached the path")
    }

    // A file name is shown only when it says something.
    check(ProjectRules.readableName("Beach day.mp4") == "Beach day", "a real name was dropped")
    check(ProjectRules.readableName("1001323287.mp4") == null, "a gallery number was shown as a name")
    check(ProjectRules.readableName("VID-20260926-WA0104.mp4") == null, "a WhatsApp name was shown")
    check(ProjectRules.readableName("PXL_20260901_101112.jpg") == null, "a camera name was shown")
    check(ProjectRules.readableName(null) == null && ProjectRules.readableName("") == null, "nothing gave a name")

    // Two unnamed projects of one day are told apart by the time; a name is kept.
    run {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val day = 1790985600000L // 2 Oct 2026, 00:00 UTC
        val titles = ProjectRules.distinctTitles(listOf(
            Triple("Edit · 2 Oct", false, day + 13 * 3_600_000L + 24 * 60_000L),
            Triple("Edit · 2 Oct", false, day + 21 * 3_600_000L + 5 * 60_000L),
            Triple("Edit · 1 Oct", false, day - 3_600_000L),
            Triple("Edit · 2 Oct", true, day),
            Triple("Twin", false, day + 60_000L),
            Triple("Twin", false, day + 60_000L)
        ), utc)
        check(titles[0].startsWith("Edit · 2 Oct, 1:24"), "the first of a shared title has no time: ${titles[0]}")
        check(titles[1].startsWith("Edit · 2 Oct, 9:05"), "the second of a shared title has no time: ${titles[1]}")
        check(titles[2] == "Edit · 1 Oct", "a title of its own was changed: ${titles[2]}")
        check(titles[3] == "Edit · 2 Oct", "a name someone gave was changed: ${titles[3]}")
        check(titles[4] != titles[5], "two started the same minute still read the same: ${titles[4]}")
        check(titles.size == titles.toSet().size || titles[3] == "Edit · 2 Oct", "titles still collide: $titles")
    }

    // ---- A clip's window, as read from a draft -----------------------------
    //
    // The two numbers come out of a JSON object with optLong, which answers
    // zero for a key that is not there - so a half-written object, or one a
    // build with other names wrote, can hand back an out-point before its
    // in-point. Everything that clamps a moment into the window then calls
    // coerceIn(in, out), and coerceIn on an inverted range throws rather than
    // returning anything: sampling a frame, starting a Track or auto-reframing
    // such a clip would take the editor down.
    run {
        // In order already: untouched.
        check(ProjectRules.window(1_000L, 5_000L) == 1_000L to 5_000L, "a window in order was changed")
        check(ProjectRules.window(0L, 0L) == 0L to 0L, "an empty window was changed")
        // The way round a missing out-point leaves it: swapped, not discarded -
        // both numbers are in the file and only their order is wrong.
        check(ProjectRules.window(5_000L, 0L) == 0L to 5_000L, "an inverted window was not put in order")
        check(ProjectRules.window(5_000L, 4_999L) == 4_999L to 5_000L, "a window out by a millisecond was not put in order")
        // Never before zero, either end.
        check(ProjectRules.window(-3_000L, 5_000L) == 0L to 5_000L, "a negative in-point survived")
        check(ProjectRules.window(-3_000L, -1_000L) == 0L to 0L, "a window entirely before zero survived")
        check(ProjectRules.window(2_000L, -1_000L) == 0L to 2_000L, "a negative out-point survived")
        // The property every caller leans on, over every mix of signs and
        // orders: the pair can be handed to coerceIn without throwing.
        val values = listOf(Long.MIN_VALUE, -5_000L, -1L, 0L, 1L, 5_000L, Long.MAX_VALUE)
        for (a in values) for (b in values) {
            val (lo, hi) = ProjectRules.window(a, b)
            check(lo <= hi, "window($a, $b) came back inverted: $lo to $hi")
            check(lo >= 0L, "window($a, $b) came back before zero: $lo")
            // Which is exactly what coerceIn needs; run it to be sure, inside
            // a runCatching so a bad pair is *reported* rather than thrown out
            // of the suite before the failures above are printed.
            check(
                runCatching { 0L.coerceIn(lo, hi) in lo..hi }.getOrDefault(false),
                "window($a, $b) gave $lo to $hi, which coerceIn throws on"
            )
        }
    }

    // ---- A whole name in two characters is still a name. -------------------
    //
    // The floor was three letters flat, counted over UTF-16 chars, and a whole
    // name in Chinese, Japanese, Korean or Hebrew is routinely two - so it was
    // taken for a camera's and thrown away for "Edit · 6 Oct", and readableName
    // then returned null, so the Replace sheet read "the clip you picked" and
    // Track "the overlay" instead of naming the file. The codebase had met this
    // once already and written it down for captions ("every one-or-two-
    // character line in Chinese or Japanese, which is a whole sentence") and
    // the lesson had not reached here. This suite tested Latin names only.
    run {
        val day = 1_790_000_000_000L
        val utc = java.util.TimeZone.getTimeZone("UTC")
        listOf(
            "海滩.mp4" to "海滩",            // beach, Chinese
            "旅行.mp4" to "旅行",            // travel
            "2026旅行.mp4" to "2026旅行",    // and with a year on the front
            "바다.mp4" to "바다",            // sea, Korean
            "ים.mp4" to "ים",               // sea, Hebrew
            "海.mp4" to "海",                // one character is a word too
            "海辺日落.mp4" to "海辺日落"
        ).forEach { (file, name) ->
            check(
                ProjectRules.displayTitle(file, day, utc) == name,
                "\"$file\" was called \"${ProjectRules.displayTitle(file, day, utc)}\" rather than \"$name\""
            )
            check(ProjectRules.readableName(file) == name, "\"$file\" read as ${ProjectRules.readableName(file)}")
        }
        // And the floor still does its job on the Latin side, which is the only
        // reason it is three there: a GoPro's name is two letters wrapped round
        // six digits, and dropping the floor to two outright would admit it.
        listOf("GH010123.MP4", "C0001.MP4", "P1000123.JPG", "DJI_0001.MP4").forEach { file ->
            check(
                ProjectRules.readableName(file) == null,
                "the camera name \"$file\" read as a name: ${ProjectRules.readableName(file)}"
            )
        }
        // The made-up patterns still win over the script rule.
        check(ProjectRules.readableName("VID-20260926-WA0104.mp4") == null, "a WhatsApp name came back")
        check(ProjectRules.readableName("1001323287.mp4") == null, "a gallery number came back")
        // A name with no letters at all is not a name.
        check(ProjectRules.readableName("2026.mp4") == null, "a bare year read as a name")
        check(ProjectRules.readableName("- .mp4") == null, "punctuation read as a name")
    }

    if (problems.isEmpty()) {
        println("ProjectRulesChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}
