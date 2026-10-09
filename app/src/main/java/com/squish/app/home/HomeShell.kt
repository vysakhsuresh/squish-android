package com.squish.app.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.squish.app.history.LibraryScreen
import com.squish.app.tools.QuickTool
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.launch

/**
 * The dashboard: three places with one bar between them.
 *
 * Why it is three screens rather than the one scroll it used to be is written
 * on [HomeTab]. The bar floats over the content as a rounded strip, the way
 * every other surface in this app is drawn, rather than sitting as a slab under
 * it - so the lists glide beneath it and it reads as less chrome than it is.
 * What it costs the content is [BAR_ROOM], kept clear at the foot of whichever
 * list is showing.
 *
 * Only these three panes carry it. The tool sessions, a library item, the
 * editor, the quick tools, the export flow and Settings all navigate away from
 * here, and the bar goes with this screen.
 */
@Composable
fun HomeShell(
    onOpenProject: (String) -> Unit,
    onOpenTool: (QuickTool) -> Unit,
    onOpenDrafts: () -> Unit,
    onOpenExport: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    // Saved state, not remembered: the pane survives this screen going into the
    // back stack, so coming back from a library item lands on Library rather
    // than silently on Home.
    var tab by rememberSaveable { mutableStateOf(HomeTab.Projects) }
    val toolDrafts by viewModel.toolDrafts.collectAsState()
    val trashed by viewModel.trashed.collectAsState()
    val marked = HomeTabRules.markedTab(toolDrafts.size, trashed.size)

    val homeList = rememberLazyListState()
    val libraryList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Back leaves the pane before it leaves the app. Declared above the panes so
    // the one HomeScreen registers for its selection mode is the later, and wins
    // while a selection is open.
    BackHandler(enabled = HomeTabRules.backLandsOn(tab) != null) {
        HomeTabRules.backLandsOn(tab)?.let { tab = it }
    }

    Box(modifier = Modifier.fillMaxSize().background(SquishColors.Background)) {
        when (tab) {
            HomeTab.Projects -> HomeScreen(
                onOpenProject = onOpenProject,
                onOpenSettings = onOpenSettings,
                bottomRoom = BAR_ROOM,
                listState = homeList,
                viewModel = viewModel
            )
            HomeTab.Tools -> ToolsPane(
                sessions = toolDrafts.size,
                binned = trashed.size,
                onOpenTool = onOpenTool,
                onOpenDrafts = onOpenDrafts
            )
            HomeTab.Library -> LibraryScreen(
                onBack = null,
                onOpen = onOpenExport,
                listState = libraryList,
                bottomRoom = BAR_ROOM
            )
        }
        // Under the bar, so a list fades out rather than being sliced by it. A
        // floating bar over a list has to do this or the row half under it reads
        // as a drawing fault - a project card cut in two by a strip.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(BAR_ROOM + 56.dp)
                .background(
                    Brush.verticalGradient(
                        0f to SquishColors.Background.copy(alpha = 0f),
                        0.45f to SquishColors.Background,
                        1f to SquishColors.Background
                    )
                )
        )
        HomeBar(
            current = tab,
            marked = marked,
            onSelect = { tapped ->
                if (HomeTabRules.retapScrollsToTop(tab, tapped)) {
                    scope.launch {
                        when (tapped) {
                            HomeTab.Projects -> homeList.animateScrollToItem(0)
                            HomeTab.Library -> libraryList.animateScrollToItem(0)
                            // One screen of tiles; there is nothing to scroll back.
                            HomeTab.Tools -> Unit
                        }
                    }
                } else {
                    tab = tapped
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/** The quick tools, and the door to what they left half done. */
@Composable
private fun ToolsPane(
    sessions: Int,
    binned: Int,
    onOpenTool: (QuickTool) -> Unit,
    onOpenDrafts: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Outside the scroll, and the top one or the title is drawn under
            // the clock: this pane has no Scaffold to hand it the insets.
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = BAR_ROOM),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tools", style = MaterialTheme.typography.displayLarge, color = SquishColors.TextPrimary)
            Text(
                "One job, one tap",
                style = MaterialTheme.typography.bodyMedium,
                color = SquishColors.TextMuted
            )
        }
        QuickTool.entries.chunked(2).forEach { pair ->
            // As tall as the taller of the two, never fixed: at a large font the
            // blurb used to be cut off at a hard-coded height.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)
            ) {
                pair.forEach { tool ->
                    ToolTile(tool = tool, modifier = Modifier.weight(1f).fillMaxHeight()) { onOpenTool(tool) }
                }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
        // Only when there is something behind it, as on the old dashboard.
        if (sessions > 0 || binned > 0) {
            DraftsDoor(
                tools = sessions,
                binned = binned,
                onClick = onOpenDrafts,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun HomeBar(
    current: HomeTab,
    marked: HomeTab?,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            // Full width on a phone held upright, capped and centred on its
            // side: spread over 2400 px the three places sat a hand apart, and
            // a thumb could reach none of them.
            .widthIn(max = BAR_MAX_WIDTH)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SquishColors.Surface)
            .border(1.dp, SquishColors.Border, RoundedCornerShape(22.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HomeTab.entries.forEach { tab ->
            BarItem(
                tab = tab,
                selected = tab == current,
                marked = tab == marked,
                modifier = Modifier.weight(1f)
            ) { onSelect(tab) }
        }
    }
}

/**
 * One place in the bar.
 *
 * [selectable] rather than clickable, and with [Role.Tab]: a tint and a brighter
 * glyph are the only marks of which one you are in, and neither announces
 * anything - the lesson a sweep had already written down for every chip row in
 * the app.
 */
@Composable
private fun BarItem(
    tab: HomeTab,
    selected: Boolean,
    marked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = accentOf(tab)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 54.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box {
            Icon(
                glyphOf(tab),
                contentDescription = null,
                tint = if (selected) accent else SquishColors.TextMuted,
                modifier = Modifier.size(22.dp)
            )
            // A dot, not a count: the question is only whether anything is
            // there. It is named, or a screen reader sees nothing at all.
            if (marked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-2).dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(SquishColors.Cyan)
                        .semantics { contentDescription = "something waiting" }
                )
            }
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else SquishColors.TextMuted
        )
    }
}

private fun accentOf(tab: HomeTab): Color = when (tab) {
    HomeTab.Projects -> SquishColors.Primary
    HomeTab.Tools -> SquishColors.Cyan
    HomeTab.Library -> SquishColors.Violet
}

private fun glyphOf(tab: HomeTab): ImageVector = when (tab) {
    HomeTab.Projects -> Icons.Filled.GridView
    HomeTab.Tools -> Icons.Filled.Bolt
    HomeTab.Library -> Icons.Filled.VideoLibrary
}

/**
 * What the floating bar takes, kept clear at the foot of every pane's list: the
 * strip itself, its margin, and enough that the last row is not half under it.
 */
private val BAR_ROOM = 92.dp

/** As wide as a large phone, and no wider: see the bar's own comment. */
private val BAR_MAX_WIDTH = 460.dp
