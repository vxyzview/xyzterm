package com.xyzterm.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyzterm.resources.strings

/** Three connected steps. Completed steps turn into checkmarks; the current step carries the accent. */
@Composable
fun SetupStepRail(
    step: Int,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val labels =
        listOf(
            stringResource(strings.setup_terms),
            stringResource(strings.setup_permissions),
            stringResource(strings.setup_install),
        )

    if (vertical) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                text = stringResource(strings.setup_step, step),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            labels.forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepNode(index = index, step = step)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (index == step - 1) FontWeight.Bold else FontWeight.Medium,
                        color =
                            if (index < step) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                if (index < labels.lastIndex) {
                    Box(
                        modifier =
                            Modifier.padding(start = 15.dp)
                                .size(width = 3.dp, height = 16.dp)
                                .background(
                                    if (index < step - 1) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                ),
                    )
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                labels.forEachIndexed { index, _ ->
                    if (index > 0) {
                        Box(
                            modifier =
                                Modifier.height(3.dp)
                                    .weight(1f)
                                    .background(
                                        if (index < step) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                    ),
                        )
                    }
                    StepNode(index = index, step = step)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(strings.setup_step, step),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = labels[step - 1],
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StepNode(
    index: Int,
    step: Int,
) {
    val done = index < step - 1
    val current = index == step - 1
    val background =
        when {
            done -> MaterialTheme.colorScheme.primaryContainer
            current -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val foreground =
        when {
            done -> MaterialTheme.colorScheme.onPrimaryContainer
            current -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Box(
        modifier = Modifier.size(32.dp).clip(MaterialTheme.shapes.extraSmall).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = foreground,
            )
        } else {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = foreground,
            )
        }
    }
}
