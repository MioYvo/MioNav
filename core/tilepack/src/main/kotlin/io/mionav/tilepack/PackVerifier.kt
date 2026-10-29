package io.mionav.tilepack

import java.io.File
import java.security.MessageDigest

sealed interface PackCheck {
    data object Ok : PackCheck
    data class BadBytes(val expected: Long, val actual: Long) : PackCheck
    data class BadSha(val expected: String, val actual: String) : PackCheck
}

/** Streams the file once. Used on import (phone) and after download (watch). */
object PackVerifier {

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun check(file: File, expected: PackIdentity): PackCheck {
        if (!file.isFile) return PackCheck.BadBytes(expected.bytes, 0)
        val actualBytes = file.length()
        if (actualBytes != expected.bytes) return PackCheck.BadBytes(expected.bytes, actualBytes)
        val actualSha = sha256(file)
        return if (actualSha.equals(expected.sha256, ignoreCase = true)) {
            PackCheck.Ok
        } else {
            PackCheck.BadSha(expected.sha256, actualSha)
        }
    }

    fun check(file: File, expectedBytes: Long, expectedSha: String): PackCheck =
        check(file, PackIdentity("", "", expectedBytes, expectedSha))
}
