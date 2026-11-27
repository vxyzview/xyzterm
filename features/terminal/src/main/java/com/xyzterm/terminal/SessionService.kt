package com.xyzterm.terminal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.xyzterm.activities.terminal.Terminal
import com.xyzterm.resources.drawables
import com.xyzterm.resources.getFilledString
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Preference
import com.xyzterm.settings.Settings
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SessionService : Service() {
    private val sessions = hashMapOf<SessionId, TerminalSession>()
    private val sessionWorkDirs = mutableMapOf<SessionId, SessionPwd>()
    // Serializes the check-then-create window so two deep links cannot spawn one id twice.
    private val sessionCreationMutex = Mutex()
    val sessionList = mutableStateListOf<String>()
    var currentSession = mutableStateOf("main")
    var restorePending = false
    private val restoreCallbacks = mutableListOf<() -> Unit>()
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
        ): SessionInfo = sessionCreationMutex.withLock { createSessionLocked(id, client, activity, cwd) }

        /** Allocates the next free numbered name and creates it under the same lock as [createSession]. */
        suspend fun createNumberedSession(
            client: TerminalSessionClient,
            activity: Terminal,
        ): SessionInfo = sessionCreationMutex.withLock {
            val existing = sessionList.toSet()
            var index = 1
            var name: String
            do {
                name = strings.default_session_numbered.getString().format(index++)
            } while (name in existing)
            createSessionLocked(name, client, activity, null)
        }

        private suspend fun createSessionLocked(
            id: SessionId,
            client: TerminalSessionClient,
            activity: Terminal,
            cwd: String?,
        ): SessionInfo {
            // Deep-link and in-app callers can both ask for the same id; reuse a
            // session that was already published instead of spawning a second
            // shell. The maps are only touched on Main.
            val existing = withContext(Dispatchers.Main.immediate) { sessions[id] }
            if (existing != null) {
                return SessionInfo(id, sessionWorkDirs[id].orEmpty(), existing)
            }
            // No SessionInfo can be returned for an unusable id — fail before
            // creating a directory or shell for it.
            if (id.isBlank()) throw IllegalArgumentException("Session id must not be blank")

            // Capture the one-shot extraction flag: setup.sh consumes (and deletes)
            // the rootfs tarball, so reusing it for later sessions would make them
            // exit immediately with "Session ended".
            val extraction = activity.installNextStage == NEXT_STAGE.EXTRACTION
            val info =
                MkSession.createSession(activity, client, id, extraction, cwd).let {
                    val (session, pwd) = it
                    sessions[id] = session
                    sessionWorkDirs[id] = pwd
                    sessionList.add(id)
                    saveSession(id, pwd)
                    updateNotification()
                    SessionInfo(id, pwd, session)
                }
            if (extraction) activity.installNextStage = NEXT_STAGE.NONE
            return info
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
            restoreScope.launch {
                // Spawn at most two saved shells at a time. Proot startup is
                // expensive and N parallel processes on a low-end device can
                // jank or OOM; two keeps cold start quick without spiking.
                val gate = Semaphore(2)
                val activeId = Preference.getString(ACTIVE_SESSION_KEY, "")
                val built =
                    saved.entries
                        .sortedByDescending { it.key == activeId }
                        .map { (id, pwd) ->
                            async {
                                gate.withPermit {
                                    runCatching {
                                        MkSession.createSession(
                                            activity,
                                            TerminalBackEnd(),
                                            id,
                                            false,
                                            pwd,
                                            consumePendingCommand = false,
                                        )
                                    }
                                        .getOrNull()
                                        ?.let { id to it }
                                }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                withContext(Dispatchers.Main) {
                    try {
                        // App exited while the restore was in flight — drop the shells.
                        if (!daemonRunning) {
                            built.forEach { it.second.first.finishIfRunning() }
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
                        if (sessionList.isNotEmpty()) {
                            // Restore the session captured before spawning, not whichever
                            // finished last (and not a session a launcher attached meanwhile).
                            currentSession.value =
                                if (activeId in sessionList) {
                                    activeId
                                } else {
                                    sessionList.first()
                                }
                        }
                        if (sessions.isNotEmpty()) updateNotification()
                    } finally {
                        // Any exit path must release waiters and clear the busy guard.
                        restorePending = false
                        val callbacks = restoreCallbacks.toList()
                        restoreCallbacks.clear()
                        callbacks.forEach { runCatching { it() } }
                    }
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
            if (oldId == newId || !isValidSessionName(newId) || newId in sessionList) return

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

            // Keep the persisted list in sync, otherwise the old name comes back
            // after a restart and the renamed session is not restored.
            removeSession(oldId)
            saveSession(newId, pwd)

            updateNotification()
        }
    }

    private val binder = SessionBinder()
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    /** True while shells are running or being restored — backups and restores must wait. */
    fun hasActiveWork(): Boolean = restorePending || sessionList.isNotEmpty()

    /** Runs [callback] once the in-flight session restore has published, or immediately. */
    fun onRestored(callback: () -> Unit) {
        if (restorePending) {
            restoreCallbacks.add(callback)
        } else {
            callback()
        }
    }

    /** Suspends until any cold-start restore has published its sessions. */
    suspend fun awaitRestored() = suspendCancellableCoroutine<Unit> { cont -> onRestored { cont.resume(Unit) } }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /** Identity lookup for a live session; map values are the TerminalSession objects themselves. */
    fun sessionIdFor(session: TerminalSession): SessionId? = sessions.entries.firstOrNull { it.value === session }?.key

    override fun onDestroy() {
        sessions.forEach { s -> s.value.finishIfRunning() }

        restoreScope.cancel()
        daemonRunning = false
        // Only an explicit destroy clears the choice; an LMK kill skips this
        // method, so onCreate still restores it after a low-memory restart.
        setWakeLockActive(false)
        if (Settings.auto_backup) {
            // Fire-and-forget here loses the backup when the process dies; let the OS run it.
            AutoBackupJobService.schedule(this)
        }
        instanceRef.clear()
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
        instanceRef = WeakReference(this)
        createNotificationChannel()

        if (wakeLock == null) {
            wakeLock =
                (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "${strings.app_name.getString()}::${this::class.java.simpleName}",
                )
        }
        // A process kill drops the wakelock; restore the user's choice before the
        // notification is built so its label matches reality.
        setWakeLockActive(Settings.wake_lock_active)

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
    }

    var wakeLock: PowerManager.WakeLock? = null

    /** Single source of truth for the lock and its persisted choice. */
    @SuppressLint("WakelockTimeout", "Wakelock")
    private fun setWakeLockActive(active: Boolean) {
        val lock = wakeLock ?: return
        if (active != lock.isHeld) {
            if (active) {
                lock.acquire()
            } else {
                lock.release()
            }
        }
        Settings.wake_lock_active = lock.isHeld
    }

    fun actionExit() {
        sessions.forEach { s -> s.value.finishIfRunning() }
        if (daemonRunning) {
            daemonRunning = false
        }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_EXIT" -> {
                actionExit()
            }

            "ACTION_WAKE_LOCK" -> {
                setWakeLockActive(wakeLock?.isHeld != true)
                // User tapped a notification action: show the new state now,
                // not after the 500ms session-churn coalescing window.
                postNotificationNow()
            }
        }
        // Let the system recreate the service after an LMK kill; an explicit
        // stopSelf() from ACTION_EXIT still keeps it down.
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, Terminal::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val exitIntent = Intent(this, SessionService::class.java).apply { action = "ACTION_EXIT" }
        val wakeLockIntent = Intent(this, SessionService::class.java).apply { action = "ACTION_WAKE_LOCK" }

        val exitPendingIntent =
            PendingIntent.getService(
                this,
                1,
                exitIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val wakelockPendingIntent =
            PendingIntent.getService(
                this,
                2,
                wakeLockIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(strings.notification_title.getString())
            .setContentText(getNotificationContentText(wakeLock?.isHeld == true))
            .setSmallIcon(drawables.terminal)
            .setContentIntent(pendingIntent)
            .addAction(NotificationCompat.Action.Builder(null, strings.exit.getString(), exitPendingIntent).build())
            .addAction(
                NotificationCompat.Action.Builder(
                        null,
                        if (wakeLock?.isHeld == true) {
                            strings.release_wakelock.getString()
                        } else {
                            strings.acquire_wakelock.getString()
                        },
                        wakelockPendingIntent,
                    )
                    .build(),
            )
            .setOngoing(true)
            .build()
    }

    private val CHANNEL_ID = "session_service_channel"

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    strings.notification_channel_name.getString(),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply { description = strings.notification_channel_desc.getString() }
        notificationManager.createNotificationChannel(channel)
    }

    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationRunnable = Runnable { postNotificationNow() }

    private fun updateNotification() {
        // Coalesce bursts (a script spawning many shells) into a single post.
        // ponytail: 500ms window, last call wins; bump if session churn needs tighter counts.
        notificationHandler.removeCallbacks(notificationRunnable)
        notificationHandler.postDelayed(notificationRunnable, 500)
    }

    private fun postNotificationNow() {
        runCatching {
            val notification = createNotification()
            notificationManager.notify(1, notification)
        }
            .onFailure { it.printStackTrace() }
    }

    private fun getNotificationContentText(wakelock: Boolean): String {
        val base = strings.sessions_running.getFilledString(sessions.size)
        return if (wakelock) {
            "$base ${strings.wake_lock_active.getString()}"
        } else {
            base
        }
    }

    companion object {
        const val SAVED_SESSIONS_KEY = "saved_sessions"
        private var instanceRef = WeakReference<SessionService>(null)
        val instance: SessionService?
            get() = instanceRef.get()
    }

    fun clearSavedSessions() {
        Preference.removeKey(SAVED_SESSIONS_KEY)
        Preference.removeKey(ACTIVE_SESSION_KEY)
    }

    private fun saveSession(id: SessionId, pwd: SessionPwd) {
        val existing = runCatching {
            JSONObject(Preference.getString(SAVED_SESSIONS_KEY, "{}"))
        }
            .getOrElse { JSONObject() }
        val map = JSONObject()
        existing.keys().forEach { key ->
            if (existing.opt(key) is String) {
                map.put(key, existing.optString(key))
            }
        }
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
            obj.keys().forEach { key ->
                if (obj.opt(key) is String) {
                    put(key, obj.optString(key))
                }
            }
        }
    }
}

typealias SessionId = String

typealias SessionPwd = String

/** Preference key holding the id of the last active session (restored on restart). */
const val ACTIVE_SESSION_KEY = "active_session"

data class SessionInfo(val id: SessionId, val pwd: SessionPwd, val session: TerminalSession)
