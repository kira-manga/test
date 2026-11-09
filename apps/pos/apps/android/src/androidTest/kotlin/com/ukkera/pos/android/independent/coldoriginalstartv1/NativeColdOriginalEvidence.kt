package com.ukkera.pos.android.independent.coldoriginalstartv1

import android.app.Application
import android.util.AtomicFile
import android.util.Base64
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Explicit disposable-test intent, not a production client journal or recovery grant.
 * Native journals remain owned by the shared fixture. Only nonsecret immutable inputs and
 * actual observations are retained here. Any earlier/unknown invocation forbids a new run. */
internal class NativeColdOriginalEvidence(application: Application,
    plan: NativeColdOriginalFixturePlan) : NativeLocalFixtureEvidence {
    private val file = File(application.noBackupFilesDir, "cold-original-start-v1-backend-intent.json")
    private val durable: AtomicFile
    private val value = JSONObject().put("design", "cold-original-start-v1").put("case", "COS-001")
        .put("qualification", "Android same-device backend baseline")
        .put("namespace", plan.namespace.value).put("protection", plan.protection.name)
        .put("evidenceTime", plan.evidenceTime.canonicalString())
        .put("status", "SETUP_PENDING").put("bindings", JSONObject())
        .put("requests", JSONObject()).put("events", JSONArray())
        .put("unboundFrozenAssertions", JSONArray(unboundFrozenAssertions))
    init {
        check(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
        check(file.createNewFile()) { "Retained cold-original intent exists; do not overwrite unknown synthetic work" }
        durable = AtomicFile(file)
        persist() // No provisioning or business call precedes original intent retention.
    }

    override fun bind(symbol: String, value: Any?) {
        check(value == null || value is String || value is Number || value is Boolean ||
            value is JSONObject || value is JSONArray) { "Only explicit nonsecret observations may be retained" }
        this.value.getJSONObject("bindings").put(symbol, value ?: JSONObject.NULL)
        persist()
    }

    override fun event(step: String, facts: JSONObject) {
        value.getJSONArray("events").put(JSONObject().put("step", step).put("facts", facts))
        persist()
    }

    fun status(status: String) { value.put("status", status); persist() }

    fun freezeRequest(symbol: String, request: MutationRequest, view: LocalSessionView,
        original: AndroidOriginalLocalClient) {
        val requests = value.getJSONObject("requests")
        check(!requests.has(symbol) && request.actionApprovalId == null)
        val c = original.material.coordinates
        check(original.isCurrent() && view.scope == c.scope && view.actorId == c.firstActorId && view.deviceId == c.deviceId)
        val digest = semanticDigest(view.scope, view.actorId, request.command, request.payload)
        requests.put(symbol, JSONObject().put("status", "RETAINED_BEFORE_DISPATCH")
            .put("operationId", request.operationId.value)
            .put("command", request.command.name).put("commandVersion", request.command.version)
            .put("canonicalRequest", coldBase64(request.payload))
            .put("semanticDigestVersion", 1).put("semanticDigest", digest.value)
            .put("organizationId", view.scope.organizationId.value).put("companyId", view.scope.companyId.value)
            .put("branchId", view.scope.branchId.value).put("originalActorId", view.actorId.value)
            .put("originalDeviceId", view.deviceId.value).put("originalSessionId", view.sessionId.value)
            .put("originalSessionGeneration", view.generation.value)
            .put("originalPolicyRevision", view.policy.revision.value)
            .put("assignmentId", c.assignmentId.value).put("installationId", c.installationId.value)
            .put("storageInstanceId", c.storageInstanceId.value)
            .put("authorityEpoch", original.receipt.core.authorityEpoch.value)
            .put("serviceIncarnationId", original.serviceIncarnationId.value))
        persist()
    }

    /** One call per retained intent. Null/lost delivery remains pending, never NotRecorded. */
    fun markDispatch(symbol: String, path: String = "DIRECT_PROTECTED_EXECUTE") {
        val request = value.getJSONObject("requests").getJSONObject(symbol)
        check(request.getString("status") == "RETAINED_BEFORE_DISPATCH")
        request.put("status", "DISPATCH_PENDING").put("invocationPath", path)
        persist()
    }

    fun observeReply(symbol: String, reply: MutationReply) {
        val request = value.getJSONObject("requests").getJSONObject(symbol)
        check(request.getString("status") == "DISPATCH_PENDING")
        val observation = when (reply) {
            is MutationReply.Recorded -> coldRecordedJson(reply)
            is MutationReply.Deferred -> JSONObject().put("kind", "DEFERRED").put("reason", reply.reason.name)
            is MutationReply.IdentityConflict -> JSONObject().put("kind", "IDENTITY_CONFLICT")
        }
        request.put("status", "REPLY_OBSERVED").put("reply", observation)
        persist()
    }

    /** Re-read retained bytes after actual owner closure; no old runtime object is required. */
    fun savedOriginal(symbol: String): ColdSavedOriginal {
        val saved = durable.openRead().use { JSONObject(it.bufferedReader().readText()) }
            .getJSONObject("requests").getJSONObject(symbol)
        val scope = BranchScope(DomainId(saved.getString("organizationId")),
            DomainId(saved.getString("companyId")), DomainId(saved.getString("branchId")))
        val actor = DomainId(saved.getString("originalActorId"))
        val operation = OperationId(saved.getString("operationId"))
        val request = MutationRequest(operation, CommandKey(saved.getString("command"), saved.getInt("commandVersion")),
            coldStored(saved.getString("canonicalRequest")))
        val identity = OperationIdentity(scope.branchId, operation, actor, saved.getInt("semanticDigestVersion"),
            saved.getString("semanticDigest"))
        check(identity.semanticSha256 == semanticDigest(scope, actor, request.command, request.payload).value)
        val actualReply = saved.getJSONObject("reply")
        check(actualReply.getString("kind") == "RECORDED")
        return ColdSavedOriginal(symbol, request, identity, scope,
            DomainId(saved.getString("originalDeviceId")), DomainId(saved.getString("originalSessionId")),
            saved.getLong("originalSessionGeneration"), Revision(saved.getLong("originalPolicyRevision")),
            DomainId(saved.getString("assignmentId")), DomainId(saved.getString("installationId")),
            DomainId(saved.getString("storageInstanceId")), Revision(saved.getLong("authorityEpoch")),
            DomainId(saved.getString("serviceIncarnationId")),
            ColdRecordedResult.fromJson(actualReply))
    }

    private fun persist() {
        val stream = durable.startWrite()
        try {
            stream.write(value.toString().encodeToByteArray())
            durable.finishWrite(stream)
        } catch (failure: Throwable) { durable.failWrite(stream); throw failure }
    }

    companion object {
        // These are preserved missing bindings, not zero values or waived COS-001 outcomes.
        val unboundFrozenAssertions = listOf(
            "physical_instruction_count: no producer count projection is bound; test invocation counts are not evidence",
            "original interactive presentation identity and displayed UI instruction: backend lifetime has no public presentation ID",
            "receipt_count and original payment receipt identity: PaidSale is not contractually identified as a payment receipt; customer goods receipt is not substituted",
            "full COS-001 I/L/U and N1/N3/N4 network/lost-reply variants: not exercised by this same-device backend baseline",
        )
    }
}

internal data class ColdSavedOriginal(
    val symbol: String,
    val request: MutationRequest,
    val identity: OperationIdentity,
    val scope: BranchScope,
    val deviceId: DomainId,
    val sessionId: DomainId,
    val sessionGeneration: Long,
    val policyRevision: Revision,
    val assignmentId: DomainId,
    val installationId: DomainId,
    val storageInstanceId: DomainId,
    val authorityEpoch: Revision,
    val serviceIncarnationId: DomainId,
    val result: ColdRecordedResult,
)

/** No physicalStart, bearer, principal or old native owner escapes the initial lifetime. */
internal data class ColdRecordedResult(val operationId: OperationId, val actorId: DomainId,
    val committed: Boolean, val code: String, val result: StoredBytes,
    val authorityEpoch: Revision, val sequence: JournalSequence, val recordedAt: UtcInstant) {
    fun requireSame(reply: MutationReply.Recorded) {
        check(reply.operationId == operationId && reply.originalActorId == actorId &&
            reply.committed == committed && reply.code == code && reply.result == result &&
            reply.authorityEpoch == authorityEpoch && reply.sequence == sequence && reply.recordedAt == recordedAt)
    }
    companion object {
        fun fromJson(value: JSONObject) = ColdRecordedResult(OperationId(value.getString("operationId")),
            DomainId(value.getString("originalActorId")), value.getBoolean("committed"), value.getString("code"),
            coldStored(value.getString("canonicalResult")), Revision(value.getLong("authorityEpoch")),
            JournalSequence(value.getLong("sequence")), UtcInstant.parseCanonical(value.getString("recordedAt")))
    }
}

internal fun coldRecordedJson(reply: MutationReply.Recorded): JSONObject = JSONObject()
    .put("kind", "RECORDED").put("operationId", reply.operationId.value)
    .put("originalActorId", reply.originalActorId.value).put("committed", reply.committed)
    .put("code", reply.code).put("canonicalResult", coldBase64(reply.result))
    .put("authorityEpoch", reply.authorityEpoch.value).put("sequence", reply.sequence.value)
    .put("recordedAt", reply.recordedAt.canonicalString()).put("replayed", reply.replayed)

internal fun coldBase64(value: StoredBytes): String {
    val bytes = value.copyBytes()
    return try { Base64.encodeToString(bytes, Base64.NO_WRAP) } finally { bytes.fill(0) }
}
internal fun coldStored(value: String): StoredBytes {
    val bytes = Base64.decode(value, Base64.NO_WRAP)
    return try { StoredBytes(bytes) } finally { bytes.fill(0) }
}

internal fun coldDigest(value: StoredBytes): PayloadDigest {
    val bytes = value.copyBytes()
    return try { sha256(bytes) } finally { bytes.fill(0) }
}
