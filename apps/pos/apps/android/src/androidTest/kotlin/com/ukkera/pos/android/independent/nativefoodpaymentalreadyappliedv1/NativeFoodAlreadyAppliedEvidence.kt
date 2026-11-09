package com.ukkera.pos.android.independent.nativefoodpaymentalreadyappliedv1

import android.app.Application
import android.util.AtomicFile
import android.util.Base64
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.File
import kotlin.time.Clock
import org.json.JSONArray
import org.json.JSONObject

/** Detached synthetic-run comparison DATA only. The separate native employee journal
 * owns Stage/Submit/remembrance/ACK. This file never owns authority, credentials or native
 * provenance, and is not a serializer golden. Prior or uncertain evidence is not overwritten. */
internal class NativeFoodAlreadyAppliedEvidence(application: Application) : NativeLocalFixtureEvidence {
    private val file = File(application.noBackupFilesDir, "food-payment-already-applied-v1-f02-native-data.json")
    private val durable: AtomicFile
    private val value = JSONObject().put("design", "food-payment-already-applied-v1").put("fixture", "FAA-F02")
        .put("assertionSlices", JSONArray(listOf("FAA-002", "FAA-008"))).put("expectationVersion", "1.0.0")
        .put("scope", "GENUINE_ANDROID_SAME_DEVICE_NATIVE_BACKEND_AND_EXPLICIT_FINANCIAL_WORKFLOW")
        .put("status", "SETUP_PENDING").put("wholeCases", "NOT_QUALIFIED")
        .put("unbound", JSONArray(unbound)).put("bindings", JSONObject()).put("requests", JSONObject()).put("events", JSONArray())
    init {
        check(!File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
        check(file.createNewFile()) { "Preserve previous FAA evidence; this fixture needs a fresh isolated synthetic installation" }
        durable = AtomicFile(file)
        persist()
    }

    override fun bind(symbol: String, value: Any?) {
        check(value == null || value is String || value is Number || value is Boolean || value is JSONObject || value is JSONArray)
        val bindings = this.value.getJSONObject("bindings")
        val next = value ?: JSONObject.NULL
        if (bindings.has(symbol)) check(bindings.get(symbol).toString() == next.toString()) { "An original binding cannot be replaced" }
        else bindings.put(symbol, next)
        persist()
    }
    override fun event(step: String, facts: JSONObject) {
        value.getJSONArray("events").put(JSONObject().put("step", step).put("witnessedAtUtc", foodAlreadyAppliedNow().canonicalString())
            .put("facts", facts))
        persist()
    }
    fun status(next: String) { value.put("status", next); persist() }
    fun binding(symbol: String): Any = value.getJSONObject("bindings").get(symbol)

    fun freezeRequest(symbol: String, request: MutationRequest, view: LocalSessionView,
        original: AndroidOriginalLocalClient, nativeStage: Boolean = false) {
        val rows = value.getJSONObject("requests")
        val c = original.material.coordinates
        check(!rows.has(symbol) && request.actionApprovalId == null && original.isCurrent() &&
            view.scope == c.scope && view.deviceId == c.deviceId && view.deviceId == original.peer.deviceId)
        rows.put(symbol, JSONObject().put("state", "INPUT_PINNED_BEFORE_CALL").put("nativeStageRequested", nativeStage)
            .put("operationId", request.operationId.value).put("command", request.command.name).put("commandVersion", request.command.version)
            .put("canonicalRequestData", foodAlreadyAppliedBase64(request.payload))
            .put("semanticDigestData", semanticDigest(view.scope, view.actorId, request.command, request.payload).value)
            .put("organizationId", view.scope.organizationId.value).put("companyId", view.scope.companyId.value)
            .put("branchId", view.scope.branchId.value).put("originalActorId", view.actorId.value).put("originalDeviceId", view.deviceId.value)
            .put("originalAuthSessionId", view.sessionId.value).put("originalSessionGeneration", view.generation.value)
            .put("originalPolicyRevision", view.policy.revision.value).put("installationId", c.installationId.value)
            .put("assignmentId", c.assignmentId.value).put("storageInstanceId", c.storageInstanceId.value)
            .put("authorityEpoch", original.receipt.core.authorityEpoch.value)
            .put("canonicalEncodingQualification", "FAA-Q04_UNBOUND_PREPINNED_DATA_NOT_EXPECTED_GOLDEN"))
        persist()
    }
    fun staged(symbol: String, operationId: OperationId, action: String, formVersion: Int) {
        val row = value.getJSONObject("requests").getJSONObject(symbol)
        check(row.getString("state") == "INPUT_PINNED_BEFORE_CALL" && row.getBoolean("nativeStageRequested") &&
            row.getString("operationId") == operationId.value && !row.has("nativeStage"))
        row.put("nativeStage", JSONObject().put("operationId", operationId.value).put("action", action).put("formVersion", formVersion)
            .put("path", "ACTUAL_PUBLIC_FINANCIAL_WORKFLOW_ORIGINAL_NATIVE_EMPLOYEE_JOURNAL")
            .put("order", "STAGE_SEPARATE_OPERATION_WHILE_O_PENDING_THEN_FIRST_APPLY_THEN_SUBMIT_SEPARATE_OPERATION")
            .put("setupQualification", "FAA-Q02_BINDING_DATA_RACE_SETUP_NOT_INDEPENDENT_ORACLE"))
        persist()
    }
    fun markCall(symbol: String, path: String = "GENUINE_PROTECTED_EXECUTE") {
        val row = value.getJSONObject("requests").getJSONObject(symbol)
        check(row.getString("state") == "INPUT_PINNED_BEFORE_CALL")
        row.put("state", "CALL_PENDING").put("path", path)
        persist()
    }
    fun observeReply(symbol: String, reply: MutationReply) {
        val row = value.getJSONObject("requests").getJSONObject(symbol)
        check(row.getString("state") == "CALL_PENDING")
        val data = when (reply) {
            is MutationReply.Recorded -> foodAlreadyAppliedRecordedData(reply)
            is MutationReply.Deferred -> JSONObject().put("kind", "DEFERRED").put("reason", reply.reason.name)
            is MutationReply.IdentityConflict -> JSONObject().put("kind", "IDENTITY_CONFLICT")
        }
        row.put("state", "REPLY_OBSERVED").put("reply", data)
        persist()
    }

    /** Re-read ordinary DATA after actual close; no native staging/result is recreated. */
    fun originals(): List<FoodAlreadyAppliedSavedOriginal> {
        val rows = durable.openRead().use { JSONObject(it.bufferedReader().readText()).getJSONObject("requests") }
        return rows.keys().asSequence().map { symbol ->
            val row = rows.getJSONObject(symbol)
            check(row.getString("state") == "REPLY_OBSERVED")
            val result = row.getJSONObject("reply")
            check(result.getString("kind") == "RECORDED")
            val operation = OperationId(row.getString("operationId"))
            val actor = DomainId(row.getString("originalActorId"))
            val scope = BranchScope(DomainId(row.getString("organizationId")), DomainId(row.getString("companyId")),
                DomainId(row.getString("branchId")))
            val request = MutationRequest(operation, CommandKey(row.getString("command"), row.getInt("commandVersion")),
                foodAlreadyAppliedStored(row.getString("canonicalRequestData")))
            FoodAlreadyAppliedSavedOriginal(symbol, request, OperationIdentity(scope.branchId, operation, actor, 1,
                row.getString("semanticDigestData")), scope, DomainId(row.getString("originalDeviceId")),
                DomainId(row.getString("originalAuthSessionId")), SessionGeneration(row.getLong("originalSessionGeneration")),
                Revision(row.getLong("originalPolicyRevision")),
                DomainId(row.getString("installationId")), DomainId(row.getString("assignmentId")), DomainId(row.getString("storageInstanceId")),
                FoodAlreadyAppliedRecordedResult.fromJson(result), row.getBoolean("nativeStageRequested"))
        }.sortedBy { it.result.sequence.value }.toList()
    }

    private fun persist() {
        val output = durable.startWrite()
        try {
            output.write(value.toString().encodeToByteArray())
            durable.finishWrite(output)
        } catch (failure: Throwable) { durable.failWrite(output); throw failure }
    }
    companion object {
        val unbound = listOf(
            "FAA-Q01_COMPLETE_PUBLIC_SOURCE_QUALIFICATION_AND_PROJECTION",
            "FAA-Q02_PRESTAGED_SECOND_APPLY_IS_BINDING_DATA_NOT_INDEPENDENT_SETUP_RESOLUTION",
            "FAA-Q03_MINIMUM_PERMISSION_SET_AND_SCOPED_SUPERVISOR",
            "FAA-Q04_INDEPENDENT_CANONICAL_BYTE_GOLDENS",
            "LITERAL_NATIVE_ISSUED_SCOPE_ACTOR_DEVICE_AUTH_SESSION_UUID_VARIANTS",
            "LITERAL_CASH_MOVEMENT_UUID_NATIVE_MAPPING_NOT_A_NEW_ORACLE",
            "ISSUED_F01_AND_F03_OWNER_PRICE_RECIPE_TRANSITIONS",
            "ADMITTED_REMOTE_RPC_TRANSPORT_RECEIPT_AND_LOST_RESPONSE_ACK_VARIANTS",
            "OFFLINE_CASH_READ_REVOCATION_RESTORATION_AND_ALL_OTHER_AUTHORITY_VARIANTS",
            "PROCESS_CRASH_PERIPHERAL_PHYSICAL_HUMAN_EVENT_AND_OTHER_PLATFORM_CAMPAIGNS",
            "RETAINED_BRANCH_CALENDAR_AND_TAX_DOCUMENT_DATE_POLICY",
        )
    }
}

internal data class FoodAlreadyAppliedSavedOriginal(val symbol: String, val request: MutationRequest,
    val identity: OperationIdentity, val scope: BranchScope, val deviceId: DomainId, val authSessionId: DomainId,
    val sessionGeneration: SessionGeneration, val policyRevision: Revision, val installationId: DomainId,
    val assignmentId: DomainId, val storageInstanceId: DomainId,
    val result: FoodAlreadyAppliedRecordedResult, val nativeStaged: Boolean)

internal data class FoodAlreadyAppliedRecordedResult(val operationId: OperationId, val actorId: DomainId,
    val committed: Boolean, val code: String, val payload: StoredBytes, val epoch: Revision,
    val sequence: JournalSequence, val recordedAt: UtcInstant) {
    constructor(reply: MutationReply.Recorded) : this(reply.operationId, reply.originalActorId, reply.committed, reply.code,
        reply.result, reply.authorityEpoch, reply.sequence, reply.recordedAt)
    fun requireSame(reply: MutationReply.Recorded) {
        check(this == FoodAlreadyAppliedRecordedResult(reply)) { "The exact original result/actor/identity/time changed" }
    }
    companion object {
        fun fromJson(value: JSONObject) = FoodAlreadyAppliedRecordedResult(OperationId(value.getString("operationId")),
            DomainId(value.getString("originalActorId")), value.getBoolean("committed"), value.getString("code"),
            foodAlreadyAppliedStored(value.getString("canonicalResultData")), Revision(value.getLong("authorityEpoch")),
            JournalSequence(value.getLong("sequence")), UtcInstant.parseCanonical(value.getString("recordedAt")))
    }
}

internal fun foodAlreadyAppliedRecordedData(reply: MutationReply.Recorded) = JSONObject().put("kind", "RECORDED")
    .put("operationId", reply.operationId.value).put("originalActorId", reply.originalActorId.value).put("committed", reply.committed)
    .put("code", reply.code).put("canonicalResultData", foodAlreadyAppliedBase64(reply.result)).put("authorityEpoch", reply.authorityEpoch.value)
    .put("sequence", reply.sequence.value).put("recordedAt", reply.recordedAt.canonicalString()).put("replayed", reply.replayed)
internal fun foodAlreadyAppliedBase64(value: StoredBytes): String {
    val bytes = value.copyBytes()
    return try { Base64.encodeToString(bytes, Base64.NO_WRAP) } finally { bytes.fill(0) }
}
internal fun foodAlreadyAppliedStored(value: String): StoredBytes {
    val bytes = Base64.decode(value, Base64.NO_WRAP)
    return try { StoredBytes(bytes) } finally { bytes.fill(0) }
}
internal fun foodAlreadyAppliedDigest(value: StoredBytes): PayloadDigest {
    val bytes = value.copyBytes()
    return try { sha256(bytes) } finally { bytes.fill(0) }
}
internal fun foodAlreadyAppliedNow() = UtcInstant(Clock.System.now())
internal fun foodAlreadyAppliedScope(scope: BranchScope) = listOf(scope.organizationId.value, scope.companyId.value,
    scope.branchId.value).joinToString("/")
internal fun sameFoodAlreadyAppliedRequest(a: MutationRequest, b: MutationRequest) = a.operationId == b.operationId &&
    a.command == b.command && a.payload == b.payload && a.actionApprovalId == b.actionApprovalId
