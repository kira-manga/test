package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json

/**
 * Coroutines1.11 can clone ordinary CancellationException for stack recovery across
 * Room's withContext. This TEST-only sentinel explicitly opts out, so exact-reference
 * propagation assertions still detect application replacement without disabling
 * recovery globally or weakening the independent rollback checks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LocalStorageCancellationSentinel(message: String) :
    CancellationException(message), CopyableThrowable<LocalStorageCancellationSentinel> {
    val identityMarker: Any = Any()
    override fun createCopy(): LocalStorageCancellationSentinel? = null
}

/**
 * Actual AES-GCM with EXPLICIT SYNTHETIC TEST KEYS. The status flag is a test seam,
 * not OS custody qualification. No application composition root installs this provider.
 */
internal class TestLocalStorageAead(
    private val permittedReference: ProtectedStorageKeyReference,
    keyMaterial: ByteArray = ByteArray(32) { (it + 71).toByte() },
) : DeviceProtectedLocalStorage {
    private val key = SecretKeySpec(keyMaterial.copyOf(), "AES")
    private val random = SecureRandom()
    var status = KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT
    var failure: ProtectedKeyFailure? = null
    var throwOnOpen: Throwable? = null
    var throwOnSeal: Throwable? = null
    var afterOpen: (suspend () -> Unit)? = null
    var onProtectionStatusRead: (() -> Unit)? = null
    var openCalls = 0
    var sealCalls = 0
    override val protectionStatus: KeyProtectionStatus get() {
        onProtectionStatusRead?.invoke()
        return status
    }

    override suspend fun seal(reference: ProtectedStorageKeyReference, binding: ProtectedLocalBinding, plaintext: ByteArray): LocalSealResult {
        sealCalls++
        throwOnSeal?.let { throw it }
        failure?.let { return LocalSealResult.Unavailable(it) }
        if (status != KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT || reference != permittedReference) {
            return LocalSealResult.Unavailable(ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED)
        }
        binding.requirePlaintextSize(plaintext.size)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(binding.aad(reference))
        return LocalSealResult.Sealed(SealedLocalRecord(binding, reference, nonce, cipher.doFinal(plaintext)))
    }

    override suspend fun open(reference: ProtectedStorageKeyReference, expected: ProtectedLocalBinding, sealed: SealedLocalRecord): LocalOpenResult {
        openCalls++
        throwOnOpen?.let { throw it }
        failure?.let { return LocalOpenResult.Unavailable(it) }
        if (status != KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT || reference != permittedReference) {
            return LocalOpenResult.Unavailable(ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED)
        }
        if (sealed.keyReference != reference || sealed.binding != expected) {
            return LocalOpenResult.Unavailable(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        }
        var plaintext: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyNonce()))
            cipher.updateAAD(expected.aad(reference))
            plaintext = cipher.doFinal(sealed.copyCiphertextAndTag())
            expected.requirePlaintextSize(plaintext.size)
            val opened = LocalOpenResult.Opened(plaintext)
            afterOpen?.invoke()
            opened
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LocalOpenResult.Unavailable(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        } finally {
            plaintext?.fill(0)
        }
    }
}

/** All initialization/reset/corruption helpers in this class are TEST ONLY. */
internal class LocalSecurityStoreFixture(
    val path: Path = Files.createTempDirectory("ukkera-local-security-test-").resolve("master.db"),
    driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    val scope = TEST_SCOPE
    val actorId = TEST_ACTOR
    val deviceId = TEST_AUTHORITY.deviceId
    val bootId = testId(9101)
    val keyReference = ProtectedStorageKeyReference(testId(9102))
    val protection = TestLocalStorageAead(keyReference)
    val database = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize), driver)
    val store = RoomLocalSecurityStore(database, protection, keyReference)

    fun defaultAccount(
        revision: Long = 4,
        generation: Long = 5,
        enabled: Boolean = true,
        credentialRevision: Long = 2,
        sessions: List<LocalSessionRecord> = listOf(LocalSessionRecord(
            testId(9110), scope, actorId, deviceId, SessionGeneration(generation), SessionStatus.ACTIVE,
            AuthenticationPurpose.INTERACTIVE,
        )),
        hasCredential: Boolean = true,
    ): ProtectedLocalAccount = ProtectedLocalAccount(
        LocalAccountSessionState(scope, actorId, enabled, Revision(revision), SessionGeneration(generation), sessions),
        Revision(credentialRevision),
        if (!hasCredential) null else PasswordHashRecord(
            CredentialBinding(scope, actorId, Revision(credentialRevision)), PasswordHashParameters(),
            ProtectedPepperReference(testId(9111)), ByteArray(16) { (it + 33).toByte() }, ByteArray(32) { (it + 101).toByte() },
        ),
    )

    fun defaultPolicy(revision: Long = 3): ActivatedLocalPolicy = ActivatedLocalPolicy(
        scope, actorId, Revision(revision), listOf(PermissionGrant(
            Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
        )), LocalSessionPolicy(), ThrottlePolicy(),
    )

    fun defaultAdmission(revision: Long = 6, admitted: Boolean = true): LocalDeviceAdmission = LocalDeviceAdmission(
        scope, deviceId, Revision(revision), admitted, "synthetic-public-key-id", "synthetic-fingerprint",
        setOf("test.local-storage"),
    )

    fun defaultThrottle(dimension: ThrottleDimension = ThrottleDimension.Account, revision: Long = 0, failures: Int = 0): PersistentThrottleState =
        PersistentThrottleState(ThrottleKey(scope, actorId, dimension), Revision(revision), failures,
            MonotonicMoment(bootId, 1000), 0, null)

    suspend fun seed(
        account: ProtectedLocalAccount? = defaultAccount(),
        policy: ActivatedLocalPolicy? = defaultPolicy(),
        admission: LocalDeviceAdmission? = defaultAdmission(),
        accountThrottle: PersistentThrottleState? = defaultThrottle(),
        deviceThrottle: PersistentThrottleState? = defaultThrottle(ThrottleDimension.Device(deviceId)),
    ) {
        val accountRecord = account?.let { seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Account(scope, actorId), it.state.revision),
            ProtectedLocalSecurityCodec.encodeAccount(it)) }
        val policyRecord = policy?.let { seal(ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(scope, actorId), it.revision),
            ProtectedLocalSecurityCodec.encodePolicy(it)) }
        val admissionRecord = admission?.let { seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(scope, deviceId), it.revision),
            ProtectedLocalSecurityCodec.encodeAdmission(it)) }
        val accountBucket = accountThrottle?.let { seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Throttle(it.key), it.revision),
            ProtectedLocalSecurityCodec.encodeThrottle(it)) }
        val deviceBucket = deviceThrottle?.let { seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Throttle(it.key), it.revision),
            ProtectedLocalSecurityCodec.encodeThrottle(it)) }
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                database.facts().insertBranch(BranchRow(scope.storageKey(), TEST_AUTHORITY.assignmentId.value,
                    deviceId.value, 1, 0, 0, "0".repeat(64), true,
                    Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(scope.branchId))))
                val dao = database.localSecurity()
                if (accountRecord != null) dao.insertAccount(ProtectedLocalAccountRow(scope.storageKey(), actorId.value, 1, 1, "AES-256-GCM",
                    accountRecord.binding.revision.value, keyReference.keyId.value,
                    accountRecord.copyNonce(), accountRecord.copyCiphertextAndTag()))
                if (policyRecord != null) dao.insertPolicy(ProtectedEffectivePolicyRow(scope.storageKey(), actorId.value, 1, 1, "AES-256-GCM",
                    policyRecord.binding.revision.value, keyReference.keyId.value,
                    policyRecord.copyNonce(), policyRecord.copyCiphertextAndTag()))
                if (admissionRecord != null) dao.insertAdmission(ProtectedDeviceAdmissionRow(scope.storageKey(), deviceId.value, 1, 1, "AES-256-GCM",
                    admissionRecord.binding.revision.value, keyReference.keyId.value,
                    admissionRecord.copyNonce(), admissionRecord.copyCiphertextAndTag()))
                for (record in listOfNotNull(accountBucket, deviceBucket)) {
                    val key = record.binding.key as ProtectedLocalRecordKey.Throttle
                    dao.insertThrottle(ProtectedLoginThrottleRow(scope.storageKey(), actorId.value, key.dimensionKey,
                        1, 1, "AES-256-GCM", record.binding.revision.value, keyReference.keyId.value,
                        record.copyNonce(), record.copyCiphertextAndTag()))
                }
            }
        }
    }

    suspend fun seal(binding: ProtectedLocalBinding, plaintext: ByteArray): SealedLocalRecord = try {
        assertIs<LocalSealResult.Sealed>(protection.seal(keyReference, binding, plaintext)).record
    } finally { plaintext.fill(0) }

    suspend fun replaceAccountFixture(expectedRevision: Long, value: ProtectedLocalAccount) = replaceFixture(
        expectedRevision, ProtectedLocalBinding(ProtectedLocalRecordKey.Account(value.state.scope, value.state.actorId), value.state.revision),
        ProtectedLocalSecurityCodec.encodeAccount(value),
    )

    suspend fun replacePolicyFixture(expectedRevision: Long, value: ActivatedLocalPolicy) = replaceFixture(
        expectedRevision, ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(value.scope, value.actorId), value.revision),
        ProtectedLocalSecurityCodec.encodePolicy(value),
    )

    suspend fun replaceAdmissionFixture(expectedRevision: Long, value: LocalDeviceAdmission) = replaceFixture(
        expectedRevision, ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(value.scope, value.deviceId), value.revision),
        ProtectedLocalSecurityCodec.encodeAdmission(value),
    )

    suspend fun replaceThrottleFixture(expectedRevision: Long, value: PersistentThrottleState) = replaceFixture(
        expectedRevision, ProtectedLocalBinding(ProtectedLocalRecordKey.Throttle(value.key), value.revision),
        ProtectedLocalSecurityCodec.encodeThrottle(value),
    )

    private suspend fun replaceFixture(expectedRevision: Long, binding: ProtectedLocalBinding, plaintext: ByteArray) {
        val record = seal(binding, plaintext)
        val location = location(binding.key)
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                usePrepared("UPDATE ${location.table} SET schemaVersion=1,formatVersion=1,algorithm='AES-256-GCM',revision=?,keyId=?,nonce=?,ciphertextAndTag=? WHERE ${location.where} AND revision=?") {
                    it.bindLong(1, binding.revision.value); it.bindText(2, keyReference.keyId.value)
                    it.bindBlob(3, record.copyNonce()); it.bindBlob(4, record.copyCiphertextAndTag())
                    location.values.forEachIndexed { index, value -> it.bindText(5 + index, value) }
                    it.bindLong(5 + location.values.size, expectedRevision); it.step()
                }
                assertEquals(1L, usePrepared("SELECT changes()") { check(it.step()); it.getLong(0) })
            }
        }
    }

    /** Matching-key raw metadata/bytes for rollback assertions, never a log/notification. */
    suspend fun rawRecord(key: ProtectedLocalRecordKey): List<String>? {
        val location = location(key)
        return database.useWriterConnection { connection ->
            connection.usePrepared("SELECT typeof(revision)||':'||quote(revision), typeof(schemaVersion)||':'||quote(schemaVersion), " +
                "typeof(formatVersion)||':'||quote(formatVersion), typeof(algorithm)||':'||hex(CAST(algorithm AS BLOB)), " +
                "typeof(keyId)||':'||hex(CAST(keyId AS BLOB)), typeof(nonce)||':'||hex(nonce), " +
                "typeof(ciphertextAndTag)||':'||hex(ciphertextAndTag) FROM ${location.table} WHERE ${location.where}") {
                location.values.forEachIndexed { index, value -> it.bindText(index + 1, value) }
                if (!it.step()) null else (0..6).map(it::getText)
            }
        }
    }

    /** Deliberate corruption of disposable files; restore exact guard DDL in the same transaction. */
    suspend fun corrupt(key: ProtectedLocalRecordKey, sqlSetClause: String) {
        val location = location(key)
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val guards = usePrepared("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND tbl_name=?") {
                    it.bindText(1, location.table)
                    buildList { while (it.step()) add(it.getText(0) to it.getText(1)) }
                }
                try {
                    for ((name, _) in guards) usePrepared("DROP TRIGGER `$name`") { it.step() }
                    usePrepared("UPDATE ${location.table} SET $sqlSetClause WHERE ${location.where}") {
                        location.values.forEachIndexed { index, value -> it.bindText(index + 1, value) }; it.step()
                    }
                } finally {
                    for ((_, ddl) in guards) usePrepared(ddl) { it.step() }
                }
            }
        }
    }

    fun close() = database.close()

    private data class Location(val table: String, val where: String, val values: List<String>)
    private fun location(key: ProtectedLocalRecordKey): Location = when (key) {
        is ProtectedLocalRecordKey.Account -> Location("protected_local_account", "scope=? AND actorId=?", listOf(key.scope.storageKey(), key.actorId.value))
        is ProtectedLocalRecordKey.EffectivePolicy -> Location("protected_effective_policy", "scope=? AND actorId=?", listOf(key.scope.storageKey(), key.actorId.value))
        is ProtectedLocalRecordKey.Admission -> Location("protected_device_admission", "scope=? AND deviceId=?", listOf(key.scope.storageKey(), key.deviceId.value))
        is ProtectedLocalRecordKey.Throttle -> Location("protected_login_throttle", "scope=? AND actorId=? AND dimensionKey=?", listOf(key.scope.storageKey(), key.recordId.value, key.dimensionKey))
        is ProtectedLocalRecordKey.SessionIssuance -> Location("protected_local_access_issuance", "scope=? AND sessionId=?", listOf(key.scope.storageKey(), key.sessionId.value))
        is ProtectedLocalRecordKey.ActionApproval,
        is ProtectedLocalRecordKey.AuthConfiguration,
        is ProtectedLocalRecordKey.AuthEvent,
        is ProtectedLocalRecordKey.RefreshIssuance,
        is ProtectedLocalRecordKey.SessionArchive -> error("Unsupported protected record key in legacy fixture")
    }
}
