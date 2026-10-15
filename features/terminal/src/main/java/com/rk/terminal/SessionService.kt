package com.rk.terminal

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
import com.rk.DefaultScope
import com.rk.activities.terminal.Terminal
import com.rk.resources.drawables
import com.rk.resources.getFilledString
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Preference
import com.rk.settings.Settings
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
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

/**
 * Whether a session's shell process exists and may be signalled.
 *
 * Termux's `TerminalSession.isRunning()` is `mShellPid != -1`, and `mShellPid` is a plain `int`
 * that stays `0` until `TerminalView.attachSession()` triggers `initializeEmulator()` and forks
 * the shell. A session built by `MkSession.createSession()` and never attached therefore reports
 * itself as *running*, and `finishIfRunning()` issues `Os.kill(0, SIGKILL)` — POSIX pid 0 means
 * every process in the caller's process group, which includes this app.
 *
 * `0` = constructed but never forked. `-1` = already exited. Anything above `0` is a real pid.
 */
internal fun shouldKill(pid: Int): Boolean = pid > 0

/**
 * [shouldKill] applied to a live session, then its orphaned descendants.
 *
 * proot's `--kill-on-exit` runs from `atexit()`, which never fires under the SIGKILL that
 * `finishIfRunning()` sends. The pid we hold is the tracer; killing it alone detaches its
 * tracees and leaves every guest process running under this app's UID. Sweep children first.
 */
private fun TerminalSession.killIfSpawned() {
    if (!shouldKill(pid)) return
    SessionProcessTree.killTree(pid)
    runCatching { finishIfRunning() }
}

class SessionService : Service() {
    private val sessions = hashMapOf<SessionId, TerminalSession>()
    private val sessionWorkDirs = mutableMapOf<SessionId, SessionPwd>()
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
            restoreScope.launch {
                // Spawn chunk by chunk, awaiting each before starting the next: one proot
                // tracer per session, all concurrently, is tens of MB each and expensive to
                // ptrace. Capped and ordered — see restoreChunks().
                val built = mutableListOf<Pair<SessionId, Pair<TerminalSession, SessionPwd>>>()
                for (chunk in restoreChunks(saved.keys.toList())) {
                    val batch =
                        chunk
                            .map { id ->
                                async {
                                    runCatching {
                                            MkSession.createSession(activity, TerminalBackEnd(), id, false, saved[id])
                                        }
                                        .getOrNull()
                                        ?.let { id to it }
                                }
                            }
                            .awaitAll()
                            .filterNotNull()
                    built += batch
                }
                withContext(Dispatchers.Main) {
                    // App exited while the restore was in flight — drop the shells.
                    if (!daemonRunning) {
                        built.forEach { it.second.first.killIfSpawned() }
                        restorePending = false
                        return@withContext
                    }
                    built.forEach { (id, pair) ->
                        val (session, pwd) = pair
                        if (id in sessions) {
                            // Already created on the main thread meanwhile (e.g.
                            // from the drawer) — drop the duplicate shell.
                            session.killIfSpawned()
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
            // Guard on the process, not on `emulator`: a session restored in the background and
            // never opened by the user has no emulator but does have a live shell — the old
            // `emulator != null` check skipped the kill and leaked the process.
            sessions[id]?.killIfSpawned()
            sessions.remove(id)
            // proot never frees PROOT_TMP_DIR on SIGKILL, and prepareEnvironment only
            // cleaned it on the *next* spawn of the same id.
            releaseTmpDir(id)
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
    fun onRestored(callback: () -> Unit) {
        if (restorePending) {
            restoreCallbacks.add(callback)
        } else {
            callback()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /** Kill every live session and release its proot tmp dir. */
    private fun shutdownAllSessions() {
        sessions.forEach { (id, session) ->
            session.killIfSpawned()
            releaseTmpDir(id)
        }
    }

    override fun onDestroy() {
        shutdownAllSessions()

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
        createNotificationChannel()
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
        shutdownAllSessions()
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
                    .build()
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

    private companion object {
        const val SAVED_SESSIONS_KEY = "saved_sessions"
    }

    private fun saveSession(id: SessionId, pwd: SessionPwd) {
        val existing = runCatching {
            JSONObject(Preference.getString(SAVED_SESSIONS_KEY, "{}"))
        }
            .getOrElse { JSONObject() }
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
        return buildMap { obj.keys().forEach { key -> put(key, obj.getString(key)) } }
    }
}

typealias SessionId = String

typealias SessionPwd = String

/**
 * Sessions restored on a cold start, and how many may spawn at once.
 *
 * Restoring was `saved.map { async { createSession(...) } }.awaitAll()`: one proot tracer, one
 * bash and one private tmp dir per saved session, all concurrently. ptrace is CPU-expensive and
 * each pair costs tens of MB, so a user who left several sessions around hit the watchdog or the
 * low-memory killer on cold start.
 *
 * [MAX_RESTORE] is a hard ceiling rather than a default: `Terminal` is `exported="true"` with a
 * `BROWSABLE` filter, so `xyzterm://session/<anything>` adds entries to that map for free.
 */
const val MAX_RESTORE = 3

const val MAX_CONCURRENT_RESTORE = 2

/** Total sessions one user may have open, however they were created. */
const val MAX_SESSIONS = 16

/** Restore batches: capped, ordered, and never wider than [MAX_CONCURRENT_RESTORE]. */
internal fun restoreChunks(saved: Collection<SessionId>): List<List<SessionId>> =
    saved.take(MAX_RESTORE).chunked(MAX_CONCURRENT_RESTORE)

internal fun canCreateSession(currentCount: Int): Boolean = currentCount < MAX_SESSIONS

/**
 * A guest working directory supplied from outside the app.
 *
 * `Terminal` is exported and accepts `ACTION_SEND`, so any app can put a `cwd` extra on the
 * intent. It reaches `cd "$WKDIR"` in init.sh, which falls back cleanly to `$HOME` — but an
 * externally-controlled string reaching `cd` unvalidated is not a thing to leave in place.
 */
internal fun isSafeGuestCwd(value: String): Boolean =
    value.isNotEmpty() && value.startsWith("/") && !value.contains("..")

/** Preference key holding the id of the last active session (restored on restart). */
const val ACTIVE_SESSION_KEY = "active_session"

data class SessionInfo(val id: SessionId, val pwd: SessionPwd, val session: TerminalSession)
