package com.rk

import android.app.Application
import android.os.Build
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.LocaleListCompat
import com.github.anrwatchdog.ANRWatchDog
import com.rk.commands.CommandProvider
import com.rk.commands.KeybindingsManager
import com.rk.crashhandler.CrashHandler
import com.rk.extension.ActivityProvider
import com.rk.extension.AppActivityScope
import com.rk.utils.FontCache
import com.rk.icons.pack.IconPackManager
import com.rk.resources.Res
import com.rk.settings.Settings
import com.rk.settings.debugOptions.LogcatService
import com.rk.settings.debugOptions.startThemeFlipperIfNotRunning
import com.rk.utils.DEFAULT_APP_FONT_PATH
import com.rk.utils.DEFAULT_TERMINAL_FONT_PATH
import com.rk.theme.ThemeManager
import com.rk.utils.application
import com.rk.utils.getTempDir
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(DelicateCoroutinesApi::class)
open class App : Application() {
    companion object {
        val versionCode: Long by lazy {
            val app = application ?: throw IllegalStateException("Application is not initialized yet")
            PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
        }

        lateinit var activityScope: AppActivityScope
            private set
    }

    lateinit var iconPackManager: IconPackManager; private set
    lateinit var themeManager: ThemeManager; private set

    init {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        application = this
        Res.application = this
        iconPackManager = IconPackManager(this)
        themeManager = ThemeManager(this)

        // ponytail: scope must exist before ActivityProvider registers, otherwise
        // the very first onActivityResumed callback (terminal activity launch)
        // would null-set a not-yet-constructed scope. Eager construct here.
        App.activityScope = AppActivityScope()
        registerActivityLifecycleCallbacks(ActivityProvider(App.activityScope))

        // ponytail: must run BEFORE FeatureRegistry.initFeatures (below chain reaches
        // ToolbarConfiguration, whose object-init reads CommandProvider.SettingsCommand,
        // a lateinit set only here). Building commands is in-memory (no IO), so doing
        // it synchronously on the main thread is safe and removes the startup race that
        // crashed the app (lateinit property SettingsCommand has not been initialized).
        CommandProvider.buildCommands()

        val currentLocale = Locale.forLanguageTag(Settings.current_lang)
        val appLocale = LocaleListCompat.create(currentLocale)
        AppCompatDelegate.setApplicationLocales(appLocale)

        GlobalScope.launch(Dispatchers.IO) {
            // Command registration now runs synchronously in onCreate() before
            // features init; only keybind loading (file IO) stays off the critical path.
            launch(Dispatchers.IO) {
                KeybindingsManager.loadKeybindings()
            }
            launch(Dispatchers.IO) {
                iconPackManager.indexLocalPacks()
                iconPackManager.indexStoreIconPacks()
            }
            launch(Dispatchers.IO) {
                themeManager.indexLocalThemes()
                themeManager.indexStoreThemes()
            }

            launch(Dispatchers.IO) {
                val appFontPath = Settings.app_font_path.ifEmpty { DEFAULT_APP_FONT_PATH }
                val isAppAsset = if (appFontPath.isNotEmpty()) Settings.is_app_font_asset else true

                val terminalFontPath = Settings.terminal_font_path.ifEmpty { DEFAULT_TERMINAL_FONT_PATH }
                val isTerminalAsset = if (terminalFontPath.isNotEmpty()) Settings.is_terminal_font_asset else true

                FontCache.loadFont(this@App, appFontPath, isAppAsset)
                FontCache.loadFont(this@App, terminalFontPath, isTerminalAsset)
            }

            launch(Dispatchers.IO) { DocumentProvider.setDocumentProviderEnabled(this@App, Settings.expose_home_dir) }

            launch(Dispatchers.IO) {
                getTempDir().apply {
                    if (exists() && listFiles().isNullOrEmpty().not()) {
                        deleteRecursively()
                    }
                }
            }

            // Version migrations do recursive deletes and file copies; keep
            // them off the main thread. Nothing during startup reads their
            // results synchronously (strict_mode/keybind migrations only
            // matter after first frame, and keybinds reload inside inspect).
            launch(Dispatchers.IO) { UpdateManager.inspect() }

            // debug options
            startThemeFlipperIfNotRunning()
            if (Settings.enable_logcat) {
                LogcatService.start(this@App)
            }
        }

        if (Settings.anr_watchdog) {
            ANRWatchDog().start()
        }

        if (Settings.strict_mode) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .apply {
                        detectAll()
                        penaltyLog()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            penaltyListener(Executors.newSingleThreadExecutor()) { violation ->
                                violation.printStackTrace()
                                violation.cause?.let { throw it }
                            }
                        }
                    }
                    .build()
            )
        }
    }
}
