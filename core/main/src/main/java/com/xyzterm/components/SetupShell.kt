package com.xyzterm.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyzterm.resources.drawables
import com.xyzterm.resources.strings

/** Compact brand row used by the setup flow. */
@Composable
fun SetupBrand(
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                painter = painterResource(drawables.terminal),
                contentDescription = null,
                modifier = Modifier.padding(10.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = stringResource(strings.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(strings.setup_tagline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Page title and one-line explanation shared by the setup steps. */
@Composable
fun SetupHeading(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Flat tonal card shared by every setup step; the outline keeps it legible on AMOLED and light themes. */
@Composable
fun SetupCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(content = content)
    }
}

/**
 * Frame shared by setup steps. Phone: the brand and step rail stay fixed while the step content scrolls. Wide window: a
 * progress sidebar beside the step content, so a tablet shows structure instead of a stretched phone layout.
 */
@Composable
fun SetupShell(
    step: Int,
    scrollState: ScrollState = rememberScrollState(),
    brandTrailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val expanded = maxWidth >= 840.dp
        val compactHeight = maxHeight < 480.dp
        if (expanded) {
            Row(
                modifier =
                    Modifier.fillMaxSize()
                        .safeContentPadding()
                        .padding(horizontal = 24.dp, vertical = if (compactHeight) 8.dp else 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier.width(228.dp).padding(end = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SetupBrand(trailing = brandTrailing)
                    SetupStepRail(step = step, vertical = true)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(strings.permission_later),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier =
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight().verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    content()
                }
            }
        } else {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .safeContentPadding()
                        .padding(horizontal = 16.dp, vertical = if (compactHeight) 8.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SetupBrand(trailing = brandTrailing)
                Spacer(modifier = Modifier.height(if (compactHeight) 8.dp else 14.dp))
                SetupStepRail(step = step)
                Spacer(modifier = Modifier.height(if (compactHeight) 8.dp else 18.dp))
                Column(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f).verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    content()
                }
            }
        }
    }
}
