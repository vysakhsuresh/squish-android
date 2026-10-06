package com.squish.app.media.effects

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * The cubes on disk.
 *
 * Apart from [LutStore] and [CubeFile], which are arithmetic and text and are
 * executed on the JVM by `tools/jvm/LutChecks.kt`. Nothing Android-shaped can
 * live in those files or the suite cannot compile them, and a checker that
 * cannot be run is a checker nobody runs.
 */
object LutFiles {

    private fun dir(context: Context): File = File(context.filesDir, LutStore.DIR).apply { mkdirs() }

    /**
     * A picked `.cube` copied in and read. The file's own name is kept, made
     * unique, because that name is what the sheet shows and what a draft
     * stores - "Kodak 2383" says more than a number would.
     *
     * Null when it could not be used, with [CubeFile.Problem]'s own words handed
     * to [problem] so the sheet can say what was wrong rather than "failed".
     */
    fun import(context: Context, uri: Uri, problem: (String) -> Unit = {}): String? {
        val display = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "look.cube"

        val text: String = runCatching {
            // Through PickedText, not decodeToString(), which replaces what it
            // cannot read with U+FFFD. The numbers are ASCII either way, so a
            // cube saved in a legacy encoding still loaded - it was the TITLE
            // line that was quietly mangled. Same reader as an imported .srt.
            context.contentResolver.openInputStream(uri)
                ?.use { com.squish.app.data.PickedText.decode(it.readBytes()) }
        }.getOrNull() ?: run {
            problem("That file could not be opened.")
            return null
        }

        val cube = try {
            CubeFile.parse(text)
        } catch (e: CubeFile.Problem) {
            problem(e.message ?: "That file is not a .cube.")
            return null
        } catch (e: IllegalArgumentException) {
            problem(e.message ?: "That file is not a .cube.")
            return null
        }

        val name = LutStore.freeName(display)
        return runCatching {
            File(dir(context), name).writeText(text)
            LutStore.put(name, cube)
            name
        }.getOrElse {
            problem("There was no room to keep that LUT.")
            null
        }
    }

    /**
     * Every cube on disk read back into memory. Done once for the process when
     * the editor starts, because a draft stores only a name and the grade needs
     * the numbers behind it.
     */
    fun loadAll(context: Context) {
        dir(context).listFiles()
            ?.filter { it.extension.equals("cube", ignoreCase = true) && !LutStore.has(it.name) }
            ?.forEach { file ->
                runCatching { CubeFile.parse(file.readText()) }.getOrNull()?.let { LutStore.put(file.name, it) }
            }
    }
}
