package com.squish.app.media

import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import com.squish.app.editor.TextOverlayItem

/**
 * Burns one caption into the exported video for its [TextOverlayItem.startMs]..[endMs]
 * window. Verify this against the Media3 version pinned in libs.versions.toml on first
 * build - TextOverlay/OverlaySettings' exact anchor API shifted a couple of times across
 * Media3 1.3/1.4 minor releases, and this sandbox couldn't compile-check it (see README).
 */
class SquishTextOverlay(private val item: TextOverlayItem) : TextOverlay() {

    override fun getText(presentationTimeUs: Long): SpannableString {
        val visibleUs = (item.startMs * 1000)..(item.endMs * 1000)
        val text = if (presentationTimeUs in visibleUs) item.text else ""
        return SpannableString(text).apply {
            if (text.isNotEmpty()) {
                setSpan(ForegroundColorSpan(item.colorArgb), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AbsoluteSizeSpan(item.sizeSp, true), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(item.xFraction * 2 - 1, 1 - item.yFraction * 2)
            .build()
    }
}
