package com.squish.app.settings

import android.content.Context
import com.squish.app.editor.EditorUiState
import com.squish.app.media.ExportQuality
import com.squish.app.media.ExportSettings

/**
 * What the last export was set to: size, frame rate, quality, codec, HDR and
 * the size limit. A new project starts from these, the way CapCut's does;
 * every edit used to open at Original, so a 4K60 clip re-encoded at 4K60
 * until the sheet was changed by hand, on every project.
 *
 * A draft keeps its own choices in its file (ProjectAutosave) and those win
 * when it is reopened; these only fill in a fresh project.
 */
data class ExportDefaults(
    val outputP: Int,
    val outputFps: Int,
    val quality: ExportQuality,
    val hevc: Boolean,
    val keepHdr: Boolean,
    val fitToSize: Boolean,
    val targetSizeMb: Int
)

/**
 * The app's own settings, kept in SharedPreferences like the starred songs
 * and the saved text styles are - one small file, read once on the way into
 * the editor.
 */
object Preferences {

    /** The last export's choices, or null when nothing has been exported from the editor yet. */
    fun exportDefaults(context: Context): ExportDefaults? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_OUTPUT_P)) return null
        return ExportDefaults(
            outputP = prefs.getInt(KEY_OUTPUT_P, 0),
            outputFps = prefs.getInt(KEY_FPS, ExportSettings.SOURCE_FPS),
            quality = ExportQuality.fromName(prefs.getString(KEY_QUALITY, null)),
            hevc = prefs.getBoolean(KEY_HEVC, false),
            keepHdr = prefs.getBoolean(KEY_KEEP_HDR, false),
            fitToSize = prefs.getBoolean(KEY_FIT, false),
            targetSizeMb = prefs.getInt(KEY_TARGET_MB, 16)
        )
    }

    /** Keeps [state]'s export choices as the next project's defaults. Sound-only is not kept: it is a choice for one file. */
    fun rememberExport(context: Context, state: EditorUiState) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_OUTPUT_P, state.outputP)
            .putInt(KEY_FPS, state.outputFps)
            .putString(KEY_QUALITY, state.quality.name)
            .putBoolean(KEY_HEVC, state.hevc)
            .putBoolean(KEY_KEEP_HDR, state.keepHdr)
            .putBoolean(KEY_FIT, state.fitToSize)
            .putInt(KEY_TARGET_MB, state.targetSizeMb)
            .apply()
    }

    private const val FILE = "export_defaults"
    private const val KEY_OUTPUT_P = "outputP"
    private const val KEY_FPS = "outputFps"
    private const val KEY_QUALITY = "quality"
    private const val KEY_HEVC = "hevc"
    private const val KEY_KEEP_HDR = "keepHdr"
    private const val KEY_FIT = "fitToSize"
    private const val KEY_TARGET_MB = "targetSizeMb"
}

/**
 * A fresh project with the last export's choices on it. The size is taken
 * only when it is not bigger than this footage (ExportSettings.defaultOutputP):
 * a 4K kept from the last project would upscale every 720p clip by default.
 */
fun EditorUiState.withExportDefaults(defaults: ExportDefaults): EditorUiState = copy(
    outputP = ExportSettings.defaultOutputP(defaults.outputP, minOf(sourceWidth, sourceHeight)),
    outputFps = defaults.outputFps,
    quality = defaults.quality,
    hevc = defaults.hevc,
    keepHdr = defaults.keepHdr,
    fitToSize = defaults.fitToSize,
    targetSizeMb = defaults.targetSizeMb
)
