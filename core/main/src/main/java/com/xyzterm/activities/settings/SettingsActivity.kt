package com.xyzterm.activities.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import com.xyzterm.components.compose.preferences.base.ProvideIsExpandedScreen
import com.xyzterm.file.FileManager
import com.xyzterm.resources.strings
import com.xyzterm.theme.XyzTheme
import com.xyzterm.utils.AppDialogHost
import com.xyzterm.utils.toast
import java.lang.ref.WeakReference

class SettingsActivity : AppCompatActivity() {
    val fileManager = FileManager(this)

    companion object {
        private var activityRef = WeakReference<SettingsActivity?>(null)
        var instance: SettingsActivity?
            get() = activityRef.get()
            private set(value) {
                activityRef = WeakReference(value)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        enableEdgeToEdge()
        setContent {
            XyzTheme {
                AppDialogHost()

                Surface {
                    ProvideIsExpandedScreen {
                        val navController = rememberNavController()
                        SettingsNavHost(activity = this@SettingsActivity, navController = navController)

                        val snackbarHostState = remember { SnackbarHostState() }

                        LaunchedEffect(intent) {
                            if (intent.hasExtra("route")) {
                                val route = intent.getStringExtra("route")
                                if (route != null) {
                                    navController.navigate(route)
                                } else {
                                    toast(strings.unknown_err)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
