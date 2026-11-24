package com.xyzterm.terminal

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.xyzterm.activities.settings.DisclaimerScreen
import com.xyzterm.activities.terminal.Terminal
import com.xyzterm.components.SetupCard
import com.xyzterm.components.SetupHeading
import com.xyzterm.components.SetupShell
import com.xyzterm.file.FilePermission
import com.xyzterm.resources.drawables
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
fun SetupFlow(
    activity: Terminal,
    installContent: @Composable () -> Unit,
) {
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

/** Final step after the install finishes: flat Material 3 tonal surfaces, no gradients. */
@Composable
fun SetupCompleteScreen(
    onOpen: () -> Unit,
    onHome: () -> Unit,
) {
    SetupShell(step = 3) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(112.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Text(
                text = stringResource(strings.setup_complete_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(strings.setup_complete_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            SetupCard {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(drawables.terminal),
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(strings.setup_complete_info),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = CircleShape,
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(strings.setup_complete_open))
            }
            OutlinedButton(
                onClick = onHome,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = CircleShape,
            ) {
                Icon(imageVector = Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(strings.back_to_home))
            }
        }
    }
}

@Composable
private fun PermissionStep(
    activity: Terminal,
    onContinue: () -> Unit,
) {
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    val showNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var storageGranted by remember { mutableStateOf(FilePermission.hasStorageAccess(activity)) }
    var notificationsGranted by remember { mutableStateOf(!activity.notificationPermissionNeeded()) }
    var skipped by remember { mutableStateOf(false) }

    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsGranted = granted
        }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = FilePermission.hasStorageAccess(activity)
                notificationsGranted = !activity.notificationPermissionNeeded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val required = if (showNotifications) 2 else 1
    val allowed = (if (storageGranted) 1 else 0) + (if (showNotifications && notificationsGranted) 1 else 0)
    val canContinue = allowed == required || skipped

    SetupShell(step = 2) {
        SetupHeading(
            title = stringResource(strings.permission_heading),
            description = stringResource(strings.permission_desc),
        )
        PermissionProgress(allowed = allowed, required = required)

        SetupCard {
            Column {
                PermissionRow(
                    icon = Icons.Outlined.Info,
                    label = stringResource(strings.permission_storage),
                    description = stringResource(strings.permission_storage_desc),
                    granted = storageGranted,
                    onClick = {
                        activity.lifecycleScope.launch {
                            FilePermission.verifyStoragePermission(activity)
                            storageGranted = FilePermission.hasStorageAccess(activity)
                        }
                    },
                )
                if (showNotifications) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    PermissionRow(
                        icon = Icons.Outlined.Settings,
                        label = stringResource(strings.permission_notifications),
                        description = stringResource(strings.permission_notifications_desc),
                        granted = notificationsGranted,
                        onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = CircleShape,
            enabled = canContinue,
            onClick = onContinue,
        ) {
            Text(text = stringResource(strings.permission_continue))
        }
        if (!canContinue) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = CircleShape,
                onClick = {
                    skipped = true
                    onContinue()
                },
            ) {
                Text(text = stringResource(strings.permission_skip))
            }
        }
    }
}

@Composable
private fun PermissionProgress(
    allowed: Int,
    required: Int,
) {
    val status = stringResource(strings.permission_allowed_count, allowed, required)
    Row(
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = status
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(required) { index ->
                Box(
                    modifier =
                        Modifier.weight(1f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index < allowed) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            ),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (allowed == required) FontWeight.SemiBold else FontWeight.Medium,
            color =
                if (allowed == required) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    label: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 84.dp)
                // A granted row is a status, not an action: don't announce it as a button.
                .semantics { if (!granted) role = Role.Button }
                .clickable(
                    enabled = !granted,
                    onClickLabel =
                        stringResource(if (granted) strings.permission_granted else strings.permission_not_granted),
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = MaterialTheme.shapes.medium,
            color =
                if (granted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(10.dp),
                tint =
                    if (granted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(if (granted) strings.permission_granted else strings.permission_not_granted),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector =
                if (granted) {
                    Icons.Default.Check
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint =
                if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}
