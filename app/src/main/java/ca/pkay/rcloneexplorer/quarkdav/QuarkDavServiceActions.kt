package ca.pkay.rcloneexplorer.quarkdav

import android.content.Context
import android.content.Intent
import android.os.Build
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.SyncLog

object QuarkDavServiceActions {
    const val ACTION_RECONCILE = "ca.pkay.rcloneexplorer.quarkdav.RECONCILE"
    const val ACTION_STOP_REMOTE = "ca.pkay.rcloneexplorer.quarkdav.STOP_REMOTE"
    const val ACTION_STOP_ALL = "ca.pkay.rcloneexplorer.quarkdav.STOP_ALL"
    const val ACTION_BOOT_RECONCILE = "ca.pkay.rcloneexplorer.quarkdav.BOOT_RECONCILE"
    const val ACTION_LOGGING_PREFERENCE_CHANGED =
        "ca.pkay.rcloneexplorer.quarkdav.LOGGING_PREFERENCE_CHANGED"
    const val EXTRA_REMOTE_ID = "remote_id"
    const val EXTRA_LOGGING_ENABLED = "logging_enabled"

    fun startRemote(context: Context, id: String) {
        QuarkDavRepository.setEnabled(context, id, true)
        send(
            context,
            Intent(context, QuarkDavService::class.java)
                .setAction(ACTION_RECONCILE)
                .putExtra(EXTRA_REMOTE_ID, id),
        )
    }

    fun stopRemote(context: Context, id: String) {
        QuarkDavRepository.setEnabled(context, id, false)
        send(
            context,
            Intent(context, QuarkDavService::class.java)
                .setAction(ACTION_STOP_REMOTE)
                .putExtra(EXTRA_REMOTE_ID, id),
        )
    }

    fun reconcile(context: Context) {
        send(context, Intent(context, QuarkDavService::class.java).setAction(ACTION_RECONCILE))
    }

    fun reconcileIfEnabled(context: Context) {
        if (QuarkDavRepository.hasEnabled(context)) reconcile(context)
    }

    fun reconcileAtBoot(context: Context) {
        send(context, Intent(context, QuarkDavService::class.java).setAction(ACTION_BOOT_RECONCILE))
    }

    fun stopAll(context: Context) {
        send(context, Intent(context, QuarkDavService::class.java).setAction(ACTION_STOP_ALL))
    }

    /** Updates a currently running :quarkdav process without starting the service. */
    fun notifyLoggingPreferenceChanged(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.sendBroadcast(
            Intent(ACTION_LOGGING_PREFERENCE_CHANGED)
                .setPackage(app.packageName)
                .putExtra(EXTRA_LOGGING_ENABLED, enabled),
        )
    }

    private fun send(context: Context, intent: Intent) {
        val app = context.applicationContext
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        }.onFailure { error ->
            val message = app.getString(R.string.quarkdav_service_start_failed, error.message ?: error.javaClass.simpleName)
            SyncLog.quarkDavError(app, app.getString(R.string.quarkdav_title), message)
            intent.getStringExtra(EXTRA_REMOTE_ID)?.let { id ->
                QuarkDavStatusStore.write(app, QuarkDavRuntimeStatus(id, QuarkDavRuntimeState.ERROR, message = message))
            }
        }
    }
}
