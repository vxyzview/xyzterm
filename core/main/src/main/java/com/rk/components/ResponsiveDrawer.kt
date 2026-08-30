package com.rk.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.onGloballyPositioned
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rk.settings.Settings
import kotlinx.coroutines.launch

@Composable
@Suppress("NOTHING_TO_INLINE")
inline fun getDrawerWidth(): Dp {
    val density = LocalDensity.current
    val widthPx = LocalWindowInfo.current.containerSize.width
    val width = with(density) { (widthPx * 0.83f).toDp() }
    return width
}

@Composable
fun ResponsiveDrawer(
    drawerState: DrawerState,
    fullscreen: Boolean,
    mainContent: @Composable () -> Unit,
    sheetContent: @Composable ColumnScope.() -> Unit,
) {
    // containerSize is PIXELS: convert via density or the threshold fires on
    // ordinary phones (1080px ≈ 400dp) instead of real tablets.
    val density = LocalDensity.current
    val screenWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val isPermanentDrawer =
        remember(screenWidthDp, Settings.desktop_mode) {
            Settings.desktop_mode && screenWidthDp >= 1080.dp
        }

    if (isPermanentDrawer) {
        PermanentNavigationDrawer(
            content = mainContent,
            modifier = Modifier.imePadding().systemBarsPadding(),
            drawerContent = {
                PermanentDrawerSheet(
                    windowInsets = if (fullscreen) WindowInsets() else DrawerDefaults.windowInsets,
                    drawerShape = RectangleShape,
                    drawerContainerColor = MaterialTheme.colorScheme.background,
                    content = sheetContent,
                )
            },
        )
    } else {
        // ponytail: ModalNavigationDrawer's built-in edge swipe covers the full
        // width — accidental swipes near the bezel while pinching / panning
        // terminal text open the drawer. Disable that and drive the
        // drawer ourselves: only horizontal drags whose *start* point is
        // within `edgeMargin` of the screen edge open the drawer. Mid-screen
        // drags fall through to the TerminalView underneath untouched. The
        // 32dp margin matches the system gesture insets the OS already uses
        // for back-edge navigation.
        val scope = rememberCoroutineScope()
        val edgeMarginPx = with(density) { 32.dp.toPx() }
        var contentWidthPx by remember { mutableStateOf(0) }
        ModalNavigationDrawer(
            modifier = Modifier.imePadding().systemBarsPadding(),
            drawerState = drawerState,
            gesturesEnabled = false,
            content = {
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { contentWidthPx = it.size.width }
                        .pointerInput(drawerState, contentWidthPx, edgeMarginPx) {
                            // Detect-drag only fires once a horizontal motion
                            // exceeds the touch slop. Inside awaitPointerEventScope
                            // we look at the FIRST down event's position; if
                            // it's not in an edge margin, we bail out before
                            // claiming the gesture, so the TerminalView gets
                            // every non-edge touch unchanged.
                            awaitPointerEventScope {
                                awaitFirstDown(requireUnconsumed = false)
                                val startX = currentEvent.changes.first().position.x
                                val width = contentWidthPx.toFloat()
                                val atEdge =
                                    contentWidthPx > 0 &&
                                    (startX <= edgeMarginPx ||
                                        startX >= width - edgeMarginPx)
                                if (!atEdge) return@awaitPointerEventScope
                                // We are at an edge: claim the drag and open.
                                if (!drawerState.isOpen) {
                                    scope.launch { drawerState.open() }
                                }
                                // Consume the rest of the drag so the view
                                // underneath doesn't fight us.
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.all { it.changedToUp() }) break
                                }
                            }
                        },
                ) {
                    mainContent()
                }
            },
            drawerContent = {
                ModalDrawerSheet(
                    windowInsets = if (fullscreen) WindowInsets() else DrawerDefaults.windowInsets,
                    modifier = Modifier.width(getDrawerWidth()),
                    drawerShape = RectangleShape,
                    drawerContainerColor = MaterialTheme.colorScheme.background,
                    content = sheetContent,
                )
            },
        )
    }
}
