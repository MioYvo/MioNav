package io.mionav.tilepack

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface SyncResult {
    data class Updated(val identity: PackIdentity, val bytes: Long) : SyncResult
    data class UpToDate(val identity: PackIdentity) : SyncResult
    data class Failed(val reason: String) : SyncResult
}

/**
 * Pulls a pack from the phone and swaps it in only after the bytes check out.
 *
 * Interruptions are cheap: the partial file keeps its own identity, so a later run resumes from
 * the byte it stopped on. A failed check never touches the pack that already works.
 */
class PackClient(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 20_000,
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {

    /** Sidecar files live beside the destination so an atomic rename stays on one filesystem. */
    private fun sidecar(dest: File) = File(dest.parentFile, dest.name + ".json")

    private fun part(dest: File) = File(dest.parentFile, dest.name + ".part")

    private fun partIdentity(dest: File) = File(dest.parentFile, dest.name + ".part.json")

    fun sync(
        baseUrl: String,
        dest: File,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): SyncResult {
        dest.parentFile?.mkdirs()

        val remote = try {
            head(baseUrl)
        } catch (e: IOException) {
            return SyncResult.Failed("无法连接手机：${e.message ?: e.javaClass.simpleName}")
        } ?: return SyncResult.Failed("手机没有返回包的标识")

        val installed = PackSidecar.read(sidecar(dest))
        if (installed != null && installed.samePack(remote) && dest.isFile &&
            dest.length() == remote.bytes
        ) {
            return SyncResult.UpToDate(installed)
        }

        val part = part(dest)
        val resumeFrom = resumePoint(dest, remote)
        if (resumeFrom == 0L && part.isFile) part.delete()

        // Say no before writing a single byte: a half-written pack is worse than a refusal.
        val needed = remote.bytes - resumeFrom
        val available = freeSpace(dest.absoluteFile.parentFile ?: dest.absoluteFile)
        if (needed > available) {
            return SyncResult.Failed("存储空间不足：需要 ${needed / 1_048_576} MB，可用 ${available / 1_048_576} MB")
        }

        val written = try {
            download(baseUrl, remote, part, resumeFrom, onProgress)
        } catch (e: IOException) {
            // Keep the partial file: the next attempt continues from here.
            return SyncResult.Failed("下载中断：${e.message ?: e.javaClass.simpleName}")
        }

        if (part.length() != remote.bytes) {
            // A connection that ends early looks like a short file, not an error: keep it to resume.
            return SyncResult.Failed("下载未完成：${part.length()}/${remote.bytes} 字节")
        }

        when (val check = PackVerifier.check(part, remote)) {
            PackCheck.Ok -> Unit
            is PackCheck.BadBytes -> {
                discard(dest)
                return SyncResult.Failed("长度不符：期望 ${check.expected}，实际 ${check.actual}")
            }
            is PackCheck.BadSha -> {
                discard(dest)
                return SyncResult.Failed("校验和不符，已丢弃 ${part.name}")
            }
        }

        return try {
            Files.move(
                part.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            partIdentity(dest).delete()
            PackSidecar.write(sidecar(dest), remote)
            SyncResult.Updated(remote, written)
        } catch (e: IOException) {
            SyncResult.Failed("无法替换本地包：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // --- steps -----------------------------------------------------------------------------

    private fun head(baseUrl: String): PackIdentity? {
        val connection = open("$baseUrl${PackServer.PACK_PATH}")
        connection.requestMethod = "HEAD"
        return try {
            if (connection.responseCode != 200) return null
            val id = connection.getHeaderField(PackServer.HEADER_PACK) ?: return null
            val version = connection.getHeaderField(PackServer.HEADER_VERSION) ?: return null
            val sha = connection.getHeaderField(PackServer.HEADER_SHA256) ?: return null
            val bytes = connection.getHeaderField("Content-Length")?.toLongOrNull()
                ?: connection.contentLengthLong.takeIf { it > 0 }
                ?: return null
            val tiles = connection.getHeaderField(PackServer.HEADER_TILES)?.toIntOrNull() ?: 0
            PackIdentity(id, version, bytes, sha, tiles)
        } finally {
            connection.disconnect()
        }
    }

    /** How many bytes of the partial file are still valid for this remote pack. */
    private fun resumePoint(dest: File, remote: PackIdentity): Long {
        val part = part(dest)
        if (!part.isFile || part.length() == 0L) return 0
        val recorded = PackSidecar.read(partIdentity(dest))
        if (recorded == null || !recorded.samePack(remote)) return 0
        if (part.length() > remote.bytes) return 0
        return part.length()
    }

    private fun download(
        baseUrl: String,
        remote: PackIdentity,
        part: File,
        resumeFrom: Long,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long {
        PackSidecar.write(File(part.parentFile, part.name + ".json"), remote)

        val connection = open("$baseUrl${PackServer.PACK_PATH}")
        if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        val code = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
        if (code != 200 && code != 206) {
            connection.disconnect()
            throw IOException("HTTP $code")
        }

        // A server that ignores Range answers 200: start over instead of appending a duplicate.
        val append = code == 206
        var written = if (append) resumeFrom else 0L
        RandomAccessFile(part, "rw").use { raf ->
            if (append) raf.seek(resumeFrom) else raf.setLength(0)
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    raf.write(buffer, 0, read)
                    written += read
                    onProgress(written, remote.bytes)
                }
            }
            raf.fd.sync()
        }
        connection.disconnect()
        return written
    }

    private fun discard(dest: File) {
        part(dest).delete()
        partIdentity(dest).delete()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
        }
}
