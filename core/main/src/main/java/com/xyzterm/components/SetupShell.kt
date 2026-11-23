package com.xyzterm.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyzterm.resources.drawables
import com.xyzterm.resources.strings

/** Compact brand row used by the setup flow. */
@Composable
fun SetupBrand(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                painter = painterResource(drawables.terminal),
                contentDescription = null,
                modifier = Modifier.padding(11.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = stringResource(strings.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(strings.setup_tagline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Quiet tonal wash that gives the setup steps their own ground without fighting the terminal theme. */
@Composable
private fun Modifier.setupBackdrop(): Modifier =
    background(
        Brush.verticalGradient(
            colors =
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.background,
                ),
        ),
    )

/**
 * Centered frame shared by setup steps. Phone: one column. Wide window: a progress sidebar beside the step content,
 * so a tablet shows structure instead of a stretched phone layout.
 */
@Composable
fun SetupShell(
    step: Int,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().setupBackdrop()) {
        val expanded = maxWidth >= 840.dp
        if (expanded) {
            Row(
                modifier =
                    Modifier.fillMaxSize().safeContentPadding().padding(horizontal = 40.dp, vertical = 32.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier.width(260.dp).padding(end = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    SetupBrand()
                    SetupStepRail(step = step, vertical = true)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(strings.permission_later),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier.widthIn(max = 560.dp).verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    content()
                }
            }
        } else {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .safeContentPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    SetupBrand()
                    SetupStepRail(step = step)
                    content()
                }
            }
        }
    }
}
