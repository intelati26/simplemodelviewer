package io.github.intelati26.simplemodelview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A card of the Today page in two sizes: a symbol and a one-line summary (and, where it helps, a small picture), and the details under it when the card is tapped.
 * [key] keeps the card's open state while the screen is rotated or left and come back to; it starts folded unless [startOpen].
 */
@Composable
internal fun ExpandCard(
    key: String, symbol: String, title: String, startOpen: Boolean = false,
    summary: @Composable ColumnScope.() -> Unit, details: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Text(symbol, fontSize = 24.sp, modifier = Modifier.width(34.dp).padding(top = 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                summary()
            }
            Text(if (open) "▴" else "▾", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { details() }
    }
}

/** The hours of a timing card as a row of small blocks (a block is outlined where it is past the line). */
@Composable
internal fun MiniStrip(cells: List<Pair<Color, Boolean>>, height: Int = 12) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((color, over) in cells) Box(
            Modifier.weight(1f).height(height.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surface).background(color)
                .then(if (over) Modifier.border(BorderStroke(1.dp, Color(0xFFB3261E)), RoundedCornerShape(2.dp)) else Modifier),
        )
    }
}
