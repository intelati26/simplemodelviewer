package io.github.intelati26.simplemodelview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The minimal layout (a setting): the map has the whole screen. What is left over it is a small menu button with a one-line status next to it, and, only
 * while a point is tapped, the read-out strip at the bottom. The menu button opens a sheet over the lower part of the screen with everything the normal
 * layout shows in its two strips ([menu]: the product, run, sector, share, overlays, legend, hour slider and loop controls); the map stays visible above it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.MinimalChrome(
    summary: String, busy: Boolean, panel: Color,
    readout: @Composable ColumnScope.() -> Unit,
    menu: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = { open = true }, shape = CircleShape, color = panel, shadowElevation = 2.dp,
            modifier = Modifier.size(44.dp).semantics { contentDescription = "Menu" },
        ) { Box(contentAlignment = Alignment.Center) { Text("☰", style = MaterialTheme.typography.titleLarge) } }
        if (summary.isNotEmpty() || busy) Surface(shape = RoundedCornerShape(16.dp), color = panel) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(summary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    // the strip sits above the navigation bar and has a background only while it has something to show (a tapped point)
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding()) { Column(Modifier.fillMaxWidth().background(panel)) { readout() } }
    if (open) ModalBottomSheet(
        onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), scrimColor = Color.Transparent,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) { menu() }
    }
}
