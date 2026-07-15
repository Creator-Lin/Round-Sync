package ca.pkay.rcloneexplorer.quarkdav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class QuarkDavBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val shouldStart = QuarkDavRepository.list(context).any { it.enabled && it.startAtBoot }
        if (shouldStart) QuarkDavServiceActions.reconcileAtBoot(context)
    }
}
