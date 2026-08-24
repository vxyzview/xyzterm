package com.rk.utils

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.rk.resources.getString
import com.rk.resources.strings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Progress popup that survives configuration changes by rendering through [DialogHost].
 * Safe to create from any thread; [show]/[hide]/[setMessage] are idempotent.
 */
class LoadingPopup(private val activity: AppCompatActivity?, hideAfterMillis: Long? = null) {
    private val id = DialogHost.nextId()
    private val message = mutableStateOf(strings.wait.getString())

    init {
        if (activity == null) {
            Log.e(this::class.java.simpleName, "Activity is null this loading popup will not show")
        }

        hideAfterMillis?.let { delayMillis ->
            show()
            activity?.lifecycleScope?.launch {
                delay(delayMillis)
                hide()
            }
        }
    }

    fun setMessage(message: String): LoadingPopup {
        this.message.value = message
        return this
    }

    fun show(): LoadingPopup {
        val activity = activity ?: return this
        if (activity.isFinishing || activity.isDestroyed) return this

        if (DialogHost.loadings.none { it.id == id }) {
            DialogHost.pushLoading(
                DialogRequest(id = id, createdAt = System.currentTimeMillis(), cancelable = false) {
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp).padding(8.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = message.value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            )
        }

        return this
    }

    fun hide() {
        DialogHost.remove(id)
    }
}
