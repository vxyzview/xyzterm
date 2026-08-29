package com.rk.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.rk.file.sandboxHomeDir
import com.rk.settings.Preference
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Owns the session map and the lifecycle state machine that callers used to
 * reconstruct by hand: who is the current session, what is restoring, what
 * intent arrived. Hides the binder, the pendingCommand global, and the
 * WeakReference dance between [Terminal.handleIntent] and
 * [TerminalScreen.attachOrCreateSession].
 *
 * Public surface is narrow on purpose — see [attach], [detach], [switchTo],
 * [onIntent], [startRestore], [terminate], [rename]. Everything else is
 * private. Callers that need Termux-level wiring (attaching a session to a
 * view) get the [TerminalSession] back from [attach] and do that
 * themselves.
 */
class SessionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val factory: SessionFactory,
) {
    val sessions = mutableStateListOf<SessionId>()
    val currentId: MutableState<SessionId> = mutableStateOf("main")
    val attachedView: State<TerminalView?> get() = _attachedView

    private val _attachedView = mutableStateOf<TerminalView?>(null)
    private val sessionMap = mutableMapOf<SessionId, TerminalSession>()
    private val pwdMap = mutableMapOf<SessionId, SessionPwd>()
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var restorePending = false
    private val restoreCallbacks = mutableListOf<() -> Unit>()
    private var daemonRunning = false

    /**
     * Attaches [view] for the session named by [currentId]. Returns the
     * `TerminalSession` so the caller can wire it to the view (call
     * `view.attachSession(session)` and `view.setTerminalViewClient(client)`).
     * If a restore is in flight, defer until it lands, then re-resolve
     * against whatever the saved map produced.
     */
    suspend fun attach(view: TerminalView): TerminalSession? {
        _attachedView.value = view
        if (restorePending) {
            onRestored { attach(view) }
            return null
        }
        // attach always consumes the pending one-shot command, even if the
        // session was already mapped. Matches the previous behaviour where
        // MkSession.createSession nulled pendingCommand unconditionally.
        val pending = consumePendingCommand()
        return materialize(currentId.value, "", pending, newSession = false)?.session
    }

    fun detach() {
        _attachedView.value = null
    }

    /**
     * Switches the attached view to the session with [id]. Caller does the
     * view wiring using the returned [TerminalSession].
     */
    suspend fun switchTo(id: SessionId, client: TerminalSessionClient): TerminalSession? {
        val view = attachedView.value ?: return null
        if (!restorePending && id == currentId.value && sessionMap[id]?.isRunning == true) {
            return sessionMap[id]
        }
        return materialize(id, "", null, newSession = true, clientOverride = client)?.session?.also {
            view.attachSession(it)
        }
    }

    /**
     * Hands the latest intent to the controller. Handles the cwd-intent
     * (creates-or-finds the session for that pwd and switches to it) and
     * the xyzterm://session/<name> deep-link. Other intents are ignored.
     * Defer if restore is in flight.
     */
    fun onIntent(intent: Intent) {
        if (restorePending) {
            onRestored { onIntent(intent) }
            return
        }
        if (intent.data?.scheme == "xyzterm") {
            handleDeepLink(intent.data ?: return)
            return
        }
        val pwd = intent.getStringExtra("cwd") ?: return
        val view = attachedView.value ?: return
        val sessionId = File(pwd).name
        scope.launch(Dispatchers.Main) {
            val info = materialize(sessionId, pwd, null, newSession = true) ?: return@launch
            view.attachSession(info.session)
        }
    }

    private fun handleDeepLink(uri: Uri) {
        when (uri.host) {
            "session" -> {
                val name = uri.lastPathSegment?.trim().orEmpty()
                if (!isValidSessionName(name)) return
                scope.launch(Dispatchers.Main) {
                    val view = attachedView.value ?: return@launch
                    val info = materialize(name, "", null, newSession = true) ?: return@launch
                    view.attachSession(info.session)
                }
            }
            // "run" / "?cmd=" intentionally dropped: a BROWSABLE link writing
            // commands into a live session is unprompted exec inside the sandbox.
        }
    }

    /**
     * Spawns the saved sessions off the main thread. Idempotent — concurrent
     * or post-publish calls are no-ops. Mirrors the previous
     * SessionService.restoreSessions behaviour including the
     * daemonRunning check.
     */
    fun startRestore() {
        if (restorePending || sessionMap.isNotEmpty()) return
        val saved = savedSessions()
        if (saved.isEmpty()) return
        restorePending = true
        restoreScope.launch {
            val built =
                saved
                    .map { (id, pwd) ->
                        async {
                            runCatching { factory.create(id, pwd, TerminalBackEnd(), isExtraction = false) }
                                .getOrNull()
                                ?.let { id to it }
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            withContext(Dispatchers.Main) {
                if (!daemonRunning) {
                    built.forEach { it.second.first.finishIfRunning() }
                    restorePending = false
                    return@withContext
                }
                built.forEach { (id, pair) ->
                    val (session, pwd) = pair
                    if (id in sessionMap) {
                        session.finishIfRunning()
                    } else {
                        sessionMap[id] = session
                        pwdMap[id] = pwd
                        sessions.add(id)
                    }
                }
                restorePending = false
                if (sessions.isNotEmpty()) {
                    val savedActive = Preference.getString(ACTIVE_SESSION_KEY, "")
                    currentId.value =
                        if (savedActive in sessions) savedActive else sessions.first()
                }
                val callbacks = restoreCallbacks.toList()
                restoreCallbacks.clear()
                callbacks.forEach { runCatching { it() } }
            }
        }
    }

    /** Finishes the session, removes from the map and persistence. */
    fun terminate(id: SessionId) {
        sessionMap[id]?.finishIfRunning()
        sessionMap.remove(id)
        pwdMap.remove(id)
        sessions.remove(id)
        removeSession(id)
        if (sessions.isEmpty() && daemonRunning) {
            daemonRunning = false
        }
    }

    /**
     * Validates [newId] against the shared rules (no `.`, `..`, slashes;
     * not a duplicate; not the same as [oldId]) and rewrites the session
     * map, persistence, and currentId in place.
     */
    fun rename(oldId: SessionId, newId: SessionId) {
        if (oldId == newId || !isValidSessionName(newId) || newId in sessions) return
        val session = sessionMap.remove(oldId) ?: return
        val pwd = pwdMap.remove(oldId) ?: return
        sessionMap[newId] = session
        pwdMap[newId] = pwd
        val index = sessions.indexOf(oldId)
        if (index != -1) sessions[index] = newId
        if (currentId.value == oldId) currentId.value = newId
        if (Preference.getString(ACTIVE_SESSION_KEY, "") == oldId) {
            Preference.setString(ACTIVE_SESSION_KEY, newId)
        }
        saveSession(newId, pwd)
        removeSession(oldId)
    }

    /** Tells the controller the host process is leaving. */
    fun shutdown() {
        sessionMap.values.forEach { it.finishIfRunning() }
        if (daemonRunning) daemonRunning = false
    }

    /** Allows new session spawns; called by the host on attach. */
    fun markDaemonRunning() {
        daemonRunning = true
    }

    /** The TerminalSession currently considered active, or null. */
    fun currentSession(): TerminalSession? = sessionMap[currentId.value]

    /**
     * The one place that turns an id+pwd into a SessionInfo. Either reuses
     * an existing session (when [newSession] is false and the id is already
     * mapped), or runs the factory off-thread, persists the new mapping,
     * sets currentId, and returns the wired info.
     *
     * The [pwd] parameter is honoured if non-empty; otherwise the factory
     * resolves its own default. [pending] is a one-shot command; if
     * non-null, it's used to override the working dir and the shell/args.
     */
    private suspend fun materialize(
        id: SessionId,
        pwd: SessionPwd,
        pending: com.rk.exec.TerminalCommand?,
        newSession: Boolean,
        clientOverride: TerminalSessionClient? = null,
    ): SessionInfo? {
        val client: TerminalSessionClient = clientOverride ?: TerminalBackEnd()
        if (!newSession) {
            sessionMap[id]?.let { existing ->
                existing.updateTerminalSessionClient(client)
                return SessionInfo(id, pwdMap[id] ?: "", existing)
            }
        }
        // Apply a one-shot pending command by writing it to the factory's
        // stash right before the factory call. The factory consumes it.
        if (pending != null) setPendingCommand(pending)
        val resolvedPwd = pwd.takeIf { it.isNotEmpty() }
            ?: pending?.workingDir?.takeIf { !it.isNullOrBlank() }
            ?: ""
        val (session, realPwd) = factory.create(id, resolvedPwd, client, isExtraction = false)
        sessionMap[id] = session
        pwdMap[id] = realPwd
        if (id !in sessions) sessions.add(id)
        saveSession(id, realPwd)
        currentId.value = id
        Preference.setString(ACTIVE_SESSION_KEY, id)
        session.updateTerminalSessionClient(client)
        return SessionInfo(id, realPwd, session)
    }

    private fun onRestored(callback: () -> Unit) {
        if (restorePending) restoreCallbacks.add(callback) else callback()
    }

    private fun saveSession(id: SessionId, pwd: SessionPwd) {
        val existing =
            runCatching { JSONObject(Preference.getString(SAVED_SESSIONS_KEY, "{}")) }.getOrElse { JSONObject() }
        val map = JSONObject()
        existing.keys().forEach { key -> map.put(key, existing.getString(key)) }
        map.put(id, pwd)
        Preference.setString(SAVED_SESSIONS_KEY, map.toString())
    }

    private fun removeSession(id: SessionId) {
        val obj = runCatching { JSONObject(Preference.getString(SAVED_SESSIONS_KEY, "{}")) }.getOrElse { JSONObject() }
        obj.remove(id)
        Preference.setString(SAVED_SESSIONS_KEY, obj.toString())
    }

    private fun savedSessions(): Map<SessionId, SessionPwd> {
        val obj = runCatching { JSONObject(Preference.getString(SAVED_SESSIONS_KEY, "{}")) }.getOrElse { JSONObject() }
        return buildMap {
            obj.keys().forEach { key -> put(key, obj.getString(key)) }
        }
    }

    private fun isValidSessionName(name: String): Boolean =
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\\')

    private companion object {
        const val SAVED_SESSIONS_KEY = "saved_sessions"
    }
}

/**
 * Produces a fully wired [TerminalSession] for the given id + working dir.
 * Implementations encapsulate the shell-env assembly (sandbox or not,
 * extraction mode, pending command) so the controller never touches proot.
 */
interface SessionFactory {
    suspend fun create(
        id: SessionId,
        workingDir: SessionPwd,
        client: TerminalSessionClient,
        isExtraction: Boolean,
    ): Pair<TerminalSession, SessionPwd>
}