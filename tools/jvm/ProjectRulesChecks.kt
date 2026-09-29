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
        for (made in listOf("squish_1790712584952.mp4", "photo_1790638835660.mp4", "1001319240.jpg", "VID-20260926-WA0104.mp4", "IMG_1234.JPG", "PXL_20260901_101112.mp4", "Screen_Recording_20260929.mp4", "5ee44925-efb7-4c1a-9d3e-2b6f0a1c9e77.mp4", "", null)) {
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

    if (problems.isEmpty()) {
        println("ProjectRulesChecks: all checks passed")
    } else {
        problems.forEach { println("FAIL: $it") }
        exitProcess(1)
    }
}
