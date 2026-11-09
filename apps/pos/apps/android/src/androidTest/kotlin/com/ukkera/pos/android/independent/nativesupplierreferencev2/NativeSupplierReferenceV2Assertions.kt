package com.ukkera.pos.android.independent.nativesupplierreferencev2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import org.json.JSONObject

internal data class SupplierReferenceV2State(val names: LocalNamesV1, val active: Boolean,
    val reference: SupplierReferenceV2Vector?)

internal data class SupplierReferenceV2Step(val symbol: String, val version: Int,
    val after: SupplierReferenceV2State, val preservePreloadedReference: Boolean = false) {
    // A genuine current V1 update preserves the preceding V2 record and schema.
    val sourceSchema: String get() = if (version == 1 && symbol == "create_v1")
        LocalConfigurationProfile.schema(LocalConfigurationProfile.SUPPLIER) else LocalSupplierProfileV2.SCHEMA
}

/** Only the two accepted bounded selections; every other frozen variant remains unbound. */
internal fun supplierReferenceV2Steps(inputs: SupplierReferenceV2Inputs, leg: SupplierReferenceV2Leg): List<SupplierReferenceV2Step> =
    when (leg) {
        SupplierReferenceV2Leg.FULL_STATE -> listOf(
            SupplierReferenceV2Step("create_v1", 1, SupplierReferenceV2State(inputs.initialNames, true, null)),
            SupplierReferenceV2Step("set_reference", 2, SupplierReferenceV2State(inputs.initialNames, true, inputs.literal)),
            SupplierReferenceV2Step("names_only_v2", 2, SupplierReferenceV2State(inputs.editedNames, false, inputs.literal), true),
            SupplierReferenceV2Step("replace_reference", 2, SupplierReferenceV2State(inputs.editedNames, false, inputs.mixed)),
            SupplierReferenceV2Step("clear_reference", 2, SupplierReferenceV2State(inputs.editedNames, false, null)))
        SupplierReferenceV2Leg.CURRENT_V1_OMISSION -> listOf(
            SupplierReferenceV2Step("create_v2", 2, SupplierReferenceV2State(inputs.initialNames, true, inputs.literal)),
            SupplierReferenceV2Step("names_active_v1_omission", 1, SupplierReferenceV2State(inputs.editedNames, false, inputs.literal)),
            SupplierReferenceV2Step("clear_reference", 2, SupplierReferenceV2State(inputs.editedNames, false, null)))
    }

/** Actual protected V2 descriptor and record, including independently observed storage CAS. */
internal class SupplierReferenceV2Form(val descriptor: LocalActionForm, val record: LocalRecord?) {
    val reference: LocalRecordReference? get() = record?.reference
    private val scalars = descriptor.fields.filterIsInstance<LocalFormField.Scalar>()
    fun scalar(field: LocalField): LocalFormField.Scalar? = scalars.singleOrNull { it.field == field }
    val supplierId: DomainId get() = (checkNotNull(scalar(LocalField.SUPPLIER_ID)).initialValue as LocalScalarValue.Identifier).value
    val expectedStorageRevision: Revision? get() = (scalar(LocalField.EXPECTED_STORAGE_REVISION)?.initialValue as? LocalScalarValue.Integer)
        ?.let { Revision(it.value) }

    init {
        check(descriptor.version == 2 && descriptor.reservedOperationId == null && scalars.size == descriptor.fields.size)
        val required = setOf(LocalField.SUPPLIER_ID, LocalField.NEW_ITEM, LocalField.NAME_ENGLISH,
            LocalField.NAME_ARABIC, LocalField.ACTIVE, LocalField.SUPPLIER_REFERENCE) +
            if (record == null) emptySet() else setOf(LocalField.EXPECTED_STORAGE_REVISION)
        check(scalars.map { it.field }.toSet() == required)
        check(descriptor.action == if (record == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE)
        check((checkNotNull(scalar(LocalField.NEW_ITEM)).initialValue as LocalScalarValue.Flag).value == (record == null))
        check((expectedStorageRevision == null) == (record == null))
        check(checkNotNull(scalar(LocalField.SUPPLIER_REFERENCE)).let {
            !it.required && it.kind == LocalInputKind.TEXT && it.maximumCharacters == 256
        })
        reference?.let { check(it.kind == LocalRecordKind.SUPPLIER && it.id == supplierId && it.revision != null) }
    }

    fun requireState(expected: SupplierReferenceV2State) {
        check(record != null)
        check((checkNotNull(scalar(LocalField.NAME_ENGLISH)).initialValue as LocalScalarValue.Text).value == expected.names.english &&
            (checkNotNull(scalar(LocalField.NAME_ARABIC)).initialValue as LocalScalarValue.Text).value == expected.names.arabic &&
            (checkNotNull(scalar(LocalField.ACTIVE)).initialValue as LocalScalarValue.Flag).value == expected.active)
        val referenceField = checkNotNull(scalar(LocalField.SUPPLIER_REFERENCE))
        check(referenceField.initialValue == null || referenceField.initialValue is LocalScalarValue.Text)
        check((referenceField.initialValue as? LocalScalarValue.Text)?.value == expected.reference?.value)
        val referenceDisplay = record.values.single { it.label == referenceField.label }
        expected.reference?.let {
            check(referenceDisplay.value.english == it.code.original && referenceDisplay.value.arabic == it.code.original)
        }
        // Absence is qualified by the actual null preload and native Code/source read below,
        // not a guessed English/Arabic display word or a reference manufactured from ID/names.
    }

    fun intent(operation: OperationId, step: SupplierReferenceV2Step): LocalCommandIntent {
        check(step.version in 1..2)
        val values = scalars.mapNotNull { field ->
            // Explicit current V1 grammar selection; never a V2 failure fallback or old-binary claim.
            if (field.field == LocalField.SUPPLIER_REFERENCE && step.version == 1) null
            else LocalScalarInput(field.field, when (field.field) {
                LocalField.NAME_ENGLISH -> LocalScalarValue.Text(step.after.names.english)
                LocalField.NAME_ARABIC -> LocalScalarValue.Text(step.after.names.arabic)
                LocalField.ACTIVE -> LocalScalarValue.Flag(step.after.active)
                LocalField.SUPPLIER_REFERENCE -> if (step.preservePreloadedReference) {
                    checkNotNull(field.initialValue).also {
                        check((it as LocalScalarValue.Text).value == checkNotNull(step.after.reference).value)
                    }
                } else LocalScalarValue.Text(step.after.reference?.value.orEmpty())
                else -> checkNotNull(field.initialValue) { "The real protected supplier form input is incomplete" }
            })
        }
        check(values.any { it.field == LocalField.SUPPLIER_REFERENCE } == (step.version == 2))
        return LocalCommandIntent(operation, descriptor.action, step.version, reference, values)
    }

    /** Existing public canonical grammar bound to the frozen semantic input and actual CAS. */
    fun expectedRequest(operation: OperationId, step: SupplierReferenceV2Step): MutationRequest =
        if (step.version == 2) MutationRequest(operation, LocalSupplierProfileV2.COMMAND,
            LocalSupplierCodecV2.encode(LocalSupplierRequestV2(expectedStorageRevision,
                LocalSupplierV2(supplierId, step.after.names, step.after.active, step.after.reference?.code))))
        else MutationRequest(operation, LocalConfigurationProfile.COMMAND,
            LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ConfigureSupplier(expectedStorageRevision,
                LocalSupplierV1(supplierId, step.after.names, step.after.active))))
}

internal suspend fun supplierReferenceV2Form(session: BranchUiSession, supplierId: DomainId? = null): SupplierReferenceV2Form {
    val attachment = checkNotNull(session.attachmentKey)
    val page = (session.workflows.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
        reference = supplierId?.let { LocalRecordReference(LocalRecordKind.SUPPLIER, it) },
        purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.SUPPLIERS))) as? LocalReadResult.Available)?.page
        ?: error("The genuine protected V2 supplier form/detail is unavailable")
    check(session.isCurrent() && session.attachmentKey == attachment && page.nextCursor == null)
    val record = if (supplierId == null) {
        check(page.records.isEmpty()) { "A fresh supplier installation is not empty" }
        null
    } else page.records.single().also { check(it.reference.kind == LocalRecordKind.SUPPLIER && it.reference.id == supplierId) }
    return SupplierReferenceV2Form(page.forms.single {
        it.action == if (supplierId == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE
    }, record)
}

internal suspend fun requireSupplierReferenceV2Current(client: SupplierReferenceV2Client, session: BranchUiSession,
    symbol: String, evidence: SupplierReferenceV2Evidence): LocalSessionAttachmentKey {
    val current = session.current() as? LocalSessionCheckResult.Current
        ?: error("The original employee did not pass a fresh protected Current")
    val c = client.original.material.coordinates
    val attachment = checkNotNull(current.attachmentKey)
    check(client.original.isCurrent() && session.isCurrent() && client.signIns == 1 &&
        current.display == session.display && attachment == session.attachmentKey &&
        current.display.actorId == c.firstActorId && current.display.companyId == c.scope.companyId &&
        current.display.branchId == c.scope.branchId && current.display.workspaceMode == LocalSessionWorkspaceMode.SUPPLIER_ONLY)
    evidence.bind(symbol, JSONObject().put("actorId", current.display.actorId.value)
        .put("companyId", current.display.companyId.value).put("branchId", current.display.branchId?.value)
        .put("attachmentOwnerId", attachment.ownerId.value).put("attachmentGeneration", attachment.generation)
        .put("source", "actual gateway sign-in and protected Current; attachment is UI DATA, not native session ID"))
    return attachment
}

internal suspend fun requireSupplierReferenceV2Pending(session: BranchUiSession,
    expected: Set<OperationId>): List<LocalPendingOperation> {
    val result = session.workflows.pending() as? LocalPendingResult.Available
        ?: error("The actual original native pending journal is unavailable")
    check(result.nextCursor == null && result.operations.size == expected.size &&
        result.operations.map { it.operationId }.toSet() == expected)
    return result.operations
}

internal fun requireSupplierReferenceV2Applied(operation: OperationId, step: SupplierReferenceV2Step,
    supplierId: DomainId, received: LocalOperationResult): LocalOperationResult.Recorded {
    val actual = received as? LocalOperationResult.Recorded ?: error("The original supplier result is unresolved")
    val result = checkNotNull(actual.result)
    check(actual.operationId == operation && actual.decision == LocalRecordedDecision.APPLIED &&
        actual.originalAction == if (step.symbol.startsWith("create_")) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE)
    check(actual.food == null && actual.returnStock == null && result.reference.kind == LocalRecordKind.SUPPLIER &&
        result.reference.id == supplierId && result.reference.revision != null)
    if (step.version == 1) check(result.values.isEmpty()) { "Current V1 original result acquired a reference field" }
    else {
        val reference = result.values.single()
        check(reference.label.english.isNotBlank() && reference.label.arabic.isNotBlank())
        step.after.reference?.let {
            check(reference.value.english == it.code.original && reference.value.arabic == it.code.original)
        }
    }
    return actual.copy(result = result.copy(values = result.values.toList()))
}

/** Complete immutable application result DATA; only the replay observation may differ. */
internal fun requireSameSupplierReferenceV2Result(expected: LocalOperationResult.Recorded, actual: LocalOperationResult) {
    check(actual is LocalOperationResult.Recorded && actual.copy(replayed = expected.replayed) == expected) {
        "The original supplier application result changed or became unresolved"
    }
}

/** Detached observation from actual native OPERATION/JOURNAL bytes, never a MutationReply producer. */
internal data class SupplierReferenceV2Source(
    val canonicalResult: StoredBytes,
    val originalSupplierRecord: StoredBytes,
    val originalJournalEnvelope: StoredBytes,
    val sourceSchema: String,
    val storageRevision: Revision,
    val domainRevision: Revision,
    val authenticationId: DomainId,
    val policyRevision: Revision,
    val authorityEpoch: Long,
    val sequence: Long,
    val recordedAt: UtcInstant,
    val immutableRowsFingerprint: String,
)

internal class SupplierReferenceV2Original(val step: SupplierReferenceV2Step, val request: MutationRequest,
    val owner: LocalClientIdentity, val afterStorageRevision: Revision,
    val uiResult: LocalOperationResult.Recorded) {
    var source: SupplierReferenceV2Source? = null
        private set

    fun observeSource(observation: SupplierReferenceV2Source, evidence: SupplierReferenceV2Evidence) {
        check(observation.domainRevision == checkNotNull(uiResult.result).reference.revision &&
            observation.storageRevision == afterStorageRevision && observation.sourceSchema == step.sourceSchema)
        val before = source
        if (before != null) check(before == observation) { "An earlier own-cut original source/result changed" }
        else {
            source = observation
            evidence.bind("ORIGINAL_${step.symbol}", JSONObject().put("operationId", request.operationId.value)
                .put("originalActorId", owner.actorId.value).put("command", request.command.name)
                .put("commandVersion", request.command.version).put("authenticationId", observation.authenticationId.value)
                .put("policyRevision", observation.policyRevision.value).put("authorityEpoch", observation.authorityEpoch)
                .put("sequence", observation.sequence).put("recordedAt", observation.recordedAt.canonicalString())
                .put("storageRevision", observation.storageRevision.value).put("domainRevision", observation.domainRevision.value)
                .put("sourceSchema", observation.sourceSchema)
                .put("canonicalOriginalResult", supplierReferenceV2Base64(observation.canonicalResult.copyBytes()))
                .put("ownCutSupplierRecord", supplierReferenceV2Base64(observation.originalSupplierRecord.copyBytes()))
                .put("observation", "closed original native history after this step, before any later supplier mutation"))
        }
    }
}
