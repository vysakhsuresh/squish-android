package com.squish.app.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp

/**
 * A row of chips that scrolls sideways and opens with the lit one in sight.
 * A line animated with Spin opened its sheet on None · Fade · Pop · Slide ·
 * Type · Bounce with nothing lit, as if it had no arrival at all. Once, when
 * the row first lays out: scrolling again on every pick would pull the chip
 * out from under the finger that tapped it.
 */
@Composable
fun <T> SideScrollChips(
    options: List<T>,
    isSelected: (T) -> Boolean,
    modifier: Modifier = Modifier,
    chip: @Composable (T) -> Unit
) {
    val scroll = rememberScrollState()
    var litAt by remember { mutableStateOf<Int?>(null) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(litAt, scroll.viewportSize) {
        val x = litAt ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        if (settled || viewport <= 0) return@LaunchedEffect
        settled = true
        // Only when it is out of the first screenful, and then a third in, so
        // the chip before it shows and the row reads as one that scrolls.
        if (x > viewport * 2 / 3) scroll.scrollTo((x - viewport / 3).coerceAtLeast(0))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.horizontalScroll(scroll)) {
        options.forEach { option ->
            val lit = isSelected(option)
            Box(if (lit && !settled) Modifier.onGloballyPositioned { litAt = it.positionInParent().x.toInt() } else Modifier) {
                chip(option)
            }
        }
    }
}
