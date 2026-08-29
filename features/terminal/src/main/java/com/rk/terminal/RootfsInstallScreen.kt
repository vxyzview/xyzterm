package com.rk.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rk.resources.strings

/**
 * Renders the rootfs install progress: a centered status line + LinearProgressIndicator
 * + percent (when size is known) + bottom warning. Pure presentational — takes only an
 * [InstallProgress] and renders the same bytes the activity used to inline.
 */
@Composable
fun RootfsInstallScreen(progress: InstallProgress, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatProgressText(progress),
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { if (progress.totalBytes > 0) progress.downloadedBytes.toFloat() / progress.totalBytes else 0f },
                modifier = Modifier.fillMaxWidth(0.8f),
            )

            if (progress.totalBytes > 0) {
                val percent = (progress.downloadedBytes.toFloat() / progress.totalBytes * 100).toInt()

                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .padding(top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        Text(
            text = stringResource(strings.warn_dont_leave_setup),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .safeDrawingPadding(),
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatProgressText(progress: InstallProgress): String {
    if (progress.totalBytes <= 0) {
        return strings.installing.getString()
    }
    val downloadedMB = "%.2f".format(progress.downloadedBytes / (1024.0 * 1024.0))
    val totalMB = "%.2f".format(progress.totalBytes / (1024.0 * 1024.0))
    val fileLabel = progress.fileName.removeSuffix(".so").removePrefix("lib")
    return "${strings.downloading.getString()} $fileLabel ($downloadedMB/$totalMB MB)"
}
