package ca.pkay.rcloneexplorer.quarkdav

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
enum class QuarkDavRuntimeState { STOPPED, STARTING, RUNNING, ERROR }

@Serializable
data class QuarkDavRuntimeStatus(
    val id: String = "",
    val state: QuarkDavRuntimeState = QuarkDavRuntimeState.STOPPED,
    val url: String = "",
    val message: String = "",
    val pid: Long = 0L,
    val configuredCookieHash: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

object QuarkDavStatusStore {
    const val ACTION_STATUS_CHANGED = "ca.pkay.rcloneexplorer.quarkdav.STATUS_CHANGED"
    const val EXTRA_REMOTE_ID = "remote_id"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun dir(context: Context) = File(context.filesDir, "quarkdav/status")
    private fun file(context: Context, id: String) = File(dir(context), "$id.json")

    fun read(context: Context, id: String): QuarkDavRuntimeStatus {
        val f = file(context, id)
        return if (!f.exists()) QuarkDavRuntimeStatus(id = id) else
            runCatching { json.decodeFromString<QuarkDavRuntimeStatus>(f.readText()) }.getOrElse { QuarkDavRuntimeStatus(id = id) }
    }

    fun write(context: Context, status: QuarkDavRuntimeStatus) {
        dir(context).mkdirs()
        val target = file(context, status.id)
        val tmp = File(target.parentFile, target.name + ".${UUID.randomUUID()}.tmp")
        tmp.writeText(json.encodeToString(status.copy(updatedAt = System.currentTimeMillis())), StandardCharsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
            tmp.delete()
        }
        context.sendBroadcast(
            android.content.Intent(ACTION_STATUS_CHANGED)
                .setPackage(context.packageName)
                .putExtra(EXTRA_REMOTE_ID, status.id)
        )
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
    }
}
