package com.rk.events

import com.rk.extension.api.XedExtensionPoint
import com.rk.file.FileObject
import com.rk.icons.pack.LocalIconPack
import com.rk.settings.debugOptions.LogEntry
import com.rk.theme.ThemeHolder
import com.rk.utils.logError
import java.util.Locale
import kotlin.reflect.KClass

/** Base interface for all events in the application. */
interface Event

/** Events related to file system operations. */
sealed interface FileEvent : Event {

    /** Event triggered when a new file or directory is created. */
    data class Created(val file: FileObject) : FileEvent

    /** Event triggered when a file or directory is deleted. */
    data class Deleted(val path: String) : FileEvent

    /** Event triggered when a file or directory is renamed. */
    data class Renamed(
        val file: FileObject,
        val oldPath: String,
    ) : FileEvent

    /** Event triggered when a file or directory is moved. */
    data class Moved(
        val file: FileObject,
        val oldPath: String,
    ) : FileEvent

    /** Event triggered when a file or directory is copied. */
    data class Copied(
        val file: FileObject,
        val sourcePath: String,
    ) : FileEvent
}

/** General application-level events. */
sealed interface AppEvent : Event {

    /** Event triggered when the application theme changes. */
    data class ThemeChanged(val newTheme: ThemeHolder, val oldTheme: ThemeHolder?) : AppEvent

    /** Event triggered when the application icon pack changes. */
    data class IconPackChanged(val newIconPack: LocalIconPack?, val oldIconPack: LocalIconPack?) : AppEvent

    /** Event triggered when the application language changes. */
    data class LanguageChanged(val newLanguage: Locale, val oldLanguage: Locale?) : AppEvent

    /** Event triggered when a log entry is written to the application debug logs. */
    data class LogEntryWritten(val logEntry: LogEntry, val extensionId: String?) : AppEvent
}

/** Represents a subscription to an event. */
interface EventSubscription {
    /** Unsubscribes from the event. */
    fun unsubscribe()
}

/** Central event bus for the application. */
object Events {

    @PublishedApi internal val listeners = mutableMapOf<KClass<out Event>, MutableList<suspend (Event) -> Unit>>()

    /**
     * Subscribes to events of type [T].
     *
     * @param T The type of [Event] to subscribe to.
     * @param listener The listener function to be called when the event is published.
     * @return An [EventSubscription] object that can be used to unsubscribe.
     */
    @XedExtensionPoint
    inline fun <reified T : Event> subscribe(noinline listener: suspend (T) -> Unit): EventSubscription {
        val list = listeners.getOrPut(T::class) { mutableListOf() }

        val wrapper: suspend (Event) -> Unit = { listener(it as T) }

        list += wrapper

        return object : EventSubscription {
            override fun unsubscribe() {
                list -= wrapper
            }
        }
    }

    /**
     * Triggers an event for all subscribed listeners.
     *
     * @param event The event to trigger.
     */
    suspend fun publish(event: Event) {
        listeners
            .filterKeys { it.isInstance(event) }
            .values
            .flatten()
            .forEach { listener ->
                try {
                    listener(event)
                } catch (t: Throwable) {
                    logError(t, "Listener failed for ${event::class.simpleName}")
                }
            }
    }
}
