package com.rk.terminal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.rk.DefaultScope
import com.rk.activities.terminal.Terminal
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Preference
import com.rk.settings.Settings
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.rk.terminal.virtualkeys.VirtualKeysView
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// No-op port for the rare case where a session is restored before the
// activity has constructed its TerminalViewPortHolder (service connects
// pre-onCreate on cold start). The restored back-end only ever reads from
// it when the user actually interacts; before the view attaches, those
// reads return null and the early-return guards skip the work.
private object RestorePlaceholderPort : TerminalViewPort {
    private val placeholderBell = BellState()
    private val placeholderForeground = mutableStateOf(false)
    override fun view(): TerminalView? = null
    override fun virtualKeys(): VirtualKeysView? = null
    override fun installView(view: TerminalView) = Unit
    override fun installVirtualKeys(view: VirtualKeysView) = Unit
    override val bell: BellState = placeholderBell
    override val isForeground = placeholderForeground
}

class SessionService : Service() {
    private val sessions = hashMapOf<SessionId, TerminalSession>()
    private val sessionWorkDirs = mutableMapOf<SessionId, SessionPwd>()
    val sessionList = mutableStateListOf<String>()
    var currentSession = mutableStateOf("main")
    var restorePending = false
    private val restoreCallbacks = mutableListOf<suspend () -> Unit>()
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var daemonRunning = false

    inner class SessionBinder : Binder() {
        fun getService(): SessionService {
            return this@SessionService
        }

        suspend fun createSession(
            id: SessionId,
            client: TerminalSessionClient,
            activity: Terminal,
            cwd: String? = null,
        ): SessionInfo {
            return MkSession.createSession(
                    activity,
                    client,
                    id,
                    activity.installNextStage != null && activity.installNextStage == NEXT_STAGE.EXTRACTION,
                    cwd,
                )
                .let {
                    val (session, pwd) = it
                    sessions[id] = session
                    sessionWorkDirs[id] = pwd
                    sessionList.add(id)
                    saveSession(id, pwd)
                    updateNotification()
                    SessionInfo(id, pwd, session)
                }
        }

        fun getSession(id: SessionId): TerminalSession? {
            return sessions[id]
        }

        fun getSessionInfoByPwd(pwd: SessionPwd): SessionInfo? {
            return sessionWorkDirs.keys
                .find { sessionWorkDirs[it] == pwd }
                ?.let { SessionInfo(it, sessionWorkDirs[it]!!, sessions[it]!!) }
        }

        fun restoreSessions(activity: Terminal) {
            if (sessions.isNotEmpty() || restorePending) return
            val saved = savedSessions()
            if (saved.isEmpty()) return

            // Spawn the saved shells off the main thread — proot startup takes
            // hundreds of ms per session, and doing it synchronously froze the
            // first frames of cold start. The terminal view defers attaching
            // until the current session is published below.
            restorePending = true
            // The activity's port may not exist yet (the binder can connect
            // before the activity has run onCreate). Fall back to a throwaway
            // port that returns no view: the screen-side setTerminalViewClient
            // and attachSession in TerminalScreen.installView/installVirtualKeys
            // never need it, and the restored session's TerminalBackEnd only
            // uses the port when the bell rings or a key is pressed, both of
            // which need a live view that the screen hasn't installed yet.
            val restorePort = activity.port ?: RestorePlaceholderPort
            restoreScope.launch {
                // Spawn the saved shells in parallel — proot startup takes hundreds
                // of ms per session, and restoring N sessions serially multiplied
                // that into seconds of cold-start delay.
                val built =
                    saved
                        .map { (id, pwd) ->
                            async {
                                runCatching {
                                    MkSession.createSession(activity, TerminalBackEnd(restorePort), id, false, pwd)
                                }
                                    .getOrNull()
                                    ?.let { id to it }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                withContext(Dispatchers.Main) {
                    // App exited while the restore was in flight — drop the shells.
                    if (!daemonRunning) {
                        built.forEach { it.second.first.finishIfRunning() }
                        restorePending = false
                        return@withContext
                    }
                    built.forEach { (id, pair) ->
                        val (session, pwd) = pair
                        if (id in sessions) {
                            // Already created on the main thread meanwhile (e.g.
                            // from the drawer) — drop the duplicate shell.
                            session.finishIfRunning()
                        } else {
                            sessions[id] = session
                            sessionWorkDirs[id] = pwd
                            sessionList.add(id)
                        }
                    }
                    restorePending = false
                    if (sessionList.isNotEmpty()) {
                        // Restore the session the user was actually using when
                        // the app closed, not whichever restored last.
                        val savedActive = Preference.getString(ACTIVE_SESSION_KEY, "")
                        currentSession.value =
                            if (savedActive in sessionList) {
                                savedActive
                            } else {
                                sessionList.first()
                            }
                    }
                    val callbacks = restoreCallbacks.toList()
                    restoreCallbacks.clear()
                    callbacks.forEach { runCatching { it() } }
                    if (sessions.isNotEmpty()) updateNotification()
                }
            }
        }

        fun terminateSession(id: SessionId) {
            sessions[id]?.apply {
                if (emulator != null) {
                    sessions[id]?.finishIfRunning()
                }
            }
            sessions.remove(id)
            sessionList.remove(id)
            sessionWorkDirs.remove(id)
            removeSession(id)

            if (sessions.isEmpty()) {
                stopSelf()
                if (daemonRunning) {
                    daemonRunning = false
                }
            } else {
                updateNotification()
            }
        }

        fun renameSession(oldId: String, newId: String) {
            if (oldId == newId || newId.isEmpty() || newId in sessionList) return

            val session = sessions.remove(oldId) ?: return
            val pwd = sessionWorkDirs.remove(oldId) ?: return

            sessions[newId] = session
            sessionWorkDirs[newId] = pwd

            val index = sessionList.indexOf(oldId)
            if (index != -1) {
                sessionList[index] = newId
            }

            if (currentSession.value == oldId) {
                currentSession.value = newId
            }

            if (Preference.getString(ACTIVE_SESSION_KEY, "") == oldId) {
                Preference.setString(ACTIVE_SESSION_KEY, newId)
            }

            updateNotification()
        }
    }

    private val binder = SessionBinder()
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    /** Runs [callback] once the in-flight session restore has published, or immediately. */
    fun onRestored(callback: suspend () -> Unit) {
        if (restorePending) {
            restoreCallbacks.add(callback)
        } else {
            restoreScope.launch { callback() }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        sessions.forEach { s -> s.value.finishIfRunning() }

        restoreScope.cancel()
        daemonRunning = false
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        if (Settings.auto_backup) {
            // IO dispatcher: tar blocks for minutes; Default pool is for CPU.
            DefaultScope.launch(Dispatchers.IO) { runCatching { TerminalBackup.autoBackup() } }
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Settings.terminate_sessions_on_exit) {
            actionExit()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        TerminalNotifications.registerChannels(this)
        val notification = createNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(1, notification)
        }

        if (daemonRunning.not()) {
            daemonRunning = true
        }

        if (wakeLock == null) {
            wakeLock =
                (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "${strings.app_name.getString()}::${this::class.java.simpleName}",
                )
        }
    }

    var wakeLock: PowerManager.WakeLock? = null

    fun actionExit() {
        sessions.forEach { s -> s.value.finishIfRunning() }
        if (daemonRunning) {
            daemonRunning = false
        }
        stopSelf()
    }

    @SuppressLint("WakelockTimeout", "Wakelock")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_EXIT" -> {
                actionExit()
            }

            "ACTION_WAKE_LOCK" -> {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                } else {
                    wakeLock?.acquire()
                }
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotification(): Notification =
        TerminalNotifications.serviceNotification(
            context = this,
            sessionCount = sessions.size,
            wakelockHeld = wakeLock?.isHeld == true,
        )

    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationRunnable = Runnable { postNotification() }

    private fun updateNotification() {
        // Coalesce bursts (a script spawning many shells) into a single post.
        // ponytail: 500ms window, last call wins; bump if session churn needs tighter counts.
        notificationHandler.removeCallbacks(notificationRunnable)
        notificationHandler.postDelayed(notificationRunnable, 500)
    }

    private fun postNotification() {
        runCatching {
            val notification = createNotification()
            notificationManager.notify(1, notification)
        }.onFailure { it.printStackTrace() }
    }

    private companion object {
        const val SAVED_SESSIONS_KEY = "saved_sessions"
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
}

typealias SessionId = String

typealias SessionPwd = String

/** Preference key holding the id of the last active session (restored on restart). */
const val ACTIVE_SESSION_KEY = "active_session"

data class SessionInfo(val id: SessionId, val pwd: SessionPwd, val session: TerminalSession)
