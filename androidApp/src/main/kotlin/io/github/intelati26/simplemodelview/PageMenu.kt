package io.github.intelati26.simplemodelview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The pages of the app: the id saved as the page last used, and the name on the page menu. */
internal val APP_PAGES = listOf("spc" to "REFS", "mrms" to "MRMS", "nbm" to "NBM", "gfs" to "GFS", "point" to "Extended")

/** One button with the name of the page on show; it opens a list of the pages (all of them, the current one marked). */
@Composable
internal fun PageMenu(current: String, onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text((APP_PAGES.firstOrNull { it.first == current }?.second ?: "Pages") + " ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((id, name) in APP_PAGES) DropdownMenuItem(
                text = { Text(if (id == current) "$name  ✓" else name) },
                onClick = { open = false; if (id != current) onOpen(id) },
            )
        }
    }
}
