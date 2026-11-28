package com.xyzterm.terminal

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xyzterm.bridge.XyztermNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24
private const val POLL_INTERVAL_MS = 16L

private data class TerminalSnapshot(
    val row: Int,
    val col: Int,
    val title: String,
    val screen: String,
)

private fun parseSnapshot(json: String): TerminalSnapshot? =
    runCatching {
        val obj = JSONObject(json)
        TerminalSnapshot(
            row = obj.optInt("row"),
            col = obj.optInt("col"),
            title = obj.optString("title"),
            screen = obj.optString("screen"),
        )
    }
        .getOrNull()

/**
 * Renders a Rust-owned terminal session. Kotlin only draws snapshots and forwards input bytes; the VT parser, grid
 * and PTY live in Rust.
 */
@Composable
fun RustTerminalView(
    shell: String = "/system/bin/sh",
    fontSizeSp: Float = 14f,
    modifier: Modifier = Modifier,
) {
    var sessionHandle by remember { mutableStateOf(0L) }
    var snapshot by remember { mutableStateOf(TerminalSnapshot(0, 0, "", "")) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scrollPx by remember { mutableStateOf(0f) }
    var imeText by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val colorScheme = MaterialTheme.colorScheme
    val textStyle =
        remember(colorScheme, fontSizeSp) {
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSizeSp.sp,
                color = colorScheme.onBackground,
            )
        }
    val cellSize =
        remember(textMeasurer, textStyle) {
            val size = textMeasurer.measure("M", style = textStyle).size
            IntSize(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        }
    val cellWidth = cellSize.width.toFloat()
    val cellHeight = cellSize.height.toFloat()
    val lines = remember(snapshot.screen) { snapshot.screen.split('\n') }

    DisposableEffect(shell) {
        val handle = XyztermNative.createSession(shell, DEFAULT_COLUMNS, DEFAULT_ROWS)
        sessionHandle = handle
        onDispose { XyztermNative.closeSession(handle) }
    }

    val columns = if (canvasSize.width > 0) (canvasSize.width / cellWidth).toInt().coerceAtLeast(1) else DEFAULT_COLUMNS
    val rows = if (canvasSize.height > 0) (canvasSize.height / cellHeight).toInt().coerceAtLeast(1) else DEFAULT_ROWS

    LaunchedEffect(sessionHandle, columns, rows) {
        if (sessionHandle == 0L) return@LaunchedEffect
        withContext(Dispatchers.IO) { XyztermNative.resizeTerminal(sessionHandle, columns, rows) }
    }

    LaunchedEffect(sessionHandle) {
        if (sessionHandle == 0L) return@LaunchedEffect
        while (isActive) {
            val next =
                withContext(Dispatchers.IO) {
                    // Rust drains PTY output into its own grid; the returned bytes are already accounted for.
                    XyztermNative.readOutput(sessionHandle)
                    XyztermNative.sessionSnapshot(sessionHandle)?.let(::parseSnapshot)
                }
            if (next != null) snapshot = next
            delay(POLL_INTERVAL_MS)
        }
    }

    fun send(text: String) {
        if (text.isEmpty() || sessionHandle == 0L) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        scope.launch { withContext(Dispatchers.IO) { XyztermNative.writeInput(sessionHandle, bytes) } }
    }

    Box(modifier.fillMaxSize()) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) { detectTapGestures { focusRequester.requestFocus() } }
                    .pointerInput(snapshot.screen, cellHeight) {
                        detectDragGestures { _, dragAmount ->
                            val maxScroll = (lines.size * cellHeight - canvasSize.height).coerceAtLeast(0f)
                            scrollPx = (scrollPx + dragAmount.y).coerceIn(-maxScroll, 0f)
                        }
                    },
        ) {
            drawRect(color = colorScheme.background)
            lines.forEachIndexed { row, line ->
                drawText(
                    textMeasurer = textMeasurer,
                    text = line,
                    topLeft = Offset(0f, row * cellHeight + scrollPx),
                    style = textStyle,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    maxLines = 1,
                )
            }
            drawRect(
                color = colorScheme.primary.copy(alpha = 0.35f),
                topLeft =
                    Offset(
                        snapshot.col.coerceIn(0, columns - 1) * cellWidth,
                        snapshot.row.coerceIn(0, rows - 1) * cellHeight + scrollPx,
                    ),
                size = Size(cellWidth, cellHeight),
            )
        }

        // Tiny transparent field keeps the soft keyboard connected; printable characters arrive as text changes.
        BasicTextField(
            value = imeText,
            onValueChange = { value ->
                if (value.isNotEmpty()) {
                    send(value)
                    imeText = ""
                }
            },
            modifier =
                Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val code = event.nativeKeyEvent.keyCode
                        var mode = 0
                        if (event.isShiftPressed) mode = mode or (1 shl 29)
                        if (event.isAltPressed) mode = mode or Int.MIN_VALUE
                        if (event.isCtrlPressed) mode = mode or (1 shl 30)
                        if (event.nativeKeyEvent.metaState and KeyEvent.META_NUM_LOCK_ON != 0) {
                            mode = mode or (1 shl 28)
                        }
                        // shortcut: cursor/keypad application modes are not in the snapshot; normal-mode sequences only.
                        val special =
                            when (code) {
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT,
                                KeyEvent.KEYCODE_MOVE_HOME,
                                KeyEvent.KEYCODE_MOVE_END,
                                KeyEvent.KEYCODE_TAB,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_DEL,
                                KeyEvent.KEYCODE_FORWARD_DEL,
                                KeyEvent.KEYCODE_ESCAPE,
                                KeyEvent.KEYCODE_PAGE_UP,
                                KeyEvent.KEYCODE_PAGE_DOWN,
                                in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> true

                                else -> false
                            }
                        if (special) {
                            val sequence = XyztermNative.keyCode(code, mode, false, false)
                            if (!sequence.isNullOrEmpty()) {
                                send(sequence)
                                return@onKeyEvent true
                            }
                        }
                        if (event.isCtrlPressed && !event.isAltPressed && code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                            send((code - KeyEvent.KEYCODE_A + 1).toChar().toString())
                            return@onKeyEvent true
                        }
                        false
                    },
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
        )
    }
}
