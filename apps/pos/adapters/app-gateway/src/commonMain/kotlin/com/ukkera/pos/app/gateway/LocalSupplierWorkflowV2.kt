package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal fun StoredClientIntentV1.isSupplierV2(): Boolean =
    CommandKey(commandName, commandVersion) == LocalSupplierProfileV2.COMMAND

internal fun supplierConfigurationRequestV2(action: LocalAction, request: MutationRequest): LocalSupplierRequestV2 {
    if (request.command != LocalSupplierProfileV2.COMMAND || action !in supplierConfigurationActions || request.actionApprovalId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val saved = try { LocalSupplierCodecV2.decode(request.payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    if (LocalSupplierCodecV2.encode(saved) != request.payload || saved.expectedStorageRevision?.value == 0L ||
        action != if (saved.expectedStorageRevision == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return saved
}

internal fun savedSupplierConfigurationRequestV2(entry: StoredClientIntentV1): LocalSupplierRequestV2 {
    if (entry.formVersion != 2 || entry.actionApproval != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val saved = supplierConfigurationRequestV2(entry.localAction, entry.request())
    try {
        entry.validate(entry.owner)
        entry.recorded?.let { retained ->
            val payload = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            supplierConfigurationOutcomeV2(saved, retained.committed, retained.code, payload)
        }
    } catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    return saved
}

internal fun supplierConfigurationRecordedV2(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalSupplierResultV2 {
    val saved = savedSupplierConfigurationRequestV2(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId || reply.physicalStart != null)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return supplierConfigurationOutcomeV2(saved, reply.committed, reply.code, reply.result)
}

private fun supplierConfigurationOutcomeV2(saved: LocalSupplierRequestV2, committed: Boolean,
    code: String, payload: StoredBytes): LocalSupplierResultV2 {
    val result = try { LocalSupplierCodecV2.decodeResult(payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    val canonical = journalJson.encodeToString(LocalSupplierResultV2.serializer(), result)
        .encodeToByteArray(throwOnInvalidSequence = true).useWiped { StoredBytes(it) }
    if (canonical != payload) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    if (committed) {
        if (code != "committed" || result.code != "configuration.applied" || result.id != saved.supplier.id ||
            result.revision != (saved.expectedStorageRevision ?: Revision(0)) || result.revision?.value == Long.MAX_VALUE ||
            result.reference != saved.supplier.reference)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    } else if (code != result.code || result.code !in setOf("configuration.stale_revision", "configuration.capacity_required") ||
        result.id != null || result.revision != null || result.reference != null)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return result
}

internal suspend fun prepareSupplierConfigurationV2(access: GatewayAccess, intent: LocalCommandIntent): MutationRequest {
    // This is an explicit form version, never a fallback from an unsupported reference write.
    if (intent.formVersion != 2 || intent.action !in supplierConfigurationActions ||
        !access.installed.has(LocalSupplierProfileV2.COMMAND) || !access.installed.has(LocalSupplierProfileV2.QUERY))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val input = InputValues(intent.values)
    val id = input.id(LocalField.SUPPLIER_ID)
    access.requireAction(intent.action, id)
    if (intent.lines.isNotEmpty() || intent.lineField != null || intent.arrivalEvidence != null) invalid(LocalField.LINES)
    val revision = input.configurationRevision()
    if ((intent.action == LocalAction.SUPPLIER_CREATE) != (revision == null)) invalid(LocalField.NEW_ITEM)
    val selected = intent.reference
    if (selected != null && (selected.kind != LocalRecordKind.SUPPLIER || selected.id != id || selected.parentId != null ||
            selected.revision?.let { it.value != Long.MAX_VALUE && it.next() == revision } != true))
        invalid(LocalField.EXPECTED_STORAGE_REVISION)
    val reference = input.optionalText(LocalField.SUPPLIER_REFERENCE)?.takeIf { it.isNotEmpty() }?.let {
        try { Code.exact(it) } catch (_: IllegalArgumentException) { invalid(LocalField.SUPPLIER_REFERENCE) }
    }
    val request = LocalSupplierRequestV2(revision, LocalSupplierV2(id, input.names(), input.flag(LocalField.ACTIVE), reference))
    input.finish()
    return MutationRequest(intent.operationId, LocalSupplierProfileV2.COMMAND, LocalSupplierCodecV2.encode(request))
}

/** The whole returned page is qualified before filtering or offering a revision-bound edit. */
private suspend fun supplierReadV2(access: GatewayAccess, query: LocalSupplierQueryV2): Pair<LocalSupplierPageV2, ReadReply.Data> {
    val reply = access.read(ReadRequest(LocalSupplierProfileV2.QUERY, LocalSupplierCodecV2.query(query)))
    val page = LocalSupplierCodecV2.decodePage(reply.payload)
    if (page.items.size > query.limit || page.items.map { it.id }.distinct().size != page.items.size ||
        page.items.any { it.revision.value == Long.MAX_VALUE || it.storageRevision != it.revision.next() } ||
        page.items.zipWithNext().any { (a, b) -> a.id.value >= b.id.value } ||
        query.after?.let { after -> page.items.any { it.id.value <= after.value } } == true ||
        // A byte-bounded V2 page can be shorter than the requested row limit. The
        // continuation must still name its final complete row, never an omitted one.
        page.nextAfter != null && (query.id != null || page.items.isEmpty() || page.nextAfter != page.items.lastOrNull()?.id))
        unavailable()
    val id = query.id
    if (id == null) {
        if (page.detail != null) unavailable()
    } else {
        if (page.items.size > 1 || page.items.any { it.id != id }) unavailable()
        val row = page.items.singleOrNull()
        if (row == null) {
            if (page.detail != null) unavailable()
        } else if (page.detail != LocalSupplierV2(row.id, row.names, row.active, row.reference)) unavailable()
    }
    return page to reply
}

internal suspend fun supplierWorkflowV2(access: GatewayAccess, query: LocalWorkflowQuery, after: DomainId?): LocalWorkflowPage {
    val (page, reply) = supplierReadV2(access, LocalSupplierQueryV2(query.reference?.id, after, 25))
    val records = page.items.filter { row ->
        query.search.isEmpty() || row.id.value == query.search || row.reference?.normalizedKey == query.search ||
            row.names.english.contains(query.search, ignoreCase = true) || row.names.arabic.contains(query.search)
    }.map { row ->
        LocalRecord(LocalRecordReference(LocalRecordKind.SUPPLIER, row.id, row.revision), names(row.names, row.id),
            if (row.active) text("Active", "نشط") else text("Inactive", "غير نشط"), listOf(
                recordValue(fieldLabel(LocalField.EXPECTED_STORAGE_REVISION), literal(row.storageRevision.value.toString())),
                recordValue(fieldLabel(LocalField.SUPPLIER_REFERENCE), supplierReferenceValue(row.reference))))
    }
    val selected = page.detail
    val row = selected?.let { supplier -> page.items.singleOrNull { it.id == supplier.id } }
    val action = if (selected == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE
    val forms = if (access.installed.has(LocalSupplierProfileV2.COMMAND) && access.allow(action, selected?.id) &&
        (selected != null || query.reference == null)) listOf(form(action,
        configurationBase(LocalField.SUPPLIER_ID, selected?.id, row?.storageRevision, selected?.names, selected?.active) +
            scalar(LocalField.SUPPLIER_REFERENCE, LocalInputKind.TEXT, selected?.reference?.original?.let(LocalScalarValue::Text),
                required = false, maximum = 256, help = text(
                    "Optional directory reference. Leading zeros, case and spaces are retained exactly. Empty clears the reference.",
                    "مرجع اختياري في الدليل. تُحفظ الأصفار البادئة وحالة الأحرف والمسافات كما هي. اتركه فارغًا لمسح المرجع.")),
        version = 2)) else emptyList()
    return LocalWorkflowPage(query.workflow, records, forms,
        nextCursor = if (query.reference == null) page.nextAfter?.let { "cfg|${LocalConfigurationProfile.SUPPLIER}|${it.value}" } else null,
        notice = text(
            "Current supplier page only; search matches names or an exact directory reference. Changes and original recovery require current Master permission. ${readCut(reply).english}",
            "صفحة الموردين الحالية فقط؛ يبحث المرشح في الأسماء أو المرجع الدقيق في الدليل. يتطلب التعديل والاسترداد الأصلي إذنًا ساريًا من الجهاز الرئيسي. ${readCut(reply).arabic}"))
}

internal fun supplierReferenceValue(reference: Code?): LocalText = reference?.original?.let(::literal)
    ?: text("Not recorded", "غير مسجل")
