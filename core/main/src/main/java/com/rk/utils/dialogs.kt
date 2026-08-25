package com.rk.utils

import android.app.Activity
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.rk.extension.ActivityProvider
import com.rk.extension.api.XedExtensionPoint
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.theme.XedTheme
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

// Requests outlive configuration changes by design; this caps orphans left behind
// when no host ever renders them again.
private const val REQUEST_TTL_MS = 5 * 60 * 1000L

class DialogRequest(
    val id: Long,
    val createdAt: Long,
    val cancelable: Boolean,
    val content: @Composable () -> Unit,
)

/**
 * Process-singleton store for state-hoisted dialogs. [dialog], [composableDialog] and
 * [LoadingPopup] push requests here; every activity root must compose [AppDialogHost]
 * once so the requests render.
 */
object DialogHost {
    val dialogs = mutableStateListOf<DialogRequest>()
    val loadings = mutableStateListOf<DialogRequest>()

    private val idCounter = AtomicLong()

    fun nextId(): Long = idCounter.getAndIncrement()

    fun push(request: DialogRequest) {
        dialogs.add(request)
        isDialogShowing = true
    }

    fun pushLoading(request: DialogRequest) {
        loadings.add(request)
    }

    fun remove(id: Long) {
        val removed = dialogs.removeAll { it.id == id } or loadings.removeAll { it.id == id }
        if (removed && dialogs.isEmpty() && loadings.isEmpty()) {
            isDialogShowing = false
        }
    }
}

/** Renders every pending [DialogHost] request. Compose once per activity root, inside XedTheme. */
@Composable
fun AppDialogHost() {
    if (!rememberHostResumed()) {
        // Paused hosts stay silent so a request is only visible in the foreground
        // activity, matching the old window-attached behaviour.
        return
    }

    DialogHost.dialogs.forEach { HostEntry(it) }

    DialogHost.loadings.forEach { HostEntry(it) }
}

@Composable
private fun rememberHostResumed(): Boolean {
    val lifecycleOwner = LocalContext.current as? LifecycleOwner
    val resumed =
        remember {
            mutableStateOf(
                lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
            )
        }
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose {}
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    resumed.value = event == Lifecycle.Event.ON_RESUME
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    return resumed.value
}

@Composable
private fun HostEntry(request: DialogRequest) {
    LaunchedEffect(request.id) {
        // Non-cancelable dialogs are mandatory prompts (e.g. storage
        // permission): expiring them without invoking onOk/onCancel wedges the
        // flow that pushed them. Only orphans of cancelable requests expire.
        if (!request.cancelable) return@LaunchedEffect
        val remaining = REQUEST_TTL_MS - (System.currentTimeMillis() - request.createdAt)
        if (remaining > 0) {
            delay(remaining)
        }
        DialogHost.remove(request.id)
    }

    Dialog(
        onDismissRequest = { DialogHost.remove(request.id) },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = true,
                dismissOnBackPress = request.cancelable,
                dismissOnClickOutside = request.cancelable,
            ),
    ) {
        XedTheme { request.content() }
    }
}

fun errorDialog(
    activity: Activity? = ActivityProvider.currentActivity,
    title: String = strings.error.getString(),
    msg: String,
) {
    Log.e("ERROR_DIALOG", msg)

    runOnUiThread {
        if (msg.isBlank()) {
            Log.w("ERROR_DIALOG", "Message is blank")
            return@runOnUiThread
        }
        if (msg.contains("Job was cancelled")) {
            Log.w("ERROR_DIALOG", msg)
            return@runOnUiThread
        }

        dialogRes(activity = activity, title = title, msg = msg, onOk = {})
    }
}

fun errorDialog(@StringRes msgRes: Int) {
    runOnUiThread { errorDialog(msg = msgRes.getString()) }
}

fun errorDialog(
    activity: Activity? = ActivityProvider.currentActivity,
    throwable: Throwable,
    title: String = strings.error.getString(),
) {
    runOnUiThread {
        if (throwable.message.toString().contains("Job was cancelled")) {
            Log.w("ERROR_DIALOG", throwable.message.toString())
            return@runOnUiThread
        }
        val message = StringBuilder()
        throwable.let {
            message.append(it.message).append("\n")
            if (Settings.verbose_error) {
                message.append(it.stackTraceToString()).append("\n")
            }
        }

        errorDialog(activity = activity, title = title, msg = message.toString())
    }
}

fun errorDialog(exception: Exception) {
    val message = StringBuilder()
    exception.let {
        var msg = it.message
        if (msg.isNullOrBlank()) {
            msg = it.javaClass.simpleName.replace("Exception", "")
        }
        message.append(msg).append("\n")
        if (Settings.verbose_error) {
            message.append(it.stackTraceToString()).append("\n")
        }
    }

    errorDialog(msg = message.toString())
}

var isDialogShowing = false
    private set

fun dialogRes(
    activity: Activity? = ActivityProvider.currentActivity,
    title: String? = null,
    msg: String,
    @StringRes cancelRes: Int = strings.cancel,
    @StringRes okRes: Int = strings.ok,
    onOk: (AlertDialog?) -> Unit = {},
    onCancel: ((AlertDialog?) -> Unit)? = null,
    cancelable: Boolean = true,
) {
    dialog(
        activity = activity,
        title = title,
        msg = msg,
        cancelText = cancelRes.getString(),
        okText = okRes.getString(),
        onOk = onOk,
        onCancel = onCancel,
        cancelable = cancelable,
    )
}

/**
 * Shows a confirm dialog that survives configuration changes.
 *
 * The callbacks receive `null` instead of an [AlertDialog]: there is no AppCompat handle
 * anymore. Callers must treat the parameter as nullable-and-always-null (existing
 * `?.dismiss()` style call sites are unaffected).
 */
@XedExtensionPoint
fun dialog(
    activity: Activity? = ActivityProvider.currentActivity,
    title: String? = null,
    msg: String,
    cancelText: String = strings.cancel.getString(),
    okText: String = strings.ok.getString(),
    onOk: (AlertDialog?) -> Unit = {},
    onCancel: ((AlertDialog?) -> Unit)? = null,
    cancelable: Boolean = true,
    neutralText: String? = null,
    onNeutral: ((AlertDialog?) -> Unit)? = null,
) {
    if (activity == null) {
        toast(strings.unknown_error)
        return
    }
    if (activity.isFinishing || activity.isDestroyed) {
        toast(msg)
        return
    }

    val id = DialogHost.nextId()
    DialogHost.push(
        DialogRequest(id = id, createdAt = System.currentTimeMillis(), cancelable = cancelable) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
                DialogContent(
                    title = title,
                    msg = msg,
                    cancelString = cancelText,
                    okString = okText,
                    onOk = {
                        DialogHost.remove(id)
                        onOk(null)
                    },
                    onCancel =
                        if (onCancel == null) {
                            null
                        } else {
                            {
                                DialogHost.remove(id)
                                onCancel.invoke(null)
                            }
                        },
                    neutralString = neutralText,
                    onNeutral =
                        if (onNeutral == null) {
                            null
                        } else {
                            {
                                DialogHost.remove(id)
                                onNeutral.invoke(null)
                            }
                        },
                )
            }
        }
    )
}

@Composable
private fun DialogContent(
    title: String?,
    msg: String,
    cancelString: String,
    okString: String,
    onOk: () -> Unit,
    onCancel: (() -> Unit)? = null,
    neutralString: String? = null,
    onNeutral: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(24.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(text = msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 24.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onNeutral != null && neutralString != null) {
                TextButton(
                    onClick = {
                        onNeutral()
                    }
                ) {
                    Text(neutralString)
                }
            }

            if (onNeutral != null && neutralString != null) {
                Spacer(modifier = Modifier.weight(1f))
            }

            if (onCancel != null) {
                TextButton(
                    onClick = {
                        onCancel()
                    }
                ) {
                    Text(cancelString)
                }

                Spacer(modifier = Modifier.width(8.dp))
            }

            TextButton(
                onClick = {
                    onOk()
                }
            ) {
                Text(okString)
            }
        }
    }
}

/**
 * Shows a custom composable dialog that survives configuration changes.
 *
 * The composable receives `null` instead of an [AlertDialog]; there is no AppCompat
 * handle anymore. Callers must treat the parameter as nullable-and-always-null.
 */
@XedExtensionPoint
fun composableDialog(
    activity: Activity? = ActivityProvider.currentActivity,
    cancelable: Boolean = true,
    composable: @Composable (AlertDialog?) -> Unit,
) {
    if (activity == null) {
        toast(strings.unknown_error)
        return
    }
    val id = DialogHost.nextId()
    DialogHost.push(
        DialogRequest(id = id, createdAt = System.currentTimeMillis(), cancelable = cancelable) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) { composable(null) }
        }
    )
}
