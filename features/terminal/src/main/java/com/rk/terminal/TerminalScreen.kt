package com.rk.terminal

import android.content.Intent
import android.graphics.Typeface
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rk.activities.settings.SettingsActivity
import com.rk.activities.settings.SettingsRoutes
import com.rk.activities.terminal.Terminal
import com.rk.animations.NavigationAnimationTransitions
import com.rk.components.compose.preferences.base.ProvideIsExpandedScreen
import com.rk.components.ResponsiveDrawer
import com.rk.components.SingleInputDialog
import com.rk.utils.FontCache
import com.rk.utils.isDarkTheme
import com.rk.exec.pendingCommand
import com.rk.file.child
import com.rk.file.sandboxDir
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.settings.Preference
import com.rk.utils.DEFAULT_TERMINAL_FONT_PATH
import com.rk.settings.editor.TerminalFontScreen
import com.rk.settings.terminal.DEFAULT_TERMINAL_EXTRA_KEYS
import com.rk.settings.terminal.SettingsTerminalScreen
import com.rk.settings.terminal.TerminalCheckScreen
import com.rk.settings.terminal.TerminalExtraKeys
import com.rk.terminal.virtualkeys.VirtualKeysConstants
import com.rk.terminal.virtualkeys.VirtualKeysInfo
import com.rk.terminal.virtualkeys.VirtualKeysListener
import com.rk.terminal.virtualkeys.VirtualKeysView
import com.rk.theme.LocalThemeHolder
import com.rk.theme.ThemeHolder
import com.rk.utils.dpToPx
import com.rk.utils.toast
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Properties

var terminalView = WeakReference<TerminalView?>(null)
var virtualKeysView = WeakReference<VirtualKeysView?>(null)

/** Set by TerminalBackEnd.onBell; the header indicator flashes until reset. */
var bellPulse by mutableStateOf(false)

@Composable
fun TerminalScreen(modifier: Modifier = Modifier, terminalActivity: Terminal) {
    val navController = rememberNavController()
    ProvideIsExpandedScreen {
    NavHost(
        navController = navController,
        startDestination = "terminal",
        enterTransition = { NavigationAnimationTransitions.enterTransition() },
        exitTransition = { NavigationAnimationTransitions.exitTransition() },
        popEnterTransition = { NavigationAnimationTransitions.popEnterTransition() },
        popExitTransition = { NavigationAnimationTransitions.popExitTransition() },
    ) {
        composable("terminal") {
            TerminalScreenInternal(terminalActivity = terminalActivity, navController = navController)
        }
        composable(SettingsRoutes.TerminalSettings.route) { SettingsTerminalScreen(navController) }
        composable(SettingsRoutes.TerminalFontScreen.route) { TerminalFontScreen() }
        composable(SettingsRoutes.TerminalExtraKeys.route) { TerminalExtraKeys() }
        composable(SettingsRoutes.TerminalCheck.route) { TerminalCheckScreen() }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreenInternal(modifier: Modifier = Modifier, terminalActivity: Terminal, navController: NavController) {
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
            fullscreen = false,
            mainContent = {
                Scaffold(
                    topBar = {
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
                                            Modifier
                                                .size(8.dp)
                                                .background(
                                                    if (bellPulse) {
                                                        Color(0xFFFFB300)
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    },
                                                    CircleShape,
                                                ),
                                    )
                                    SessionTitle(terminalActivity)
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, stringResource(strings.drawer))
                                }
                            },
                        )
                    }
                ) { paddingValues ->
                    Column(modifier = Modifier.padding(paddingValues)) {
                        TerminalView(isDarkMode, currentTheme, surfaceColor, onSurfaceColor, terminalActivity)

                        // Extra-keys row: height derives from the key-row count so each
                        // key keeps a >=48dp touch target (a11y minimum). The default
                        // matrix has 2 rows -> 96dp; landscape also 96dp so keys are
                        // tappable instead of the previous 26-37dp. The input page uses
                        // the same height so the two pager pages align.
                        // Parse the extra-keys matrix once per settings change, not on every
                        // recomposition (the JSONArray constructor is slow enough to jank the
                        // terminal screen when sessions/theme/state churn).
                        val extraKeysRowCount =
                            remember(Settings.terminal_extra_keys) {
                                runCatching { org.json.JSONArray(Settings.terminal_extra_keys).length() }.getOrElse { 2 }
                            }
                        // Hidden entirely when disabled or the matrix is empty —
                        // an empty row would still reserve space and a swipe zone.
                        val showExtraKeys =
                            Settings.terminal_show_extra_keys &&
                                extraKeysRowCount > 0

                        if (showExtraKeys) {
                            val pagerState = rememberPagerState(pageCount = { 2 })
                            val keyRowHeight = (extraKeysRowCount * 48).coerceAtLeast(52).dp
                            val extraKeysLabel = stringResource(strings.extra_keys)

                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxWidth().height(keyRowHeight),
                            ) { page ->
                                when (page) {
                                    0 -> {
                                        AndroidView(
                                            factory = { context ->
                                                VirtualKeysView(context, null).apply {
                                                    virtualKeysView = WeakReference(this)
                                                    virtualKeysViewClient =
                                                        terminalView.get()?.mTermSession?.let { VirtualKeysListener(it) }
    
                                                    buttonTextColor = onSurfaceColor
    
                                                    runCatching {
                                                        reload(
                                                            VirtualKeysInfo(
                                                                Settings.terminal_extra_keys,
                                                                "",
                                                                VirtualKeysConstants.CONTROL_CHARS_ALIASES,
                                                            )
                                                        )
                                                    }
                                                        .onFailure {
                                                            toast(strings.invalid_terminal_extra_keys)
                                                            reload(
                                                                VirtualKeysInfo(
                                                                    DEFAULT_TERMINAL_EXTRA_KEYS,
                                                                    "",
                                                                    VirtualKeysConstants.CONTROL_CHARS_ALIASES,
                                                                )
                                                            )
                                                        }
                                                }
                                            },
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(keyRowHeight)
                                                    .semantics {
                                                        contentDescription = extraKeysLabel
                                                    },
                                        )
                                    }
    
                                    1 -> {
                                        var text by rememberSaveable { mutableStateOf("") }
                                        val focusRequester = remember { FocusRequester() }
    
                                        Box(
                                            modifier = Modifier.fillMaxWidth().height(keyRowHeight),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            TextField(
                                                value = text,
                                                onValueChange = { text = it },
                                                maxLines = 1,
                                                singleLine = true,
                                                label = { Text(text = stringResource(strings.input)) },
                                                shape = MaterialTheme.shapes.medium,
                                                colors =
                                                    TextFieldDefaults.colors(
                                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    ),
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                                keyboardActions =
                                                    KeyboardActions(
                                                        onDone = {
                                                            if (text.isEmpty()) {
                                                                // Dispatch enter key events if text is empty
                                                                val eventDown =
                                                                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                                                                val eventUp =
                                                                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                                                                terminalView.get()?.dispatchKeyEvent(eventDown)
                                                                terminalView.get()?.dispatchKeyEvent(eventUp)
                                                            } else {
                                                                terminalView.get()?.currentSession?.write(text)
                                                                text = ""
                                                            }
                                                        }
                                                    ),
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 8.dp)
                                                        .focusRequester(focusRequester),
                                            )
                                        }
    
                                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                                    }
                                }
                            }
    
                            // Refocus the terminal when swiping back from the input
                            // page so typing resumes without an extra tap.
                            LaunchedEffect(pagerState.currentPage) {
                                if (pagerState.currentPage == 0) {
                                    terminalView.get()?.requestFocus()
                                }
                            }
                        }
                    }
                }
            },
        ) {
            TerminalDrawer(terminalActivity, navController)
        }
    }
}

@Composable
private fun SessionTitle(terminalActivity: Terminal) {
    val name = terminalActivity.sessionBinder?.get()?.getService()?.currentSession?.value ?: "main"
    Text(
        text = name,
        style = MaterialTheme.typography.titleLarge,
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
    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
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
                val client = TerminalBackEnd()
                // Wire up the client immediately, independent of session availability
                // below. TerminalView.onCreateInputConnection() reads mClient
                // unconditionally as soon as this view is attached/focused, which can
                // race ahead of an in-flight session restore on cold start
                // (restorePending below) and NPE if setTerminalViewClient() were
                // deferred until a session exists.
                setTerminalViewClient(client)

                val session =
                    if (pendingCommand != null) {
                        terminalActivity.sessionBinder?.get()!!.getService().currentSession.value =
                            pendingCommand!!.id
                        terminalActivity.sessionBinder?.get()!!.getSession(pendingCommand!!.id)
                            ?: terminalActivity.sessionBinder
                                ?.get()!!
                                .createSession(pendingCommand!!.id, client, terminalActivity)
                                .session
                    } else {
                        val binder = terminalActivity.sessionBinder?.get()!!
                        val service = binder.getService()
                        val current = service.currentSession.value
                        binder.getSession(current)
                            ?: if (service.restorePending) {
                                // Saved sessions are being spawned off the main
                                // thread (cold start) — attach the restored
                                // session to this view when it lands.
                                service.onRestored {
                                    if (terminalView.get()?.mTermSession != null) return@onRestored
                                    terminalActivity.changeSession(
                                        terminalActivity.sessionBinder?.get()!!.getService().currentSession.value
                                    )
                                }
                                null
                            } else {
                                binder.createSession(current, client, terminalActivity).session
                            }
                    }

                if (session != null) {
                    session.updateTerminalSessionClient(client)
                    attachSession(session)
                    setTerminalViewClient(client)
                }

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
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics { contentDescription = terminalOutputLabel },
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

@Composable
private fun ColumnScope.TerminalDrawer(terminalActivity: Terminal, navController: NavController) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }
    var sessionToRename by remember { mutableStateOf("") }
    var renameValue by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

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
        neighborSession?.let { terminalActivity.changeSession(it) }

        binder.terminateSession(id)

        if (svc.sessionList.isEmpty()) {
            terminalActivity.finish()
            svc.actionExit()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Branded header ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    painter = painterResource(drawables.terminal),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp).size(28.dp),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = stringResource(strings.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // ── Settings entry ─────────────────────────────────────────────
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .semantics { role = Role.Button }
                    .clickable { context.startActivity(Intent(context, SettingsActivity::class.java)) },
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
            )
        }

        // ── Sessions ───────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
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
                            newString = "main #$index"
                            index++
                        } while (newString in existingStrings)

                        return newString
                    }
                    terminalView.get()?.let {
                        val client = TerminalBackEnd()
                        val info =
                            terminalActivity.sessionBinder
                                ?.get()!!
                                .createSession(
                                    generateUniqueString(
                                        terminalActivity.sessionBinder?.get()!!.getService().sessionList
                                    ),
                                    client,
                                    terminalActivity,
                                )
                        // Switch to the new session immediately — creating it
                        // silently in the background feels like a dead button.
                        terminalActivity.changeSession(info.id)
                    }
                },
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
                modifier = Modifier.weight(1f),
            ) {
                items(sessions) { sessionId ->
                    val isSelected = sessionId == service.currentSession.value

                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = sessionId,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        selected = isSelected,
                        onClick = { terminalActivity.changeSession(sessionId) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        colors =
                            NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                unselectedContainerColor = Color.Transparent,
                            ),
                        badge = {
                            Row {
                                IconButton(
                                    onClick = {
                                        sessionToRename = sessionId
                                        renameValue = sessionId
                                        renameError = null
                                        showRenameDialog = true
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = stringResource(strings.rename),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        sessionToDelete = sessionId
                                        showDeleteConfirm = true
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(strings.delete_session),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        // ── Footer actions ─────────────────────────────────────────────
        val activeSession = service?.currentSession?.value

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (service == null || activeSession == null) return@TextButton
                    sessionToDelete = activeSession
                    showDeleteConfirm = true
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(strings.delete_session),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(strings.delete_session),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TextButton(
                onClick = {
                    // Respect the "Confirm exit" setting: ask before closing the app.
                    if (Settings.confirm_exit) {
                        showExitConfirm = true
                    } else {
                        service?.actionExit()
                    }
                },
            ) {
                Text(
                    text = stringResource(strings.logout),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
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
                    },
                ) {
                    Text(
                        text = stringResource(strings.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    sessionToDelete = null
                }) {
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
                    },
                ) {
                    Text(text = stringResource(strings.logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(text = stringResource(strings.cancel))
                }
            },
        )
    }
}

fun Terminal.changeSession(sessionId: String) {
    val terminalView = terminalView.get() ?: return
    val binder = sessionBinder!!.get()!!

    val client = TerminalBackEnd()
    val session = binder.getSession(sessionId) ?: binder.createSession(sessionId, client, this).session

    session.updateTerminalSessionClient(client)
    terminalView.attachSession(session)
    terminalView.setTerminalViewClient(client)

    terminalView.apply {
        post {
            if (Settings.terminal_keep_screen_on) keepScreenOn = true
            isFocusableInTouchMode = true
            requestFocus()
        }
    }
    virtualKeysView.get()?.apply { virtualKeysViewClient = VirtualKeysListener(terminalView.mTermSession) }

    binder.getService().currentSession.value = sessionId
    Preference.setString(ACTIVE_SESSION_KEY, sessionId)
}

// Colors last applied to the terminal, so applyTerminalColors can skip the
// expensive reset + full repaint when nothing changed. Both the AndroidView
// update block and the layout-change listener fire on every recomposition
// and every resize frame (e.g. the IME show/hide animation), where a
// redundant palette reset + invalidate would double every redraw.
private var lastAppliedColors: Properties? = null
private var lastAppliedSurface = 0
private var lastAppliedOnSurface = 0

private fun TerminalView.applyTerminalColors(onSurfaceColor: Int, surfaceColor: Int, terminalColors: Properties) {
    if (mEmulator == null) return
    if (terminalColors == lastAppliedColors && onSurfaceColor == lastAppliedOnSurface && surfaceColor == lastAppliedSurface) {
        return
    }
    lastAppliedColors = terminalColors
    lastAppliedSurface = surfaceColor
    lastAppliedOnSurface = onSurfaceColor

    this.onScreenUpdated()

    mEmulator?.mColors?.reset()
    TerminalColors.COLOR_SCHEME.updateWith(terminalColors)

    // Honor the theme's own cursor color when provided, falling back to onSurface
    // for the default schemes that don't define one.
    val cursorColor = terminalColors.getProperty("cursor")?.let { android.graphics.Color.parseColor(it) } ?: onSurfaceColor

    mEmulator?.mColors?.mCurrentColors?.apply {
        set(TextStyle.COLOR_INDEX_FOREGROUND, onSurfaceColor)
        set(TextStyle.COLOR_INDEX_BACKGROUND, surfaceColor)
        set(TextStyle.COLOR_INDEX_CURSOR, cursorColor)
    }

    invalidate()
}
