package com.xyzterm.terminal

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.xyzterm.activities.settings.DisclaimerScreen
import com.xyzterm.activities.terminal.Terminal
import com.xyzterm.components.SetupShell
import com.xyzterm.file.FilePermission
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings
import kotlinx.coroutines.launch

private enum class SetupStep { Terms, Permissions, Install }

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

        SetupStep.Permissions -> PermissionStep(activity = activity, onContinue = { step = SetupStep.Install })

        SetupStep.Install -> installContent()
    }
}

@Composable
private fun PermissionStep(activity: Terminal, onContinue: () -> Unit) {
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    var storageGranted by remember { mutableStateOf(!FilePermission.needsStoragePermission(activity)) }
    var notificationsGranted by remember { mutableStateOf(!activity.notificationPermissionNeeded()) }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsGranted = granted
        }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    storageGranted = !FilePermission.needsStoragePermission(activity)
                    notificationsGranted = !activity.notificationPermissionNeeded()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SetupShell(step = 2) {
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

        PermissionCard(
            icon = Icons.Outlined.Info,
            label = stringResource(strings.permission_storage),
            description = stringResource(strings.permission_storage_desc),
            granted = storageGranted,
            onClick = {
                activity.lifecycleScope.launch {
                    FilePermission.verifyStoragePermission(activity)
                    storageGranted = !FilePermission.needsStoragePermission(activity)
                }
            },
        )
        PermissionCard(
            icon = Icons.Outlined.Settings,
            label = stringResource(strings.permission_notifications),
            description = stringResource(strings.permission_notifications_desc),
            granted = notificationsGranted,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    notificationsGranted = true
                }
            },
        )

        Text(
            text = stringResource(strings.permission_later),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(modifier = Modifier.fillMaxWidth(), onClick = onContinue) {
            Text(text = stringResource(strings.permission_continue))
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    label: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (granted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    }
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 16.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(if (granted) strings.permission_granted else strings.permission_not_granted),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Checkbox(checked = granted, onCheckedChange = null)
        }
    }
}
