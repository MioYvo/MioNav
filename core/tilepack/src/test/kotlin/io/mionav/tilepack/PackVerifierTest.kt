package io.mionav.tilepack

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PackVerifierTest {

    private fun tempFile(text: String): File =
        kotlin.io.path.createTempFile("mionav-verify").toFile().apply { writeText(text) }

    @Test
    fun `matching bytes and hash pass`() {
        val file = tempFile("hello pack")
        val identity = PackIdentity("p", "1", file.length(), PackVerifier.sha256(file))
        assertEquals(PackCheck.Ok, PackVerifier.check(file, identity))
    }

    @Test
    fun `wrong length is reported`() {
        val file = tempFile("hello pack")
        val check = PackVerifier.check(file, PackIdentity("p", "1", file.length() + 1, PackVerifier.sha256(file)))
        assertIs<PackCheck.BadBytes>(check)
        assertEquals(file.length() + 1, check.expected)
        assertEquals(file.length(), check.actual)
    }

    @Test
    fun `wrong hash is reported`() {
        val file = tempFile("hello pack")
        val identity = PackIdentity("p", "1", file.length(), "0".repeat(64))
        val check = PackVerifier.check(file, identity)
        assertIs<PackCheck.BadSha>(check)
        assertEquals("0".repeat(64), check.expected)
        assertEquals(PackVerifier.sha256(file), check.actual)
    }

    @Test
    fun `missing file is bad bytes`() {
        val file = File(tempFile("x").parentFile, "does-not-exist")
        val check = PackVerifier.check(file, PackIdentity("p", "1", 10, "x"))
        assertIs<PackCheck.BadBytes>(check)
    }
}
