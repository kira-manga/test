package com.ukkera.pos.android.independent.nativesupplierworkflowv1

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import org.json.JSONArray
import org.json.JSONObject

/** Fresh production Current, not a principal constructed from the fixture's display labels. */
internal suspend fun requireSupplierSession(client: NativeSupplierClient, session: BranchUiSession,
    symbol: String, evidence: NativeSupplierEvidence): LocalSessionView {
    val checked = session.current() as? LocalSessionCheckResult.Current
        ?: error("The application session did not pass a fresh protected Current")
    val actual = client.observer.currentView
    val coordinates = client.original.material.coordinates
    check(session.isCurrent() && client.original.isCurrent() && client.observer.signIns == 1 &&
        checked.display == session.display && checked.attachmentKey == session.attachmentKey &&
        actual.actorId == coordinates.firstActorId && actual.scope == coordinates.scope &&
        actual.deviceId == coordinates.deviceId && actual.login == NativeSupplierFixture.login &&
        checked.display.actorId == actual.actorId && checked.display.companyId == actual.scope.companyId &&
        checked.display.branchId == actual.scope.branchId)
    requireSupplierPolicy(NativeSupplierFixture.account(coordinates).policy, actual.policy)
    evidence.bind(symbol, JSONObject().put("sessionId", actual.sessionId.value)
        .put("generation", actual.generation.value).put("actorId", actual.actorId.value)
        .put("branchId", actual.scope.branchId.value).put("deviceId", actual.deviceId.value)
        .put("policyRevision", actual.policy.revision.value)
        .put("source", "actual gateway issuance plus protected Current before shared UI publication"))
    return actual
}

/** Named size-1 production query in the exact application session; never an SQL page. */
internal suspend fun requireSupplierRead(client: NativeSupplierClient, session: BranchUiSession,
    expected: LocalSupplierV1?, detail: Boolean, label: String, evidence: NativeSupplierEvidence,
    bindCut: String? = null): LocalConfigurationListItemV1? {
    check(session.isCurrent())
    val actualSession = client.observer.currentView
    val query = LocalConfigurationQueryV1(LocalConfigurationProfile.SUPPLIER,
        id = if (detail) checkNotNull(expected).id else null, limit = 1)
    val (reply, page) = client.observer.namedRead(query)
    check(session.isCurrent() && client.observer.currentView.sessionId == actualSession.sessionId &&
        page.kind == LocalConfigurationProfile.SUPPLIER && page.nextAfter == null)
    val row = if (expected == null) {
        check(page.items.isEmpty() && page.detail == null)
        null
    } else {
        val actual = page.items.single()
        check(actual.id == expected.id && actual.names == expected.names && actual.active == expected.active && actual.code == null)
        if (detail) check((page.detail as? LocalConfigurationDetailV1.Supplier)?.value == expected)
        else check(page.detail == null)
        actual
    }
    bindCut?.let { evidence.bind(it, reply.branchRevision.value) }
    evidence.event(label, JSONObject().put("entry", "${LocalConfigurationProfile.QUERY.name}/${LocalConfigurationProfile.QUERY.version}")
        .put("layer", "P protected same-device named query, not UI pagination or RPC")
        .put("sessionId", actualSession.sessionId.value).put("pageSize", query.limit)
        .put("detail", detail).put("branchRevision", reply.branchRevision.value)
        .put("observedAt", reply.observedAt.canonicalString()).put("nextAfter", JSONObject.NULL)
        .put("supplierIds", JSONArray(page.items.map { it.id.value }))
        .put("domainRevision", row?.revision?.value ?: JSONObject.NULL)
        .put("storageRevision", row?.storageRevision?.value ?: JSONObject.NULL))
    return row
}

/** The descriptor/reference were actually returned by the protected application workflow. */
internal class NativeSupplierForm(val descriptor: LocalActionForm, val reference: LocalRecordReference?) {
    private fun scalar(field: LocalField): LocalScalarValue? = descriptor.fields
        .filterIsInstance<LocalFormField.Scalar>().singleOrNull { it.field == field }?.initialValue

    val supplierId: DomainId get() = (scalar(LocalField.SUPPLIER_ID) as LocalScalarValue.Identifier).value
    val expectedStorageRevision: Revision? get() = (scalar(LocalField.EXPECTED_STORAGE_REVISION) as? LocalScalarValue.Integer)?.let { Revision(it.value) }

    init {
        check(descriptor.reservedOperationId == null && descriptor.fields.all { it is LocalFormField.Scalar })
        check(descriptor.action == if (reference == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE)
        check((scalar(LocalField.NEW_ITEM) as LocalScalarValue.Flag).value == (reference == null))
        check((expectedStorageRevision == null) == (reference == null))
        reference?.let { check(it.kind == LocalRecordKind.SUPPLIER && it.id == supplierId) }
    }

    /** Only the synthetic lower-boundary stale case uses this. It is explicitly NOT the UI
     * controller's ID generation or an editable stale-revision control in the installed app. */
    fun syntheticStaleIntent(): LocalCommandIntent {
        check(descriptor.action == LocalAction.SUPPLIER_UPDATE && expectedStorageRevision != null)
        val values = descriptor.fields.filterIsInstance<LocalFormField.Scalar>().map { field ->
            LocalScalarInput(field.field, when (field.field) {
                LocalField.NAME_ENGLISH -> LocalScalarValue.Text(NativeSupplierFixture.staleNames.english)
                LocalField.NAME_ARABIC -> LocalScalarValue.Text(NativeSupplierFixture.staleNames.arabic)
                LocalField.ACTIVE -> LocalScalarValue.Flag(true)
                else -> checkNotNull(field.initialValue) { "The original protected form input is incomplete" }
            })
        }
        return LocalCommandIntent(OperationId.random(), descriptor.action, descriptor.version, reference, values)
    }
}

internal suspend fun readSupplierForm(session: BranchUiSession, supplierId: DomainId? = null): NativeSupplierForm {
    val selected = supplierId?.let { LocalRecordReference(LocalRecordKind.SUPPLIER, it) }
    val page = (session.workflows.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING, reference = selected,
        purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.SUPPLIERS))) as? LocalReadResult.Available)?.page
        ?: error("The actual supplier command form is unavailable")
    val reference = if (supplierId == null) null else page.records.single { it.reference.id == supplierId }.reference
    return NativeSupplierForm(page.forms.single {
        it.action == if (supplierId == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE
    }, reference)
}

internal suspend fun requireSupplierPending(session: BranchUiSession, expected: Set<OperationId>): List<LocalPendingOperation> {
    val result = session.workflows.pending() as? LocalPendingResult.Available
        ?: error("The genuine original pending journal is unavailable")
    check(result.nextCursor == null && result.operations.map { it.operationId }.toSet() == expected && result.operations.size == expected.size)
    return result.operations
}

internal fun requireSupplierResult(original: NativeSupplierOriginal, received: LocalOperationResult,
    applied: Boolean, evidence: NativeSupplierEvidence): LocalOperationResult.Recorded {
    val actual = received as? LocalOperationResult.Recorded ?: error("The original supplier result is unresolved")
    val raw = checkNotNull(original.result)
    val typed = LocalConfigurationCodec.decodeResult(raw.result)
    val code = if (applied) "configuration.applied" else "configuration.stale_revision"
    check(raw.committed == applied && raw.code == if (applied) "committed" else code)
    check(typed.code == code && actual.operationId == original.operationId && actual.food == null && actual.returnStock == null &&
        actual.originalAction == original.input.action &&
        actual.decision == if (applied) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED)
    if (applied) {
        check(typed.id == original.input.supplierId && typed.revision != null)
        check(actual.result?.reference == LocalRecordReference(LocalRecordKind.SUPPLIER, checkNotNull(typed.id), typed.revision))
    } else check(typed.id == null && typed.revision == null && actual.result == null)
    val symbol = original.symbol.removePrefix("K_")
    evidence.bind("RESULT_$symbol", JSONObject().put("operationId", raw.operationId.value)
        .put("originalActorId", raw.originalActorId.value).put("committed", raw.committed).put("code", raw.code)
        .put("originalDomainRevision", typed.revision?.value ?: JSONObject.NULL)
        .put("authorityEpoch", raw.authorityEpoch.value).put("sequence", raw.sequence.value)
        .put("recordedAt", raw.recordedAt.canonicalString()).put("canonicalOriginalResult", supplierBase64(raw.result)))
    return actual
}

internal fun requireSameSupplierUiResult(expected: LocalOperationResult.Recorded, actual: LocalOperationResult) {
    check(actual is LocalOperationResult.Recorded && expected.operationId == actual.operationId &&
        expected.decision == actual.decision && expected.message == actual.message && expected.result == actual.result &&
        expected.food == actual.food && expected.returnStock == actual.returnStock &&
        expected.originalAction == actual.originalAction) { "The immutable original application result changed" }
}
