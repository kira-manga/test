package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Fixed storage profile, not evidence that an OS key or service context has been qualified. */
public object ProtectedLocalStorageFormat {
    public const val FORMAT_VERSION: Int = 1
    public const val ALGORITHM: String = "AES-256-GCM"
    public const val NONCE_BYTES: Int = 12
    public const val TAG_BYTES: Int = 16
    public const val MAX_ADMISSION_DESCRIPTOR_BYTES: Int = 512
    public const val MAX_DEVICE_CAPABILITIES: Int = 64
    public const val MAX_DEVICE_CAPABILITY_BYTES: Int = 64
}

/**
 * Logical table identities have a fixed one-to-one mapping to the five local SQL tables.
 * These are NOT command payload budgets. In particular, retained history of 256 sessions can
 * exceed the command kernel's 64 KiB limit. Both record counts and encoded byte limits apply;
 * a codec must reject excess rather than truncate history, targets or grants.
 */
public enum class ProtectedLocalTable(
    public val wireName: String,
    public val maximumPlaintextBytes: Int,
) {
    ACCOUNT("account", 256 * 1024),
    EFFECTIVE_POLICY("effective_policy", 1024 * 1024),
    ADMISSION("admission", 16 * 1024),
    THROTTLE("throttle", 4 * 1024),
    SESSION_ISSUANCE("local_session_issuance", 4 * 1024),
    AUTH_CONFIGURATION("local_auth_configuration", 4 * 1024),
    REFRESH_ISSUANCE("local_refresh_issuance", 4 * 1024),
    AUTH_EVENT("local_auth_event", 4 * 1024),
    SESSION_ARCHIVE("local_session_archive", 4 * 1024),
    ACTION_APPROVAL("local_action_approval", 16 * 1024),
    ;

    public val schemaVersion: Int get() = 1
}

/** Typed lookup coordinates, not authority to read, provision or activate any identity. */
public sealed interface ProtectedLocalRecordKey {
    public val scope: BranchScope
    public val table: ProtectedLocalTable
    public val recordId: DomainId

    public data class Account(
        override val scope: BranchScope,
        public val actorId: DomainId,
    ) : ProtectedLocalRecordKey {
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.ACCOUNT
        override val recordId: DomainId get() = actorId
    }

    public data class EffectivePolicy(
        override val scope: BranchScope,
        public val actorId: DomainId,
    ) : ProtectedLocalRecordKey {
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.EFFECTIVE_POLICY
        override val recordId: DomainId get() = actorId
    }

    public data class Admission(
        override val scope: BranchScope,
        public val deviceId: DomainId,
    ) : ProtectedLocalRecordKey {
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.ADMISSION
        override val recordId: DomainId get() = deviceId
    }

    public data class Throttle(public val key: ThrottleKey) : ProtectedLocalRecordKey {
        override val scope: BranchScope get() = key.scope
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.THROTTLE
        override val recordId: DomainId get() = key.actorId
        public val dimensionKey: String get() = when (val dimension = key.dimension) {
            ThrottleDimension.Account -> "account"
            is ThrottleDimension.Device -> "device:${dimension.deviceId.value}"
        }
    }

    /** One immutable access issuance per branch/session identity; never a bearer selector proof. */
    public data class SessionIssuance(
        override val scope: BranchScope,
        public val sessionId: DomainId,
    ) : ProtectedLocalRecordKey {
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.SESSION_ISSUANCE
        override val recordId: DomainId get() = sessionId
    }

    public data class AuthConfiguration(override val scope: BranchScope, public val actorId: DomainId) : ProtectedLocalRecordKey {
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.AUTH_CONFIGURATION
        override val recordId: DomainId get() = actorId
    }

    public data class RefreshIssuance(override val scope: BranchScope, public val sessionId: DomainId) : ProtectedLocalRecordKey {
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.REFRESH_ISSUANCE
        override val recordId: DomainId get() = sessionId
    }

    public data class AuthEvent(override val scope: BranchScope, public val eventId: DomainId) : ProtectedLocalRecordKey {
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.AUTH_EVENT
        override val recordId: DomainId get() = eventId
    }

    public data class SessionArchive(override val scope: BranchScope, public val sessionId: DomainId) : ProtectedLocalRecordKey {
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.SESSION_ARCHIVE
        override val recordId: DomainId get() = sessionId
    }

    public data class ActionApproval(override val scope: BranchScope, public val approvalId: DomainId) : ProtectedLocalRecordKey {
        override val table: ProtectedLocalTable get() = ProtectedLocalTable.ACTION_APPROVAL
        override val recordId: DomainId get() = approvalId
    }
}

/** A non-exporting storage-key selector. It is neither the key nor permission to use it. */
public data class ProtectedStorageKeyReference(public val keyId: DomainId)

/**
 * Expected selected-row identity. The adapter derives [key] from its lookup, never from the
 * envelope alone, and validates actual SQLite metadata types before constructing this value.
 * Revision zero remains valid, as in the existing session, credential and throttle types. The
 * immutable session-issuance binding permits ONLY zero; the four older table profiles are unchanged.
 */
public data class ProtectedLocalBinding(
    public val key: ProtectedLocalRecordKey,
    public val revision: Revision,
) {
    init {
        require((key !is ProtectedLocalRecordKey.SessionIssuance && key !is ProtectedLocalRecordKey.RefreshIssuance &&
            key !is ProtectedLocalRecordKey.AuthEvent && key !is ProtectedLocalRecordKey.SessionArchive) || revision.value == 0L) {
            "Local access issuance revision must remain zero"
        }
        require(key !is ProtectedLocalRecordKey.ActionApproval || revision.value in 0..1) {
            "Local action approval permits only issue and consumption revisions"
        }
    }

    public val table: ProtectedLocalTable get() = key.table
    public val schemaVersion: Int get() = table.schemaVersion

    public fun requirePlaintextSize(size: Int): Unit {
        require(size in 1..table.maximumPlaintextBytes) { "Protected local plaintext exceeds supported bounds" }
    }

    /**
     * Format 1: unsigned 32-bit big-endian field count, then a 32-bit big-endian byte length
     * and UTF-8 bytes for each field. The twelve fields, in order, are domain, envelope format,
     * algorithm, key ID, logical table, payload schema, organization, company, branch, record ID,
     * throttle dimension (empty for other tables), and canonical decimal revision. No JSON,
     * locale, enum ordinal, delimiter escaping, or caller-provided free-form AAD participates.
     */
    public fun aad(reference: ProtectedStorageKeyReference): ByteArray = frameLocalAad(listOf(
        "ukkera.local-storage/aad/v1",
        ProtectedLocalStorageFormat.FORMAT_VERSION.toString(),
        ProtectedLocalStorageFormat.ALGORITHM,
        reference.keyId.value,
        table.wireName,
        schemaVersion.toString(),
        key.scope.organizationId.value,
        key.scope.companyId.value,
        key.scope.branchId.value,
        key.recordId.value,
        (key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey.orEmpty(),
        revision.value.toString(),
    ))
}

private fun frameLocalAad(fields: List<String>): ByteArray {
    // All fields above are bounded constants, canonical UUIDs or bounded numeric strings.
    val encoded = fields.map(String::encodeToByteArray)
    val output = ByteArray(4 + encoded.sumOf { 4 + it.size })
    var offset = 0
    fun writeInt(value: Int) {
        output[offset++] = (value ushr 24).toByte()
        output[offset++] = (value ushr 16).toByte()
        output[offset++] = (value ushr 8).toByte()
        output[offset++] = value.toByte()
    }
    writeInt(encoded.size)
    for (field in encoded) {
        writeInt(field.size)
        field.copyInto(output, offset)
        offset += field.size
    }
    return output
}

/**
 * Inert protected account payload; it issues no session or credential. The SQL row revision is
 * [state].revision. A null credential explicitly means no usable password credential, not a
 * storage/key/decode error. Its independent revision is retained, including valid revision zero.
 * Any credential replacement/removal must advance the appropriate revisions in the adapter.
 *
 * The adapter preserves all historical sessions and compares expected revisions. Ordinary
 * changes and the reducer's terminal jump are distinct: a low account revision may jump to MAX
 * when its generation is exhausted. No generic +1 guard may prevent terminal lockdown, and no
 * same-revision/stale replacement may reactivate it. No initializer or reactivation flow is here.
 */
public class ProtectedLocalAccount(
    state: LocalAccountSessionState,
    public val credentialRevision: Revision,
    public val credential: PasswordHashRecord?,
) {
    private val stateValue: LocalAccountSessionState = copyLocalAccountState(state)

    init {
        require(credential == null || credential.binding == CredentialBinding(
            stateValue.scope, stateValue.actorId, credentialRevision,
        )) { "Protected credential binding does not match account" }
        if (stateValue.revision.value == Long.MAX_VALUE || stateValue.generation.value == Long.MAX_VALUE) {
            require(stateValue.revision.value == Long.MAX_VALUE && stateValue.generation.value == Long.MAX_VALUE &&
                !stateValue.enabled && stateValue.sessions.none { it.status == SessionStatus.ACTIVE }
            ) { "Exhausted account must be terminally disabled" }
        }
    }

    public val state: LocalAccountSessionState get() = copyLocalAccountState(stateValue)
    override fun toString(): String = "ProtectedLocalAccount([REDACTED])"
}

private fun copyLocalAccountState(state: LocalAccountSessionState): LocalAccountSessionState =
    LocalAccountSessionState(state.scope, state.actorId, state.enabled, state.revision, state.generation, state.sessions)

/**
 * Effective per-actor policy data only, not authentication or a release-activation ceremony.
 * Exact-branch and same-organization/company grants retain their original meaningful scope.
 * This type neither narrows nor combines grants, and never interprets their presence as login.
 */
public class ActivatedLocalPolicy(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val revision: Revision,
    grants: List<PermissionGrant>,
    public val sessionPolicy: LocalSessionPolicy,
    public val throttlePolicy: ThrottlePolicy,
    public val approvalPolicy: LocalApprovalPolicy? = null,
) {
    init {
        require(grants.size <= 1024) { "Effective policy exceeds supported bounds" }
    }

    private val grantsValue: List<PermissionGrant> = grants.map(::copyLocalGrant)

    init {
        require(grantsValue.all { grant ->
            when (val grantScope = grant.scope) {
                is BranchScope -> grantScope == scope
                is CompanyScope -> grantScope == scope.company()
            }
        }) { "Effective grant is outside the policy tenant" }
        require(grantsValue.all { it.namedSubjectId == null || it.namedSubjectId == actorId }) {
            "Effective grant names another subject"
        }
    }

    public val grants: List<PermissionGrant> get() = grantsValue.map(::copyLocalGrant)
    override fun toString(): String = "ActivatedLocalPolicy([REDACTED])"
}

private fun copyLocalGrant(grant: PermissionGrant): PermissionGrant = PermissionGrant(
    grant.permission, grant.scope, grant.effect, grant.limits, grant.ownerAccess,
    grant.namedSubjectId, grant.targetIds, grant.delegationCeiling,
)

/**
 * Per-device admission data, not verified transport membership or employee authority. Public
 * identity strings are opaque descriptors: their fingerprint format, pairing evidence and
 * comparison to an authenticated peer still require a separately qualified admission adapter.
 * Capability codes are descriptions, not permission grants or verified support claims.
 */
public class LocalDeviceAdmission(
    public val scope: BranchScope,
    public val deviceId: DomainId,
    public val revision: Revision,
    public val admitted: Boolean,
    public val publicKeyIdentifier: String?,
    public val publicFingerprint: String?,
    capabilities: Set<String>,
) {
    init {
        for (descriptor in listOf(publicKeyIdentifier, publicFingerprint)) {
            require(descriptor == null || (descriptor.length in 1..ProtectedLocalStorageFormat.MAX_ADMISSION_DESCRIPTOR_BYTES &&
                descriptor.all { it.code in 0x21..0x7e })
            ) { "Invalid public device descriptor" }
        }
        require(!admitted || publicKeyIdentifier != null || publicFingerprint != null) {
            "Admitted device requires a public identity descriptor"
        }
        require(capabilities.size <= ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES && capabilities.all { code ->
            code.length in 1..ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES && code.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-"
            }
        }) { "Invalid device capability descriptors" }
    }

    private val capabilitiesValue: Set<String> = capabilities.toSet()
    public val capabilities: Set<String> get() = capabilitiesValue.toSet()
    override fun toString(): String = "LocalDeviceAdmission([REDACTED])"
}

/**
 * Syntactically bounded ciphertext, NOT proof that its tag or metadata has been authenticated.
 * SQL adapters must reject unsupported stored format/schema/algorithm metadata before creating
 * this fixed-profile value. Nonce generation/uniqueness belongs to the qualified AEAD provider;
 * a row revision is not a safe nonce. No exported secret key exists in this envelope.
 */
public class SealedLocalRecord(
    public val binding: ProtectedLocalBinding,
    public val keyReference: ProtectedStorageKeyReference,
    nonce: ByteArray,
    ciphertextAndTag: ByteArray,
) {
    init {
        require(nonce.size == ProtectedLocalStorageFormat.NONCE_BYTES) { "Invalid protected local nonce size" }
        binding.requirePlaintextSize(ciphertextAndTag.size - ProtectedLocalStorageFormat.TAG_BYTES)
    }

    private val nonceValue: ByteArray = nonce.copyOf()
    private val ciphertextValue: ByteArray = ciphertextAndTag.copyOf()
    public val formatVersion: Int get() = ProtectedLocalStorageFormat.FORMAT_VERSION
    public val algorithm: String get() = ProtectedLocalStorageFormat.ALGORITHM
    public val ciphertextAndTagSize: Int get() = ciphertextValue.size
    public fun copyNonce(): ByteArray = nonceValue.copyOf()
    public fun copyCiphertextAndTag(): ByteArray = ciphertextValue.copyOf()
    override fun toString(): String = "SealedLocalRecord([REDACTED])"
}

public sealed interface LocalSealResult {
    public data class Sealed(public val record: SealedLocalRecord) : LocalSealResult
    public data class Unavailable(public val reason: ProtectedKeyFailure) : LocalSealResult
}

public sealed interface LocalOpenResult {
    /** No public serializer; the receiving adapter must additionally enforce its table's budget. */
    public class Opened(plaintext: ByteArray) : LocalOpenResult {
        init {
            require(plaintext.size in 1..ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes) {
                "Protected local plaintext exceeds supported bounds"
            }
        }

        private val value: ByteArray = plaintext.copyOf()
        public val size: Int get() = value.size
        public fun copyPlaintext(): ByteArray = value.copyOf()
        override fun toString(): String = "LocalOpenResult.Opened([REDACTED])"
    }

    public data class Unavailable(public val reason: ProtectedKeyFailure) : LocalOpenResult
}

/**
 * Non-exporting AES-256-GCM data-operation boundary. No key lifecycle, authentication, token or
 * provisioning operation is exposed. Only a qualified supervised service context may succeed.
 * A status flag or synthetic provider is not evidence of that qualification.
 *
 * Seal enforces the binding's plaintext budget and chooses a fresh nonce. Open requires exact
 * sealed.binding == expected and sealed.keyReference == reference before key use; reference must
 * be the caller's configured permitted key, not an arbitrary selector trusted from storage.
 * Authenticate expected.aad(reference), the full tag and ciphertext before returning any bytes.
 * Both operations enforce this fixed format and per-table size limits. Adapters independently
 * validate returned metadata/lengths and decoded payload identity against their expected lookup.
 *
 * Preserve CancellationException; sanitize other key/provider/tag failures with the existing
 * failure enum, without logging private data or provider transcripts. A failure never deletes
 * records, initializes empty state, retries arbitrary keys or uses plaintext/adjacent-key fallback.
 * Authenticated encryption does not prove freshness against database rollback or deleted rows.
 */
public interface DeviceProtectedLocalStorage {
    public val protectionStatus: KeyProtectionStatus
    public suspend fun seal(
        reference: ProtectedStorageKeyReference,
        binding: ProtectedLocalBinding,
        plaintext: ByteArray,
    ): LocalSealResult

    public suspend fun open(
        reference: ProtectedStorageKeyReference,
        expected: ProtectedLocalBinding,
        sealed: SealedLocalRecord,
    ): LocalOpenResult
}

/** No in-memory/file-key or accepting production fallback. Actual AEAD providers remain gated. */
public object UnavailableDeviceProtectedLocalStorage : DeviceProtectedLocalStorage {
    override val protectionStatus: KeyProtectionStatus = KeyProtectionStatus.UNSUPPORTED
    override suspend fun seal(
        reference: ProtectedStorageKeyReference,
        binding: ProtectedLocalBinding,
        plaintext: ByteArray,
    ): LocalSealResult = LocalSealResult.Unavailable(ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED)

    override suspend fun open(
        reference: ProtectedStorageKeyReference,
        expected: ProtectedLocalBinding,
        sealed: SealedLocalRecord,
    ): LocalOpenResult = LocalOpenResult.Unavailable(ProtectedKeyFailure.CONTEXT_NOT_QUALIFIED)
}
