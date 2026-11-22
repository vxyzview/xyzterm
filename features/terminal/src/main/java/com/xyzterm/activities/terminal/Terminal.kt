package com.xyzterm.activities.terminal

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.xyzterm.UpdateManager
import com.xyzterm.activities.settings.DisclaimerScreen
import com.xyzterm.commands.ActionContext
import com.xyzterm.commands.KeyCombination
import com.xyzterm.commands.KeybindingsManager
import com.xyzterm.exec.isTerminalInstalled
import com.xyzterm.file.FilePermission
import com.xyzterm.file.child
import com.xyzterm.resources.getFilledString
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings
import com.xyzterm.terminal.NEXT_STAGE
import com.xyzterm.terminal.SessionService
import com.xyzterm.terminal.TerminalBackEnd
import com.xyzterm.terminal.TerminalScreen
import com.xyzterm.terminal.archmAssetForAbi
import com.xyzterm.terminal.changeSession
import com.xyzterm.terminal.getNextStage
import com.xyzterm.terminal.terminalView
import com.xyzterm.theme.XyzTheme
import com.xyzterm.utils.AppDialogHost
import com.xyzterm.utils.getTempDir
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InstallStage { Copying, Extracting, Ready, UnsupportedCpu, Failed }

/** Pure transition used by the first-run host; Retry clears failed/copyDone. */
internal fun nextInstallStage(
    installed: Boolean,
    supported: Boolean,
    copyDone: Boolean,
    extractionDone: Boolean,
    failed: Boolean,
): InstallStage =
    when {
        failed -> InstallStage.Failed
        !supported -> InstallStage.UnsupportedCpu
        installed || extractionDone -> InstallStage.Ready
        copyDone -> InstallStage.Extracting
        else -> InstallStage.Copying
    }

class Terminal : AppCompatActivity() {
    var sessionBinder by mutableStateOf<WeakReference<SessionService.SessionBinder>?>(null)
    var isBound = false

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
        isForeground = true
    }

    fun handleIntent(intent: Intent) {
        this.intent = intent

        val binder = sessionBinder?.get() ?: return
        // Service still restoring saved sessions: defer deep-link / SEND / cwd
        // handling until restore lands, so a raced intent no longer creates a
        // duplicate fresh session (deep link) or writes into an empty session
        // map (SEND/cwd, silently dropped).
        if (binder.getService().restorePending) {
            binder.getService().onRestored { handleIntent(intent) }
            return
        }

        if (intent.data?.scheme == "xyzterm") {
            handleDeepLink(intent.data ?: return)
            return
        }

        val pwd = intent.getStringExtra("cwd") ?: return
        terminalView.get() ?: return

        val sessionId = File(pwd).name

        lifecycleScope.launch(Dispatchers.Main) {
            val client = TerminalBackEnd()
            val info = binder.getSessionInfoByPwd(pwd) ?: binder.createSession(sessionId, client, this@Terminal)

            this@Terminal.changeSession(info.id)
            setIntent(intent)
        }
    }

    /**
     * Handles xyzterm:// URIs: xyzterm://run?cmd=<command> write command to the current session
     * xyzterm://session/<name>?cmd=<cmd> create or switch to session <name>, optionally run <cmd>
     */
    private fun handleDeepLink(uri: Uri) {
        val binder = sessionBinder?.get() ?: return
        when (uri.host) {
            "session" -> {
                val name = uri.lastPathSegment?.trim().orEmpty()
                // ponytail: deep-link session name is attacker-controlled; reject traversal
                // and special segments before they reach MkSession.childSafe / deleteRecursively.
                if (name.isEmpty() || name == "." || name == ".." || name.contains("/") || name.contains("\\")) {
                    return
                }
                lifecycleScope.launch(Dispatchers.Main) {
                    if (name !in binder.getService().sessionList) {
                        binder.createSession(name, TerminalBackEnd(), this@Terminal)
                    }
                    this@Terminal.changeSession(name)
                }
            }
        // "run" and "?cmd=" intentionally dropped: a BROWSABLE link writing commands
        // into a live session is unprompted command execution inside the sandbox.
        }
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
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

        /** True while the terminal UI is visible; gates bell notifications. */
        var isForeground = false
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instance = this

        if (needsNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            XyzTheme {
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
                        TerminalScreenHost(this)
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
                            Button(onClick = { recreate() }) { Text(text = stringResource(strings.retry)) }
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

    var progressText by mutableStateOf(strings.installing.getString())
    var installNextStage by mutableStateOf<NEXT_STAGE?>(null)

    @OptIn(DelicateCoroutinesApi::class)
    @Composable
    fun TerminalScreenHost(context: Context) {
        var downloadedBytes by remember { mutableLongStateOf(0L) }
        var totalBytes by remember { mutableLongStateOf(0L) }
        var supported by remember { mutableStateOf(true) }
        var copyDone by remember { mutableStateOf(false) }
        var extractionDone by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf("") }
        var retryKey by remember { mutableStateOf(0) }

        LaunchedEffect(retryKey) {
            failed = false
            copyDone = false
            errorMessage = ""
            downloadedBytes = 0L
            if (withContext(Dispatchers.IO) { isTerminalInstalled() }) {
                extractionDone = true
                return@LaunchedEffect
            }

            val asset = archmAssetForAbi(Build.SUPPORTED_ABIS)
            if (asset == null) {
                supported = false
                return@LaunchedEffect
            }

            try {
                withContext(Dispatchers.IO) {
                    val output = getTempDir().child("sandbox.tar.gz")
                    // aapt may leave .tar.gz uncompressed (openFd works) or store
                    // it deflated; both paths must produce the same staged file.
                    val descriptor = runCatching { context.assets.openFd("terminal/${asset.name}") }.getOrNull()
                    val input: InputStream =
                        descriptor?.createInputStream() ?: context.assets.open("terminal/${asset.name}")
                    totalBytes = descriptor?.length ?: -1L
                    var lastCopyEmit = 0L
                    input.use { stream ->
                        FileOutputStream(output).use { fileOut ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                fileOut.write(buffer, 0, read)
                                downloadedBytes += read
                                val now = System.currentTimeMillis()
                                if (now - lastCopyEmit >= 250L) {
                                    lastCopyEmit = now
                                    withContext(Dispatchers.Main) {
                                        progressText =
                                            if (totalBytes > 0) {
                                                "${strings.installing.getString()} ${downloadedBytes * 100 / totalBytes}%"
                                            } else {
                                                strings.installing.getString()
                                            }
                                    }
                                }
                            }
                        }
                    }
                    descriptor?.close()

                    if (!sha256Matches(output, asset.sha256)) {
                        output.delete()
                        throw IllegalStateException("Bundled rootfs checksum mismatch: ${asset.name}")
                    }
                    val next = getNextStage(this@Terminal)
                    installNextStage = next
                    extractionDone = next == NEXT_STAGE.NONE
                    copyDone = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                failed = true
                errorMessage = strings.setup_failed.getFilledString(e.message)
            }
        }

        // SessionService clears the one-shot stage after setup.sh consumes the
        // tarball; follow it so the UI leaves the extraction screen.
        LaunchedEffect(installNextStage) {
            if (copyDone && installNextStage == NEXT_STAGE.NONE) extractionDone = true
        }

        val stage =
            nextInstallStage(
                installed = extractionDone,
                supported = supported,
                copyDone = copyDone,
                extractionDone = extractionDone,
                failed = failed,
            )

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

            when (stage) {
                InstallStage.UnsupportedCpu -> {
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
                        Button(onClick = { finishAffinity() }) { Text(text = stringResource(strings.ok)) }
                    }
                }

                InstallStage.Ready -> TerminalScreen(terminalActivity = this@Terminal)

                InstallStage.Failed -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = errorMessage.ifBlank { stringResource(strings.install_failed) },
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { retryKey++ }) { Text(text = stringResource(strings.retry)) }
                    }
                }

                InstallStage.Copying, InstallStage.Extracting -> {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(text = progressText, style = MaterialTheme.typography.bodyLarge)

                            Spacer(modifier = Modifier.height(16.dp))

                            if (totalBytes > 0) {
                                LinearProgressIndicator(
                                    progress = { downloadedBytes.toFloat() / totalBytes },
                                    modifier = Modifier.fillMaxWidth(0.8f),
                                )
                                val percent = (downloadedBytes.toFloat() / totalBytes * 100).toInt()
                                Text(
                                    text = "$percent%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier =
                                        Modifier.padding(top = 8.dp).semantics {
                                            liveRegion = LiveRegionMode.Polite
                                        },
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.8f))
                            }
                        }

                        Text(
                            text = stringResource(strings.warn_dont_leave_setup),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).safeDrawingPadding(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    private fun sha256Matches(file: File, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrEmpty()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedSha256, ignoreCase = true)
        }
            .getOrElse {
                it.printStackTrace()
                false
            }
    }
}
