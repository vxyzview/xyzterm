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
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
import com.rk.terminal.NEXT_STAGE
import com.rk.terminal.ROOTFS_ARM
import com.rk.terminal.ROOTFS_ARM64
import com.rk.terminal.ROOTFS_X64
import com.rk.terminal.ROOTFS_ARM_SHA256
import com.rk.terminal.ROOTFS_ARM64_SHA256
import com.rk.terminal.ROOTFS_X64_SHA256
import com.rk.terminal.SessionService
import com.rk.terminal.TerminalBackEnd
import com.rk.terminal.TerminalScreen
import com.rk.terminal.changeSession
import com.rk.terminal.extractRootfs
import com.rk.terminal.getNextStage
import com.rk.terminal.terminalView
import com.rk.theme.XedTheme
import com.rk.utils.AppDialogHost
import com.rk.utils.dialogRes
import com.rk.utils.errorDialog
import com.rk.utils.getTempDir
import com.rk.utils.okHttpClient
import com.rk.utils.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

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
        if (intent === lastHandledIntent) return
        lastHandledIntent = intent
        this.intent = intent

        if (intent.data?.scheme == "xyzterm") {
            handleDeepLink(intent.data ?: return)
            return
        }

        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            confirmAndRunCommand(text)
            return
        }

        val pwd = intent.getStringExtra("cwd") ?: return
        val binder = sessionBinder?.get() ?: return
        terminalView.get() ?: return

        val sessionId = File(pwd).name
        if (!SESSION_NAME_REGEX.matches(sessionId)) return

        lifecycleScope.launch(Dispatchers.Main) {
            val client = TerminalBackEnd()
            val info = binder.getSessionInfoByPwd(pwd) ?: binder.createSession(sessionId, client, this@Terminal)

            this@Terminal.changeSession(info.id)
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
        lifecycleScope.launch(Dispatchers.Main) {
            when (uri.host) {
                "run" -> {
                    val cmd = uri.getQueryParameter("cmd") ?: return@launch
                    confirmAndRunCommand("$cmd\n")
                }

                "session" -> {
                    val name = uri.lastPathSegment?.trim().orEmpty()
                    if (!SESSION_NAME_REGEX.matches(name)) return@launch
                    if (name !in binder.getService().sessionList) {
                        binder.createSession(name, TerminalBackEnd(), this@Terminal)
                    }
                    this@Terminal.changeSession(name)
                    uri.getQueryParameter("cmd")?.let { cmd -> confirmAndRunCommand("$cmd\n", name) }
                }
            }
        }
    }

    private fun confirmAndRunCommand(payload: String, sessionId: String? = null) {
        dialogRes(
            activity = this,
            title = strings.warning.getString(),
            msg = payload.trimEnd('\n'),
            onOk = {
                val binder = sessionBinder?.get() ?: return@dialogRes
                val target = sessionId ?: binder.getService().currentSession.value
                binder.getSession(target)?.write(payload)
            },
        )
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

private var lastHandledIntent: Intent? = null

            internal val SESSION_NAME_REGEX = Regex("^[A-Za-z0-9_-]+$")

        private const val INSTALL_TAG = "TerminalInstall"

        /** Extraction must finish, and keep emitting progress, within these bounds. */
        private const val EXTRACT_HARD_TIMEOUT_MS = 15 * 60 * 1000L
        private const val EXTRACT_STALL_TIMEOUT_MS = 60_000L
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

    var progressText by mutableStateOf(strings.installing.getString())
    var installNextStage by mutableStateOf<NEXT_STAGE?>(null)

    @OptIn(DelicateCoroutinesApi::class)
    @Composable
    fun TerminalScreenHost(context: Context) {
        var currentFileName by remember { mutableStateOf("") }
        var downloadedBytes by remember { mutableLongStateOf(0L) }
        var totalBytes by remember { mutableLongStateOf(0L) }
        var unsupportedCpu by remember { mutableStateOf(false) }
        var downloadStarted by remember { mutableStateOf(false) }
        var ubuntuInstalled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            ubuntuInstalled = withContext(Dispatchers.IO) { isTerminalInstalled() }
        }

        // Swallowing back during install would abandon a half-installed rootfs.
        BackHandler(enabled = downloadStarted || installNextStage == NEXT_STAGE.EXTRACTION) {}

        // Helper function to format bytes to MB string
        fun formatBytesToMB(bytes: Long): String {
            return "%.2f".format(bytes / (1024.0 * 1024.0))
        }

        fun startInstall() {
            progressText = strings.installing.getString()
            downloadStarted = true

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

                val filesToDownload =
                    mutableListOf(
                        DownloadFile(
                            url = rootfs.first,
                            outputFile = getTempDir().child("sandbox.tar.gz"),
                            sha256 = rootfs.second,
                        ),
                    )

                try {
                    setupEnvironment(
                        context = context,
                        filesToDownload = filesToDownload,
                        onProgress = { fileName, downloaded, total ->
                            downloadedBytes = downloaded
                            totalBytes = total
                            currentFileName = fileName

                            if (total > 0) {
                                val downloadedMB = formatBytesToMB(downloaded)
                                val totalMB = formatBytesToMB(total)
                                progressText =
                                    "${strings.downloading.getString()} ${fileName.removeSuffix(".so").removePrefix("lib")} ($downloadedMB/$totalMB MB)"
                            }
                        },
                        onComplete = {
                            installNextStage = it
                            ubuntuInstalled = it != NEXT_STAGE.NONE || isTerminalInstalled()
                        },
                        onError = { error, file ->
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
                        },
                    )
                } catch (e: Exception) {
                    if (e is UnknownHostException) {
                        toast(strings.network_err.getString())
                    } else if (e is SocketTimeoutException) {
                        errorDialog(strings.timeout)
                    } else {
                        e.printStackTrace()
                        toast(strings.setup_failed.getFilledString(e.message))
                    }
                    downloadStarted = false
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val context = LocalContext.current
            val activity = context as? Activity

            DisposableEffect(Settings.fullscreen, Settings.keep_device_awake) {
                activity?.window?.let { window ->
                    if (Settings.keep_device_awake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
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
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { finishAffinity() }) {
                            Text(text = stringResource(strings.ok))
                        }
                    }
                }

                installNextStage == NEXT_STAGE.EXTRACTION -> {
                    var extractProgress by remember { mutableFloatStateOf(0f) }

                    LaunchedEffect(Unit) {
                        // CancellationException must propagate: swallowing it turns a
                        // rotation/back mid-extract into a bogus "Setup failed" while
                        // the disk state may already be complete.
                        val failure =
                            try {
                                var lastProgressAt = SystemClock.elapsedRealtime()
                                withTimeout(EXTRACT_HARD_TIMEOUT_MS) {
                                    coroutineScope {
                                        val watchdog =
                                            launch {
                                                while (true) {
                                                    delay(5000)
                                                    if (SystemClock.elapsedRealtime() - lastProgressAt > EXTRACT_STALL_TIMEOUT_MS) {
                                                        throw IOException("Extraction stalled: no progress in ${EXTRACT_STALL_TIMEOUT_MS / 1000}s")
                                                    }
                                                }
                                            }
                                        try {
                                            extractRootfs { fraction ->
                                                lastProgressAt = SystemClock.elapsedRealtime()
                                                extractProgress = fraction
                                            }
                                        } finally {
                                            watchdog.cancel()
                                        }
                                    }
                                }
                                null
                            } catch (e: TimeoutCancellationException) {
                                // Our own cancel (activity destroyed) also surfaces as TCE;
                                // only report a timeout when this coroutine is still alive.
                                if (!isActive) throw e
                                IOException("Extraction exceeded ${EXTRACT_HARD_TIMEOUT_MS / 60000} min")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e
                            }

                        if (failure != null) {
                            failure.printStackTrace()
                            Log.w(INSTALL_TAG, "extraction failed: ${failure.message}")
                            errorDialog(msg = strings.setup_failed.getFilledString(failure.message))
                            installNextStage = null
                            ubuntuInstalled = false
                        } else {
                            installNextStage = NEXT_STAGE.NONE
                            ubuntuInstalled = true
                        }
                        downloadStarted = false
                    }

                    Column(
                        modifier =
                            Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(strings.install_extracting_title),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(strings.install_extracting_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        LinearProgressIndicator(
                            progress = { extractProgress },
                            modifier = Modifier.fillMaxWidth(0.8f),
                        )
                        Text(
                            text =
                                stringResource(
                                    strings.install_extracting_progress,
                                    (extractProgress * 100).toInt(),
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier =
                                Modifier
                                    .padding(top = 8.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }

                installNextStage != null && (installNextStage != NEXT_STAGE.NONE || ubuntuInstalled) -> {
                    TerminalScreen(terminalActivity = this@Terminal)
                }

                downloadStarted -> {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(text = progressText, style = MaterialTheme.typography.bodyLarge)

                            Spacer(modifier = Modifier.height(24.dp))

                            LinearProgressIndicator(
                                progress = { if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f },
                                modifier = Modifier.fillMaxWidth(0.8f),
                            )

                            if (totalBytes > 0) {
                                val percent = (downloadedBytes.toFloat() / totalBytes * 100).toInt()

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

                ubuntuInstalled -> {
                    TerminalScreen(terminalActivity = this@Terminal)
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
                        TextButton(onClick = { finish() }) {
                            Text(text = stringResource(strings.not_now))
                        }
                    }
                }
            }
        }
    }

    data class DownloadFile(val url: String, val outputFile: File, val sha256: String? = null)

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun setupEnvironment(
        context: Context,
        filesToDownload: List<DownloadFile>,
        onProgress: (fileName: String, downloadedBytes: Long, totalBytes: Long) -> Unit,
        onComplete: (NEXT_STAGE) -> Unit,
        onError: (Exception, File?) -> Unit,
    ) {
        var currentFile: File? = null

        withContext(Dispatchers.IO) {
            try {
                var completedFiles = 0

                filesToDownload.forEach { file ->
                    val outputFile = file.outputFile
                    currentFile = outputFile

                    outputFile.parentFile?.mkdirs()

                    if (
                        !outputFile.exists() ||
                        !isValidGzip(outputFile) ||
                        !sha256Matches(outputFile, file.sha256)
                    ) {
                        // Existing file is a leftover from a killed download, a
                        // pre-resume version, or a stale rootfs from an older app
                        // release (hash mismatch): discard it, the download writes
                        // a .part sibling and only renames it once verified.
                        outputFile.delete()
                        downloadFile(
                            url = file.url,
                            outputFile = outputFile,
                            onProgress = { downloaded, total -> onProgress(file.outputFile.name, downloaded, total) },
                        )
                    } else {
                        // Report existing file as already downloaded
                        onProgress(file.outputFile.name, outputFile.length(), outputFile.length())
                    }
                    completedFiles++

                    if (file.sha256 != null && !sha256Matches(outputFile, file.sha256)) {
                        outputFile.delete()
                        throw Exception("Rootfs checksum mismatch: ${outputFile.name}")
                    }

                    Log.w(
                        INSTALL_TAG,
                        "download ok: ${outputFile.name} bytes=${outputFile.length()} shaVerified=${file.sha256 != null}",
                    )

                    runCatching { outputFile.setExecutable(true) }.onFailure { it.printStackTrace() }
                }

                val stage = getNextStage(this@Terminal)
                Log.w(INSTALL_TAG, "next stage: $stage")
                onComplete(stage)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.w(INSTALL_TAG, "setup failed before extraction: ${e.message}")
                withContext(Dispatchers.Main) { onError(e, currentFile) }
                if (currentFile?.exists() == true) {
                    currentFile.delete()
                }
            }
        }
    }

    private suspend fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val client =
                okHttpClient
                    .newBuilder()
                    .connectTimeout(1, TimeUnit.MINUTES)
                    .readTimeout(1, TimeUnit.MINUTES)
                    .writeTimeout(1, TimeUnit.MINUTES)
                    .callTimeout(10, TimeUnit.MINUTES)
                    .build()

            // Download to a .part sibling: a killed download leaves a resumable
            // partial, and a partial can never be mistaken for a complete rootfs.
            val partFile = File(outputFile.parentFile, outputFile.name + ".part")

            // Resume from where the previous attempt stopped; the server answers
            // 206 with the remainder, or 200 if it ignores the Range header.
            val rangeStart = if (partFile.exists()) partFile.length() else 0L
            val request =
                Request.Builder()
                    .url(url)
                    .apply { if (rangeStart > 0) header("Range", "bytes=$rangeStart-") }
                    .build()

            var startedAt = rangeStart
            var skipDownload = false
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> startedAt = rangeStart
                    200 -> {
                        startedAt = 0
                        partFile.delete()
                    }
                    416 -> {
                        // Server reports nothing past what we already have: the
                        // partial is complete, integrity is verified below.
                        skipDownload = true
                    }
                    else -> {
                        // e.g. 404 after a re-release: the partial is stale, drop
                        // it so the next attempt starts clean.
                        partFile.delete()
                        throw Exception("Failed to download file: ${response.code}")
                    }
                }

                if (!skipDownload) {
                    val body = response.body ?: throw Exception("Empty response body")
                    val totalBytes = startedAt + body.contentLength()

                    var downloadedBytes = startedAt
                    // Throttle progress: hopping to the main thread and recomposing the
                    // progress UI on every 8 KiB block (tens of thousands of times for a
                    // 200-400 MB rootfs) janks the setup screen. Emit at most every ~250ms
                    // and always send the final 100% update.
                    val THROTTLE_MS = 250L
                    var lastEmit = 0L

                    FileOutputStream(partFile, startedAt > 0).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesRead: Int

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastEmit >= THROTTLE_MS) {
                                    lastEmit = now
                                    withContext(Dispatchers.Main) { onProgress(downloadedBytes, totalBytes) }
                                }
                            }
                            withContext(Dispatchers.Main) { onProgress(downloadedBytes, totalBytes) }
                        }
                    }
                }
            }

            // Reading the file to EOF verifies the gzip CRC-32 trailer: a stream
            // cut short cannot pass, so the file is only promoted to the real name
            // once it is actually complete and uncorrupted.
            if (!isValidGzip(partFile)) {
                partFile.delete()
                throw Exception("Downloaded file failed integrity check: ${outputFile.name}")
            }
            if (!partFile.renameTo(outputFile)) {
                throw Exception("Failed to move downloaded file: ${outputFile.name}")
            }
        }
    }

    private fun isValidGzip(file: File): Boolean =
        runCatching {
            GZIPInputStream(file.inputStream()).use { input ->
                val buffer = ByteArray(8 * 1024)
                while (input.read(buffer) != -1) {
                    // Drain to EOF: GZIPInputStream only verifies the trailer CRC
                    // once the stream is fully consumed.
                }
            }
        }.isSuccess

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
            digest
                .digest()
                .joinToString("") { "%02x".format(it) }
                .equals(expectedSha256, ignoreCase = true)
        }
            .getOrElse {
                it.printStackTrace()
                false
            }
    }
}
