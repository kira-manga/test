package com.ukkera.pos.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TEST ONLY: genuine file-backed Room + synthetic JCA AEAD and scripted peer/time providers.
 * Unlike the older current-authorization fixture, authenticate() uses the actual new verifier
 * and real principal factory. It does NOT install an accepting runtime or qualify providers.
 */
internal class LocalAccessVerifierFixture(
    driver: SQLiteDriver = BundledSQLiteDriver(),
    capacity: Int = 2,
    path: Path = Files.createTempDirectory("ukkera-local-access-test-").resolve("master.db"),
) {
    val base = LocalAuthorizationStoreFixture(path, driver)
    val peers = TestLocalAccessPeers()
    val verifier = RoomLocalAccessPrincipalVerifier(TEST_SCOPE, base.protectedStore, base.source, peers, capacity)
    val factory = TrustedPrincipalFactory(verifier)

    suspend fun authenticate(bytes: ByteArray = localAccessWire(), connectionId: DomainId = ACCESS_CONNECTION): LocalEmployeePrincipal? {
        val credential = OpaqueCredential(bytes)
        return try { factory.authenticateLocal(LocalGatewayCredentials(connectionId, credential)) }
        finally { credential.destroy() }
    }

    suspend fun revoke(onWriter: () -> Unit = {}) {
        base.protectedStore.transaction { transaction ->
            onWriter()
            val old = assertNotNull(transaction.readAccount(TEST_SCOPE, TEST_ACTOR))
            val revoked = assertIs<SessionTransition.Applied>(LocalSessions.revoke(old, testId(9110)))
            assertEquals(setOf(testId(9110)), revoked.revokedSessionIds)
            assertEquals(old.revision.next(), revoked.state.revision)
            assertEquals(old.generation.next(), revoked.state.generation)
            assertTrue(transaction.replaceAccount(old.revision, revoked.state))
        }
    }

    fun close() = base.close()
}

internal val ACCESS_CONNECTION: DomainId = testId(9950)

/** Independent JDK UUID/ByteBuffer encoder, never calls the production parser. */
internal fun localAccessWire(sessionId: DomainId = testId(9110), secret: ByteArray = ByteArray(32) { (it + 83).toByte() }): ByteArray {
    val uuid = UUID.fromString(sessionId.value)
    return ByteBuffer.allocate(20 + secret.size).put(byteArrayOf(0x55, 0x4c, 0x41, 0x31))
        .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).put(secret).array()
}

internal class TestLocalAccessPeers : RoomLocalAccessPeerSource {
    val captures = AtomicInteger()
    val finalChecks = AtomicInteger()
    @Volatile var available = true
    @Volatile var current = true
    @Volatile var returnNullAfterCapture = false
    @Volatile var lifetime: Any = Any()
    var connectionId = ACCESS_CONNECTION
    var scope = TEST_SCOPE
    var deviceId = TEST_AUTHORITY.deviceId
    var key: String? = "synthetic-public-key-id"
    var fingerprint: String? = "synthetic-fingerprint"
    var afterCapture: (suspend () -> Unit)? = null
    var onCurrent: (() -> Unit)? = null

    override suspend fun capture(connectionId: DomainId): RoomLocalAccessPeerBinding? {
        captures.incrementAndGet()
        if (!available) return null
        val capturedLifetime = lifetime
        val result = RoomLocalAccessPeerBinding(this.connectionId, scope, deviceId, key, fingerprint) {
            finalChecks.incrementAndGet()
            onCurrent?.invoke()
            current && lifetime === capturedLifetime
        }
        afterCapture?.invoke()
        return if (returnNullAfterCapture) null else result
    }
}

internal suspend fun withAccessFixture(capacity: Int = 2, block: suspend (LocalAccessVerifierFixture) -> Unit) {
    val fixture = LocalAccessVerifierFixture(capacity = capacity)
    try { fixture.base.seed(); block(fixture) } finally { fixture.close() }
}

/** Synthetic driver observation/faults on REAL SQL execution and real outer-loan cleanup. */
internal class AccessBoundaryDriver : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    val statements = CopyOnWriteArrayList<String>()
    @Volatile var recording = false
    @Volatile var afterCommit: (() -> Unit)? = null
    val commitHookArmed = AtomicBoolean(false)
    val commitsObserved = AtomicInteger()
    val cleanupArmed = AtomicBoolean(false)
    val cleanupFailures = AtomicInteger()
    val cleanupReachedRecycle = AtomicBoolean(false)
    @Volatile var cleanupThrowable: Throwable = IllegalStateException("synthetic private cleanup detail")
    val cleanupWasOutsideTransaction = AtomicBoolean(false)

    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool
    override fun open(fileName: String): SQLiteConnection = instrument(delegate.open(fileName))

    private fun instrument(connection: SQLiteConnection): SQLiteConnection = object : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement {
            val statement = connection.prepare(sql)
            return object : SQLiteStatement by statement {
                override fun step(): Boolean {
                    if (recording) statements.add(sql)
                    val result = statement.step()
                    if (sql == "END TRANSACTION" && commitHookArmed.compareAndSet(true, false)) {
                        check(!connection.inTransaction())
                        commitsObserved.incrementAndGet()
                        afterCommit?.invoke()
                    }
                    return result
                }
            }
        }

        override fun inTransaction(): Boolean {
            if (cleanupArmed.compareAndSet(true, false)) {
                cleanupWasOutsideTransaction.set(!connection.inTransaction())
                cleanupReachedRecycle.set(Throwable().stackTrace.any { "markRecycled" in it.methodName })
                cleanupFailures.incrementAndGet()
                throw cleanupThrowable
            }
            return connection.inTransaction()
        }
    }
}
