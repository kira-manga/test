package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure DB adapter. Security owns ALL A/D7/approval/genesis/R7/ACK canonical bytes. This object
 * owns only the new protected event wrapper and actual existing protected-row size preflight.
 * No codec result is a verifier, approved proposal, SQL submission permit or original receipt.
 * Old schema6/LocalAuthEvent serializers remain unchanged.
 */
internal object LocalBranchInstallationCodecV2 {
    private const val ROW_DOMAIN = "branch-installed-row"
    private const val KIND = "BRANCH_INSTALLED"

    fun snapshotDefinition(value: LocalBranchInstallationDefinitionV2): LocalBranchInstallationSnapshotV2 =
        guarded { LocalBranchInstallationWireV2.snapshotDefinition(value) }

    fun encodeDefinition(snapshot: LocalBranchInstallationSnapshotV2): ByteArray =
        LocalBranchInstallationWireV2.encodeDefinition(snapshot)

    fun decodeDefinition(bytes: ByteArray): LocalBranchInstallationSnapshotV2 =
        guarded { checkNotNull(LocalBranchInstallationWireV2.decodeDefinition(bytes)) }

    /**
     * Before an actual native submission-eligible attempt is issued: round-trip complete D7 and
     * each existing protected policy/config/admission codec, enforce THEIR byte budgets, and
     * prove their complete semantic projections are identical. Never strip policy extensions
     * or narrow a grant to fit. Credential/account/throttle checks still occur on actual seeds.
     */
    fun preflight(snapshot: LocalBranchInstallationSnapshotV2): LocalInstallationSeedTemplatesV2 = guarded {
        val captured = LocalBranchInstallationWireV2.snapshotDefinition(snapshot.definition)
        check(captured.definitionDigest == snapshot.definitionDigest &&
            captured.allocationDigest == snapshot.allocationDigest)
        val d = captured.definition; val c = d.allocation.coordinates; val account = d.firstAccount
        val config = LocalAuthConfiguration(c.scope, c.firstActorId, Revision(0), account.login, account.tokenPolicy)
        val policyBinding = ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(c.scope, c.firstActorId), Revision(0))
        val configBinding = ProtectedLocalBinding(ProtectedLocalRecordKey.AuthConfiguration(c.scope, c.firstActorId), Revision(0))
        val admissionBinding = ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(c.scope, c.deviceId), Revision(0))
        val buffers = mutableListOf<ByteArray>()
        try {
            val policy = LocalEffectivePolicyCodec.encodePolicy(account.policy).also { buffers += it }
            val configuration = LocalAuthenticationCodec.encodeConfiguration(config).also { buffers += it }
            val admission = ProtectedLocalSecurityCodec.encodeAdmission(d.masterAdmission).also { buffers += it }
            policyBinding.requirePlaintextSize(policy.size)
            configBinding.requirePlaintextSize(configuration.size)
            admissionBinding.requirePlaintextSize(admission.size)
            val decodedPolicy = LocalEffectivePolicyCodec.decodePolicy(policyBinding, policy)
            val decodedConfiguration = LocalAuthenticationCodec.decodeConfiguration(configBinding, configuration)
            val decodedAdmission = ProtectedLocalSecurityCodec.decodeAdmission(admissionBinding, admission)
            equalAndClear(
                LocalBranchInstallationWireV2.encodePolicy(account.policy),
                LocalBranchInstallationWireV2.encodePolicy(decodedPolicy),
            )
            equalAndClear(
                LocalBranchInstallationWireV2.encodeConfiguration(config),
                LocalBranchInstallationWireV2.encodeConfiguration(decodedConfiguration),
            )
            equalAndClear(
                LocalBranchInstallationWireV2.encodeAdmission(d.masterAdmission),
                LocalBranchInstallationWireV2.encodeAdmission(decodedAdmission),
            )
            LocalInstallationSeedTemplatesV2(captured, policy, configuration, admission)
        } finally { buffers.forEach { it.fill(0) } }
    }

    fun encodeReceipt(receipt: LocalBranchInstallationReceiptV2): ByteArray =
        LocalBranchInstallationWireV2.encodeReceipt(receipt)

    fun decodeReceipt(bytes: ByteArray): LocalBranchInstallationReceiptV2 =
        guarded { checkNotNull(LocalBranchInstallationWireV2.decodeReceipt(bytes)) }

    /** A new explicitly versioned plaintext under unchanged AUTH_EVENT AAD/schema1/revision0. */
    fun encodeEvent(receipt: LocalBranchInstallationReceiptV2): ByteArray = guarded {
        val c = receipt.core.allocation.coordinates
        val writer = InstallationEventWriterV2()
        try {
            writer.text(LocalBranchInstallationWireV2.PROFILE); writer.text(ROW_DOMAIN)
            writer.integer(LocalBranchInstallationWireV2.FORMAT); writer.text(KIND)
            writer.id(c.scope.organizationId); writer.id(c.scope.companyId); writer.id(c.scope.branchId)
            writer.id(c.installationId); writer.long(0)
            val payload = LocalBranchInstallationWireV2.encodeReceipt(receipt)
            try { writer.field(payload) } finally { payload.fill(0) }
            writer.finish()
        } finally { writer.clear() }
    }

    /**
     * Caller must FIRST authenticate the expected original storage key/AAD/envelope. Fixed
     * original selection and complete-history/physical-close verification belong to P3/P4.
     */
    fun decodeEvent(binding: ProtectedLocalBinding, bytes: ByteArray): LocalBranchInstallationReceiptV2 = guarded {
        check(binding.table == ProtectedLocalTable.AUTH_EVENT && binding.schemaVersion == 1 &&
            binding.revision == Revision(0))
        binding.requirePlaintextSize(bytes.size)
        val owned = bytes.copyOf()
        try {
            val reader = InstallationEventReaderV2(owned)
            check(reader.text(32) == LocalBranchInstallationWireV2.PROFILE && reader.text(64) == ROW_DOMAIN &&
                reader.integer() == LocalBranchInstallationWireV2.FORMAT && reader.text(16) == KIND)
            val scope = BranchScope(reader.id(), reader.id(), reader.id())
            val installation = reader.id()
            check(reader.long() == 0L)
            val receiptBytes = reader.field(LocalBranchInstallationWireV2.MAXIMUM_RECEIPT_BYTES)
            val receipt = try { checkNotNull(LocalBranchInstallationWireV2.decodeReceipt(receiptBytes)) }
                finally { receiptBytes.fill(0) }
            reader.end()
            val c = receipt.core.allocation.coordinates
            check(c.scope == scope && c.installationId == installation &&
                binding.key == ProtectedLocalRecordKey.AuthEvent(scope, installation))
            val canonical = encodeEvent(receipt)
            try { check(owned.contentEquals(canonical)) } finally { canonical.fill(0) }
            receipt
        } finally { owned.fill(0) }
    }

    private fun equalAndClear(expected: ByteArray, actual: ByteArray) {
        try { check(expected.contentEquals(actual)) } finally { expected.fill(0); actual.fill(0) }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { protectedLocalUnavailable() }
}

/** Owned non-secret seed templates, not an approval. Clearing does not claim JVM string erasure. */
internal class LocalInstallationSeedTemplatesV2(
    val snapshot: LocalBranchInstallationSnapshotV2,
    policy: ByteArray,
    configuration: ByteArray,
    admission: ByteArray,
) {
    private val policyValue = policy.copyOf()
    private val configurationValue = configuration.copyOf()
    private val admissionValue = admission.copyOf()
    private var cleared = false
    val policyDigest: PayloadDigest = sha256(policyValue)
    val configurationDigest: PayloadDigest = sha256(configurationValue)
    val admissionDigest: PayloadDigest = sha256(admissionValue)

    fun copyPolicy(): ByteArray { check(!cleared); return policyValue.copyOf() }
    fun copyConfiguration(): ByteArray { check(!cleared); return configurationValue.copyOf() }
    fun copyAdmission(): ByteArray { check(!cleared); return admissionValue.copyOf() }
    fun clear() {
        if (!cleared) { cleared = true; policyValue.fill(0); configurationValue.fill(0); admissionValue.fill(0) }
    }
    override fun toString(): String = "LocalInstallationSeedTemplatesV2([REDACTED])"
}

/** Only the small DB auth-event wrapper; never another D7, proof or receipt encoder. */
private class InstallationEventWriterV2 {
    private val bytes = ByteArray(ProtectedLocalTable.AUTH_EVENT.maximumPlaintextBytes)
    private var at = 0
    private fun room(count: Int) { check(count >= 0 && count <= bytes.size - at) }
    fun integer(value: Int) {
        check(value >= 0); room(4)
        for (shift in intArrayOf(24, 16, 8, 0)) bytes[at++] = (value ushr shift).toByte()
    }
    fun long(value: Long) {
        check(value >= 0); room(8)
        for (shift in 56 downTo 0 step 8) bytes[at++] = (value ushr shift).toByte()
    }
    fun field(value: ByteArray) { integer(value.size); room(value.size); value.copyInto(bytes, at); at += value.size }
    fun text(value: String) {
        val encoded = value.encodeToByteArray(throwOnInvalidSequence = true)
        try { field(encoded) } finally { encoded.fill(0) }
    }
    fun id(value: DomainId) { check(INSTALLATION_EVENT_ID_V2.matches(value.value)); text(value.value) }
    fun finish(): ByteArray = bytes.copyOf(at).also { check(it.isNotEmpty()) }
    fun clear() { bytes.fill(0) }
}

private class InstallationEventReaderV2(private val bytes: ByteArray) {
    private var at = 0
    private fun available(count: Int) { check(count >= 0 && count <= bytes.size - at) }
    fun integer(): Int {
        available(4); var result = 0
        repeat(4) { result = (result shl 8) or (bytes[at++].toInt() and 255) }
        check(result >= 0); return result
    }
    fun long(): Long {
        available(8); var result = 0L
        repeat(8) { result = (result shl 8) or (bytes[at++].toLong() and 255L) }
        check(result >= 0); return result
    }
    fun field(maximum: Int): ByteArray {
        val size = integer(); check(size <= maximum); available(size)
        return bytes.copyOfRange(at, at + size).also { at += size }
    }
    fun text(maximum: Int): String {
        val value = field(maximum)
        return try { value.decodeToString(throwOnInvalidSequence = true) } finally { value.fill(0) }
    }
    fun id(): DomainId {
        val value = text(36); check(INSTALLATION_EVENT_ID_V2.matches(value)); return DomainId(value)
    }
    fun end() { check(at == bytes.size) }
}

private val INSTALLATION_EVENT_ID_V2 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
