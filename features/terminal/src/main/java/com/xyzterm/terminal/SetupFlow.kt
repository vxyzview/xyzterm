package com.xyzterm.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.xyzterm.activities.settings.DisclaimerScreen
import com.xyzterm.activities.terminal.Terminal
import com.xyzterm.components.BrandHeader
import com.xyzterm.components.SetupStepRail
import com.xyzterm.file.FilePermission
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings
import kotlinx.coroutines.launch

private enum class SetupStep {
    Terms,
    Permissions,
    Install,
}

/** Three calm steps before the first shell: terms, permissions, then install. */
@Composable
fun SetupFlow(activity: Terminal, installContent: @Composable () -> Unit) {
    var step by remember {
        mutableStateOf(
            when {
                !Settings.shown_disclaimer -> SetupStep.Terms
                FilePermission.needsStoragePermission(activity) || activity.notificationPermissionNeeded() ->
                    SetupStep.Permissions
                else -> SetupStep.Install
            },
        )
    }

    when (step) {
        SetupStep.Terms ->
            DisclaimerScreen(
                onAccept = { step = SetupStep.Permissions },
                onDecline = { activity.finishAffinity() },
            )

        SetupStep.Permissions ->
            PermissionStep(
                needsNotifications = activity.notificationPermissionNeeded(),
                onContinue = {
                    activity.requestNotificationPermission()
                    activity.lifecycleScope.launch { FilePermission.verifyStoragePermission(activity) }
                    step = SetupStep.Install
                },
                onSkip = {
                    Settings.ignore_storage_permission = true
                    step = SetupStep.Install
                },
            )

        SetupStep.Install -> installContent()
    }
}

@Composable
private fun PermissionStep(
    needsNotifications: Boolean,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BrandHeader()
        SetupStepRail(step = 2)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(strings.permission_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(strings.permission_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PermissionRow(
                    icon = Icons.Outlined.Folder,
                    label = stringResource(strings.permission_storage),
                    description = stringResource(strings.permission_storage_desc),
                )
                if (needsNotifications) {
                    PermissionRow(
                        icon = Icons.Outlined.Notifications,
                        label = stringResource(strings.permission_notifications),
                        description = stringResource(strings.permission_notifications_desc),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = onSkip) {
                Text(stringResource(strings.permission_skip), maxLines = 1)
            }
            Button(modifier = Modifier.weight(1f), onClick = onContinue) {
                Text(stringResource(strings.permission_continue), maxLines = 1)
            }
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, label: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
