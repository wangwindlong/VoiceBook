package us.wangxy.voicebook.bloom

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun BloomChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalBloomTokens.current
    val shape = rememberBloomShape(percent = 50)
    val interactionSource = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.surfaceVariant.copy(alpha = 0.72f),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "bloomChipContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimary else scheme.onSurface,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "bloomChipContent",
    )
    val edge: Color = if (selected) Color.Transparent else scheme.outline.copy(alpha = 0.35f)
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .bloomHalo(
                enabled = selected,
                intensity = tokens.glowIntensity * 0.7f,
                spread = 10.dp,
            )
            .bloomPress(enabled = true)
            .clip(shape)
            .background(container)
            .border(1.dp, edge, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = content,
    )
}
