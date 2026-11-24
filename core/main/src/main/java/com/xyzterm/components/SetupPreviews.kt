package com.xyzterm.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.xyzterm.activities.settings.DisclaimerScreen

@Preview(name = "Setup card", showBackground = true)
@Composable
private fun SetupCardPreview() {
    MaterialTheme {
        SetupCard {
            Text(text = "Flat tonal card", style = MaterialTheme.typography.titleMedium)
            Text(text = "Material You, no gradients", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "Step rail", showBackground = true)
@Composable
private fun SetupStepRailPreview() {
    MaterialTheme { SetupStepRail(step = 2) }
}

@Preview(name = "Terms", showBackground = true)
@Composable
private fun DisclaimerScreenPreview() {
    MaterialTheme { DisclaimerScreen(onAccept = {}, onDecline = {}) }
}
