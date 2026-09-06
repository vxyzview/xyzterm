package com.rk.terminal

import android.content.Intent
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rk.activities.settings.SettingsActivity
import com.rk.activities.terminal.Terminal
import com.rk.components.ResponsiveDrawer
import com.rk.components.SingleInputDialog
import com.rk.components.compose.preferences.base.ProvideIsExpandedScreen
import com.rk.exec.pendingCommand
import com.rk.file.child
import com.rk.file.sandboxDir
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Preference
import com.rk.settings.Settings
import com.rk.terminal.virtualkeys.VirtualKeysListener
import com.rk.terminal.virtualkeys.VirtualKeysView
import com.rk.theme.LocalThemeHolder
import com.rk.theme.ThemeHolder
import com.rk.theme.yellowStatus
import com.rk.utils.DEFAULT_TERMINAL_FONT_PATH
import com.rk.utils.FontCache
import com.rk.utils.dpToPx
import com.rk.utils.isDarkTheme
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import java.lang.ref.WeakReference
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

var terminalView = WeakReference<TerminalView?>(null)
var virtualKeysView = WeakReference<VirtualKeysView?>(null)

/** Set by TerminalBackEnd.onBell; the header indicator flashes until reset. */
var bellPulse by mutableStateOf(false)

@Composable
fun TerminalScreen(modifier: Modifier = Modifier, terminalActivity: Terminal) {
    ProvideIsExpandedScreen { TerminalScreenInternal(terminalActivity = terminalActivity) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreenInternal(modifier: Modifier = Modifier, terminalActivity: Terminal) {
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    // Use the app's theme-mode resolution (honors Settings.theme_mode), not the raw
    // system flag, so the ANSI palette matches the rendered scheme when the user
    // forces light/dark independent of the system.
    val isDarkMode = isDarkTheme(LocalContext.current)
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentTheme = LocalThemeHolder.current

    DisposableEffect(Unit) { onDispose { keyboardController?.hide() } }

    Box(modifier = Modifier.imePadding()) {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

        BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

        ResponsiveDrawer(
            drawerState = drawerState,
            fullscreen = Settings.fullscreen,
            mainContent = {
                Scaffold(topBar = { TerminalTopBar(terminalActivity, scope, drawerState) }) { paddingValues ->
                    Column(modifier = Modifier.padding(paddingValues)) {
                        TerminalView(isDarkMode, currentTheme, surfaceColor, onSurfaceColor, terminalActivity)

                        // One-tap command snippets (configured under terminal settings).
                        SnippetsRow()

                        ExtraKeysPager(onSurfaceColor = onSurfaceColor)
                    }
                }
            },
        ) {
            TerminalDrawer(terminalActivity)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TerminalTopBar(terminalActivity: Terminal, scope: CoroutineScope, drawerState: DrawerState) {
    // Reads that change often (IME visibility every frame of the keyboard
    // animation; bellPulse on every background-job ring) live HERE, not in
    // TerminalScreenInternal, so a toggle only recomposes the header — not the
    // whole screen (which would re-run the terminal AndroidView update + drawer).
    if (Settings.smart_toolbar && WindowInsets.isImeVisible) return
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Session indicator: solid while live, flashes
                // amber for 2s when the shell rings the bell
                // (e.g. a background job finished).
                LaunchedEffect(bellPulse) {
                    if (bellPulse) {
                        delay(2000)
                        bellPulse = false
                    }
                }
                Box(
                    modifier =
                        Modifier.size(8.dp)
                            .background(
                                if (bellPulse) {
                                    MaterialTheme.colorScheme.yellowStatus
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                CircleShape,
                            )
                )
                SessionTitle(terminalActivity)
            }
        },
        navigationIcon = {
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Default.Menu, stringResource(strings.drawer))
            }
        },
        actions = {
            // no search action
        },
    )
}

@Composable
private fun SessionTitle(terminalActivity: Terminal) {
    val name =
        terminalActivity.sessionBinder?.get()?.getService()?.currentSession?.value
            ?: stringResource(strings.default_session_name)
    Text(
        text = name,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ColumnScope.TerminalView(
    isDarkMode: Boolean,
    currentTheme: ThemeHolder,
    surfaceColor: Int,
    onSurfaceColor: Int,
    terminalActivity: Terminal,
) {
    val terminalOutputLabel = stringResource(strings.terminal_output)
    val scope = rememberCoroutineScope()
    AndroidView(
        factory = { context ->
            val client = TerminalBackEnd()
            TerminalView(context, null)
                .apply {
                    val terminalColors =
                        if (isDarkMode) {
                            currentTheme.darkTerminalColors
                        } else {
                            currentTheme.lightTerminalColors
                        }
                    applyTerminalColors(
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        terminalColors = terminalColors,
                    )

                    terminalView = WeakReference(this)
                    terminalActivity.handleIntent(terminalActivity.intent)
                    setTextSize(dpToPx(Settings.terminal_font_size.toFloat(), context))
                    // Wire up the client immediately, independent of session availability
                    // below. TerminalView.onCreateInputConnection() reads mClient
                    // unconditionally as soon as this view is attached/focused, which can
                    // race ahead of an in-flight session restore on cold start
                    // (restorePending below) and NPE if setTerminalViewClient() were
                    // deferred until a session exists.
                    setTerminalViewClient(client)

                    // Legacy behavior
                    val fontFile = sandboxDir().child("etc/font.ttf")
                    if (fontFile.exists()) {
                        setTypeface(Typeface.createFromFile(fontFile))
                    } else {
                        val fontPath = Settings.terminal_font_path
                        val font =
                            if (fontPath.isNotEmpty()) {
                                FontCache.getTypeface(context, fontPath, Settings.is_terminal_font_asset)
                                    ?: FontCache.getTypeface(context, DEFAULT_TERMINAL_FONT_PATH, true)
                            } else {
                                FontCache.getTypeface(context, DEFAULT_TERMINAL_FONT_PATH, true)
                            }

                        setTypeface(font)
                    }

                    addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val widthChanged = (right - left) != (oldRight - oldLeft)
                        val heightChanged = (bottom - top) != (oldBottom - oldTop)

                        if (widthChanged || heightChanged) {
                            val terminalColors =
                                if (isDarkMode) {
                                    currentTheme.darkTerminalColors
                                } else {
                                    currentTheme.lightTerminalColors
                                }
                            terminalView
                                .get()
                                ?.applyTerminalColors(
                                    surfaceColor = surfaceColor,
                                    onSurfaceColor = onSurfaceColor,
                                    terminalColors = terminalColors,
                                )
                        }
                    }

                    post {
                        if (Settings.terminal_keep_screen_on) keepScreenOn = true
                        isFocusableInTouchMode = true
                        requestFocus()
                    }
                }
                .also { view ->
                    // Session creation performs disk I/O (tmp dir cleanup, sandbox
                    // build, setup scripts) — run it off the main thread and attach
                    // the finished session to this view when it lands.
                    scope.launch { view.attachOrCreateSession(scope, client, terminalActivity) }
                }
        },
        modifier = Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = terminalOutputLabel },
        update = { terminalView ->
            val terminalColors =
                if (isDarkMode) {
                    currentTheme.darkTerminalColors
                } else {
                    currentTheme.lightTerminalColors
                }

            terminalView.applyTerminalColors(
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                terminalColors = terminalColors,
            )
        },
    )
}

/** Resolves, creates and attaches the session for [view]'s lifecycle; called off the factory path. */
private suspend fun TerminalView.attachOrCreateSession(
    scope: CoroutineScope,
    client: TerminalBackEnd,
    terminalActivity: Terminal,
) {
    val session =
        if (pendingCommand != null) {
            val binder = terminalActivity.sessionBinder?.get() ?: return // service unbound mid-recreate
            binder.getService().currentSession.value = pendingCommand!!.id
            binder.getSession(pendingCommand!!.id)
                ?: binder.createSession(pendingCommand!!.id, client, terminalActivity).session
        } else {
            val binder = terminalActivity.sessionBinder?.get() ?: return
            val service = binder.getService()
            val current = service.currentSession.value
            binder.getSession(current)
                ?: if (service.restorePending) {
                    // Saved sessions are being spawned off the main
                    // thread (cold start) — attach the restored
                    // session to this view when it lands.
                    service.onRestored {
                        if (terminalView.get()?.mTermSession != null) return@onRestored
                        val restoredBinder = terminalActivity.sessionBinder?.get() ?: return@onRestored
                        scope.launch {
                            terminalActivity.changeSession(restoredBinder.getService().currentSession.value)
                        }
                    }
                    null
                } else {
                    binder.createSession(current, client, terminalActivity).session
                }
        }

    if (session != null) {
        session.updateTerminalSessionClient(client)
        // Apply terminal colors BEFORE attaching the session to ensure the shell prompt
        // uses the correct theme colors, not hardcoded ANSI codes
        reapplyTerminalColors(this)
        attachSession(session)
        setTerminalViewClient(client)
        // On-screen extra-keys (TAB/arrows) follow the attached session — same
        // as changeSession. Posted to dodge the extra-keys-factory race.
        wireExtraKeysClient()
    }
}

@Composable
private fun ColumnScope.TerminalDrawer(terminalActivity: Terminal) {
    val scope = rememberCoroutineScope()
    // Saveable: rotation must not close a mid-action confirm/rename dialog.
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showExitConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var sessionToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionToRename by rememberSaveable { mutableStateOf("") }
    var renameValue by rememberSaveable { mutableStateOf("") }
    var renameError by rememberSaveable { mutableStateOf<String?>(null) }

    if (showRenameDialog) {
        val service = terminalActivity.sessionBinder?.get()?.getService()

        SingleInputDialog(
            title = stringResource(strings.rename_session),
            inputLabel = stringResource(strings.name),
            inputValue = renameValue,
            errorMessage = renameError,
            onInputValueChange = {
                renameValue = it
                renameError =
                    if (it.isBlank()) {
                        strings.name_empty_err.getString()
                    } else if (it != sessionToRename && service?.sessionList?.contains(it) == true) {
                        strings.session_name_exists.getString()
                    } else null
            },
            onConfirm = {
                if (renameError == null && renameValue.isNotBlank() && renameValue != sessionToRename) {
                    terminalActivity.sessionBinder?.get()?.renameSession(sessionToRename, renameValue)
                }
            },
            onFinish = { showRenameDialog = false },
        )
    }

    val service = terminalActivity.sessionBinder?.get()?.getService()
    val context = LocalContext.current

    fun deleteSession(id: String) {
        val binder = terminalActivity.sessionBinder?.get() ?: return
        val svc = service ?: return
        val index = svc.sessionList.indexOf(id)
        val sessionBefore = svc.sessionList.getOrNull(index - 1)
        val sessionAfter = svc.sessionList.getOrNull(index + 1)
        val neighborSession = sessionBefore ?: sessionAfter
        neighborSession?.let { neighbor -> scope.launch { terminalActivity.changeSession(neighbor) } }

        binder.terminateSession(id)

        if (svc.sessionList.isEmpty()) {
            terminalActivity.finish()
            svc.actionExit()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Branded header ─────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                ) {
                    Icon(
                        painter = painterResource(drawables.terminal),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = stringResource(strings.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(strings.developed_by),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Settings entry ─────────────────────────────────────────────
        Surface(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp)
                    .semantics { role = Role.Button }
                    .clickable { context.startActivity(Intent(context, SettingsActivity::class.java)) },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(strings.settings),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // ── Sessions ───────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(strings.sessions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            TextButton(
                onClick = {
                    fun generateUniqueString(existingStrings: List<String>): String {
                        var index = 1
                        var newString: String

                        do {
                            newString = strings.default_session_numbered.getString().format(index)
                            index++
                        } while (newString in existingStrings)

                        return newString
                    }
                    terminalView.get()?.let {
                        val binder = terminalActivity.sessionBinder?.get() ?: return@let
                        val client = TerminalBackEnd()
                        scope.launch {
                            val info =
                                binder.createSession(
                                    generateUniqueString(binder.getService().sessionList),
                                    client,
                                    terminalActivity,
                                )
                            // Switch to the new session immediately — creating it
                            // silently in the background feels like a dead button.
                            terminalActivity.changeSession(info.id)
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(strings.add_session),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(strings.add_session),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        service?.sessionList?.let { sessions ->
            val listState = rememberLazyListState()

            // Keep the active session visible when the drawer opens or the
            // selection changes — with many sessions it can sit off-screen.
            LaunchedEffect(service.currentSession.value, sessions.size) {
                val index = sessions.indexOf(service.currentSession.value)
                if (index >= 0) {
                    listState.scrollToItem(index)
                }
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(sessions, key = { it }) { sessionId ->
                    val isSelected = sessionId == service.currentSession.value

                    Surface(
                        onClick = { scope.launch { terminalActivity.changeSession(sessionId) } },
                        shape = MaterialTheme.shapes.large,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = sessionId,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onBackground
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                            )
                            IconButton(
                                onClick = {
                                    sessionToRename = sessionId
                                    renameValue = sessionId
                                    renameError = null
                                    showRenameDialog = true
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = stringResource(strings.rename),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Footer actions ─────────────────────────────────────────────
        val activeSession = service?.currentSession?.value

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Surface(
                onClick = {
                    if (service == null || activeSession == null) return@Surface
                    sessionToDelete = activeSession
                    showDeleteConfirm = true
                },
                enabled = activeSession != null,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(strings.delete_session),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(strings.delete_session),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                onClick = {
                    // Respect the "Confirm exit" setting: ask before closing the app.
                    if (Settings.confirm_exit) {
                        showExitConfirm = true
                    } else {
                        service?.actionExit()
                    }
                },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = stringResource(strings.logout),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(strings.logout),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showDeleteConfirm && sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirm = false
                sessionToDelete = null
            },
            title = { Text(text = stringResource(strings.delete_session)) },
            text = { Text(text = stringResource(strings.delete_session_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        sessionToDelete?.let { deleteSession(it) }
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(strings.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        sessionToDelete = null
                    }
                ) {
                    Text(text = stringResource(strings.cancel))
                }
            },
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(text = stringResource(strings.confirm_exit)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        service?.actionExit()
                    }
                ) {
                    Text(text = stringResource(strings.logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text(text = stringResource(strings.cancel)) }
            },
        )
    }
}

suspend fun Terminal.changeSession(sessionId: String) {
    val terminalView = terminalView.get() ?: return
    val binder = sessionBinder?.get() ?: return

    val client = TerminalBackEnd()
    val session = binder.getSession(sessionId) ?: binder.createSession(sessionId, client, this).session

    session.updateTerminalSessionClient(client)
    // Apply terminal colors BEFORE attaching the session to ensure the shell prompt
    // uses the correct theme colors, not hardcoded ANSI codes
    reapplyTerminalColors(terminalView)
    terminalView.attachSession(session)
    terminalView.setTerminalViewClient(client)

    terminalView.apply {
        post {
            if (Settings.terminal_keep_screen_on) keepScreenOn = true
            isFocusableInTouchMode = true
            requestFocus()
        }
    }
    wireExtraKeysClient()

    binder.getService().currentSession.value = sessionId
    Preference.setString(ACTIVE_SESSION_KEY, sessionId)
}

// Re-apply the theme palette after attaching a session. attachSession builds a
// fresh emulator whose colors reset to Termux defaults, and the composable only
// calls applyTerminalColors on recomposition/layout — so a switched or added
// ("other") session would otherwise render with the default palette, making the
// roo@xyz prompt (ANSI 32/34) mismatch the themed app until a relayout. The
// last-applied signature lives on the view tag, so this is cheap + idempotent.
private fun reapplyTerminalColors(view: TerminalView) {
    val sig = view.tag as? TerminalColorSignature ?: return
    if (sig.colors == null || sig.colors.isEmpty) return
    view.applyTerminalColors(sig.onSurface, sig.surface, sig.colors)
}

// Point the on-screen extra-keys row at the live session so TAB/arrow keys
// emit into the right shell. Posted: at attach time virtualKeysView may still
// be null (its own AndroidView factory runs a frame later), so a direct
// assignment would silently no-op and leave the keys dead until a manual
// switch. A post() retries on the next frame, after both views exist.
private fun wireExtraKeysClient() {
    terminalView.get()?.post {
        val session = terminalView.get()?.mTermSession ?: return@post
        virtualKeysView.get()?.virtualKeysViewClient = VirtualKeysListener(session)
    }
}

// Signature of colors last applied to a given TerminalView, stored on the view
// itself (tag) so applyTerminalColors can skip the expensive reset + full
// repaint when nothing changed. Per-view storage matters: after activity
// recreation the fresh TerminalView has no signature yet, so its first apply
// always runs — a process-global cache here would let the new view inherit a
// stale "already applied" verdict and render with default palette colors.
private data class TerminalColorSignature(
    val colors: Properties?,
    val surface: Int,
    val onSurface: Int,
)

private fun TerminalView.applyTerminalColors(onSurfaceColor: Int, surfaceColor: Int, terminalColors: Properties) {
    if (mEmulator == null) return
    val last = tag as? TerminalColorSignature
    if (last?.colors == terminalColors && last.surface == surfaceColor && last.onSurface == onSurfaceColor) {
        return
    }
    tag = TerminalColorSignature(terminalColors, surfaceColor, onSurfaceColor)

    this.onScreenUpdated()

    mEmulator?.mColors?.reset()
    TerminalColors.COLOR_SCHEME.updateWith(terminalColors)

    val cursorColor =
        terminalColors.getProperty("cursor")?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
            ?: onSurfaceColor

    mEmulator?.mColors?.mCurrentColors?.apply {
        set(TextStyle.COLOR_INDEX_FOREGROUND, onSurfaceColor)
        set(TextStyle.COLOR_INDEX_BACKGROUND, surfaceColor)
        set(TextStyle.COLOR_INDEX_CURSOR, cursorColor)
    }

    invalidate()
}
