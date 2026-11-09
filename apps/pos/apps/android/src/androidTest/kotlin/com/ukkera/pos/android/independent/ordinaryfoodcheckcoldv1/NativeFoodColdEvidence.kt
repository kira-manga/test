package com.ukkera.pos.android.independent.ordinaryfoodcheckcoldv1

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

/** Nonsecret private test DATA only. Neither this file nor a parsed original grants
 * employee authority, native custody, producer admission or a production journal. */
internal class NativeFoodColdEvidence(application: Application, plan: NativeFoodColdInputPlan) : NativeLocalFixtureEvidence {
    private val file = File(application.noBackupFilesDir, "ordinary-food-cold101-${plan.profile}-backend-intent.json")
    private val durable: AtomicFile
    private val root = JSONObject().put("design", "ordinary-food-check-cold-v1")
        .put("baseVersion", NativeFoodColdInputPlan.BASE_VERSION).put("case", "OFC-COLD-101").put("profile", plan.profile)
        .put("selectedAdjuncts", JSONArray().put(JSONObject().put("variant", NativeFoodColdInputPlan.IDENTITY_VARIANT)
            .put("version", NativeFoodColdInputPlan.ADJUNCT_VERSION)).put(JSONObject()
            .put("variant", NativeFoodColdInputPlan.CATALOG_VARIANT).put("version", NativeFoodColdInputPlan.ADJUNCT_VERSION)))
        .put("wholeCase", "NOT_QUALIFIED").put("status", "SETUP_PENDING")
        .put("bindings", JSONObject()).put("requests", JSONObject()).put("events", JSONArray())
        .put("unbound", JSONArray(unbound))

    init {
        check(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
        check(file.createNewFile()) { "Prior OFC-COLD-101 intent exists; do not overwrite unknown synthetic work" }
        durable = AtomicFile(file)
        persist() // Selected versions/profile retained before any native setup or publication.
    }

    override fun bind(symbol: String, value: Any?) {
        check(value == null || value is String || value is Number || value is Boolean || value is JSONObject || value is JSONArray)
        val bindings = root.getJSONObject("bindings")
        check(!bindings.has(symbol)) { "An observed identity/input may only be bound once: $symbol" }
        bindings.put(symbol, value ?: JSONObject.NULL)
        persist()
    }
    override fun event(step: String, facts: JSONObject) {
        root.getJSONArray("events").put(JSONObject().put("step", step).put("facts", facts))
        persist()
    }
    fun status(status: String) { root.put("status", status); persist() }

    fun freezeSources(sources: FoodColdRequestedSources) {
        val all = JSONArray()
        sources.proposals.forEach { proposal ->
            val content = proposal.copyCanonicalContent()
            try {
                check(sha256(content) == proposal.contentDigest)
                all.put(JSONObject().put("kind", proposal.kind.name).put("publicationId", proposal.publicationId.value)
                    .put("entityId", proposal.entityId.value).put("contentDigest", proposal.contentDigest.value)
                    .put("canonicalContent", foodColdBase64(content)))
            } finally { content.fill(0) }
        }
        bind("COMPLETE_PROPOSED_SOURCES_BEFORE_ANY_PREPARE", JSONObject()
            .put("organizationId", sources.company.organizationId.value).put("companyId", sources.company.companyId.value)
            .put("orderedPublications", all))
    }

    fun retainRequest(symbol: String, actor: FoodColdActor, request: MutationRequest, view: LocalSessionView,
        original: AndroidOriginalLocalClient) {
        val requests = root.getJSONObject("requests")
        check(!requests.has(symbol) && request.actionApprovalId == null && original.isCurrent())
        check(requests.keys().asSequence().none { requests.getJSONObject(it).getString("operationId") == request.operationId.value })
        val c = original.material.coordinates
        check(view.scope == c.scope && view.deviceId == c.deviceId)
        requests.put(symbol, JSONObject().put("actorRole", actor.name).put("status", "RETAINED_BEFORE_DISPATCH")
            .put("operationId", request.operationId.value).put("command", request.command.name).put("commandVersion", request.command.version)
            .put("canonicalRequest", foodColdBase64(request.payload)).put("semanticDigestVersion", 1)
            .put("semanticDigest", semanticDigest(view.scope, view.actorId, request.command, request.payload).value)
            .put("organizationId", view.scope.organizationId.value).put("companyId", view.scope.companyId.value)
            .put("branchId", view.scope.branchId.value).put("originalActorId", view.actorId.value)
            .put("deviceId", view.deviceId.value).put("sessionId", view.sessionId.value)
            .put("policyRevision", view.policy.revision.value).put("installationId", c.installationId.value)
            .put("assignmentId", c.assignmentId.value).put("storageInstanceId", c.storageInstanceId.value)
            .put("authorityEpoch", original.receipt.core.authorityEpoch.value)
            .put("serviceIncarnationId", original.serviceIncarnationId.value))
        persist()
    }
    fun dispatching(symbol: String) {
        val request = root.getJSONObject("requests").getJSONObject(symbol)
        check(request.getString("status") == "RETAINED_BEFORE_DISPATCH")
        request.put("status", "DISPATCH_PENDING"); persist()
    }
    fun observeReply(symbol: String, reply: MutationReply) {
        val request = root.getJSONObject("requests").getJSONObject(symbol)
        check(request.getString("status") == "DISPATCH_PENDING")
        val result = when (reply) {
            is MutationReply.Recorded -> JSONObject().put("kind", "RECORDED").put("operationId", reply.operationId.value)
                .put("originalActorId", reply.originalActorId.value).put("committed", reply.committed).put("code", reply.code)
                .put("result", foodColdBase64(reply.result)).put("epoch", reply.authorityEpoch.value)
                .put("sequence", reply.sequence.value).put("recordedAt", reply.recordedAt.canonicalString())
                .put("replayed", reply.replayed)
            is MutationReply.Deferred -> JSONObject().put("kind", "DEFERRED").put("reason", reply.reason.name)
            is MutationReply.IdentityConflict -> JSONObject().put("kind", "IDENTITY_CONFLICT")
        }
        request.put("status", "REPLY_OBSERVED").put("reply", result); persist()
    }

    /** After actual closure, read original inputs/results from their retained test DATA.
     * There is no saved bearer, mutable source refresh or newly created operation. */
    fun originals(): List<FoodColdSavedOriginal> {
        val stored = durable.openRead().use { JSONObject(it.bufferedReader().readText()) }.getJSONObject("requests")
        return stored.keys().asSequence().map { symbol ->
            val value = stored.getJSONObject(symbol)
            check(value.getString("status") == "REPLY_OBSERVED")
            val reply = value.getJSONObject("reply")
            check(reply.getString("kind") == "RECORDED" && reply.getBoolean("committed") && !reply.getBoolean("replayed"))
            val scope = BranchScope(DomainId(value.getString("organizationId")), DomainId(value.getString("companyId")),
                DomainId(value.getString("branchId")))
            val request = MutationRequest(OperationId(value.getString("operationId")),
                CommandKey(value.getString("command"), value.getInt("commandVersion")), foodColdStored(value.getString("canonicalRequest")))
            val identity = OperationIdentity(scope.branchId, request.operationId, DomainId(value.getString("originalActorId")),
                value.getInt("semanticDigestVersion"), value.getString("semanticDigest"))
            check(identity.semanticSha256 == semanticDigest(scope, identity.originalActorId, request.command, request.payload).value)
            FoodColdSavedOriginal(symbol, FoodColdActor.valueOf(value.getString("actorRole")), request, identity, scope,
                DomainId(value.getString("deviceId")), DomainId(value.getString("sessionId")), Revision(value.getLong("policyRevision")),
                DomainId(value.getString("installationId")), DomainId(value.getString("assignmentId")),
                DomainId(value.getString("storageInstanceId")), FoodColdRecordedResult(request.operationId,
                    DomainId(reply.getString("originalActorId")), reply.getString("code"), foodColdStored(reply.getString("result")),
                    Revision(reply.getLong("epoch")), JournalSequence(reply.getLong("sequence")),
                    UtcInstant.parseCanonical(reply.getString("recordedAt"))))
        }.sortedBy { it.result.sequence.value }.toList()
    }
    private fun persist() {
        val stream = durable.startWrite()
        try { stream.write(root.toString().encodeToByteArray()); durable.finishWrite(stream) }
        catch (failure: Throwable) { durable.failWrite(stream); throw failure }
    }
    companion object {
        val unbound = listOf(
            "required pre-close and independent closed FOOD native original-event/source proof: retained publication snapshots, original replies and census/journal DATA do not bind either proof",
            "subscription and CURRENT_CURSOR/EXPIRED_CURSOR synchronization repair",
            "installed production UI, private navigation and client-outage drafts/pending presentation",
            "admitted official LAN RPC and distinct client devices",
            "additional employee native intent-journal integration beyond same-cohost authenticated backend sessions",
            "process crash, OS lifecycle, other native drivers and Windows/macOS/iPhone/iPad",
            "physical printers/peripherals, owner acceptance and cases OFC-COLD-102 through 108",
        )
    }
}

internal data class FoodColdSavedOriginal(val symbol: String, val actor: FoodColdActor, val request: MutationRequest,
    val identity: OperationIdentity, val scope: BranchScope, val deviceId: DomainId, val sessionId: DomainId,
    val policyRevision: Revision, val installationId: DomainId, val assignmentId: DomainId, val storageInstanceId: DomainId,
    val result: FoodColdRecordedResult)
internal data class FoodColdRecordedResult(val operationId: OperationId, val actorId: DomainId, val code: String,
    val body: StoredBytes, val epoch: Revision, val sequence: JournalSequence, val recordedAt: UtcInstant) {
    fun requireSame(reply: MutationReply.Recorded) {
        check(reply.committed && reply.operationId == operationId && reply.originalActorId == actorId && reply.code == code &&
            reply.result == body && reply.authorityEpoch == epoch && reply.sequence == sequence && reply.recordedAt == recordedAt &&
            reply.physicalStart == null) { "Original operation/result/actor/time/sequence changed" }
    }
}
internal fun foodColdBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
internal fun foodColdBase64(bytes: StoredBytes): String = bytes.copyBytes().let { copy ->
    try { foodColdBase64(copy) } finally { copy.fill(0) }
}
internal fun foodColdStored(text: String): StoredBytes = Base64.decode(text, Base64.NO_WRAP).let { bytes ->
    try { StoredBytes(bytes) } finally { bytes.fill(0) }
}
