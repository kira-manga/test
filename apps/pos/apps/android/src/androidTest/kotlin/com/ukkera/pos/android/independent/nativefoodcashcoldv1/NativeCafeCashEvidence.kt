package com.ukkera.pos.android.independent.nativefoodcashcoldv1

import android.app.Application
import android.util.AtomicFile
import android.util.Base64
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.File
import kotlin.time.Clock
import org.json.JSONArray
import org.json.JSONObject

/** Disposable synthetic-run DATA only, never the production request journal or a grant.
 * A prior/unknown invocation is not overwritten. No password, access/work bearer, private
 * key, live native object or constructed retained-command authority enters this file. */
internal class NativeCafeCashEvidence(application: Application) : NativeLocalFixtureEvidence {
    private val file = File(application.noBackupFilesDir, "food-cash-cold-recovery-v1-cafe-backend-data.json")
    private val durable: AtomicFile
    private val value = JSONObject().put("design", "food-cash-cold-recovery-v1").put("case", "FCC-C-01")
        .put("expectations", "base cases v1.0.0 + timestamp binding v1.0.1")
        .put("scope", "GENUINE_ANDROID_SAME_DEVICE_NATIVE_BACKEND_LEG")
        .put("wholeCase", "NOT_QUALIFIED").put("status", "SETUP_PENDING")
        .put("bindings", JSONObject()).put("requests", JSONObject()).put("events", JSONArray())
        .put("unbound", JSONArray(unbound))
    init {
        check(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
        check(file.createNewFile()) { "Prior Cafe CASH evidence exists; preserve unknown original work and use a fresh isolated installation" }
        durable = AtomicFile(file)
        persist()
    }

    override fun bind(symbol: String, value: Any?) {
        check(value == null || value is String || value is Number || value is Boolean || value is JSONObject || value is JSONArray)
        val bindings = this.value.getJSONObject("bindings")
        val next = value ?: JSONObject.NULL
        if (bindings.has(symbol)) check(bindings.get(symbol).toString() == next.toString()) {
            "An original synthetic-run binding must not be replaced"
        } else bindings.put(symbol, next)
        persist()
    }
    override fun event(step: String, facts: JSONObject) {
        value.getJSONArray("events").put(JSONObject().put("step", step)
            .put("witnessedAtUtc", cafeNow().canonicalString()).put("facts", facts))
        persist()
    }
    fun status(status: String) { value.put("status", status); persist() }

    fun freezeSources(requested: CafeRequestedSources) {
        bind("COMPLETE_PROPOSED_COMPANY_SOURCE_DATA_BEFORE_FIRST_PREPARE", JSONArray(requested.proposals.map { proposal ->
            val bytes = proposal.copyCanonicalContent()
            try { JSONObject().put("kind", proposal.kind.name).put("publicationId", proposal.publicationId.value)
                .put("entityId", proposal.entityId.value).put("contentDigest", proposal.contentDigest.value)
                .put("canonicalContent", Base64.encodeToString(bytes, Base64.NO_WRAP)) }
            finally { bytes.fill(0) }
        }))
    }

    fun freezeRequest(symbol: String, request: MutationRequest, view: LocalSessionView,
        original: AndroidOriginalLocalClient, sqlStagingRequested: Boolean = false) {
        val requests = value.getJSONObject("requests")
        check(!requests.has(symbol) && request.actionApprovalId == null)
        val c = original.material.coordinates
        check(original.isCurrent() && view.scope == c.scope && view.actorId == c.firstActorId && view.deviceId == c.deviceId)
        requests.put(symbol, JSONObject().put("state", "INPUT_RETAINED_BEFORE_CALL")
            .put("meaning", if (sqlStagingRequested) "EXPECTED_COMPARISON_DATA_BEFORE_ACTUAL_NATIVE_SQL_STAGE"
                else "TEST_HELD_INPUT_COMPARISON_DATA_NOT_PRODUCTION_STAGING")
            .put("productionSqlStagingRequested", sqlStagingRequested)
            .put("operationId", request.operationId.value).put("command", request.command.name)
            .put("commandVersion", request.command.version).put("canonicalRequest", cafeBase64(request.payload))
            .put("semanticDigest", semanticDigest(view.scope, view.actorId, request.command, request.payload).value)
            .put("organizationId", view.scope.organizationId.value).put("companyId", view.scope.companyId.value)
            .put("branchId", view.scope.branchId.value).put("originalActorId", view.actorId.value)
            .put("originalDeviceId", view.deviceId.value).put("originalSessionId", view.sessionId.value)
            .put("originalSessionGeneration", view.generation.value).put("originalPolicyRevision", view.policy.revision.value)
            .put("installationId", c.installationId.value).put("assignmentId", c.assignmentId.value)
            .put("storageInstanceId", c.storageInstanceId.value).put("authorityEpoch", original.receipt.core.authorityEpoch.value))
        persist()
    }

    /** Public durable-stage reply, submitted form-version and verified journal-owner DATA.
     * The authoritative body is in the separately owned original native employee journal,
     * not reconstructed or authorized by this file. */
    fun recordSqlStage(symbol: String, formVersion: Int, staged: LocalPendingOperation, owner: LocalClientIdentity) {
        val row = value.getJSONObject("requests").getJSONObject(symbol)
        check(row.getString("state") == "INPUT_RETAINED_BEFORE_CALL" && row.getBoolean("productionSqlStagingRequested") &&
            !row.has("nativeSqlStage") && staged.phase == LocalPendingPhase.STAGED && staged.recordedDecision == null && staged.submitAllowed &&
            staged.operationId.value == row.getString("operationId") && staged.action == NativeCafeCashStaging.actions[symbol] &&
            owner.scope.organizationId.value == row.getString("organizationId") && owner.scope.companyId.value == row.getString("companyId") &&
            owner.scope.branchId.value == row.getString("branchId") && owner.actorId.value == row.getString("originalActorId") &&
            owner.deviceId.value == row.getString("originalDeviceId"))
        row.put("nativeSqlStage", JSONObject().put("path", "SHARED_PUBLIC_SQL_WORKFLOW_ORIGINAL_NATIVE_EMPLOYEE_JOURNAL")
            .put("operationId", staged.operationId.value).put("action", staged.action.name).put("formVersion", formVersion)
            .put("phase", staged.phase.name).put("ownerActorId", owner.actorId.value).put("ownerDeviceId", owner.deviceId.value)
            .put("labelEnglish", staged.label.english).put("labelArabic", staged.label.arabic)
            .put("summary", JSONArray(staged.summary.map { item -> JSONObject().put("labelEnglish", item.label.english)
                .put("labelArabic", item.label.arabic).put("valueEnglish", item.value.english).put("valueArabic", item.value.arabic) })))
        persist()
    }

    fun requireFourSqlStages(originals: List<CafeSavedOriginal>) {
        val data = read()
        val rows = data.getJSONObject("requests")
        check(originals.size == NativeCafeCashStaging.actions.size &&
            originals.map { it.symbol }.toSet() == NativeCafeCashStaging.actions.keys)
        originals.forEach { original ->
            val row = rows.getJSONObject(original.symbol)
            val stage = row.getJSONObject("nativeSqlStage")
            val expectedVersion = when (original.symbol) {
                "CREATE_CAFE_TAKEAWAY_DRAFT" -> 2
                "QUOTE_ORIGINAL_TWO_SMALL_LATTES" -> 3
                else -> 1
            }
            check(row.getBoolean("productionSqlStagingRequested") &&
                stage.getString("path") == "SHARED_PUBLIC_SQL_WORKFLOW_ORIGINAL_NATIVE_EMPLOYEE_JOURNAL" &&
                stage.getString("operationId") == original.request.operationId.value &&
                stage.getString("action") == checkNotNull(NativeCafeCashStaging.actions[original.symbol]).name &&
                stage.getInt("formVersion") == expectedVersion && stage.getString("phase") == LocalPendingPhase.STAGED.name &&
                stage.getString("ownerActorId") == original.identity.originalActorId.value && stage.getString("ownerDeviceId") == original.deviceId.value &&
                row.getString("path") == "GENUINE_SHARED_SQL_WORKFLOW_ORIGINAL_NATIVE_INTENT_DISPATCH")
        }
        val events = data.getJSONArray("events")
        val acknowledgements = (0 until events.length()).map { events.getJSONObject(it) }
            .filter { it.getString("step") == "ACTUAL_SHARED_SQL_CLIENT_ACKNOWLEDGED" }.map { it.getJSONObject("facts").getString("symbol") }
        check(acknowledgements.size == originals.size && acknowledgements.toSet() == NativeCafeCashStaging.actions.keys)
        check(rows.keys().asSequence().count { rows.getJSONObject(it).has("nativeSqlStage") } == originals.size)
    }

    /** Once-only backend call or native capture. Uncertain/lost replies stay pending. */
    fun markCall(symbol: String, path: String = "GENUINE_PROTECTED_EXECUTE") {
        val row = value.getJSONObject("requests").getJSONObject(symbol)
        check(row.getString("state") == "INPUT_RETAINED_BEFORE_CALL")
        row.put("state", "CALL_PENDING").put("path", path)
        persist()
    }
    fun observeReply(symbol: String, reply: MutationReply) {
        val row = value.getJSONObject("requests").getJSONObject(symbol)
        check(row.getString("state") == "CALL_PENDING")
        val observed = when (reply) {
            is MutationReply.Recorded -> cafeRecordedJson(reply)
            is MutationReply.Deferred -> JSONObject().put("kind", "DEFERRED").put("reason", reply.reason.name)
            is MutationReply.IdentityConflict -> JSONObject().put("kind", "IDENTITY_CONFLICT")
        }
        row.put("state", "REPLY_OBSERVED").put("reply", observed)
        persist()
    }

    /** Re-read DATA only; fresh production admission/session/lookup is still mandatory. */
    fun originals(): List<CafeSavedOriginal> {
        val rows = read().getJSONObject("requests")
        return rows.keys().asSequence().map { symbol ->
            val row = rows.getJSONObject(symbol)
            check(row.getString("state") == "REPLY_OBSERVED")
            val reply = row.getJSONObject("reply")
            check(reply.getString("kind") == "RECORDED" && reply.getBoolean("committed"))
            val scope = BranchScope(DomainId(row.getString("organizationId")), DomainId(row.getString("companyId")),
                DomainId(row.getString("branchId")))
            val actor = DomainId(row.getString("originalActorId"))
            val request = MutationRequest(OperationId(row.getString("operationId")),
                CommandKey(row.getString("command"), row.getInt("commandVersion")), cafeStored(row.getString("canonicalRequest")))
            val identity = OperationIdentity(scope.branchId, request.operationId, actor, 1, row.getString("semanticDigest"))
            check(identity.semanticSha256 == semanticDigest(scope, actor, request.command, request.payload).value)
            CafeSavedOriginal(symbol, request, identity, scope, DomainId(row.getString("originalDeviceId")),
                DomainId(row.getString("originalSessionId")), row.getLong("originalSessionGeneration"),
                Revision(row.getLong("originalPolicyRevision")), DomainId(row.getString("installationId")),
                DomainId(row.getString("assignmentId")), DomainId(row.getString("storageInstanceId")),
                Revision(row.getLong("authorityEpoch")), CafeRecordedResult.fromJson(reply))
        }.toList().sortedBy { it.result.sequence.value }.also { saved ->
            check(saved.map { it.request.operationId }.distinct().size == saved.size &&
                saved.map { it.result.sequence }.distinct().size == saved.size)
        }
    }
    fun binding(symbol: String): Any = read().getJSONObject("bindings").get(symbol)
    private fun read(): JSONObject = durable.openRead().use { JSONObject(it.bufferedReader().readText()) }
    private fun persist() {
        val bytes = value.toString().encodeToByteArray()
        try {
            val stream = durable.startWrite()
            try { stream.write(bytes); durable.finishWrite(stream) }
            catch (failure: Throwable) { durable.failWrite(stream); throw failure }
        } finally { bytes.fill(0) }
    }
    override fun toString(): String = "NativeCafeCashEvidence([REDACTED])"

    companion object {
        val unbound = listOf(
            "whole FCC-C-01, application completion and owner acceptance: NOT_QUALIFIED",
            "full Branch authentication/credential-journal composition, unselected setup staging, physical I/A staging and installed bilingual UI: UNBOUND; four SQL originals use shared native-journal staging",
            "admitted Client/RPC/partition, lost response, fault, revocation and other platform variants: UNBOUND",
            "human cash receipt/change, physically counted drawer and hardware/peripheral delivery: UNBOUND",
            "interactive presentation/instruction ID and actual instruction count: UNBOUND; native slot/source counts are not substitutes",
            "actual positive kitchen Send execution/capability: UNBOUND; current UNKNOWN is diagnostic only and no Send is invoked",
            "pre-close complete FOOD producer/original-event source proof: UNBOUND; public read/certificate/census DATA are not custody",
            "retained branch timezone/cutoff configuration, tax-document date and separate Client capture time: UNBOUND",
            "human owner action, installed UI-only close, process termination/crash and surviving-service campaign: UNBOUND",
        )
    }
}

internal data class CafeSavedOriginal(val symbol: String, val request: MutationRequest, val identity: OperationIdentity,
    val scope: BranchScope, val deviceId: DomainId, val sessionId: DomainId, val sessionGeneration: Long,
    val policyRevision: Revision, val installationId: DomainId, val assignmentId: DomainId,
    val storageInstanceId: DomainId, val authorityEpoch: Revision, val result: CafeRecordedResult)

/** No physical-start capability or credential crosses the original live-owner boundary. */
internal data class CafeRecordedResult(val operationId: OperationId, val actorId: DomainId, val committed: Boolean,
    val code: String, val result: StoredBytes, val authorityEpoch: Revision, val sequence: JournalSequence,
    val recordedAt: UtcInstant) {
    fun requireSame(reply: MutationReply.Recorded) {
        check(reply.operationId == operationId && reply.originalActorId == actorId && reply.committed == committed &&
            reply.code == code && reply.result == result && reply.authorityEpoch == authorityEpoch &&
            reply.sequence == sequence && reply.recordedAt == recordedAt) { "Exact original result/identity/time changed" }
    }
    companion object {
        fun fromJson(value: JSONObject) = CafeRecordedResult(OperationId(value.getString("operationId")),
            DomainId(value.getString("originalActorId")), value.getBoolean("committed"), value.getString("code"),
            cafeStored(value.getString("canonicalResult")), Revision(value.getLong("authorityEpoch")),
            JournalSequence(value.getLong("sequence")), UtcInstant.parseCanonical(value.getString("recordedAt")))
    }
}
internal fun cafeRecordedJson(reply: MutationReply.Recorded): JSONObject = JSONObject().put("kind", "RECORDED")
    .put("operationId", reply.operationId.value).put("originalActorId", reply.originalActorId.value)
    .put("committed", reply.committed).put("code", reply.code).put("canonicalResult", cafeBase64(reply.result))
    .put("authorityEpoch", reply.authorityEpoch.value).put("sequence", reply.sequence.value)
    .put("recordedAt", reply.recordedAt.canonicalString()).put("replayed", reply.replayed)
internal fun cafeBase64(value: StoredBytes): String {
    val bytes = value.copyBytes()
    return try { Base64.encodeToString(bytes, Base64.NO_WRAP) } finally { bytes.fill(0) }
}
internal fun cafeStored(value: String): StoredBytes {
    val bytes = Base64.decode(value, Base64.NO_WRAP)
    return try { StoredBytes(bytes) } finally { bytes.fill(0) }
}
internal fun cafeDigest(value: StoredBytes): PayloadDigest {
    val bytes = value.copyBytes()
    return try { sha256(bytes) } finally { bytes.fill(0) }
}
internal fun cafeNow(): UtcInstant = UtcInstant(Clock.System.now())
internal fun cafeScope(scope: BranchScope): String = listOf(scope.organizationId.value, scope.companyId.value,
    scope.branchId.value).joinToString("/")
