package io.github.intelati26.simplemodelview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import simplemodelview.loopLengthOptions

/** "Loop: 12" / "Loop: all": how many frames Play (and a loop video) covers; 0 = all [total] frames. */
@Composable
internal fun LoopLengthMenu(length: Int, total: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(if (length <= 0 || length >= total) "Loop: all" else "Loop: $length")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (n in loopLengthOptions(total)) DropdownMenuItem(text = { Text(if (n == 0) "All $total frames" else "$n frames") }, onClick = { open = false; onPick(n) })
        }
    }
}
