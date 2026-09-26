@file:androidx.annotation.OptIn(UnstableApi::class)

package com.squish.app.media

import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import com.squish.app.editor.TextOverlayItem

/**
 * Burns one caption into the exported video between its [TextOverlayItem.startMs] and [endMs]
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

    /**
     * Asked for per presentation time, which is exactly the hook a pinned caption
     * needs: a tracked overlay simply reports a different anchor each frame.
     */
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val (x, y) = item.anchorAt(presentationTimeUs / 1000L)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(x * 2 - 1, 1 - y * 2)
            .build()
    }
}
