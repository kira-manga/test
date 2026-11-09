package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingProfileV2
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId

/** Original application intent only. These files never prove native ownership or SQL fate.
 * No secret, certificate, SDK key or employee credential is stored or reconstructed here.
 * Native originals independently supply the complete definition and actual terminal history. */
public class LocalDeviceAdmissionIntents(private val storage: LocalSetupIntentStorage) {
    private val monitor = LocalSetupMonitor()
    public data class Context(public val installationId: DomainId, public val scope: BranchScope, public val cohostDeviceId: DomainId)
    public data class Identity(public val context: Context, public val actionId: DomainId, public val requestId: DomainId,
        public val deviceId: DomainId, public val keyId: DomainId, public val acquisitionId: DomainId, public val preparationId: DomainId,
        public val definitionDigest: String, public val requestDigest: String, public val challengeDigest: String,
        public val sdkDigest: String, public val rootFingerprint: String) {
        init {
            require(listOf(definitionDigest, requestDigest, challengeDigest, sdkDigest, rootFingerprint).all { DIGEST.matches(it) })
        }
    }
    public enum class Terminal { APPLIED, KNOWN_NO_EFFECT }
    public data class Record(public val identity: Identity, public val headAttempted: Boolean, public val authorityDigest: String?,
        public val admissionAttempted: Boolean, public val terminal: Terminal?, public val eventId: DomainId?) {
        public val unresolvedAttempt: Boolean get() = terminal == null &&
            (admissionAttempted || headAttempted && authorityDigest == null)
    }

    public fun records(context: Context): List<Record> = withSetupLock(monitor) {
        val names = storage.names() ?: return@withSetupLock emptyList()
        require(names.size <= MAXIMUM_FILES && names.distinct().size == names.size)
        val files = names.map { name ->
            val match = checkNotNull(NAME.matchEntire(name))
            Triple(DomainId(match.groupValues[1]), match.groupValues[2], name)
        }
        val records = files.groupBy { it.first }.map { (actionId, group) ->
            val kinds = group.map { it.second }.toSet()
            require(kinds.size == group.size && "intent" in kinds)
            val fields = read(filename(actionId, "intent"))
            require(fields.size == IDENTITY_FIELDS && fields[1] == "intent")
            val identity = Identity(Context(DomainId(fields[2]), BranchScope(DomainId(fields[3]),
                DomainId(fields[4]), DomainId(fields[5])), DomainId(fields[6])), DomainId(fields[7]),
                DomainId(fields[8]), DomainId(fields[9]), DomainId(fields[10]), DomainId(fields[11]),
                DomainId(fields[12]), fields[13], fields[14], fields[15], fields[16], fields[17])
            require(identity.context == context && identity.actionId == actionId)
            var authority: String? = null
            var eventId: DomainId? = null
            for ((_, kind, name) in group) {
                val actual = read(name)
                val expected = identityFields(identity, kind)
                when (kind) {
                    "approval" -> {
                        require(actual.size == IDENTITY_FIELDS + 1 && actual.take(IDENTITY_FIELDS) == expected && DIGEST.matches(actual.last()))
                        authority = actual.last()
                    }
                    "applied" -> {
                        require(actual.size == IDENTITY_FIELDS + 1 && actual.take(IDENTITY_FIELDS) == expected)
                        eventId = DomainId(actual.last()); require(eventId == identity.requestId)
                    }
                    else -> require(actual == expected)
                }
            }
            require("admit" !in kinds || authority != null)
            require(!("applied" in kinds && "no-effect" in kinds))
            Record(identity, "head" in kinds, authority, "admit" in kinds,
                if ("applied" in kinds) Terminal.APPLIED else if ("no-effect" in kinds) Terminal.KNOWN_NO_EFFECT else null, eventId)
        }.sortedBy { it.identity.actionId.value }
        require(records.size <= MAXIMUM_RECORDS && records.count { it.unresolvedAttempt } <= 1 &&
            records.map { it.identity.requestId }.distinct().size == records.size)
        storage.verify()
        records
    }

    /** Adopt only an exact original returned by the actual native review port, not UI bytes. */
    public fun retainOriginal(context: Context, action: LocalDevicePairingAction): Record = withSetupLock(monitor) {
        val identity = identity(context, action)
        val existing = records(context)
        existing.singleOrNull { it.identity.actionId == identity.actionId }?.let {
            require(it.identity == identity); return@withSetupLock it
        }
        require(existing.size < MAXIMUM_RECORDS && existing.none { it.unresolvedAttempt })
        write(filename(identity.actionId, "intent"), identityFields(identity, "intent"), false)
        original(identity)
    }

    public fun original(identity: Identity): Record = withSetupLock(monitor) {
        records(identity.context).single { it.identity.actionId == identity.actionId }.also { require(it.identity == identity) }
    }

    /** Force before head verification/signing can become uncertain. Never re-arm on a refusal. */
    public fun markHeadAttempt(identity: Identity): Record = withSetupLock(monitor) {
        val before = original(identity)
        require(!before.headAttempted && before.authorityDigest == null && !before.admissionAttempted && before.terminal == null &&
            records(identity.context).none { it.unresolvedAttempt && it.identity != identity })
        write(filename(identity.actionId, "head"), identityFields(identity, "head"), false)
        original(identity)
    }

    public fun retainApproval(identity: Identity, approval: LocalDevicePairingApproval): Record = withSetupLock(monitor) {
        require(approval.originalActionId == identity.actionId && approval.requested.request.requestId == identity.requestId &&
            approval.requested.definitionDigest.value == identity.definitionDigest &&
            approval.requested.request.contentDigest.value == identity.requestDigest &&
            approval.challengeDigest.value == identity.challengeDigest && approval.requested.sdkIdentity?.descriptorDigest?.value == identity.sdkDigest)
        val before = original(identity)
        require(before.authorityDigest == null || before.authorityDigest == approval.authorityDigest.value)
        write(filename(identity.actionId, "approval"), identityFields(identity, "approval") + approval.authorityDigest.value, true)
        original(identity)
    }

    /** Single-use local dispatch marker. All later/restarted calls remain original lookup only. */
    public fun markAdmissionAttempt(identity: Identity): Record = withSetupLock(monitor) {
        val before = original(identity)
        require(before.authorityDigest != null && !before.admissionAttempted && before.terminal == null &&
            records(identity.context).none { it.unresolvedAttempt && it.identity != identity })
        write(filename(identity.actionId, "admit"), identityFields(identity, "admit"), false)
        original(identity)
    }

    /** Call only after the real original native/protected outcome, never from absence/cleanup. */
    public fun retainApplied(identity: Identity, eventId: DomainId): Record = withSetupLock(monitor) {
        require(eventId == identity.requestId && original(identity).terminal != Terminal.KNOWN_NO_EFFECT)
        write(filename(identity.actionId, "applied"), identityFields(identity, "applied") + eventId.value, true)
        original(identity)
    }
    public fun retainKnownNoEffect(identity: Identity): Record = withSetupLock(monitor) {
        require(original(identity).terminal != Terminal.APPLIED)
        write(filename(identity.actionId, "no-effect"), identityFields(identity, "no-effect"), true)
        original(identity)
    }

    private fun identity(context: Context, action: LocalDevicePairingAction): Identity {
        val requested = action.requested; val sdk = checkNotNull(requested.sdkIdentity); val challenge = action.challenge
        require(requested.purpose == LocalDevicePairingProfileV2.PURPOSE && requested.request.scope == context.scope &&
            requested.admission.scope == context.scope && requested.admission.admitted && requested.admission.revision.value == 0L &&
            requested.admission.deviceId != context.cohostDeviceId && challenge.cohostDeviceId == context.cohostDeviceId)
        return Identity(context, action.originalActionId, requested.request.requestId, requested.admission.deviceId,
            requested.target.keyId, sdk.originalAcquisitionId, sdk.originalPreparationId, requested.definitionDigest.value,
            requested.request.contentDigest.value, challenge.challengeDigest.value, sdk.descriptorDigest.value, challenge.rootFingerprint)
    }
    private fun identityFields(identity: Identity, kind: String): List<String> = listOf(FORMAT, kind,
        identity.context.installationId.value, identity.context.scope.organizationId.value, identity.context.scope.companyId.value,
        identity.context.scope.branchId.value, identity.context.cohostDeviceId.value, identity.actionId.value, identity.requestId.value,
        identity.deviceId.value, identity.keyId.value, identity.acquisitionId.value, identity.preparationId.value,
        identity.definitionDigest, identity.requestDigest, identity.challengeDigest, identity.sdkDigest, identity.rootFingerprint)
    private fun filename(id: DomainId, kind: String): String {
        require(kind in KINDS); return "${id.value}.$kind.v1"
    }
    private fun read(name: String): List<String> {
        val bytes = storage.read(name)
        try {
            require(bytes.size in 1..MAXIMUM_BYTES)
            val lines = bytes.decodeToString(throwOnInvalidSequence = true).split('\n')
            require(lines.size >= IDENTITY_FIELDS + 2 && lines.last().isEmpty() && lines.first() == FORMAT)
            val fields = lines.dropLast(2); val encoded = encode(fields)
            try { require(encoded.contentEquals(bytes)) } finally { encoded.fill(0) }
            return fields
        } finally { bytes.fill(0) }
    }
    private fun write(name: String, fields: List<String>, exactExisting: Boolean) {
        val bytes = encode(fields)
        try { require(bytes.size <= MAXIMUM_BYTES); storage.writeExclusive(name, bytes, exactExisting) }
        finally { bytes.fill(0) }
    }
    private fun encode(fields: List<String>): ByteArray {
        require(fields.all { it.isNotEmpty() && '\n' !in it && '\r' !in it })
        val body = fields.joinToString("\n", postfix = "\n").encodeToByteArray()
        return try { body + "sha256:${sha256(body).value}\n".encodeToByteArray() } finally { body.fill(0) }
    }
    override fun toString(): String = "LocalDeviceAdmissionIntents([REDACTED])"
    public companion object {
        public const val MAXIMUM_BYTES: Int = 4096
        public const val MAXIMUM_RECORDS: Int = 256
        public const val MAXIMUM_FILES: Int = MAXIMUM_RECORDS * 6
        private const val IDENTITY_FIELDS = 18
        private const val FORMAT = "ukkera.original-device-admission-ui-intent/v1"
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val KINDS = setOf("intent", "head", "approval", "admit", "applied", "no-effect")
        private val NAME = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(intent|head|approval|admit|applied|no-effect)\\.v1")
    }
}
