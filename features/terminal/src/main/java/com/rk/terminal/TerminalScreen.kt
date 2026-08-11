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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
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
import com.rk.App
import com.rk.activities.settings.SettingsActivity
import com.rk.activities.settings.SettingsRoutes
import com.rk.activities.terminal.Terminal
import com.rk.animations.NavigationAnimationTransitions
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
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Properties

var terminalView = WeakReference<TerminalView?>(null)
var virtualKeysView = WeakReference<VirtualKeysView?>(null)

@Composable
fun TerminalScreen(modifier: Modifier = Modifier, terminalActivity: Terminal) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "terminal",
        enterTransition = { NavigationAnimationTransitions.enterTransition },
        exitTransition = { NavigationAnimationTransitions.exitTransition },
        popEnterTransition = { NavigationAnimationTransitions.popEnterTransition },
        popExitTransition = { NavigationAnimationTransitions.popExitTransition },
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
    val currentSessionName = terminalActivity.sessionBinder?.get()?.getService()?.currentSession?.value ?: "main"

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
                                    // Live session indicator
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(8.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    )
                                    Text(
                                        text = currentSessionName,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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

                        val pagerState = rememberPagerState(pageCount = { 2 })

                        // Extra-keys row: height derives from the key-row count so each
                        // key keeps a >=48dp touch target (a11y minimum). The default
                        // matrix has 2 rows -> 96dp; landscape also 96dp so keys are
                        // tappable instead of the previous 26-37dp. The input page uses
                        // the same height so the two pager pages align.
                        val extraKeysRowCount =
                            runCatching { org.json.JSONArray(Settings.terminal_extra_keys).length() }.getOrElse { 2 }
                        val keyRowHeight = (extraKeysRowCount * 48).coerceAtLeast(52).dp
                        val extraKeysLabel = stringResource(strings.extra_keys)

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxWidth().height(keyRowHeight),
                        ) { page ->
                            when (page) {
                                0 -> {
                                    terminalView.get()?.requestFocus()
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

                                    Surface(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .height(keyRowHeight)
                                                .padding(8.dp),
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        TextField(
                                            value = text,
                                            onValueChange = { text = it },
                                            maxLines = 1,
                                            singleLine = true,
                                            placeholder = {
                                                Text(
                                                    text = stringResource(strings.input),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
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
                                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                                        )
                                    }

                                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
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
private fun ColumnScope.TerminalView(
    isDarkMode: Boolean,
    currentTheme: ThemeHolder,
    surfaceColor: Int,
    onSurfaceColor: Int,
    terminalActivity: Terminal,
) {
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

                val session =
                    if (pendingCommand != null) {
                        terminalActivity.sessionBinder?.get()!!.getService().currentSession.value = pendingCommand!!.id
                        terminalActivity.sessionBinder?.get()!!.getSession(pendingCommand!!.id)
                            ?: terminalActivity.sessionBinder
                                ?.get()!!
                                .createSession(pendingCommand!!.id, client, terminalActivity)
                                .session
                    } else {
                        terminalActivity.sessionBinder
                            ?.get()!!
                            .getSession(terminalActivity.sessionBinder?.get()!!.getService().currentSession.value)
                            ?: terminalActivity.sessionBinder
                                ?.get()!!
                                .createSession(
                                    terminalActivity.sessionBinder?.get()!!.getService().currentSession.value,
                                    client,
                                    terminalActivity,
                                )
                                .session
                    }

                session.updateTerminalSessionClient(client)
                attachSession(session)
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
                    keepScreenOn = true
                    isFocusableInTouchMode = true
                    requestFocus()
                }
            }
        },
        modifier = Modifier.fillMaxWidth().weight(1f),
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

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Branded header ─────────────────────────────────────────────
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
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

                Column {
                    Text(
                        text = stringResource(strings.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "v${App.versionCode}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Settings entry ─────────────────────────────────────────────
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { context.startActivity(Intent(context, SettingsActivity::class.java)) },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(strings.settings),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                )
            }
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
                        terminalActivity.sessionBinder
                            ?.get()!!
                            .createSession(
                                generateUniqueString(
                                    terminalActivity.sessionBinder?.get()!!.getService().sessionList
                                ),
                                client,
                                terminalActivity,
                            )
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
            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                items(sessions) { sessionId ->
                    val isSelected = sessionId == service.currentSession.value

                    // Active session indicator: leading dot
                    Box(
                        modifier =
                            Modifier
                                .padding(vertical = 2.dp)
                                .fillMaxWidth(),
                    ) {
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
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    unselectedContainerColor = Color.Transparent,
                                ),
                            badge = {
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
                            },
                        )

                        if (isSelected) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(3.dp)
                                        .height(24.dp)
                                        .align(Alignment.CenterStart)
                                        .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }

        // ── Footer actions ─────────────────────────────────────────────
        val activeSession = service?.currentSession?.value

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (service == null || activeSession == null) return@TextButton

                    val index = service.sessionList.indexOf(activeSession)
                    val sessionBefore = service.sessionList.getOrNull(index - 1)
                    val sessionAfter = service.sessionList.getOrNull(index + 1)
                    val neighborSession = sessionBefore ?: sessionAfter
                    neighborSession?.let { terminalActivity.changeSession(it) }

                    terminalActivity.sessionBinder?.get()?.terminateSession(activeSession)

                    if (service.sessionList.isEmpty()) {
                        terminalActivity.finish()
                        service.actionExit()
                    }
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
            keepScreenOn = true
            isFocusableInTouchMode = true
            requestFocus()
        }
    }
    virtualKeysView.get()?.apply { virtualKeysViewClient = VirtualKeysListener(terminalView.mTermSession) }

    binder.getService().currentSession.value = sessionId
}

private fun TerminalView.applyTerminalColors(onSurfaceColor: Int, surfaceColor: Int, terminalColors: Properties) {
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
