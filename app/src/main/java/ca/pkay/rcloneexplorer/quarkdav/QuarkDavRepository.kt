package ca.pkay.rcloneexplorer.quarkdav

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val QUARKDAV_BACKUP_SCHEMA_VERSION = 1

@kotlinx.serialization.Serializable
private data class QuarkDavBackup(
    val schemaVersion: Int = QUARKDAV_BACKUP_SCHEMA_VERSION,
    val remotes: List<QuarkDavRemote> = emptyList(),
)

object QuarkDavRepository {
    // File locks coordinate the UI, streaming and QuarkDav service processes;
    // this monitor prevents OverlappingFileLockException between threads that
    // happen to enter the repository concurrently inside the same process.
    private val processLock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun root(context: Context) = File(context.filesDir, "quarkdav")
    private fun remotesDir(context: Context) = File(root(context), "remotes")
    private fun runtimeDir(context: Context) = File(root(context), "runtime")
    private fun lockFile(context: Context) = File(root(context), ".lock")
    private fun remoteFile(context: Context, id: String) = File(remotesDir(context), "$id.json")
    fun runtimeConfigFile(context: Context, id: String) = File(runtimeDir(context), "$id.json")

    private fun <T> locked(context: Context, block: () -> T): T = synchronized(processLock) {
        root(context).mkdirs()
        RandomAccessFile(lockFile(context), "rw").channel.use { channel ->
            channel.lock().use { block() }
        }
    }


    /** Returns a versioned backup containing every persisted QuarkDav remote property. */
    @JvmStatic
    fun exportBackup(context: Context): String = json.encodeToString(
        QuarkDavBackup(remotes = list(context.applicationContext)),
    )

    /** Parses and validates a QuarkDav backup without changing the current configuration. */
    @JvmStatic
    fun validateBackup(content: String) {
        parseBackup(content)
    }

    /** Replaces all persisted QuarkDav remotes after the complete backup has validated. */
    @JvmStatic
    fun replaceFromBackup(context: Context, content: String) {
        val app = context.applicationContext
        val imported = parseBackup(content)
        locked(app) {
            remotesDir(app).mkdirs()
            remotesDir(app).listFiles()?.forEach { file ->
                if (file.isFile && (file.extension == "json" || file.extension == "tmp")) file.delete()
            }
            runtimeDir(app).deleteRecursively()
            File(root(app), "status").deleteRecursively()
            imported.forEach { remote ->
                atomicWrite(remoteFile(app, remote.id), json.encodeToString(remote))
            }
        }
    }

    fun list(context: Context): List<QuarkDavRemote> = locked(context.applicationContext) {
        val dir = remotesDir(context)
        if (!dir.exists()) return@locked emptyList()
        dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file -> runCatching { json.decodeFromString<QuarkDavRemote>(file.readText()) }.getOrNull() }
            ?.map { it.normalized() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun get(context: Context, id: String): QuarkDavRemote? = locked(context.applicationContext) {
        val file = remoteFile(context, id)
        if (!file.exists()) null else runCatching { json.decodeFromString<QuarkDavRemote>(file.readText()).normalized() }.getOrNull()
    }

    fun save(context: Context, remote: QuarkDavRemote): QuarkDavRemote = locked(context.applicationContext) {
        val normalized = remote.normalized()
        require(normalized.name.isNotBlank()) { "Remote name is required" }
        require(normalized.cookie.isNotBlank()) { "Cookie is required" }
        val duplicate = remotesDir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<QuarkDavRemote>(it.readText()) }.getOrNull() }
            ?.firstOrNull {
                val addressConflict = it.port == normalized.port &&
                    (it.listenAddress == normalized.listenAddress || isWildcard(it.listenAddress) || isWildcard(normalized.listenAddress))
                it.id != normalized.id && (it.name.equals(normalized.name, true) || addressConflict)
            }
        require(duplicate == null) { "Remote name or listen port is already in use" }
        remotesDir(context).mkdirs()
        atomicWrite(remoteFile(context, normalized.id), json.encodeToString(normalized))
        normalized
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean): QuarkDavRemote? {
        val current = get(context, id) ?: return null
        return save(context, current.copy(enabled = enabled))
    }

    fun rename(context: Context, id: String, newName: String): QuarkDavRemote? {
        val current = get(context, id) ?: return null
        return save(context, current.copy(name = newName))
    }

    fun hasEnabled(context: Context): Boolean = list(context).any { it.enabled }

    fun delete(context: Context, id: String) = locked(context.applicationContext) {
        remoteFile(context, id).delete()
        runtimeConfigFile(context, id).delete()
        QuarkDavStatusStore.delete(context, id)
    }

    fun writeRuntimeConfig(context: Context, remote: QuarkDavRemote): File = locked(context.applicationContext) {
        val n = remote.normalized()
        val temp = n.runtimeTempDir(context)
        temp.mkdirs()
        val kernel = QuarkDavKernelConfig(
            listen = n.listenEndpoint(),
            prefix = n.prefix,
            username = n.username,
            password = n.password,
            noAuth = n.noAuth,
            cacheTtlSeconds = n.cacheTtlSeconds,
            tempDir = temp.absolutePath,
            cookie = QuarkDavCookieConfig(
                cookie = n.cookie,
                rootId = n.rootId,
                brand = n.brand,
                orderBy = n.orderBy,
                orderDirection = n.orderDirection,
                useTranscodingAddress = n.useTranscodingAddress,
                onlyListVideoFile = n.onlyListVideoFile,
            ),
        )
        runtimeDir(context).mkdirs()
        val file = runtimeConfigFile(context, n.id)
        atomicWrite(file, json.encodeToString(kernel))
        file
    }

    fun syncRuntimeCookie(context: Context, id: String) {
        locked(context.applicationContext) {
            val runtime = runtimeConfigFile(context, id)
            val metadata = remoteFile(context, id)
            if (!runtime.exists() || !metadata.exists()) return@locked
            val cookie = runCatching {
                json.parseToJsonElement(runtime.readText()).jsonObject["cookie"]
                    ?.jsonObject?.get("cookie")?.jsonPrimitive?.content.orEmpty()
            }.getOrDefault("")
            if (cookie.isBlank()) return@locked
            val remote = runCatching { json.decodeFromString<QuarkDavRemote>(metadata.readText()) }.getOrNull() ?: return@locked
            if (remote.cookie != cookie) atomicWrite(metadata, json.encodeToString(remote.copy(cookie = cookie)))
        }
    }

    fun fingerprint(remote: QuarkDavRemote): String = sha256(
        json.encodeToString(
            remote.normalized().copy(name = "", enabled = false, startAtBoot = false, keepCpuAwake = false),
        ),
    )

    fun cookieFingerprint(cookie: String): String = sha256(cookie)


    private fun parseBackup(content: String): List<QuarkDavRemote> {
        val rawRemotes = if (content.trimStart().startsWith("[")) {
            // Accept an early development format that stored only the array.
            json.decodeFromString<List<QuarkDavRemote>>(content)
        } else {
            val backup = json.decodeFromString<QuarkDavBackup>(content)
            require(backup.schemaVersion in 1..QUARKDAV_BACKUP_SCHEMA_VERSION) {
                "Unsupported QuarkDav backup schema: ${backup.schemaVersion}"
            }
            backup.remotes
        }

        val ids = HashSet<String>()
        val names = HashSet<String>()
        rawRemotes.forEachIndexed { index, remote ->
            require(remote.id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
                "QuarkDav remote #$index has an invalid ID"
            }
            require(ids.add(remote.id)) { "Duplicate QuarkDav remote ID: ${remote.id}" }
            require(remote.name.isNotBlank()) { "QuarkDav remote #$index has no name" }
            require(names.add(remote.name.trim().lowercase())) {
                "Duplicate QuarkDav remote name: ${remote.name}"
            }
            require(remote.cookie.isNotBlank()) { "QuarkDav remote ${remote.name} has no cookie" }
            require(remote.port in 1..65535) { "Invalid QuarkDav port for ${remote.name}" }
            require(remote.cacheTtlSeconds > 0) {
                "Invalid QuarkDav cache TTL for ${remote.name}"
            }
        }
        val decoded = rawRemotes.map { it.normalized() }
        decoded.forEachIndexed { leftIndex, left ->
            decoded.drop(leftIndex + 1).forEach { right ->
                val addressConflict = left.port == right.port &&
                    (left.listenAddress == right.listenAddress || isWildcard(left.listenAddress) || isWildcard(right.listenAddress))
                require(!addressConflict) {
                    "QuarkDav listen address is used more than once: ${left.listenEndpoint()}"
                }
            }
        }
        return decoded
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun isWildcard(address: String): Boolean = address.trim() in setOf("0.0.0.0", "::", "[::]", "")

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content, StandardCharsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }
}
