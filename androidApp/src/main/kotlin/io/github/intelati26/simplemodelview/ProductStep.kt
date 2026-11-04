package io.github.intelati26.simplemodelview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small arrow beside the product chip that steps to the previous (‹) or next (›) product in the picker's order. */
@Composable
internal fun ProductStepButton(next: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.height(32.dp).widthIn(min = 32.dp).semantics { contentDescription = if (next) "Next product" else "Previous product" },
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) { Text(if (next) "›" else "‹", fontSize = 24.sp) }
}
