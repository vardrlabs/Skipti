package com.vardrlabs.skipti

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

// D-260 (G-13, decision 269): a proof surface moved out of src/main, Debug builds only (src/release has none).

/**
 * `--bar-proof [tab|pushed|sheet]` (Debug builds only, a look's tool, not a user flow): each form over rows enough to
 * scroll, in whichever theme and mode the phone is set to.
 */
@Composable
internal fun BarProof(form: String?) {
    val dark = isSystemInDarkTheme()
    Box(Modifier.fillMaxSize().auroraBackground(dark)) {
        when (form) {
            "pushed" -> {
                val scroll = rememberScrollState()
                BarProofRows(scroll)
                var taps by remember { mutableIntStateOf(0) }
                PushedBar("Back taps $taps", onBack = { taps++ }, scroll = scroll)
            }
            "pushed-long" -> {
                val scroll = rememberScrollState()
                BarProofRows(scroll)
                PushedBar("A long group name that has to stay clear of both buttons", onBack = {}, scroll = scroll)
            }
            "sheet" -> {
                val scroll = rememberScrollState()
                BarProofRows(scroll)
                SheetBar("Add expense", onCancel = {}, onDone = {}, scroll = scroll)
            }
            "sheet-long" -> {
                val scroll = rememberScrollState()
                BarProofRows(scroll)
                SheetBar("A long group name that has to stay clear of both buttons", onCancel = {}, onDone = {}, scroll = scroll)
            }
            else -> TabRootScaffold(
                title = "Balance",
                ornament = true,
                trailing = {
                    Box(Modifier.size(BarDecisions.TOUCH_TARGET.dp), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.height(BarDecisions.BAR_HEIGHT.dp), contentAlignment = Alignment.Center) {
                            Icon(TabIcons.UserRound, contentDescription = "Settings", tint = Tok.textDim(dark), modifier = Modifier.size(28.dp))
                        }
                    }
                },
            ) {
                repeat(14) { BarProofRow(it + 1, dark) }
            }
        }
    }
}

@Composable
private fun BarProofRows(scroll: ScrollState) {
    val dark = isSystemInDarkTheme()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 16.dp, end = 16.dp, top = BarDecisions.CONTENT_TOP_PAD.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(14) { BarProofRow(it + 1, dark) }
    }
}

@Composable
private fun BarProofRow(n: Int, dark: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(ThemeArt.panelShape(Appearance.theme, 14))
            .background(Tok.surface(dark))
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("Row $n", style = Type.body(dark))
    }
}
