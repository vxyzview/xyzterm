package com.rk.settings.terminal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate
import com.rk.icons.LucideCircleQuestionMark
import com.rk.resources.strings
import com.rk.theme.greenStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CheckStatus {
    PENDING,
    LOADING,
    SUCCESS,
    FAILED,
}

data class Check(
    val label: String,
    val status: CheckStatus = CheckStatus.PENDING,
    val logs: List<String> = emptyList(),
    val isExpanded: Boolean = false,
    val run: suspend (printLog: (String) -> Unit) -> Boolean,
)

@Composable
fun TerminalCheckScreen() {
    val context = LocalContext.current
    val checks = terminalChecks()

    LaunchedEffect(Unit) {
        for (i in checks.indices) {
            val check = checks[i]
            // Update to LOADING and clear logs
            checks[i] = check.copy(status = CheckStatus.LOADING, logs = emptyList())

            val success =
                try {
                    // Health checks spawn processes / hit the network: never block main.
                    val result =
                        withContext(Dispatchers.IO) {
                            check.run { log ->
                                // Append log to the current check's logs
                                checks[i] = checks[i].copy(logs = checks[i].logs + log)
                            }
                        }

                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    checks[i] = checks[i].copy(logs = checks[i].logs + "Crash: ${e.localizedMessage}")
                    false
                }

            checks[i] =
                checks[i].copy(status = if (success) CheckStatus.SUCCESS else CheckStatus.FAILED, isExpanded = !success)
        }
    }

    PreferenceLayout(label = stringResource(strings.terminal_health), backArrowVisible = true) {
        checks.forEachIndexed { index, check ->
            PreferenceGroup {
                PreferenceTemplate(
                    modifier = Modifier.clickable { checks[index] = check.copy(isExpanded = !check.isExpanded) },
                    title = { Text(text = check.label, style = MaterialTheme.typography.bodyLarge) },
                    startWidget = { StatusIcon(status = check.status) },
                    endWidget = {
                        val rotation by
                            animateFloatAsState(
                                targetValue = if (check.isExpanded) 90f else 0f,
                                label = "ChevronRotation",
                            )

                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription =
                                stringResource(
                                    if (check.isExpanded) strings.collapse else strings.expand
                                ),
                            modifier = Modifier.rotate(rotation).size(24.dp),
                        )
                    },
                )

                if (check.isExpanded) {
                    Box(
                        modifier =
                            Modifier.padding(horizontal = 16.dp)
                                .padding(bottom = 12.dp)
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(12.dp)
                    ) {
                        Column {
                            if (check.logs.isEmpty()) {
                                Text(
                                    text = stringResource(strings.no_logs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                check.logs.forEach { log ->
                                    Text(
                                        text = if (log.startsWith(">")) log else "> $log",
                                        style =
                                            MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                                            ),
                                        color =
                                            if (
                                                log.contains("Error", ignoreCase = true) ||
                                                    log.contains("Fail", ignoreCase = true) ||
                                                    log.contains("Crash", ignoreCase = true)
                                            )
                                                MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: CheckStatus) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (status) {
            CheckStatus.PENDING -> {
                Icon(
                    imageVector = LucideCircleQuestionMark,
                    contentDescription = stringResource(strings.pending),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            CheckStatus.LOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 4.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            CheckStatus.SUCCESS -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(strings.success),
                    tint = MaterialTheme.colorScheme.greenStatus,
                    modifier = Modifier.size(20.dp),
                )
            }

            CheckStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(strings.failed),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
