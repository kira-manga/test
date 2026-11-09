package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId

/** Private exclusive file operations only. Neither these files nor this port grant authority. */
public interface LocalSetupIntentStorage {
    public fun names(): List<String>?
    public fun read(name: String): ByteArray
    public fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean)
    public fun verify()
}

/**
 * Shared original-ID/attempt ordering and compatible intent encoding for the setup workflow.
 * No password, policy, bearer or native key is stored here. Partial files remain unresolved;
 * native originals independently govern every privileged action on every platform.
 */
public class LocalEmployeeSetupIntents(private val storage: LocalSetupIntentStorage) {
    private val monitor = LocalSetupMonitor()
    public data class Identity(public val installationId: DomainId, public val scope: BranchScope,
        public val deviceId: DomainId, public val requestId: DomainId, public val actorId: DomainId)
    public data class Record(public val identity: Identity, public val preparing: Boolean,
        public val actionId: DomainId?, public val definitionDigest: String?, public val headAttempted: Boolean,
        public val passwordAttempted: Boolean, public val journalAttempted: Boolean, public val complete: Boolean)

    public fun records(installationId: DomainId, scope: BranchScope, deviceId: DomainId): List<Record> = withSetupLock(monitor) {
        val names = storage.names() ?: return@withSetupLock emptyList()
        require(names.size <= MAXIMUM_FILES)
        val parsed = names.map { name ->
            val match = checkNotNull(NAME.matchEntire(name))
            Triple(DomainId(match.groupValues[1]), match.groupValues[2], name)
        }
        val result = parsed.groupBy { it.first }.map { (requestId, files) ->
            require(files.map { it.second }.distinct().size == files.size && files.any { it.second == "intent" })
            val fields = read(filename(requestId, "intent"))
            require(fields.size == 9 && fields[1] == "intent")
            val identity = Identity(DomainId(fields[2]), BranchScope(DomainId(fields[3]), DomainId(fields[4]), DomainId(fields[5])),
                DomainId(fields[6]), DomainId(fields[7]), DomainId(fields[8]))
            require(identity.requestId == requestId && identity.installationId == installationId &&
                identity.scope == scope && identity.deviceId == deviceId)
            var actionId: DomainId? = null
            var definitionDigest: String? = null
            for ((_, kind, name) in files) {
                val actual = read(name)
                val expected = identityFields(identity, kind)
                if (kind == "action") {
                    require(actual.size == 11 && actual.take(9) == expected)
                    actionId = DomainId(actual[9]); definitionDigest = actual[10]
                    require(DIGEST.matches(definitionDigest))
                } else require(actual == expected)
            }
            val kinds = files.map { it.second }.toSet()
            require((actionId == null) == (definitionDigest == null))
            require("action" !in kinds || "prepare" in kinds)
            require(kinds.none { it in setOf("head", "password", "journal", "rejected", "done") } || actionId != null)
            require("done" !in kinds || "journal" in kinds || "rejected" in kinds)
            Record(identity, "prepare" in kinds, actionId, definitionDigest, "head" in kinds,
                "password" in kinds, "journal" in kinds, "done" in kinds)
        }.sortedBy { it.identity.requestId.value }
        require(result.size <= MAXIMUM_RECORDS && result.count { !it.complete } <= 1)
        storage.verify()
        result
    }

    public fun begin(identity: Identity): Record = withSetupLock(monitor) {
        val old = records(identity.installationId, identity.scope, identity.deviceId)
        require(old.none { !it.complete } && old.none { it.identity.requestId == identity.requestId } && old.size < MAXIMUM_RECORDS)
        write(filename(identity.requestId, "intent"), identityFields(identity, "intent"), allowExactExisting = false)
        readOriginal(identity)
    }

    /** Original native-reviewed reference only; this operation issues no head/employee authority. */
    public fun retainNativeOriginal(identity: Identity, action: LocalEmployeeEnrollmentAction): Record = withSetupLock(monitor) {
        require(action.requested.request.requestId == identity.requestId && action.requested.request.targetId == identity.actorId &&
            action.requested.request.scope == identity.scope && action.requested.definition.admittedDeviceIds == setOf(identity.deviceId))
        val old = records(identity.installationId, identity.scope, identity.deviceId)
        old.singleOrNull { it.identity.requestId == identity.requestId }?.let { require(it.identity == identity) } ?: begin(identity)
        mark(identity, "prepare")
        write(filename(identity.requestId, "action"), identityFields(identity, "action") +
            listOf(action.originalActionId.value, action.requested.definitionDigest.value), allowExactExisting = true)
        readOriginal(identity)
    }

    public fun markPreparing(identity: Identity): Record = withSetupLock(monitor) { mark(identity, "prepare") }
    public fun markHeadAttempt(identity: Identity): Record = withSetupLock(monitor) { mark(identity, "head") }
    public fun markPasswordAttempt(identity: Identity): Record = withSetupLock(monitor) { mark(identity, "password") }
    public fun markJournalAttempt(identity: Identity): Record = withSetupLock(monitor) { mark(identity, "journal") }
    public fun markComplete(identity: Identity): Record = withSetupLock(monitor) { mark(identity, "done") }
    public fun readOriginal(identity: Identity): Record = withSetupLock(monitor) {
        records(identity.installationId, identity.scope, identity.deviceId)
            .single { it.identity.requestId == identity.requestId }.also { require(it.identity == identity) }
    }
    public fun markRejectedComplete(identity: Identity): Record = withSetupLock(monitor) {
        mark(identity, "rejected")
        mark(identity, "done")
    }

    private fun mark(identity: Identity, kind: String): Record {
        val original = readOriginal(identity)
        require(!original.complete || kind == "journal" || kind == "done")
        if (kind in setOf("head", "password", "journal", "rejected", "done")) require(original.actionId != null)
        if (kind == "done") require(original.journalAttempted ||
            read(filename(identity.requestId, "rejected")) == identityFields(identity, "rejected"))
        write(filename(identity.requestId, kind), identityFields(identity, kind), allowExactExisting = true)
        return readOriginal(identity)
    }

    private fun read(name: String): List<String> {
        val bytes = storage.read(name)
        try {
            require(bytes.size in 1..MAXIMUM_BYTES)
            val lines = bytes.decodeToString(throwOnInvalidSequence = true).split('\n')
            require(lines.last().isEmpty() && lines.size >= 4 && lines[0] == FORMAT)
            val fields = lines.dropLast(2)
            val encoded = encode(fields)
            try { require(bytes.contentEquals(encoded)) } finally { encoded.fill(0) }
            return fields
        } finally { bytes.fill(0) }
    }

    private fun write(name: String, fields: List<String>, allowExactExisting: Boolean) {
        val bytes = encode(fields)
        try { storage.writeExclusive(name, bytes, allowExactExisting) } finally { bytes.fill(0) }
    }

    private fun identityFields(identity: Identity, kind: String): List<String> = listOf(FORMAT, kind,
        identity.installationId.value, identity.scope.organizationId.value, identity.scope.companyId.value,
        identity.scope.branchId.value, identity.deviceId.value, identity.requestId.value, identity.actorId.value)
    private fun filename(requestId: DomainId, kind: String): String {
        require(kind in KINDS)
        return "${requestId.value}.$kind.v1"
    }
    private fun encode(fields: List<String>): ByteArray {
        require(fields.all { it.isNotEmpty() && '\n' !in it && '\r' !in it })
        val body = fields.joinToString("\n", postfix = "\n").encodeToByteArray()
        try { return body + "sha256:${sha256(body).value}\n".encodeToByteArray() }
        finally { body.fill(0) }
    }

    override fun toString(): String = "LocalEmployeeSetupIntents([REDACTED])"
    public companion object {
        public const val MAXIMUM_BYTES: Int = 4096
        public val MAXIMUM_FILES: Int get() = MAXIMUM_RECORDS * KINDS.size
        private const val FORMAT = "ukkera.local-employee-setup-ui-intent/v1"
        private const val MAXIMUM_RECORDS = 4096
        private val KINDS = setOf("intent", "prepare", "action", "head", "password", "journal", "rejected", "done")
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val DIGEST = Regex("[0-9a-f]{64}")
        private val NAME = Regex("($UUID_PATTERN)\\.(intent|prepare|action|head|password|journal|rejected|done)\\.v1")
    }
}
