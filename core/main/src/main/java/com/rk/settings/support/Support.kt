package com.rk.settings.support

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.rk.components.SettingsItem
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.utils.toast

fun isUPISupported(context: Context): Boolean {
    // 1. Check if the user's region is India (Most reliable indicator for UPI)
    val currentLocale = context.resources.configuration.locales[0]
    val isIndia = currentLocale.country.equals("IN", ignoreCase = true)

    // 2. Check if there is at least one app capable of handling a UPI URI
    val uri = "upi://pay".toUri()
    val intent = Intent(Intent.ACTION_VIEW, uri)
    val packageManager = context.packageManager

    // Check if any app can resolve this intent
    val canHandleUPI =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager
                .queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
                .isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        }

    return isIndia || canHandleUPI
}

@Composable
fun Support(modifier: Modifier = Modifier) {
    PreferenceLayout(label = stringResource(strings.support), backArrowVisible = true) {
        val context = LocalContext.current

        PreferenceGroup {
            SettingsItem(
                label = "GitHub Sponsors",
                
                isEnabled = true,
                showSwitch = false,
                default = false,
                startWidget = {
                    Icon(
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        painter = painterResource(drawables.github),
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
                    val url = "https://github.com/sponsors/RohitKushvaha01"
                    val intent = Intent(Intent.ACTION_VIEW).apply { data = url.toUri() }
                    context.startActivity(intent)
                    Settings.donated = true
                },
            )
            SettingsItem(
                label = "Buy Me a Coffee",
                
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
                    val url = "https://buymeacoffee.com/rohitkushvaha01"
                    val intent = Intent(Intent.ACTION_VIEW).apply { data = url.toUri() }
                    context.startActivity(intent)
                    Settings.donated = true
                },
            )
            val upiAvailable = remember { isUPISupported(context) }
            if (upiAvailable) {
                SettingsItem(
                    label = "UPI",
                    
                    isEnabled = true,
                    showSwitch = false,
                    default = false,
                    startWidget = {
                        Icon(
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                            painter = painterResource(drawables.upi_pay),
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
                        val uri =
                            "upi://pay"
                                .toUri()
                                .buildUpon()
                                .appendQueryParameter("pa", "rohitkushwaha01x@axl")
                                .appendQueryParameter("pn", "Rohit Kushwaha")
                                .appendQueryParameter("tn", "xyzterm")
                                .appendQueryParameter("cu", "INR")
                                .build()
                        val intent = Intent(Intent.ACTION_VIEW).apply { data = uri }

                        val chooser = Intent.createChooser(intent, strings.use.getString())
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(chooser)
                            Settings.donated = true
                        } else {
                            toast(strings.no_upi_error)
                        }
                    },
                )
            }
        }
    }
}

