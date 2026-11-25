package com.xyzterm.settings.about

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.xyzterm.BuildConfig
import com.xyzterm.components.SettingsItem
import com.xyzterm.components.compose.preferences.base.PreferenceGroup
import com.xyzterm.components.compose.preferences.base.PreferenceLayout
import com.xyzterm.components.compose.preferences.base.PreferenceTemplate
import com.xyzterm.resources.drawables
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Preference
import com.xyzterm.utils.copyToClipboard
import com.xyzterm.utils.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

private const val STARS_KEY = "github_stars"
private const val STARS_TIME_KEY = "github_stars_fetched_at"
private const val STARS_TTL_MS = 24 * 60 * 60 * 1000L

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val pm = context.packageManager
    val appIcon = remember { pm.getApplicationIcon(context.packageName) }
    val packageInfo = remember { pm.getPackageInfo(context.packageName, 0) }
    val versionName = packageInfo.versionName
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

    PreferenceLayout(label = stringResource(id = strings.about), backArrowVisible = true) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(112.dp),
            ) {
                AsyncImage(
                    model = appIcon,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp).fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(strings.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = versionName.toString().uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        PreferenceGroup(heading = stringResource(strings.developer)) {
            PreferenceTemplate(
                title = {
                    Text(
                        text = stringResource(strings.developer_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
            )

            SettingsItem(
                label = stringResource(id = strings.github),
                description = "vxyzview/xyzterm",
                isEnabled = true,
                showSwitch = false,
                default = false,
                endWidget = {
                    Icon(
                        modifier = Modifier.padding(16.dp),
                        painter = painterResource(drawables.open_in_new),
                        contentDescription = null,
                    )
                },
                sideEffect = {
                    val url = "https://github.com/vxyzview/xyzterm"
                    val intent = Intent(Intent.ACTION_VIEW).apply { data = url.toUri() }
                    context.startActivity(intent)
                },
            )
        }

        PreferenceGroup(heading = stringResource(strings.build_info)) {
            // Tap-to-copy build info. The copy action lives on a normal tap so
            // TalkBack announces a real button (a single tap does something) instead
            // of a dead "button" whose action was hidden behind a long-press.
            PreferenceTemplate(
                modifier = Modifier.clickable { copyToClipboard(versionName.toString()) },
                title = {
                    Text(text = stringResource(id = strings.version), style = MaterialTheme.typography.titleMedium)
                },
                description = { Text(text = versionName.toString(), style = MaterialTheme.typography.titleSmall) },
            )

            PreferenceTemplate(
                modifier = Modifier.clickable { copyToClipboard(versionCode.toString()) },
                title = {
                    Text(text = stringResource(id = strings.version_code), style = MaterialTheme.typography.titleMedium)
                },
                description = { Text(text = versionCode.toString(), style = MaterialTheme.typography.titleSmall) },
            )

            PreferenceTemplate(
                modifier = Modifier.clickable { copyToClipboard(BuildConfig.GIT_SHORT_COMMIT_HASH) },
                title = {
                    Text(text = stringResource(id = strings.git_commit), style = MaterialTheme.typography.titleMedium)
                },
                description = {
                    Text(text = BuildConfig.GIT_SHORT_COMMIT_HASH, style = MaterialTheme.typography.titleSmall)
                },
            )
        }

        PreferenceGroup(heading = stringResource(strings.community)) {
            val stars = remember { mutableStateOf(Preference.getString(STARS_KEY, strings.loading.getString())) }

            LaunchedEffect(Unit) {
                // Cached for a day: opening About shouldn't make a network call
                // every time, and the count isn't time-sensitive.
                val cached = Preference.getString(STARS_KEY, "")
                val fresh = System.currentTimeMillis() - Preference.getLong(STARS_TIME_KEY, 0L) < STARS_TTL_MS
                if (cached.isNotEmpty() && fresh) {
                    return@LaunchedEffect
                }
                val client = okHttpClient
                val url = "https://api.github.com/repos/vxyzview/xyzterm"
                val request = Request.Builder().url(url).build()

                withContext(Dispatchers.IO) {
                    try {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val jsonBody = response.body.string()
                                val json = JSONObject(jsonBody)
                                val count = json.getInt("stargazers_count")
                                val value = count.toString()

                                Preference.setString(STARS_KEY, value)
                                Preference.setLong(STARS_TIME_KEY, System.currentTimeMillis())
                                withContext(Dispatchers.Main) { stars.value = value }
                            } else {
                                stars.value = strings.error.getString()
                            }
                        }
                    } catch (e: Exception) {
                        stars.value = e.message ?: strings.api_error.getString()
                    }
                }
            }

            PreferenceTemplate(
                title = {
                    Text(text = stringResource(strings.github_stars), style = MaterialTheme.typography.titleMedium)
                },
                description = { Text(text = stars.value, style = MaterialTheme.typography.titleSmall) },
            )

            SettingsItem(
                label = stringResource(id = strings.github),
                description = stringResource(id = strings.github_desc),
                isEnabled = true,
                showSwitch = false,
                default = false,
                endWidget = {
                    Icon(
                        modifier = Modifier.padding(16.dp),
                        painter = painterResource(drawables.open_in_new),
                        contentDescription = null,
                    )
                },
                sideEffect = {
                    val url = "https://github.com/vxyzview/xyzterm"
                    val intent = Intent(Intent.ACTION_VIEW).apply { data = url.toUri() }
                    context.startActivity(intent)
                },
            )
        }
    }
}
