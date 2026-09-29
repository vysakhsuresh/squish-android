package com.squish.app.settings

import android.content.Context
import com.squish.app.editor.CropAspect
import com.squish.app.editor.EditorUiState
import com.squish.app.media.ExportQuality
import com.squish.app.media.ExportSettings
import com.squish.app.media.StillClips
import com.squish.app.timeline.TransitionType

/**
 * What the last export was set to: size, frame rate, quality, codec, HDR and
 * the last size limit chosen. A new project starts from these, the way
 * CapCut's does; every edit used to open at Original, so a 4K60 clip
 * re-encoded at 4K60 until the sheet was changed by hand, on every project.
 *
 * Fit to a size itself is not remembered, any more than Sound only is: both
 * are choices for one file. Remembered, one export squeezed for a chat made
 * every project after it open on a 16 MB limit, downsizing 4K to 720p by
 * default with nothing on the sheet in red. The limit last picked is kept,
 * so the chips are on the right one when Fit is next turned on.
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
    val targetSizeMb: Int
)

/**
 * How a new project starts, and how the editor behaves, as set on Settings:
 * the frame's ratio, how long a photo runs when it is dropped in, the
 * transition every join gets to begin with, whether snapping ticks, and
 * whether the screen stays awake while editing. Each only fills in a new
 * project or an editor session; a draft keeps what it was given.
 */
data class EditorDefaults(
    val cropAspect: CropAspect,
    val stillMs: Long,
    val transition: TransitionType,
    val transitionMs: Long,
    val haptics: Boolean,
    val keepScreenOn: Boolean
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
            targetSizeMb = prefs.getInt(KEY_TARGET_MB, 16)
        )
    }

    /** Keeps [state]'s export choices as the next project's defaults; not Fit or Sound only, which are for one file. */
    fun rememberExport(context: Context, state: EditorUiState) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_OUTPUT_P, state.outputP)
            .putInt(KEY_FPS, state.outputFps)
            .putString(KEY_QUALITY, state.quality.name)
            .putBoolean(KEY_HEVC, state.hevc)
            .putBoolean(KEY_KEEP_HDR, state.keepHdr)
            .putInt(KEY_TARGET_MB, state.targetSizeMb)
            .apply()
    }

    /** Forgets the remembered export choices; the next project opens at Original again. */
    fun forgetExport(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---- The editor's defaults ------------------------------------------------

    fun editorDefaults(context: Context): EditorDefaults {
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        val defaults = EditorDefaults(
            cropAspect = CropAspect.Original,
            stillMs = StillClips.DEFAULT_MS,
            transition = TransitionType.None,
            transitionMs = DEFAULT_TRANSITION_MS,
            haptics = true,
            keepScreenOn = false
        )
        return EditorDefaults(
            cropAspect = prefs.getString(KEY_RATIO, null)?.let { name -> CropAspect.entries.firstOrNull { it.name == name } }
                // The hand-drawn crop is a rectangle, not a default anyone can set.
                ?.takeIf { it != CropAspect.Custom } ?: defaults.cropAspect,
            stillMs = prefs.getLong(KEY_STILL_MS, defaults.stillMs).coerceIn(STILL_CHOICES_MS.first(), STILL_CHOICES_MS.last()),
            transition = prefs.getString(KEY_TRANSITION, null)?.let { name -> TransitionType.entries.firstOrNull { it.name == name } }
                ?: defaults.transition,
            transitionMs = prefs.getLong(KEY_TRANSITION_MS, defaults.transitionMs),
            haptics = prefs.getBoolean(KEY_HAPTICS, defaults.haptics),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn)
        ).also { hapticsOn = it.haptics }
    }

    /** How long a photo runs when it is dropped onto the main track. */
    fun stillMs(context: Context): Long = editorDefaults(context).stillMs

    fun setDefaultRatio(context: Context, aspect: CropAspect) = edit(context) { putString(KEY_RATIO, aspect.name) }
    fun setStillMs(context: Context, ms: Long) = edit(context) { putLong(KEY_STILL_MS, ms) }
    fun setDefaultTransition(context: Context, type: TransitionType) = edit(context) { putString(KEY_TRANSITION, type.name) }
    fun setKeepScreenOn(context: Context, on: Boolean) = edit(context) { putBoolean(KEY_KEEP_SCREEN_ON, on) }
    fun setHaptics(context: Context, on: Boolean) {
        hapticsOn = on
        edit(context) { putBoolean(KEY_HAPTICS, on) }
    }

    /**
     * Whether snapping ticks, as last read or set - the strip asks on every
     * snap, from the frame loop, and a SharedPreferences read there is not
     * free. Read once at start (MainActivity) and kept in step by [setHaptics].
     */
    @Volatile
    var hapticsOn: Boolean = true
        private set

    // ---- Coach marks ----------------------------------------------------------

    /** Whether the one-time hint named [key] has been shown and dismissed. */
    fun coachSeen(context: Context, key: String): Boolean =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getBoolean("coach_$key", false)

    fun markCoachSeen(context: Context, key: String) = edit(context) { putBoolean("coach_$key", true) }

    private inline fun edit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().apply(block).apply()
    }

    /** The lengths a photo may be given by default, on the Settings screen. */
    val STILL_CHOICES_MS: List<Long> = listOf(2_000L, 3_000L, 4_000L, 5_000L, 8_000L)

    /** A default transition's length: the length the Transition sheet starts one at. */
    const val DEFAULT_TRANSITION_MS = 500L

    const val COACH_HOME = "home"
    const val COACH_EDITOR = "editor"

    private const val FILE = "export_defaults"
    private const val KEY_OUTPUT_P = "outputP"
    private const val KEY_FPS = "outputFps"
    private const val KEY_QUALITY = "quality"
    private const val KEY_HEVC = "hevc"
    private const val KEY_KEEP_HDR = "keepHdr"
    private const val KEY_TARGET_MB = "targetSizeMb"

    private const val SETTINGS = "settings"
    private const val KEY_RATIO = "defaultRatio"
    private const val KEY_STILL_MS = "stillMs"
    private const val KEY_TRANSITION = "defaultTransition"
    private const val KEY_TRANSITION_MS = "defaultTransitionMs"
    private const val KEY_HAPTICS = "haptics"
    private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
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
    targetSizeMb = defaults.targetSizeMb
)
