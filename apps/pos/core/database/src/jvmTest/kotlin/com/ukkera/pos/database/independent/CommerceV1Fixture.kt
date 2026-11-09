package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.sales.store.LocalCashHandler
import com.ukkera.pos.sales.store.LocalCashReadHandler
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

internal class CommerceV1WiringBlocked(val code: String) : RuntimeException(code)
internal class CommerceV1RetirementRequired : RuntimeException("commerce.process.retirement_required")

internal fun JsonObject.commerceText(name: String): String = getValue(name).jsonPrimitive.content

/**
 * Real Room/admin/auth/cash composition with explicitly synthetic initial trust.
 * No business, credential, session or grant row is inserted directly. The only
 * bootstrap row is the lead-approved inert, initially provisioned BranchRow.
 * Original neutral JCA/key/peer plumbing is owned by /root/backend. The optional
 * capsule key-port adaptation below is commerce-executor-owned, not backend approval.
 */
internal class CommerceV1Fixture(
    val binding: JsonObject,
    val metadata: JsonObject,
    val databasePath: Path,
    val observations: CommerceV1Observations,
    private val incarnation: CommerceV2Incarnation? = null,
) {
    private val aliases = binding.getValue("aliases").jsonObject
    private val operations = binding.getValue("auxiliary_operations").jsonObject
    fun id(alias: String): DomainId = DomainId(aliases.commerceText(alias))
    fun operation(label: String): OperationId = OperationId(operations.commerceText(label))
    val scope: BranchScope = BranchScope(id("O1"), id("C1"), id("C1-B1"))
    private val authority = AuthorityIdentity(id("SYN-ASSIGNMENT"), id("SYN-MASTER-DEVICE"), Revision(0))
    private val active = AtomicBoolean(true)
    private val storageReference = ProtectedStorageKeyReference(id("SYN-STORAGE-KEY"))
    private val pepperReference = ProtectedPepperReference(id("SYN-PEPPER-KEY"))
    private val originalKeys = if (incarnation == null) AuthV1SyntheticKeyPorts(storageReference, pepperReference) else null
    private val capsuleKeys = incarnation?.capsule?.let { CommerceV2SyntheticKeyPorts(storageReference, pepperReference, it) }
    private val storageKeys: DeviceProtectedLocalStorage = capsuleKeys ?: requireNotNull(originalKeys)
    private val pepperKeys: DeviceProtectedPepper = capsuleKeys ?: requireNotNull(originalKeys)
    private val keyLifetime: AutoCloseable = capsuleKeys ?: requireNotNull(originalKeys)
    private val connectionId = incarnation?.connectionId ?: id("SYN-CONNECTION")
    private val environment = AuthV1SyntheticEnvironment(incarnation?.issuerId ?: id("SYN-ISSUER"),
        MonotonicMoment(incarnation?.bootId ?: id("SYN-BOOT"), metadata.commerceText("authentication_elapsed_millis").toLong()))
    private val peers = AuthV1SyntheticPeers()
    private val provisioning = AuthV1SyntheticProvisioning(scope, buildMap {
        put(DomainId(operations.commerceText("admit-device")), LocalProvisioningAction.ADMIT_DEVICE to id("SYN-MASTER-DEVICE"))
        for (actor in listOf("M", "A", "B")) {
            put(DomainId(operations.commerceText("provision-$actor")), LocalProvisioningAction.PROVISION_ACCOUNT to id(actor))
        }
    })
    private val sessions = mutableMapOf<String, LocalSessionTokens>()
    lateinit var service: JvmLocalMasterService
        private set

    fun credentials(actor: String): LocalGatewayCredentials =
        LocalGatewayCredentials(connectionId, sessions.getValue(actor).access)

    fun credentialsAt(actor: String, connectionAlias: String): LocalGatewayCredentials =
        LocalGatewayCredentials(id(connectionAlias), sessions.getValue(actor).access)

    fun unissuedCredentials(admittedConnection: Boolean, bearer: OpaqueCredential): LocalGatewayCredentials =
        LocalGatewayCredentials(if (admittedConnection) connectionId else id("SYN-UNADMITTED-CONNECTION"), bearer)

    suspend fun open() {
        if (incarnation?.reopenAfterReap != true) {
            check(!Files.exists(databasePath)) { "commerce.fixture.requires_new_database" }
            Files.createDirectories(databasePath.parent)
            val database = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(databasePath.toString(), MasterRoomConstructor::initialize))
            try {
                database.useWriterConnection { connection ->
                    requireDurableWriter(connection)
                    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        database.facts().insertBranch(BranchRow(scope.storageKey(), authority.assignmentId.value,
                            authority.deviceId.value, authority.epoch.value, 0, 0, "0".repeat(64), true,
                            Json.encodeToString(RecoveryAdmissionState.serializer(), RecoveryAdmissionState.initiallyProvisioned(scope.branchId))))
                    }
                }
            } finally { database.close() }
            observations.event("trust_only_bootstrap", buildJsonObject {
                put("direct_insert", "one inert BranchRow only")
                put("business_or_security_seeded", false)
                put("synthetic_custody_history_proof", true)
            })
        } else {
            check(Files.isRegularFile(databasePath) && !Files.isSymbolicLink(databasePath)) { "commerce.fixture.restart_database_missing" }
            observations.event("same_database_new_process_service", buildJsonObject {
                put("bootstrap_repeated", false)
                put("issuer_id", incarnation.issuerId.value); put("boot_id", incarnation.bootId.value)
                put("session_route", "fresh real sign-in, never old bearer reuse")
            })
        }
        peers.install(connectionId, scope, id("SYN-MASTER-DEVICE"), "SYNTHETIC-COMMERCE-SAME-DEVICE")
        if (incarnation != null) {
            for ((relation, foreignScope) in listOf(
                "BRANCH" to BranchScope(scope.organizationId, scope.companyId, id("FOREIGN-BRANCH")),
                "COMPANY" to BranchScope(scope.organizationId, id("FOREIGN-COMPANY"), id("FOREIGN-BRANCH")),
                "ORGANIZATION" to BranchScope(id("FOREIGN-ORGANIZATION"), id("FOREIGN-COMPANY"), id("FOREIGN-BRANCH")),
            )) {
                peers.install(id("SYN-FOREIGN-$relation-CONNECTION"), foreignScope, id("SYN-MASTER-DEVICE"),
                    "SYNTHETIC-COMMERCE-SAME-DEVICE")
            }
        }
        service = openLocalMasterService(
            path = databasePath,
            scope = scope,
            authority = authority,
            handlers = listOf(LocalCashHandler()),
            readHandlers = listOf(LocalCashReadHandler()),
            security = LocalMasterSecurityDependencies(storageKeys, storageReference, JvmLocalPasswordHasher(pepperKeys),
                pepperReference, JvmLocalAuthenticationEntropy(), environment, peers, 1, provisioning),
            runtime = object : MasterRuntimeVerifier {
                override suspend fun writer(observation: BranchObservation): RuntimeVerification =
                    if (active.get() && observation.scope == scope && observation.authority == authority)
                        RuntimeVerification.VERIFIED else RuntimeVerification.REFUSED
                override suspend fun absentOperation(observation: BranchObservation, identity: OperationIdentity): RuntimeVerification =
                    if (identity.branchId == scope.branchId) writer(observation) else RuntimeVerification.REFUSED
            },
            processRetirement = LocalMasterRetirement { reason ->
                active.set(false)
                observations.event("production_retirement_requested", buildJsonObject { put("reason", reason.name) })
            },
            clock = MasterClock { UtcInstant.parseCanonical(metadata.commerceText("master_clock_utc")) },
            workers = 1,
            maximumQueuedRequests = 8,
        )
        val admission = LocalDeviceAdmission(scope, id("SYN-MASTER-DEVICE"), Revision(0), true,
            "SYNTHETIC-COMMERCE-SAME-DEVICE", null, setOf("SYNTHETIC_LOCAL_QUALIFICATION"))
        if (incarnation?.reopenAfterReap != true) {
            administration("admit-device", service.endpoint.admitDevice(DomainId(operations.commerceText("admit-device")), admission))
        }
        val random = SecureRandom()
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%_-"
        for (actor in listOf("M", "A", "B")) {
            val password = incarnation?.capsule?.password(actor) ?: CharArray(40) { alphabet[random.nextInt(alphabet.length)] }
            try {
                val definition = LocalAccountProvisioning(id(actor), LocalLoginIdentifier("SYN-COMMERCE-$actor"),
                    policy(actor), LocalTokenPolicy(metadata.commerceText("token_access_lifetime_millis").toLong(),
                        metadata.commerceText("token_work_lifetime_millis").toLong()), setOf(id("SYN-MASTER-DEVICE")))
                if (incarnation?.reopenAfterReap != true) {
                    administration("provision-$actor", service.endpoint.provisionAccount(
                        DomainId(operations.commerceText("provision-$actor")), definition, password))
                }
                when (val login = service.endpoint.signIn(connectionId, definition.login, password)) {
                    is LocalAuthenticationResult.Authenticated -> {
                        sessions[actor] = login.tokens
                        observations.event("real_sign_in", buildJsonObject {
                            put("actor_alias", actor); put("result", "AUTHENTICATED")
                            if (incarnation != null) {
                                put("issuer_id", incarnation.issuerId.value); put("boot_id", incarnation.bootId.value)
                                put("session_id", login.tokens.view.sessionId.value)
                                put("after_actual_process_reap", incarnation.reopenAfterReap)
                            }
                        })
                    }
                    else -> {
                        observations.event("real_sign_in", buildJsonObject {
                            put("actor_alias", actor)
                            put("result", if (login is LocalAuthenticationResult.Denied) login.reason.name else "UNAVAILABLE")
                        })
                        throw CommerceV1WiringBlocked("commerce.fixture.sign_in.$actor")
                    }
                }
            } finally { password.fill('\u0000') }
        }
        provisioning.close()
        incarnation?.capsule?.close()
    }

    private fun administration(label: String, result: LocalCredentialAdministrationResult) {
        observations.event("real_credential_administration", buildJsonObject {
            put("label", label)
            put("result", when (result) {
                is LocalCredentialAdministrationResult.Applied -> "APPLIED"
                LocalCredentialAdministrationResult.Denied -> "DENIED"
                LocalCredentialAdministrationResult.Unavailable -> "UNAVAILABLE"
                LocalCredentialAdministrationResult.StorageUncertain -> "STORAGE_UNCERTAIN"
                LocalCredentialAdministrationResult.Busy -> "BUSY"
            })
        })
        if (result !is LocalCredentialAdministrationResult.Applied) throw CommerceV1WiringBlocked("commerce.fixture.$label")
    }

    private fun policy(actor: String): ActivatedLocalPolicy {
        val grants = if (actor == "M") listOf(
            PermissionGrant(Permission(SecurityAction.CASH_CONFIGURE, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
                namedSubjectId = id(actor), targetIds = setOf(id("DA"), id("DB"), id("A"), id("B"))),
        ) else listOf(
            PermissionGrant(Permission(SecurityAction.CASH_OPEN, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
                PermissionLimits(maximumAmount = Money(Currency.EGP,
                    binding.getValue("setup").jsonObject.commerceText("opening_grant_maximum_minor").toLong())), namedSubjectId = id(actor)),
            PermissionGrant(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
                namedSubjectId = id(actor)),
        )
        val currentGrants = if (incarnation?.denyOpeningActor == actor) grants + PermissionGrant(
            Permission(SecurityAction.CASH_OPEN, PermissionVerb.PERFORM), scope, GrantEffect.DENY, namedSubjectId = id(actor),
        ) else grants
        return ActivatedLocalPolicy(scope, id(actor), Revision(0), currentGrants, LocalSessionPolicy(1), ThrottlePolicy())
    }

    suspend fun close() {
        if (::service.isInitialized) {
            val result = service.quiesceAndClose()
            observations.event("production_close", buildJsonObject { put("result", result.name) })
            if (result != LocalMasterCloseResult.CLOSED) throw CommerceV1RetirementRequired()
        }
        active.set(false)
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        provisioning.close(); peers.close(); environment.close(); keyLifetime.close(); incarnation?.capsule?.close()
    }
}

/** Metadata is public fixture wiring. Only the separately owned capsule contains secrets. */
internal class CommerceV2Incarnation(
    val issuerId: DomainId,
    val bootId: DomainId,
    val connectionId: DomainId,
    val reopenAfterReap: Boolean,
    val denyOpeningActor: String?,
    val capsule: CommerceV2Capsule,
)

/**
 * Read-only import from a supervisor-owned sealed memfd. Never serialized, logged or
 * placed in a packet. The supervisor retains this fixture's bytes across real JVM
 * death ONLY; it owns no Room handle or business service. NOT an OS key-store claim.
 */
internal class CommerceV2Capsule private constructor(private val bytes: ByteArray) : AutoCloseable {
    fun storageKey(): ByteArray = bytes.copyOfRange(8, 40)
    fun pepperKey(): ByteArray = bytes.copyOfRange(40, 72)
    fun password(actor: String): CharArray {
        val index = listOf("M", "A", "B").indexOf(actor)
        require(index >= 0)
        return CharArray(40) { bytes[72 + index * 40 + it].toInt().toChar() }
    }
    override fun close(): Unit = bytes.fill(0)
    override fun toString(): String = "CommerceV2Capsule([REDACTED])"

    companion object {
        fun read(fd: Int): CommerceV2Capsule {
            require(fd >= 3) { "commerce.capsule.fd" }
            val bytes = ByteArray(192)
            try {
                FileChannel.open(Path.of("/proc/self/fd/$fd"), StandardOpenOption.READ).use { channel ->
                    check(channel.size() == bytes.size.toLong()) { "commerce.capsule.size" }
                    channel.position(0)
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "commerce.capsule.incomplete" }
                }
                check(bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(67, 79, 77, 67, 65, 80, 50, 0))) { "commerce.capsule.format" }
                check((72 until bytes.size).all { bytes[it].toInt() in 33..126 }) { "commerce.capsule.password_format" }
                return CommerceV2Capsule(bytes)
            } catch (error: Throwable) {
                bytes.fill(0)
                throw error
            }
        }
    }
}

/** Neutral AES-GCM/HMAC fixture plumbing adapted from backend's unchanged AuthV1 helper. */
private class CommerceV2SyntheticKeyPorts(
    private val storageReference: ProtectedStorageKeyReference,
    private val pepperReference: ProtectedPepperReference,
    capsule: CommerceV2Capsule,
) : DeviceProtectedLocalStorage, DeviceProtectedPepper, AutoCloseable {
    private val storageKey = capsule.storageKey()
    private val pepperKey = capsule.pepperKey()
    private val random = SecureRandom() // Fresh nonces; NEVER a seeded/replayed random stream across restart.
    private val active = AtomicBoolean(true)
    override val protectionStatus: KeyProtectionStatus
        get() = if (active.get()) KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT else KeyProtectionStatus.UNQUALIFIED_CONTEXT

    override suspend fun seal(reference: ProtectedStorageKeyReference, binding: ProtectedLocalBinding, plaintext: ByteArray): LocalSealResult {
        if (!active.get() || reference != storageReference) return LocalSealResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        binding.requirePlaintextSize(plaintext.size)
        val nonce = ByteArray(ProtectedLocalStorageFormat.NONCE_BYTES).also(random::nextBytes)
        val aad = binding.aad(reference)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(storageKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val ciphertext = cipher.doFinal(plaintext)
            try { LocalSealResult.Sealed(SealedLocalRecord(binding, reference, nonce, ciphertext)) }
            finally { ciphertext.fill(0) }
        } catch (_: GeneralSecurityException) {
            LocalSealResult.Unavailable(ProtectedKeyFailure.PROVIDER_FAILURE)
        } finally { nonce.fill(0); aad.fill(0) }
    }

    override suspend fun open(reference: ProtectedStorageKeyReference, expected: ProtectedLocalBinding, sealed: SealedLocalRecord): LocalOpenResult {
        if (!active.get() || reference != storageReference) return LocalOpenResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        if (sealed.binding != expected || sealed.keyReference != reference) return LocalOpenResult.Unavailable(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        val nonce = sealed.copyNonce()
        val ciphertext = sealed.copyCiphertextAndTag()
        val aad = expected.aad(reference)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(storageKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(ciphertext)
            try { expected.requirePlaintextSize(plaintext.size); LocalOpenResult.Opened(plaintext) }
            finally { plaintext.fill(0) }
        } catch (_: GeneralSecurityException) {
            LocalOpenResult.Unavailable(ProtectedKeyFailure.CORRUPT_PROTECTED_STATE)
        } finally { nonce.fill(0); ciphertext.fill(0); aad.fill(0) }
    }

    override suspend fun hmacSha256(reference: ProtectedPepperReference, message: ByteArray): PepperMacResult {
        if (!active.get() || reference != pepperReference) return PepperMacResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pepperKey, "HmacSHA256"))
            val tag = mac.doFinal(message)
            try { PepperMacResult.Authenticated(tag) } finally { tag.fill(0) }
        } catch (_: GeneralSecurityException) {
            PepperMacResult.Unavailable(ProtectedKeyFailure.PROVIDER_FAILURE)
        }
    }

    override fun close() { active.set(false); storageKey.fill(0); pepperKey.fill(0) }
    override fun toString(): String = "CommerceV2SyntheticKeyPorts(SYNTHETIC,[REDACTED])"
}
