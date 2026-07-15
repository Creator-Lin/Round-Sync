package ca.pkay.rcloneexplorer.quarkdav

import android.content.Context
import ca.pkay.rcloneexplorer.Items.RemoteItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.InetAddress
import java.util.UUID

@Serializable
data class QuarkDavRemote(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "QuarkDav",
    val enabled: Boolean = false,
    val startAtBoot: Boolean = true,
    val keepCpuAwake: Boolean = true,
    val listenAddress: String = "127.0.0.1",
    val port: Int = 5244,
    val prefix: String = "/dav",
    val username: String = "quark",
    val password: String = "quark",
    val noAuth: Boolean = false,
    val cacheTtlSeconds: Int = 15,
    val tempDir: String = "",
    val cookie: String = "",
    val rootId: String = "0",
    val brand: String = "quark",
    val orderBy: String = "none",
    val orderDirection: String = "asc",
    val useTranscodingAddress: Boolean = false,
    val onlyListVideoFile: Boolean = false,
) {
    companion object {
        const val PROVIDER_NAME = "quarkdav-cookie"
        const val REMOTE_NAME_PREFIX = "quarkdav:"
    }

    fun normalized(): QuarkDavRemote {
        val normalizedPrefix = prefix.trim().let {
            when {
                it.isBlank() -> "/dav"
                it == "/" -> "/dav"
                else -> "/" + it.trim('/').ifBlank { "dav" }
            }
        }
        val normalizedAddress = listenAddress.trim().ifBlank { "127.0.0.1" }
        return copy(
            name = name.trim(),
            listenAddress = normalizedAddress,
            port = port.coerceIn(1, 65535),
            prefix = normalizedPrefix,
            cacheTtlSeconds = cacheTtlSeconds.coerceAtLeast(1),
            rootId = rootId.trim().ifBlank { "0" },
            brand = if (brand.trim().equals("uc", true)) "uc" else "quark",
            orderBy = orderBy.trim().ifBlank { "none" },
            orderDirection = if (orderDirection.trim().equals("desc", true)) "desc" else "asc",
        )
    }

    fun remoteKey(): String = REMOTE_NAME_PREFIX + id

    fun toRemoteItem(context: Context): RemoteItem {
        val item = RemoteItem(remoteKey(), PROVIDER_NAME)
        val rawStatus = QuarkDavStatusStore.read(context, id)
        val status = when {
            // enabled is the desired-state source of truth. Do not keep showing a
            // stale RUNNING/STARTING suffix after the user has disabled WebDAV.
            !enabled -> rawStatus.copy(state = QuarkDavRuntimeState.STOPPED)
            rawStatus.state in setOf(QuarkDavRuntimeState.RUNNING, QuarkDavRuntimeState.STARTING) &&
                System.currentTimeMillis() - rawStatus.updatedAt > 10L * 60L * 1000L ->
                rawStatus.copy(state = QuarkDavRuntimeState.STOPPED)
            else -> rawStatus
        }
        val suffix = when (status.state) {
            QuarkDavRuntimeState.RUNNING -> context.getString(ca.pkay.rcloneexplorer.R.string.quarkdav_status_running_suffix)
            QuarkDavRuntimeState.STARTING -> context.getString(ca.pkay.rcloneexplorer.R.string.quarkdav_status_starting_suffix)
            QuarkDavRuntimeState.ERROR -> context.getString(ca.pkay.rcloneexplorer.R.string.quarkdav_status_error_suffix)
            else -> ""
        }
        item.displayName = normalized().name + suffix
        return item
    }

    fun runtimeTempDir(context: Context): File {
        val configured = tempDir.trim()
        return if (configured.isNotEmpty()) File(configured) else File(context.cacheDir, "quarkdav/$id")
    }


    fun listenEndpoint(): String {
        val n = normalized()
        val rawHost = n.listenAddress.removePrefix("[").removeSuffix("]")
        val renderedHost = if (rawHost.contains(':')) "[$rawHost]" else rawHost
        return "$renderedHost:${n.port}"
    }

    fun localWebDavUrl(): String {
        val n = normalized()
        val host = when (n.listenAddress) {
            "0.0.0.0" -> "127.0.0.1"
            "::", "[::]" -> "::1"
            else -> n.listenAddress.removePrefix("[").removeSuffix("]")
        }
        val renderedHost = if (host.contains(':')) "[$host]" else host
        return "http://$renderedHost:${n.port}${n.prefix}/"
    }

    fun displayWebDavUrl(context: Context): String {
        val statusUrl = QuarkDavStatusStore.read(context, id).url
        if (statusUrl.isNotBlank()) return statusUrl
        val n = normalized()
        val host = if (n.listenAddress in setOf("0.0.0.0", "::", "[::]")) bestLanAddress() else n.listenAddress
        val renderedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$renderedHost:${n.port}${n.prefix}/"
    }

    private fun bestLanAddress(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }.getOrNull() ?: InetAddress.getLoopbackAddress().hostAddress
}

@Serializable
internal data class QuarkDavKernelConfig(
    val listen: String,
    val prefix: String,
    val username: String,
    val password: String,
    @SerialName("no_auth") val noAuth: Boolean,
    @SerialName("cache_ttl_seconds") val cacheTtlSeconds: Int,
    @SerialName("temp_dir") val tempDir: String,
    val driver: String = "cookie",
    val cookie: QuarkDavCookieConfig,
)

@Serializable
internal data class QuarkDavCookieConfig(
    val cookie: String,
    @SerialName("root_id") val rootId: String,
    val brand: String,
    @SerialName("order_by") val orderBy: String,
    @SerialName("order_direction") val orderDirection: String,
    @SerialName("use_transcoding_address") val useTranscodingAddress: Boolean,
    @SerialName("only_list_video_file") val onlyListVideoFile: Boolean,
)
