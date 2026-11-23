package com.xyzterm.activities.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyzterm.components.SetupShell
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings

@Composable
fun DisclaimerScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val canAccept by remember { derivedStateOf { !scrollState.canScrollForward } }

    SetupShell(step = 1, scrollState = scrollState) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(strings.terms_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(strings.disclaimer_read_carefully),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TermsSection(
                    title = stringResource(strings.data_loss_risk),
                    body = stringResource(strings.data_loss_risk_content),
                    important = true,
                )
                TermsSection(
                    title = stringResource(strings.terminal_risks),
                    body = stringResource(strings.terminal_risks_content),
                    important = true,
                )
                TermsSection(
                    title = stringResource(strings.no_warranty),
                    body = stringResource(strings.no_warranty_content),
                )
                TermsSection(
                    title = stringResource(strings.not_liable),
                    body = stringResource(strings.not_liable_content),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(strings.consent_statement),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = stringResource(if (canAccept) strings.terms_ready else strings.terms_scroll_hint),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (canAccept) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onDecline) {
                Text(text = stringResource(strings.decline), maxLines = 1)
            }
            Button(
                enabled = canAccept,
                modifier = Modifier.weight(1f),
                onClick = {
                    Settings.shown_disclaimer = true
                    onAccept()
                },
            ) {
                Text(text = stringResource(strings.i_accept), maxLines = 1)
            }
        }
    }
}

@Composable
private fun TermsSection(
    title: String,
    body: String,
    important: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (important) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(text = body, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
