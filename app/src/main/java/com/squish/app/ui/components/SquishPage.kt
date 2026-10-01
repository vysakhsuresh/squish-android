package com.squish.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.squish.app.ui.theme.SquishColors

/**
 * Every page that is not the dashboard.
 *
 * The back control is a single floating button pinned to the bottom-left, and it
 * stays there while the page scrolls. Two reasons it beats the bar it replaces: on
 * a tall phone the top-left corner is the hardest place on the screen for a thumb
 * to reach, and a fixed target means going back is the same gesture everywhere
 * instead of a different-looking link on each screen.
 *
 * The title lives in the content rather than in a bar, so a page opens with its own
 * name at reading size instead of a strip of chrome above it.
 */
@Composable
fun SquishPage(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    accent: Color = SquishColors.Primary,
    content: @Composable ColumnScope.() -> Unit
) {
    // The back button sits in its own strip at the bottom, the page's colour,
    // and the page scrolls above it: floating over the page it covered what
    // was under it (Settings' storage rows) whenever the page came to rest there.
    Scaffold(
        containerColor = SquishColors.Background,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SquishColors.Background)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, top = 8.dp, bottom = 12.dp)
            ) {
                BackOrb(accent = accent, onClick = onBack)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.displayLarge,
                        color = SquishColors.TextPrimary
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SquishColors.TextMuted
                    )
                }
                content()
                Spacer(modifier = Modifier.height(16.dp))
            }

        }
    }
}

/**
 * The fixed back target. Round, tinted, and in the same place on every page.
 *
 * The size is adjustable for the one screen that cannot float it: the editor's
 * bottom-left corner is the tool rail, so there it sits inline in the header at
 * 40dp. Same shape and same gradient, so going back still looks like one gesture
 * across the whole app rather than two unrelated controls.
 */
@Composable
fun BackOrb(
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    Box(
        // A 40dp orb in the editor's header still answers a 48dp press.
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .shadow(size * 0.25f, CircleShape)
            .clip(CircleShape)
            .background(accentSweep(accent))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = SquishColors.Background,
            modifier = Modifier.size(size * 0.43f)
        )
    }
}
