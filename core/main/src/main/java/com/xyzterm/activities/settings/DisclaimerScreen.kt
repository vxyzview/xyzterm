package com.xyzterm.activities.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyzterm.components.BrandHeader
import com.xyzterm.components.SetupStepRail
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings

@Composable
fun DisclaimerScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
        BrandHeader()
        SetupStepRail(step = 1)

        val scrollState = rememberScrollState()
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(strings.disclaimer_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(strings.disclaimer_read_carefully),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )

                Text(
                    text = stringResource(strings.data_loss_risk),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(text = stringResource(strings.data_loss_risk_content), style = MaterialTheme.typography.bodySmall)

                Text(
                    text = stringResource(strings.terminal_risks),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(text = stringResource(strings.terminal_risks_content), style = MaterialTheme.typography.bodySmall)

                Text(
                    text = stringResource(strings.no_warranty),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = stringResource(strings.no_warranty_content), style = MaterialTheme.typography.bodySmall)

                Text(
                    text = stringResource(strings.not_liable),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = stringResource(strings.not_liable_content), style = MaterialTheme.typography.bodySmall)

                Text(
                    text = stringResource(strings.consent_statement),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onDecline) {
                Text(text = stringResource(strings.decline), maxLines = 1)
            }
            val canAccept by remember { derivedStateOf { !scrollState.canScrollForward } }
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
}
