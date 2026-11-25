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
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.xyzterm.UpdateManager
import com.xyzterm.commands.ActionContext
import com.xyzterm.commands.KeyCombination
import com.xyzterm.commands.KeybindingsManager
import com.xyzterm.components.BrandHeader
import com.xyzterm.components.SetupCard
import com.xyzterm.components.SetupHeading
import com.xyzterm.components.SetupShell
import com.xyzterm.exec.isTerminalInstalled
import com.xyzterm.file.FilePermission
import com.xyzterm.file.sandboxDir
import com.xyzterm.resources.drawables
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings
import com.xyzterm.terminal.InstallFailure
import com.xyzterm.terminal.NEXT_STAGE
import com.xyzterm.terminal.RootfsInstaller
import com.xyzterm.terminal.SessionService
import com.xyzterm.terminal.SetupCompleteScreen
import com.xyzterm.terminal.SetupFlow
import com.xyzterm.terminal.TerminalBackEnd
import com.xyzterm.terminal.TerminalScreen
import com.xyzterm.terminal.archmAssetForAbi
import com.xyzterm.terminal.changeSession
import com.xyzterm.terminal.fullScrollbackBounds
import com.xyzterm.terminal.gamepadBytes
import com.xyzterm.terminal.installFailureFor
import com.xyzterm.terminal.isValidDeepLinkCommand
import com.xyzterm.terminal.isValidSessionName
import com.xyzterm.terminal.pickRootfsAsset
import com.xyzterm.terminal.terminalView
import com.xyzterm.theme.XyzTheme
import com.xyzterm.theme.onWarningSurface
import com.xyzterm.theme.warningSurface
import com.xyzterm.utils.AppDialogHost
import com.xyzterm.utils.formatFileSize
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InstallStage {
    Copying,
    Extracting,
    Ready,
    UnsupportedCpu,
    Failed,
}

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

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        if (v !is TerminalView) return
        menu.add(Menu.NONE, 1, Menu.NONE, strings.copy_all.getString()).setOnMenuItemClickListener { _: MenuItem ->
            copyAllTerminalText(v)
            true
        }
        menu.add(Menu.NONE, 2, Menu.NONE, strings.share.getString()).setOnMenuItemClickListener { _: MenuItem ->
            shareTerminalText(v)
            true
        }
    }

    override fun onContextMenuClosed(menu: Menu) {
        terminalView.get()?.onContextMenuClosed(menu)
        super.onContextMenuClosed(menu)
    }

    private fun terminalTextForMenu(view: TerminalView): String? {
        val emulator = view.mEmulator ?: return null
        val screen = emulator.getScreen()
        val bounds = fullScrollbackBounds(screen.getActiveTranscriptRows(), emulator.mColumns, emulator.mRows)
        return screen.getSelectedText(bounds[0], bounds[1], bounds[2], bounds[3])
    }

    private fun copyAllTerminalText(view: TerminalView) {
        val text = terminalTextForMenu(view) ?: return
        view.mTermSession?.onCopyTextToClipboard(text)
    }

    private fun shareTerminalText(view: TerminalView) {
        val text = terminalTextForMenu(view)?.takeIf { it.isNotBlank() } ?: return
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        startActivity(Intent.createChooser(intent, strings.share.getString()))
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

        val link = intent.data
        if (link?.scheme == "xyzterm") {
            // Keep the URI: the AndroidView factory re-drives handleIntent once a
            // view exists, and pendingPrefill is one-shot so re-handling cannot
            // write the command twice.
            handleDeepLink(link)
            return
        }

        val pwd = intent.getStringExtra("cwd") ?: return
        terminalView.get() ?: return

        val sessionId = File(pwd).name.takeIf { it.isNotBlank() } ?: return

        lifecycleScope.launch(Dispatchers.Main) {
            val client = TerminalBackEnd()
            val info = binder.getSessionInfoByPwd(pwd) ?: binder.createSession(sessionId, client, this@Terminal)

            this@Terminal.changeSession(info.id)
            setIntent(intent)
        }
    }

    /**
     * Handles xyzterm:// URIs: xyzterm://session/<name> switches to (or creates) a named session.
     * xyzterm://session/<name>?cmd=<text> prefills input; the user presses Enter to run it.
     */
    private fun handleDeepLink(uri: Uri) {
        val binder = sessionBinder?.get() ?: return
        when (uri.host) {
            "session" -> {
                val name = uri.lastPathSegment?.trim().orEmpty()
                // ponytail: deep-link session name is attacker-controlled; reject traversal
                // and special segments before they reach MkSession.childSafe / deleteRecursively.
                if (!isValidSessionName(name)) return
                val command = uri.getQueryParameter("cmd")?.takeIf { isValidDeepLinkCommand(it) }
                if (command != null) pendingPrefill = name to command
                lifecycleScope.launch(Dispatchers.Main) {
                    if (name !in binder.getService().sessionList) {
                        binder.createSession(name, TerminalBackEnd(), this@Terminal)
                    }
                    this@Terminal.changeSession(name)
                    // Covers a session whose emulator was already set; onEmulatorSet covers a cold one.
                    flushPendingPrefill()
                }
            }
        // Prefilled text is never executed automatically; no "run" host exists.
        }
    }

    /** Writes a pending command once the target session actually has a shell process. */
    fun flushPendingPrefill() {
        val (sessionId, command) = pendingPrefill ?: return
        val view = terminalView.get() ?: return
        // TerminalSession.write() is a no-op until the shell exists; keep the
        // command pending so onEmulatorSet can retry instead of losing it.
        if (view.mEmulator == null) return
        val session = view.mTermSession ?: return
        val service = sessionBinder?.get()?.getService() ?: return
        if (service.sessionIdFor(session) != sessionId) return
        // Prefill only: no newline means the shell waits for the user's Enter.
        session.write(command)
        pendingPrefill = null
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    fun notificationPermissionNeeded(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED

    fun requestNotificationPermission() {
        if (notificationPermissionNeeded()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

        /** The session attached to the view; only this session's bell is suppressed in-app. */
        var visibleSession: TerminalSession? = null

        /** One-shot command text to write when its target session's emulator becomes ready. */
        var pendingPrefill: Pair<String, String>? = null
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        instance = this

        setContent {
            XyzTheme {
                AppDialogHost()

                Surface {
                    if (sessionBinder == null) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                        ) {
                            BrandHeader()
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(strings.service_connection_error),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = { recreate() }) { Text(text = stringResource(strings.retry)) }
                        }
                    } else {
                        SetupFlow(this@Terminal) { TerminalScreenHost(this@Terminal) }
                    }
                }
            }
        }
    }

    private fun writeToShell(text: String) {
        val binder = sessionBinder?.get() ?: return
        val service = binder.getService()
        val id = service.currentSession.value
        if (id.isEmpty()) return
        binder.getSession(id)?.write(text)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (terminalView.get()?.isFocused == true && Settings.gamepad_input) {
            val bytes = gamepadBytes(event.keyCode)
            if (bytes != null && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
                // Consume both halves so the terminal view never sees a partial
                // gamepad press (auto-repeat included).
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    writeToShell(bytes)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Runtime keybinding dispatch. Hooked at activity level: the native
        // TerminalView consumes hardware keys in its own dispatch path, so
        // Compose-level onPreviewKeyEvent would never see them. Only exact
        // matches fire, and auto-repeat is ignored.
        if (event != null && event.repeatCount == 0 && terminalView.get()?.isFocused == true) {
            // Alt+1..9 jumps to the Nth session in drawer order. Intercepted here
            // because TerminalView consumes hardware keys before Compose sees them.
            if (
                event.isAltPressed &&
                    !event.isCtrlPressed &&
                    !event.isMetaPressed &&
                    keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9
            ) {
                val index = keyCode - KeyEvent.KEYCODE_1
                val id = sessionBinder?.get()?.getService()?.sessionList?.getOrNull(index)
                if (id != null) {
                    lifecycleScope.launch { changeSession(id) }
                    return true
                }
            }
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
            totalBytes = 0L
            extractionDone = false
            installNextStage = null
            progressText = strings.installing.getString()
            if (withContext(Dispatchers.IO) { isTerminalInstalled() }) {
                extractionDone = true
                return@LaunchedEffect
            }

            val preferred = archmAssetForAbi(Build.SUPPORTED_ABIS)
            if (preferred == null) {
                supported = false
                return@LaunchedEffect
            }
            // ABI-specific APKs ship only one rootfs; fall back to the bundled
            // one if the user sideloads the other ABI's APK.
            val available = context.assets.list("terminal")?.toSet().orEmpty()
            val asset = pickRootfsAsset(preferred, available)
            if (asset == null) {
                failed = true
                errorMessage = strings.install_failed_missing_asset.getString()
                return@LaunchedEffect
            }

            try {
                if (!RootfsInstaller.isAvailable(context, asset)) {
                    throw IllegalStateException("Bundled rootfs is missing from this APK")
                }
                withContext(Dispatchers.IO) {
                    RootfsInstaller.install(context, asset, sandboxDir()) { copied, total ->
                        withContext(Dispatchers.Main) {
                            downloadedBytes = copied
                            totalBytes = total
                            progressText =
                                if (total > 0) {
                                    "${strings.installing.getString()} ${copied * 100 / total}%"
                                } else {
                                    strings.installing.getString()
                                }
                        }
                    }
                }
                // The extracted tree and marker are already in place; no
                // extraction session, no setup.sh tar step on first launch.
                installNextStage = NEXT_STAGE.NONE
                extractionDone = true
                copyDone = true
            } catch (e: Exception) {
                e.printStackTrace()
                failed = true
                errorMessage =
                    when (installFailureFor(e.message)) {
                        InstallFailure.NoSpace -> strings.install_failed_no_space.getString()
                        InstallFailure.MissingAsset -> strings.install_failed_missing_asset.getString()
                        InstallFailure.Other -> strings.install_failed_other.getString()
                    }
            }
        }

        // SessionService clears the one-shot stage after setup.sh consumes the
        // tarball; follow it so the UI leaves the extraction screen.
        LaunchedEffect(installNextStage) { if (copyDone && installNextStage == NEXT_STAGE.NONE) extractionDone = true }

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
                    SetupShell(step = 3) {
                        SetupCard {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        modifier = Modifier.size(44.dp),
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.warningSurface,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Warning,
                                            contentDescription = null,
                                            modifier = Modifier.padding(10.dp),
                                            tint = MaterialTheme.colorScheme.onWarningSurface,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = stringResource(strings.unsupported_cpu),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Text(
                                    text = stringResource(strings.unsupported_cpu_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = CircleShape,
                                    onClick = { finishAffinity() },
                                ) {
                                    Text(text = stringResource(strings.ok))
                                }
                            }
                        }
                    }
                }

                InstallStage.Ready ->
                    if (Settings.shown_setup_complete) {
                        TerminalScreen(terminalActivity = this@Terminal)
                    } else {
                        SetupCompleteScreen(
                            onOpen = { Settings.shown_setup_complete = true },
                            onHome = {
                                Settings.shown_setup_complete = true
                                finishAffinity()
                            },
                        )
                    }

                InstallStage.Failed -> {
                    SetupShell(step = 3) {
                        SetupCard(color = MaterialTheme.colorScheme.errorContainer) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Warning,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(strings.install_failed),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Text(
                                    text = errorMessage.ifBlank { stringResource(strings.install_failed) },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Button(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                    shape = CircleShape,
                                    onClick = { retryKey++ },
                                ) {
                                    Text(text = stringResource(strings.retry))
                                }
                            }
                        }
                    }
                }

                InstallStage.Copying,
                InstallStage.Extracting -> {
                    val progress =
                        if (totalBytes > 0) {
                            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                    val percent = ((progress ?: 0f) * 100).toInt()
                    val installStatus = "${stringResource(strings.installing)} $percent%"
                    SetupShell(step = 3) {
                        SetupHeading(
                            title = stringResource(strings.install_heading),
                            description = stringResource(strings.install_desc),
                        )
                        SetupCard {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        modifier = Modifier.size(56.dp),
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        Icon(
                                            painter = painterResource(drawables.arch_linux),
                                            contentDescription = null,
                                            modifier = Modifier.padding(10.dp),
                                            tint = Color.Unspecified,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "$percent%",
                                            style =
                                                MaterialTheme.typography.displaySmall.copy(
                                                    fontFeatureSettings = "tnum",
                                                ),
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                        Text(
                                            text = stringResource(strings.installing),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { progress ?: 0f },
                                    modifier =
                                        Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).semantics(
                                            mergeDescendants = true,
                                        ) {
                                            contentDescription = installStatus
                                        },
                                )
                                Text(
                                    text = stringResource(strings.install_patience),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (totalBytes > 0) {
                                    Text(
                                        text = "${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        SetupCard(color = MaterialTheme.colorScheme.warningSurface) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onWarningSurface,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(strings.warn_dont_leave_setup),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onWarningSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
