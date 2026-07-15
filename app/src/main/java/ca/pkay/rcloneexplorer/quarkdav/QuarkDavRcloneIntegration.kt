package ca.pkay.rcloneexplorer.quarkdav

import android.content.Context
import android.os.SystemClock
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

object QuarkDavRcloneIntegration {
    private const val READY_TIMEOUT_MS = 65_000L
    private const val CONNECT_TIMEOUT_MS = 750
    private const val POLL_INTERVAL_MS = 250L
    private val RCLONE_CONFIG_NAME =
        Regex("^[\\w\\p{L}\\p{N}.+@]+(?:[ -]+[\\w\\p{L}\\p{N}.+@-]+)*$")

    @JvmStatic
    fun createOrUpdate(context: Context, remoteId: String): String {
        return createOrUpdate(context, remoteId, null)
    }

    @JvmStatic
    fun createOrUpdate(context: Context, remoteId: String, requestedName: String?): String {
        val app = context.applicationContext
        val remote = requireNotNull(QuarkDavRepository.get(app, remoteId)) {
            app.getString(R.string.quarkdav_rclone_remote_not_found)
        }
        val startedAt = System.currentTimeMillis()
        QuarkDavServiceActions.startRemote(app, remoteId)
        waitUntilReachable(app, remote, startedAt)

        val rcloneName = resolveRcloneName(remote.name, requestedName)
        require(isValidRcloneName(rcloneName)) {
            app.getString(R.string.quarkdav_rclone_invalid_name)
        }
        val options = arrayListOf(
            rcloneName,
            "webdav",
            "url", remote.localWebDavUrl(),
            "vendor", "other",
        )
        if (!remote.noAuth) {
            options.add("user")
            options.add(remote.username)
            options.add("pass")
            options.add(remote.password)
        }

        val rclone = Rclone(app)
        require(rclone.isCompatible) { app.getString(R.string.quarkdav_rclone_incompatible) }
        val process = rclone.configCreate(options)
            ?: error(app.getString(R.string.quarkdav_rclone_config_start_failed))
        val exitCode = try {
            process.waitFor()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
        if (exitCode != 0) {
            val errorText = rclone.getCollectedErrorOutput(process).trim()
            error(
                if (errorText.isBlank()) {
                    app.getString(R.string.quarkdav_rclone_config_exit, exitCode)
                } else {
                    errorText
                },
            )
        }
        return rcloneName
    }

    private fun waitUntilReachable(
        context: Context,
        remote: QuarkDavRemote,
        startedAt: Long,
    ) {
        val endpoint = URI(remote.localWebDavUrl())
        val host = endpoint.host
            ?: error(context.getString(R.string.quarkdav_rclone_invalid_url, remote.localWebDavUrl()))
        val port = if (endpoint.port > 0) endpoint.port else 80
        val deadline = SystemClock.elapsedRealtime() + READY_TIMEOUT_MS
        var lastError = ""

        while (SystemClock.elapsedRealtime() < deadline) {
            val status = QuarkDavStatusStore.read(context, remote.id)
            if (
                status.state == QuarkDavRuntimeState.ERROR &&
                status.updatedAt >= startedAt &&
                status.message.isNotBlank()
            ) {
                error(status.message)
            }
            if (status.state == QuarkDavRuntimeState.RUNNING && status.updatedAt >= startedAt) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    }
                    return
                } catch (error: Exception) {
                    lastError = error.message.orEmpty()
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }

        val detail = QuarkDavStatusStore.read(context, remote.id).message.ifBlank { lastError }
        error(context.getString(R.string.quarkdav_rclone_ready_timeout, detail.ifBlank { remote.localWebDavUrl() }))
    }

    @JvmStatic
    fun buildRcloneName(name: String): String {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifBlank { "remote" }
        return "quarkdav_$safe"
    }

    /**
     * Uses the current generated-name behavior when the input is empty, while preserving a
     * user-supplied valid rclone config name exactly (apart from surrounding whitespace).
     */
    @JvmStatic
    fun resolveRcloneName(quarkDavName: String, requestedName: String?): String {
        return requestedName?.trim().orEmpty().ifBlank { buildRcloneName(quarkDavName) }
    }

    @JvmStatic
    fun isValidRcloneName(name: String): Boolean = RCLONE_CONFIG_NAME.matches(name)
}
