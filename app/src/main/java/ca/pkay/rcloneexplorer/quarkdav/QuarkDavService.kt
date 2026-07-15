package ca.pkay.rcloneexplorer.quarkdav

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ca.pkay.rcloneexplorer.Activities.MainActivity
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.SyncLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class QuarkDavService : Service() {
    companion object {
        const val CHANNEL_ID = "quarkdav_server"
        const val NOTIFICATION_ID = 5244
        private const val PID_PREFIX = "QUARKDAV_PID "
        private const val READY_PREFIX = "QUARKDAV_READY "
    }

    private data class RunningProcess(
        @Volatile var config: QuarkDavRemote,
        @Volatile var fingerprint: String,
        val process: Process,
        @Volatile var pid: Long = 0L,
        val stopping: AtomicBoolean = AtomicBoolean(false),
        @Volatile var ready: Boolean = false,
        @Volatile var url: String = "",
    )

    private val running = ConcurrentHashMap<String, RunningProcess>()
    private val restartAttempts = ConcurrentHashMap<String, Int>()
    private val supervisor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "QuarkDavSupervisor").apply { isDaemon = true }
    }
    private val ioExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "QuarkDavIO").apply { isDaemon = true }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var loggingPreferenceReceiverRegistered = false
    private val loggingPreferenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != QuarkDavServiceActions.ACTION_LOGGING_PREFERENCE_CHANGED) return
            SyncLog.setQuarkDavLoggingEnabledForCurrentProcess(
                intent.getBooleanExtra(QuarkDavServiceActions.EXTRA_LOGGING_ENABLED, true),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SyncLog.setQuarkDavLoggingEnabledForCurrentProcess(
            SyncLog.readQuarkDavLoggingPreference(this),
        )
        ContextCompat.registerReceiver(
            this,
            loggingPreferenceReceiver,
            IntentFilter(QuarkDavServiceActions.ACTION_LOGGING_PREFERENCE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        loggingPreferenceReceiverRegistered = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.quarkdav_service_starting)))
        supervisor.scheduleAtFixedRate({ heartbeat() }, 45L, 45L, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            QuarkDavServiceActions.ACTION_STOP_ALL -> supervisor.execute {
                QuarkDavRepository.list(this).forEach { remote ->
                    if (remote.enabled) runCatching { QuarkDavRepository.save(this, remote.copy(enabled = false)) }
                }
                reconcileInternal()
            }
            QuarkDavServiceActions.ACTION_STOP_REMOTE -> supervisor.execute {
                intent.getStringExtra(QuarkDavServiceActions.EXTRA_REMOTE_ID)?.let { id ->
                    QuarkDavRepository.setEnabled(this, id, false)
                }
                reconcileInternal()
            }
            QuarkDavServiceActions.ACTION_BOOT_RECONCILE -> supervisor.execute { reconcileInternal(bootOnly = true) }
            else -> supervisor.execute { reconcileInternal() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.keys.toList().forEach { stopProcess(it, "service destroyed") }
        releaseResourceLocks()
        supervisor.shutdownNow()
        ioExecutor.shutdownNow()
        if (loggingPreferenceReceiverRegistered) {
            runCatching { unregisterReceiver(loggingPreferenceReceiver) }
            loggingPreferenceReceiverRegistered = false
        }
        SyncLog.clearQuarkDavLoggingOverrideForCurrentProcess()
        super.onDestroy()
    }

    private fun reconcileInternal(bootOnly: Boolean = false) {
        val all = QuarkDavRepository.list(this)
        val enabled = all.filter { it.enabled && (!bootOnly || it.startAtBoot) }.associateBy { it.id }

        running.keys.toList().forEach { id ->
            val desired = enabled[id]
            val current = running[id]
            if (desired == null || current == null || current.fingerprint != QuarkDavRepository.fingerprint(desired)) {
                val syncRuntimeCookie = desired == null || current == null || desired.cookie == current.config.cookie
                stopProcess(id, if (desired == null) "disabled" else "configuration changed", syncRuntimeCookie)
            } else {
                // Name and Android-only lifecycle flags do not require a kernel
                // restart, but the supervisor should immediately use their latest values.
                current.config = desired
                current.fingerprint = QuarkDavRepository.fingerprint(desired)
            }
        }

        // Stopping a process may persist a refreshed __puus/__pus cookie. Reload before
        // starting so a stale pre-stop snapshot can never overwrite the refreshed value.
        val refreshedEnabled = QuarkDavRepository.list(this)
            .filter { it.enabled && (!bootOnly || it.startAtBoot) }
            .associateBy { it.id }
        refreshedEnabled.values.forEach { remote ->
            if (!running.containsKey(remote.id)) startProcess(remote)
        }

        refreshRunningStatuses(syncCookies = false)
        updateResourceLocks(refreshedEnabled.values)
        updateNotification()
        if (refreshedEnabled.isEmpty() && running.isEmpty()) stopForegroundAndSelf()
    }

    private fun startProcess(remote: QuarkDavRemote) {
        val binary = File(applicationInfo.nativeLibraryDir, "libquarkdav.so")
        if (!binary.exists()) {
            fail(remote, getString(R.string.quarkdav_kernel_missing))
            return
        }

        try {
            val priorStatus = QuarkDavStatusStore.read(this, remote.id)
            terminateStaleKernel(remote.id)
            if (priorStatus.configuredCookieHash.isNotBlank() &&
                priorStatus.configuredCookieHash == QuarkDavRepository.cookieFingerprint(remote.cookie)
            ) {
                QuarkDavRepository.syncRuntimeCookie(this, remote.id)
            }
            val refreshed = QuarkDavRepository.get(this, remote.id) ?: remote
            val runtimeConfig = QuarkDavRepository.writeRuntimeConfig(this, refreshed)
            val processBuilder = ProcessBuilder(binary.absolutePath, "--config", runtimeConfig.absolutePath)
                .directory(filesDir)
            processBuilder.environment()["TMPDIR"] = refreshed.runtimeTempDir(this).absolutePath
            val process = processBuilder.start()
            val holder = RunningProcess(refreshed, QuarkDavRepository.fingerprint(refreshed), process)
            running[refreshed.id] = holder
            QuarkDavStatusStore.write(
                this,
                QuarkDavRuntimeStatus(
                    refreshed.id,
                    QuarkDavRuntimeState.STARTING,
                    refreshed.displayWebDavUrl(this),
                    pid = holder.pid,
                    configuredCookieHash = QuarkDavRepository.cookieFingerprint(refreshed.cookie),
                ),
            )
            readStream(holder, process.inputStream, false)
            readStream(holder, process.errorStream, true)
            ioExecutor.execute {
                val exit = runCatching { process.waitFor() }.getOrDefault(-1)
                supervisor.execute { onProcessExit(refreshed.id, holder, exit) }
            }
        } catch (t: Throwable) {
            fail(remote, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun readStream(holder: RunningProcess, stream: java.io.InputStream, error: Boolean) {
        ioExecutor.execute {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trimEnd()
                    if (line.isBlank()) return@forEach
                    if (!error && line.startsWith(PID_PREFIX)) {
                        val pid = line.removePrefix(PID_PREFIX).trim().toLongOrNull()
                            ?.takeIf { it > 0L } ?: return@forEach
                        // Serialize status-file writes with reconcile/stop operations.
                        // Without this, a late stdout callback can overwrite STOPPED
                        // with stale data from an obsolete kernel instance.
                        runCatching {
                            supervisor.execute {
                                if (running[holder.config.id] !== holder || holder.stopping.get()) return@execute
                                holder.pid = pid
                                val current = QuarkDavStatusStore.read(this, holder.config.id)
                                if (current.state == QuarkDavRuntimeState.STARTING || current.state == QuarkDavRuntimeState.RUNNING) {
                                    QuarkDavStatusStore.write(this, current.copy(pid = pid))
                                }
                            }
                        }
                    } else if (!error && line.startsWith(READY_PREFIX)) {
                        val url = line.removePrefix(READY_PREFIX).trim()
                        runCatching {
                            supervisor.execute {
                                // A configuration change can stop/remove this holder while its
                                // stdout reader still has buffered data. Never let an obsolete
                                // process overwrite the replacement process' runtime state.
                                if (running[holder.config.id] !== holder || holder.stopping.get()) return@execute
                                holder.ready = true
                                holder.url = url
                                restartAttempts.remove(holder.config.id)
                                QuarkDavStatusStore.write(
                                    this,
                                    QuarkDavRuntimeStatus(
                                        holder.config.id,
                                        QuarkDavRuntimeState.RUNNING,
                                        url,
                                        pid = holder.pid,
                                        configuredCookieHash = QuarkDavRepository.cookieFingerprint(holder.config.cookie),
                                    ),
                                )
                                SyncLog.quarkDavInfo(this, getString(R.string.quarkdav_log_title, holder.config.name), getString(R.string.quarkdav_log_started, url))
                                updateNotification()
                            }
                        }
                    } else if (error) {
                        SyncLog.quarkDavError(this, getString(R.string.quarkdav_log_title, holder.config.name), line)
                    } else {
                        SyncLog.quarkDavInfo(this, getString(R.string.quarkdav_log_title, holder.config.name), line)
                    }
                }
            }
        }
    }

    private fun onProcessExit(id: String, holder: RunningProcess, exitCode: Int) {
        if (running[id] !== holder) return
        running.remove(id)
        val beforeSync = QuarkDavRepository.get(this, id)
        if (beforeSync == null || beforeSync.cookie == holder.config.cookie) {
            QuarkDavRepository.syncRuntimeCookie(this, id)
        }
        val desired = QuarkDavRepository.get(this, id)
        val manuallyStopped = holder.stopping.get() || desired?.enabled != true
        if (manuallyStopped) {
            if (desired == null) QuarkDavStatusStore.delete(this, id)
            else QuarkDavStatusStore.write(this, QuarkDavRuntimeStatus(id, QuarkDavRuntimeState.STOPPED))
            restartAttempts.remove(id)
        } else {
            val message = getString(R.string.quarkdav_process_exited, exitCode)
            QuarkDavStatusStore.write(this, QuarkDavRuntimeStatus(id, QuarkDavRuntimeState.ERROR, message = message))
            SyncLog.quarkDavError(this, getString(R.string.quarkdav_log_title, holder.config.name), message)
            val attempt = (restartAttempts[id] ?: 0) + 1
            restartAttempts[id] = attempt
            val delay = min(60L, 1L shl min(attempt, 6))
            supervisor.schedule({
                val latest = QuarkDavRepository.get(this, id)
                if (latest?.enabled == true && !running.containsKey(id)) startProcess(latest)
                updateNotification()
            }, delay, TimeUnit.SECONDS)
        }
        updateResourceLocks(QuarkDavRepository.list(this).filter { it.enabled })
        updateNotification()
        if (QuarkDavRepository.list(this).none { it.enabled } && running.isEmpty()) stopForegroundAndSelf()
    }

    private fun stopProcess(id: String, reason: String, syncRuntimeCookie: Boolean = true) {
        val holder = running[id] ?: run {
            if (QuarkDavRepository.get(this, id) == null) QuarkDavStatusStore.delete(this, id)
            else QuarkDavStatusStore.write(this, QuarkDavRuntimeStatus(id, QuarkDavRuntimeState.STOPPED))
            return
        }
        // Mark the holder first so stdout callbacks cannot publish another RUNNING
        // state while the process is being removed and terminated.
        holder.stopping.set(true)
        running.remove(id, holder)
        SyncLog.quarkDavInfo(this, getString(R.string.quarkdav_log_title, holder.config.name), getString(R.string.quarkdav_log_stopping, reason))
        runCatching { holder.process.destroy() }
        waitForExit(holder.process, 50, 100L)
        if (isAlive(holder.process)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { holder.process.destroyForcibly() }
            else runCatching { holder.process.destroy() }
            waitForExit(holder.process, 20, 100L)
        }
        if (syncRuntimeCookie) QuarkDavRepository.syncRuntimeCookie(this, id)
        if (QuarkDavRepository.get(this, id) == null) QuarkDavStatusStore.delete(this, id)
        else QuarkDavStatusStore.write(this, QuarkDavRuntimeStatus(id, QuarkDavRuntimeState.STOPPED))
    }

    private fun waitForExit(process: Process, attempts: Int, delayMillis: Long) {
        repeat(attempts) {
            if (!isAlive(process)) return
            try {
                Thread.sleep(delayMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun heartbeat() {
        refreshRunningStatuses(syncCookies = true)
        updateNotification()
    }

    private fun refreshRunningStatuses(syncCookies: Boolean) {
        running.values.forEach { holder ->
            if (holder.ready && isAlive(holder.process)) {
                if (syncCookies) syncRunningCookie(holder)
                QuarkDavStatusStore.write(
                    this,
                    QuarkDavRuntimeStatus(
                        holder.config.id,
                        QuarkDavRuntimeState.RUNNING,
                        holder.url.ifBlank { holder.config.displayWebDavUrl(this) },
                        pid = holder.pid,
                        configuredCookieHash = QuarkDavRepository.cookieFingerprint(holder.config.cookie),
                    ),
                )
            }
        }
    }

    private fun syncRunningCookie(holder: RunningProcess) {
        val id = holder.config.id
        val before = QuarkDavRepository.get(this, id) ?: return
        // Only accept the kernel's refreshed cookie while the metadata still belongs
        // to this exact running configuration. A user-entered replacement always wins.
        if (before.cookie != holder.config.cookie) return
        QuarkDavRepository.syncRuntimeCookie(this, id)
        val refreshed = QuarkDavRepository.get(this, id) ?: return
        // Refresh non-kernel metadata too (for example a rename) without
        // restarting a healthy server process.
        holder.config = refreshed
        holder.fingerprint = QuarkDavRepository.fingerprint(refreshed)
    }

    private fun terminateStaleKernel(id: String) {
        val expectedConfig = runCatching { QuarkDavRepository.runtimeConfigFile(this, id).canonicalPath }
            .getOrElse { QuarkDavRepository.runtimeConfigFile(this, id).absolutePath }
        val candidates = linkedSetOf<Long>()
        QuarkDavStatusStore.read(this, id).pid.takeIf { it > 0L }?.let(candidates::add)
        File("/proc").listFiles()?.forEach { entry ->
            entry.name.toLongOrNull()?.let(candidates::add)
        }
        candidates.forEach { pid ->
            if (pid <= 0L || pid > Int.MAX_VALUE) return@forEach
            val cmdline = runCatching {
                String(File("/proc/$pid/cmdline").readBytes(), Charsets.UTF_8).replace('\u0000', ' ')
            }.getOrDefault("")
            if (cmdline.contains("libquarkdav.so") && cmdline.contains(expectedConfig)) {
                runCatching { android.os.Process.killProcess(pid.toInt()) }
                repeat(20) {
                    if (!File("/proc/$pid").exists()) return@forEach
                    try { Thread.sleep(50L) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@forEach
                    }
                }
            }
        }
    }

    private fun isAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun fail(remote: QuarkDavRemote, message: String) {
        QuarkDavStatusStore.write(
            this,
            QuarkDavRuntimeStatus(remote.id, QuarkDavRuntimeState.ERROR, message = message),
        )
        SyncLog.quarkDavError(this, getString(R.string.quarkdav_log_title, remote.name), message)
        updateNotification()
    }

    @SuppressLint("WakelockTimeout")
    private fun updateResourceLocks(enabled: Collection<QuarkDavRemote>) {
        val shouldHold = enabled.any { it.keepCpuAwake }
        if (shouldHold && wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:quarkdav").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!shouldHold) {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
        }

        if (shouldHold && wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:quarkdav-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!shouldHold) {
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            wifiLock = null
        }
    }

    private fun releaseResourceLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.quarkdav_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, QuarkDavService::class.java).setAction(QuarkDavServiceActions.ACTION_STOP_ALL),
            flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quarkdav_notification)
            .setContentTitle(getString(R.string.quarkdav_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(0, getString(R.string.quarkdav_stop_all), stopIntent)
            .build()
    }

    private fun updateNotification() {
        val enabledCount = QuarkDavRepository.list(this).count { it.enabled }
        val runningCount = running.values.count { it.ready && isAlive(it.process) }
        val text = resources.getQuantityString(R.plurals.quarkdav_notification_summary, enabledCount, runningCount, enabledCount)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopForegroundAndSelf() {
        releaseResourceLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
}
