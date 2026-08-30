package com.rk.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.rk.activities.terminal.Terminal
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Owns the externally-visible responsibilities of the session service:
 * which sessions exist, which one is current, switching between them,
 * creating new ones, renaming, terminating, and the post-restore hook that
 * fixes the share/deep-link race (see commit c4f691cfc).
 *
 * Replaces the `sessionBinder?.get()?.getService()?.…` chain that previously
 * spread across six files. Constructed once in `Terminal.onCreate` and
 * passed down to every consumer that used to read the binder directly.
 *
 * The only adapter today is [ServiceBackedSessionRegistry]; the interface
 * exists so future test code can substitute an in-memory fake.
 *
 * Threading: every method is safe to call from the main thread. The shell
 * spawning inside `createNew` / `switchTo` happens off the main thread
 * (MkSession handles its own `withContext(Dispatchers.IO)`); the binder's
 * `createSession` is itself a suspend function, so callers should call from
 * a coroutine.
 */
interface SessionRegistry {
    /** The session the screen should render. Read from the Compose tree. */
    fun currentSession(): StateFlow<String>

    /**
     * True once the binder has connected and the service is reachable.
     * Exposed as a StateFlow so Compose recomposes when the connection flips
     * (matches the old `sessionBinder: MutableState` contract).
     */
    fun connectionState(): StateFlow<Boolean>

    /** All known session ids, in user-facing order. */
    fun list(): List<String>

    /** Existing session by id, or null if it doesn't exist. Does not create. */
    fun getSession(id: String): TerminalSession?

    /**
     * Switch to [id]. If a session with [id] already exists, returns it;
     * otherwise creates one owned by [client]. If a restore is in flight,
     * the call defers until restore publishes and runs the create-or-fetch
     * then — this is the c4f691cfc contract, now a property of the registry
     * rather than an emergent pattern across four call sites.
     */
    suspend fun switchTo(id: String, client: TerminalSessionClient): TerminalSession

    /**
     * Create a brand-new session with [id], unconditionally. Differs from
     * [switchTo] in that an existing session with the same id is treated
     * as success (returns it) so the drawer's "Add session" button, which
     * generates a unique id, is safe. Restore-defer is the same as [switchTo].
     */
    suspend fun createNew(id: String, client: TerminalSessionClient): TerminalSession

    /** Rename [old] to [new]. No-op if the rename would collide or [old] is unknown. */
    fun rename(old: String, new: String)

    /** Terminate the session with [id]. Service exits if it was the last one. */
    fun terminate(id: String)

    /**
     * Bind to the running service. Must be called from the main thread
     * (Android's ServiceConnection contract). Call [unbind] from onStop.
     */
    fun bind()

    /**
     * Drop the service connection. Idempotent — safe to call when not bound.
     */
    fun unbind()

    /**
     * Stop the underlying session service. Used by the drawer's delete-last
     * path (matches the old `SessionService.actionExit()` call). No-op if
     * the service is already stopped.
     */
    fun exitService()

    /**
     * Run [callback] once the in-flight restore has published, or
     * immediately if no restore is in flight. Use at construction to
     * register handlers (e.g. replay the launching intent) that must wait
     * for the saved shells to materialise before they run.
     */
    fun onRestored(callback: suspend () -> Unit)

    /**
     * Find the session whose working directory is [pwd], or null if none.
     * Used only by the launching intent's cwd branch. Lives on the
     * interface because it has the same restore-defer contract.
     */
    fun sessionByPwd(pwd: String): SessionInfo?
}

/**
 * The only concrete registry today. Owns the `WeakReference<SessionBinder>`
 * previously stored on `Terminal` and the `ServiceConnection` glue that
 * flips `isBound` and runs `restoreSessions` once the binder connects.
 */
class ServiceBackedSessionRegistry(
    private val context: Context,
    private val terminal: Terminal,
) : SessionRegistry {
    private var binderRef: WeakReference<SessionService.SessionBinder>? = null
    private var isBound = false

    private val currentSessionFlow = MutableStateFlow("main")
    private val connectionFlow = MutableStateFlow(false)

    init {
        // startForegroundService is idempotent; the bind happens in [bind].
        // Matches the previous Terminal.onStart contract.
        ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
    }

    /**
     * Bind to the running service. Must be called from the main thread
     * (Android's ServiceConnection contract). Call [unbind] from onStop.
     */
    override fun bind() {
        context.bindService(
            Intent(context, SessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    /** Drop the connection. Idempotent — safe to call when not bound. */
    override fun unbind() {
        if (!isBound) return
        runCatching { context.unbindService(connection) }
        isBound = false
    }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as SessionService.SessionBinder
                binderRef = WeakReference(binder)
                isBound = true
                connectionFlow.value = true
                // Restore saved sessions before the host's onRestored callbacks
                // (registered at construction) replay their launching intent.
                if (binder.getService().sessionList.isEmpty()) {
                    binder.restoreSessions(terminal)
                }
                // Anything queued before the binder connected routes through
                // service.onRestored so it runs after the saved shells are
                // published (the c4f691cfc contract). Calling them directly
                // here would race the restore.
                drainPendingBeforeBind()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                isBound = false
                binderRef = null
                connectionFlow.value = false
            }
        }

    private fun binder(): SessionService.SessionBinder? = binderRef?.get()

    override fun currentSession(): StateFlow<String> {
        val b = binder()
        if (b != null) {
            val live = b.getService().currentSession.value
            if (currentSessionFlow.value != live) currentSessionFlow.value = live
        }
        return currentSessionFlow
    }

    override fun connectionState(): StateFlow<Boolean> = connectionFlow

    override fun list(): List<String> = binder()?.getService()?.sessionList?.toList() ?: emptyList()

    override fun getSession(id: String): TerminalSession? = binder()?.getSession(id)

    override suspend fun switchTo(id: String, client: TerminalSessionClient): TerminalSession {
        var result: TerminalSession? = null
        onRestored {
            val b = binder() ?: return@onRestored
            val service = b.getService()
            val existing = b.getSession(id)
            val session =
                existing ?: b.createSession(id, client, terminal).session
            // Make this the active session unless a different one is already
            // active (the deep-link path may target a session that's not the
            // saved-active one). `Terminal.changeSession` re-asserts this after
            // attach; setting it here covers the factory-path call.
            service.currentSession.value = id
            result = session
        }
        return result
            ?: error(
                "switchTo($id) returned null — service disconnected mid-call",
            )
    }

    override suspend fun createNew(id: String, client: TerminalSessionClient): TerminalSession {
        var result: TerminalSession? = null
        onRestored {
            val b = binder() ?: return@onRestored
            val existing = b.getSession(id)
            result = existing ?: b.createSession(id, client, terminal).session
        }
        return result ?: error("createNew($id) returned null — service disconnected mid-call")
    }

    override fun rename(old: String, new: String) {
        binder()?.renameSession(old, new)
    }

    override fun terminate(id: String) {
        binder()?.terminateSession(id)
    }

    override fun exitService() {
        binder()?.getService()?.actionExit()
    }

    override fun onRestored(callback: suspend () -> Unit) {
        val b = binder()
        if (b == null) {
            pendingBeforeBind.add(callback)
            return
        }
        b.getService().onRestored(callback)
    }

    override fun sessionByPwd(pwd: String): SessionInfo? {
        val b = binder() ?: return null
        return b.getSessionInfoByPwd(pwd)
    }

    private val pendingBeforeBind = mutableListOf<suspend () -> Unit>()

    /** Drain anything queued before the binder connected. */
    private fun drainPendingBeforeBind() {
        if (pendingBeforeBind.isEmpty()) return
        val queued = pendingBeforeBind.toList()
        pendingBeforeBind.clear()
        // Route each callback through service.onRestored so it runs only after
        // the in-flight restore publishes. If no restore is pending (the binder
        // connected with a non-empty sessionList), onRestored fires the
        // callback immediately.
        val b = binder()
        if (b == null) {
            // Lost the binder between onServiceConnected and this drain — put
            // them back and try again next bind.
            pendingBeforeBind.addAll(queued)
            return
        }
        val service = b.getService()
        queued.forEach { service.onRestored(it) }
    }
}