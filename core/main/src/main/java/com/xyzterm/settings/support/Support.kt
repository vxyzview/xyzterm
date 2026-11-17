package com.xyzterm.settings.support

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.xyzterm.components.SettingsItem
import com.xyzterm.components.compose.preferences.base.PreferenceGroup
import com.xyzterm.components.compose.preferences.base.PreferenceLayout
import com.xyzterm.resources.drawables
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings

@Composable
fun Support(modifier: Modifier = Modifier) {
    PreferenceLayout(label = stringResource(strings.support), backArrowVisible = true) {
        val context = LocalContext.current

        PreferenceGroup {
            SettingsItem(
                label = "Ko-fi",
                isEnabled = true,
                showSwitch = false,
                default = false,
                startWidget = {
                    Icon(
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        painter = painterResource(drawables.coffee),
                        contentDescription = null,
                    )
                },
                endWidget = {
                    Icon(
                        modifier = Modifier.padding(16.dp),
                        painter = painterResource(drawables.open_in_new),
                        contentDescription = null,
                    )
                },
                sideEffect = {
                    val url = "https://ko-fi.com/vxyzview"
                    val intent = Intent(Intent.ACTION_VIEW).apply { data = url.toUri() }
                    context.startActivity(intent)
                    Settings.donated = true
                },
            )
        }
    }
}
