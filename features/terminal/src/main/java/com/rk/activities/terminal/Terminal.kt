package com.rk.activities.terminal

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.view.KeyEvent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rk.UpdateManager
import com.rk.activities.settings.DisclaimerScreen
import com.rk.commands.ActionContext
import com.rk.commands.KeyCombination
import com.rk.commands.KeybindingsManager
import com.rk.exec.isTerminalInstalled
import com.rk.file.FilePermission
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.resources.getFilledString
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.terminal.InstallResult
import com.rk.terminal.InstallProgress
import com.rk.terminal.NEXT_STAGE
import com.rk.terminal.ROOTFS_ARM
import com.rk.terminal.ROOTFS_ARM64
import com.rk.terminal.ROOTFS_X64
import com.rk.terminal.ROOTFS_ARM_SHA256
import com.rk.terminal.ROOTFS_ARM64_SHA256
import com.rk.terminal.ROOTFS_X64_SHA256
import com.rk.terminal.RootfsInstallScreen
import com.rk.terminal.RootfsInstaller
import com.rk.terminal.RootfsSource
import com.rk.terminal.SessionService
import com.rk.terminal.TerminalBackEnd
import com.rk.terminal.TerminalScreen
import com.rk.terminal.TerminalViewPortHolder
import com.rk.terminal.changeSession
import com.rk.theme.XedTheme
import com.rk.utils.AppDialogHost
import com.rk.utils.errorDialog
import com.rk.utils.getTempDir
import com.rk.utils.toast
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class Terminal : AppCompatActivity() {
    var sessionBinder by mutableStateOf<WeakReference<SessionService.SessionBinder>?>(null)
    var isBound = false

    /**
     * The screen surface. Created in [onCreate] before any composable runs so
     * [handleIntent] / [handleDeepLink] can reach the view before the
     * Compose tree attaches. Owned by the activity; both [TerminalScreen]
     * and the back-end constructed inside it read from the same instance.
     *
     * `internal` because the settings screens (different activity, no
     * TerminalView) call back into it for live-apply on font-size and
     * keep-screen-on. They guard with `Terminal.instance != null`.
     */
    internal var port: TerminalViewPortHolder? = null

    val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as SessionService.SessionBinder
                sessionBinder = WeakReference(binder)
                isBound = true
                // Restore saved sessions from previous runs before handling the
                // intent, so the screen picks the last active session instead of
                // a fresh "main". The shells themselves spawn off the main thread
                // (restoreSessions); Compose snapshot state (sessionList/
                // currentSession) is only mutated on the main thread.
                if (binder.getService().sessionList.isEmpty()) {
                    binder.restoreSessions(this@Terminal)
                }
                handleIntent(intent)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                isBound = false
                sessionBinder = null
            }
        }

    override fun onStart() {
        super.onStart()
        ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))

        Intent(this, SessionService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }

        // Auto-update check (throttled to once per day inside).
        UpdateManager.checkForUpdates(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        instance = this
        port?.isForeground?.value = true
    }

    fun handleIntent(intent: Intent) {
        this.intent = intent

        if (intent.data?.scheme == "xyzterm") {
            handleDeepLink(intent.data ?: return)
            return
        }

        val binder = sessionBinder?.get() ?: return
        // Service still restoring saved sessions: the terminalView.get() guard
        // below already defers UI attach, but a write racing an empty session
        // map would be dropped silently. Defer the write until restore lands.
        if (binder.getService().restorePending) {
            binder.getService().onRestored { handleIntent(intent) }
            return
        }

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            lifecycleScope.launch(Dispatchers.Main) {
                val session = binder.getSession(binder.getService().currentSession.value)
                session?.write(text)
            }
            return
        }

        val pwd = intent.getStringExtra("cwd") ?: return
        val activePort = port ?: return
        activePort.view() ?: return

        val sessionId = File(pwd).name

        lifecycleScope.launch(Dispatchers.Main) {
            val client = TerminalBackEnd(activePort)
            val info = binder.getSessionInfoByPwd(pwd) ?: binder.createSession(sessionId, client, this@Terminal)

            this@Terminal.changeSession(info.id, activePort)
            setIntent(intent)
        }
    }

    /**
     * Handles xyzterm:// URIs:
     *   xyzterm://run?cmd=<command>          write command to the current session
     *   xyzterm://session/<name>?cmd=<cmd>   create or switch to session <name>, optionally run <cmd>
     */
    private fun handleDeepLink(uri: Uri) {
        val binder = sessionBinder?.get() ?: return
        when (uri.host) {
            "session" -> {
                val name = uri.lastPathSegment?.trim().orEmpty()
                // ponytail: deep-link session name is attacker-controlled; reject traversal
                // and special segments before they reach MkSession.childSafe / deleteRecursively.
                if (name.isEmpty() ||
                    name == "." ||
                    name == ".." ||
                    name.contains("/") ||
                    name.contains("\\")
                ) {
                    return
                }
                lifecycleScope.launch(Dispatchers.Main) {
                    if (name !in binder.getService().sessionList) {
                        binder.createSession(name, TerminalBackEnd(port ?: return@launch), this@Terminal)
                    }
                    this@Terminal.changeSession(name, port ?: return@launch)
                }
            }
            // "run" and "?cmd=" intentionally dropped: a BROWSABLE link writing commands
            // into a live session is unprompted command execution inside the sandbox.
        }
    }

    override fun onStop() {
        super.onStop()
        port?.isForeground?.value = false
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun needsNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private var activityRef = WeakReference<Terminal?>(null)
        var instance: Terminal?
            get() = activityRef.get()
            private set(value) {
                activityRef = WeakReference(value)
            }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instance = this
        port = TerminalViewPortHolder()

        if (needsNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            XedTheme {
                AppDialogHost()

                Surface {
                    var disclaimerAccepted by remember { mutableStateOf(Settings.shown_disclaimer) }

                    if (!disclaimerAccepted) {
                        DisclaimerScreen(
                            onAccept = { disclaimerAccepted = true },
                            onDecline = { finishAffinity() },
                        )
                    } else if (sessionBinder != null) {
                        LaunchedEffect(Unit) { FilePermission.verifyStoragePermission(this@Terminal) }
                        val port = this@Terminal.port
                        if (port != null) {
                            TerminalScreenHost(this, port)
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(strings.service_connection_error),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { recreate() }) {
                                Text(text = stringResource(strings.retry))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Runtime keybinding dispatch. Hooked at activity level: the native
        // TerminalView consumes hardware keys in its own dispatch path, so
        // Compose-level onPreviewKeyEvent would never see them. Only exact
        // matches fire, and auto-repeat is ignored.
        if (event != null && event.repeatCount == 0) {
            val command = KeybindingsManager.findCommandForKey(KeyCombination.fromEvent(event))
            if (command != null && command.isEnabled() && command.isSupported()) {
                command.performCommand(ActionContext(this))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (!isFinishing) {
            super.onDestroy()
            return
        }

        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        FilePermission.onRequestPermissionsResult(requestCode, grantResults, lifecycleScope, this)
    }

    var installProgress by mutableStateOf<InstallProgress?>(null)
    var installNextStage by mutableStateOf<NEXT_STAGE?>(null)

    @OptIn(DelicateCoroutinesApi::class)
    @Composable
    fun TerminalScreenHost(context: Context, port: TerminalViewPortHolder) {
        var unsupportedCpu by remember { mutableStateOf(false) }
        var downloadStarted by remember { mutableStateOf(false) }
        var ubuntuInstalled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { ubuntuInstalled = withContext(Dispatchers.IO) { isTerminalInstalled() } }

        // handleInstallFailure comes before startInstall so the launch block
        // below can call it directly — forward references to local functions
        // from inside a suspending lambda inside an enclosing @Composable are
        // not always resolved by the Kotlin compiler.
        fun handleInstallFailure(failure: InstallResult.Failure) {
            val error = failure.error
            val file = failure.file
            when (error) {
                is UnknownHostException -> {
                    toast(strings.network_err.getString())
                }

                is SocketTimeoutException -> {
                    errorDialog(strings.timeout)
                }

                else -> {
                    error.printStackTrace()
                    GlobalScope.launch(Dispatchers.IO) {
                        if (file?.absolutePath?.contains(localBinDir().absolutePath) == true) {
                            localBinDir().deleteRecursively()
                        }

                        if (file?.name == "sandbox.tar.gz") {
                            // Drop only the bad tarball so the retry
                            // starts clean. Never wipe sandboxDir()
                            // here: it may hold a working rootfs
                            // from an earlier successful install,
                            // and a transient download failure must
                            // not destroy it.
                            File(getTempDir(), "sandbox.tar.gz").delete()
                        }
                    }
                    errorDialog(msg = strings.setup_failed.getFilledString(error.message))
                }
            }
            downloadStarted = false
        }

        fun startInstall() {
            downloadStarted = true
            installProgress = InstallProgress(fileName = "", downloadedBytes = 0L, totalBytes = 0L)

            lifecycleScope.launch(Dispatchers.Main) {
                val abi = Build.SUPPORTED_ABIS

                val rootfs =
                    when {
                        abi.contains("x86_64") -> ROOTFS_X64 to ROOTFS_X64_SHA256
                        abi.contains("arm64-v8a") -> ROOTFS_ARM64 to ROOTFS_ARM64_SHA256
                        abi.contains("armeabi-v7a") -> ROOTFS_ARM to ROOTFS_ARM_SHA256
                        else -> {
                            unsupportedCpu = true
                            return@launch
                        }
                    }

                val sources =
                    listOf(
                        RootfsSource(
                            url = rootfs.first,
                            outputFile = getTempDir().child("sandbox.tar.gz"),
                            sha256 = rootfs.second,
                        ),
                    )

                val installer = RootfsInstaller(context)
                val result =
                    installer.install(sources) { progress -> installProgress = progress }

                when (result) {
                    is InstallResult.Success -> {
                        installNextStage = result.stage
                        ubuntuInstalled = result.stage != NEXT_STAGE.NONE || isTerminalInstalled()
                    }
                    is InstallResult.Failure -> handleInstallFailure(result)
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val context = LocalContext.current
            val activity = context as? Activity

            DisposableEffect(Settings.fullscreen) {
                if (Settings.terminal_keep_screen_on) {
                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                // Fullscreen mode: hide the status bar, swipe to reveal (toggle in
                // app settings). Re-applies when the toggle changes.
                val insetsController =
                    activity?.window?.let { window ->
                        WindowInsetsControllerCompat(window, window.decorView).also { controller ->
                            if (Settings.fullscreen) {
                                controller.hide(WindowInsetsCompat.Type.statusBars())
                                controller.systemBarsBehavior =
                                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            }
                        }
                    }

                onDispose {
                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    insetsController?.show(WindowInsetsCompat.Type.statusBars())
                }
            }

            when {
                unsupportedCpu -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(strings.unsupported_cpu),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(strings.unsupported_cpu_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { finishAffinity() }) {
                            Text(text = stringResource(strings.ok))
                        }
                    }
                }

                installNextStage != null && (installNextStage != NEXT_STAGE.NONE || ubuntuInstalled) -> {
                    TerminalScreen(terminalActivity = this@Terminal, port = port)
                }

                downloadStarted -> {
                    val progress = installProgress
                    if (progress != null) {
                        RootfsInstallScreen(progress = progress)
                    }
                }

                ubuntuInstalled -> {
                    TerminalScreen(terminalActivity = this@Terminal, port = port)
                }

                else -> {
                    // Ubuntu not installed and no download in progress: offer the
                    // optional install instead of forcing the rootfs download.
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .semantics(mergeDescendants = true) {},
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(strings.install_ubuntu_optional),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(strings.install_ubuntu_optional_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { startInstall() }) {
                            Text(text = stringResource(strings.install))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { finishAffinity() }) {
                            Text(text = stringResource(strings.not_now))
                        }
                    }
                }
            }
        }
    }
}
