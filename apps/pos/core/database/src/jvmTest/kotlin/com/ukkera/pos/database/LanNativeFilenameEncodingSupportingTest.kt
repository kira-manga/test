package com.ukkera.pos.database

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Self-authored, NOT independent. Frozen UTF-8/SHA-256 filename compatibility;
 * these real temporary files qualify neither native keys nor production authority. */
class LanNativeFilenameEncodingSupportingTest {
    @Test fun canonicalNativeFilenamesKeepTheExistingBytes() {
        val root = Files.createTempDirectory("lan-filename-compatibility-")
        try {
            val files = LanPersistentTestFiles(root)
            val expected = mapOf(
                "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.bin",
                "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad.bin",
                "original-key/12345678-1234-1234-1234-123456789abc" to "837772f2625c19c0be7a69e8f900e6416b0925e73f54f1bacdc597e46babffaf.bin",
                "صنف شبكة اختبار" to "174e0d6445b4da4efd5d858dc770984d8f08dc229ac4662fa06a449507a56c63.bin",
                "line\nnull\u0000" to "83a8e7c2d6bf2414ddaf66f95f9dcd890381ca33245c7a1c7ca2961d6339475a.bin",
                "é漢🙂" to "9f9ce7bf6e75b055897605db83193fd11935acde9785cbc5cf2093c20125c4e7.bin"
            )
            for ((label, name) in expected) {
                assertEquals(name, files.name(label))
                val body = "retained-original".encodeToByteArray()
                files.create(name, body)
                assertContentEquals(body, files.read(files.name(label), body.size))
            }
        } finally { remove(root) }
    }

    @Test fun malformedAndForeignPathsStillRefuseBeforeNativeRead() {
        val root = Files.createTempDirectory("lan-filename-refusal-")
        try {
            val files = LanPersistentTestFiles(root)
            for (name in listOf("../escape", "A".repeat(64) + ".bin", "0".repeat(63) + ".bin",
                "0".repeat(64) + ".BIN", "0".repeat(64) + ".bin/extra")) {
                assertFailsWith<IllegalArgumentException> { files.read(name, 1) }
            }
        } finally { remove(root) }
    }

    private fun remove(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
