package com.ukkera.pos.security.supporting.windowsaead

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.ukkera.pos.security.WindowsOriginalCng
import com.ukkera.pos.security.WindowsSelfOwnedStorageKey
import com.ukkera.pos.security.checkCng
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Assume.assumeTrue

/** Controlled genuine-native cipher checks, not original/service/custody qualification. */
class WindowsOriginalAesGcmNativeSupportingTest {
    @Test fun waead01ExactBinaryRoundTrips() = fixture().use { key ->
        for (size in listOf(1, 256, 65_536)) {
            val plain = ByteArray(size) { it.toByte() }
            val nonce = nonce(); val aad = "WAEAD-01/$size".encodeToByteArray()
            val encrypted = cipher(key, false, nonce, aad, plain)
            try {
                assertEquals(size + 16, encrypted.size)
                val opened = cipher(key, true, nonce, aad, encrypted)
                try { assertContentEquals(plain, opened) } finally { opened.fill(0) }
            } finally { plain.fill(0); nonce.fill(0); aad.fill(0); encrypted.fill(0) }
        }
    }

    @Test fun waead02TamperingReturnsNoPlaintext() = fixture().use { key ->
        val plain = ByteArray(32) { (it * 7).toByte() }; val nonce = nonce()
        val aad = "WAEAD-02".encodeToByteArray(); val encrypted = cipher(key, false, nonce, aad, plain)
        try {
            for (at in listOf(0, encrypted.lastIndex)) {
                val changed = encrypted.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
                try { assertFailsWith<IllegalStateException> { cipher(key, true, nonce, aad, changed) } }
                finally { changed.fill(0) }
            }
            val changedNonce = nonce.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val changedAad = aad.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            try {
                assertFailsWith<IllegalStateException> { cipher(key, true, changedNonce, aad, encrypted) }
                assertFailsWith<IllegalStateException> { cipher(key, true, nonce, changedAad, encrypted) }
            } finally { changedNonce.fill(0); changedAad.fill(0) }
            val opened = cipher(key, true, nonce, aad, encrypted)
            try { assertContentEquals(plain, opened) } finally { opened.fill(0) }
        } finally { plain.fill(0); nonce.fill(0); aad.fill(0); encrypted.fill(0) }
    }

    @Test fun waead03ExistingOnlyReopenPreservesCipherAndProfile() = fixture().use { key ->
        val plain = "Synthetic prior ciphertext".encodeToByteArray(); val nonce = nonce()
        val aad = "WAEAD-03".encodeToByteArray(); val encrypted = cipher(key, false, nonce, aad, plain)
        try {
            key.reopenExisting()
            val opened = cipher(key, true, nonce, aad, encrypted)
            try { assertContentEquals(plain, opened) } finally { opened.fill(0) }
            val nextNonce = nonce()
            val next = cipher(key, false, nextNonce, aad, plain)
            try {
                val read = cipher(key, true, nextNonce, aad, next)
                try { assertContentEquals(plain, read) } finally { read.fill(0) }
            } finally { next.fill(0); nextNonce.fill(0) }
        } finally { plain.fill(0); nonce.fill(0); aad.fill(0); encrypted.fill(0) }
    }

    @Test fun waead04IndependentKeyCannotAuthenticate() = fixture().use { first -> fixture().use { second ->
        val plain = "Synthetic key separation".encodeToByteArray(); val nonce = nonce()
        val aad = "WAEAD-04".encodeToByteArray()
        val one = cipher(first, false, nonce, aad, plain); val two = cipher(second, false, nonce, aad, plain)
        try {
            assertFailsWith<IllegalStateException> { cipher(second, true, nonce, aad, one) }
            for ((key, sealed) in listOf(first to one, second to two)) {
                val opened = cipher(key, true, nonce, aad, sealed)
                try { assertContentEquals(plain, opened) } finally { opened.fill(0) }
            }
        } finally { plain.fill(0); nonce.fill(0); aad.fill(0); one.fill(0); two.fill(0) }
    } }

    @Test fun waead05MalformedFrameRefusedWithoutPoisoningFixture() = fixture().use { key ->
        val plain = byteArrayOf(1); val nonce = nonce(); val aad = byteArrayOf(2)
        try {
            for (size in listOf(0, 11, 13))
                assertFailsWith<IllegalArgumentException> { cipher(key, false, ByteArray(size), aad, plain) }
            assertFailsWith<IllegalArgumentException> { cipher(key, false, nonce, byteArrayOf(), plain) }
            assertFailsWith<IllegalArgumentException> { cipher(key, true, nonce, aad, ByteArray(15)) }
            val encrypted = cipher(key, false, nonce, aad, plain)
            try {
                val opened = cipher(key, true, nonce, aad, encrypted)
                try { assertContentEquals(plain, opened) } finally { opened.fill(0) }
            } finally { encrypted.fill(0) }
        } finally { plain.fill(0); nonce.fill(0); aad.fill(0) }
    }

    private fun nonce() = ByteArray(12).also { SecureRandom().nextBytes(it) }
    private fun cipher(key: Fixture, decrypt: Boolean, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
        WindowsSelfOwnedStorageKey.crypt(key.handle(), decrypt, nonce, aad, input)

    private fun fixture(): Fixture {
        assumeTrue("Genuine 64-bit Windows required; other hosts are SKIPPED", Platform.isWindows() && Native.POINTER_SIZE == 8)
        return Fixture()
    }

    private class Fixture : AutoCloseable {
        private val name = WString("ukkera-pos-synthetic-aead-" + UUID.randomUUID().toString())
        private var provider: Pointer? = null
        private var key: Pointer? = null
        private var created = false
        private var closeStarted = false
        init {
            try {
                val output = PointerByReference()
                WindowsOriginalCng.api.NCryptOpenStorageProvider(output, WString("Microsoft Software Key Storage Provider"), 0).checkCng()
                provider = checkNotNull(output.value)
                WindowsOriginalCng.api.NCryptCreatePersistedKey(provider!!, output, WString("AES"), name, 0, 0).checkCng()
                key = checkNotNull(output.value); created = true
                WindowsOriginalCng.setInt(handle(), "Length", 256)
                WindowsOriginalCng.setInt(handle(), "Export Policy", 0)
                WindowsOriginalCng.setInt(handle(), "Key Usage", 1)
                WindowsOriginalCng.api.NCryptFinalizeKey(handle(), 0x40).checkCng()
                checkProfile(); setGcm()
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        fun handle(): Pointer { check(!closeStarted); return checkNotNull(key) }
        private fun checkProfile() {
            assertEquals("AES", WindowsOriginalCng.stringProperty(handle(), "Algorithm Name"))
            assertEquals(256, WindowsOriginalCng.intProperty(handle(), "Length"))
            assertEquals(0, WindowsOriginalCng.intProperty(handle(), "Export Policy"))
            assertEquals(1, WindowsOriginalCng.intProperty(handle(), "Key Usage"))
        }
        private fun setGcm() {
            val bytes = "ChainingModeGCM\u0000".toByteArray(Charsets.UTF_16LE)
            try { com.sun.jna.Memory(bytes.size.toLong()).use { memory ->
                memory.write(0, bytes, 0, bytes.size)
                WindowsOriginalCng.api.NCryptSetProperty(handle(), WString("Chaining Mode"), memory, bytes.size, 0).checkCng()
            } } finally { bytes.fill(0) }
            assertEquals("ChainingModeGCM", WindowsOriginalCng.stringProperty(handle(), "Chaining Mode"))
        }
        fun reopenExisting() {
            val old = handle(); key = null // Never retry an uncertain native free.
            WindowsOriginalCng.api.NCryptFreeObject(old).checkCng()
            val output = PointerByReference()
            WindowsOriginalCng.api.NCryptOpenKey(checkNotNull(provider), output, name, 0, 0x40).checkCng()
            key = checkNotNull(output.value); checkProfile(); setGcm()
        }
        override fun close() {
            check(!closeStarted); closeStarted = true
            var failure: Throwable? = null
            try {
                // Reopen only our exact successfully created synthetic key for cleanup.
                if (created && key == null) {
                    val output = PointerByReference()
                    WindowsOriginalCng.api.NCryptOpenKey(checkNotNull(provider), output, name, 0, 0x40).checkCng()
                    key = checkNotNull(output.value)
                }
                if (created) {
                    DeleteKey.api.NCryptDeleteKey(checkNotNull(key), 0x40).checkCng()
                    key = null
                }
            } catch (problem: Throwable) { failure = problem }
            try { provider?.let { WindowsOriginalCng.api.NCryptFreeObject(it).checkCng(); provider = null } }
            catch (problem: Throwable) { val first = failure; if (first == null) failure = problem else first.addSuppressed(problem) }
            failure?.let { throw it }
        }
    }
    private object DeleteKey {
        val api: DeleteApi by lazy { Native.load("ncrypt", DeleteApi::class.java) }
    }
    private interface DeleteApi : StdCallLibrary { fun NCryptDeleteKey(key: Pointer, flags: Int): Int }
}
