package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.app.LocalArrivalActorSelectionV2
import com.ukkera.pos.app.LocalArrivalActorSelectionModeV2
import com.ukkera.pos.app.LocalArrivalApprovalModeV2
import com.ukkera.pos.app.LocalArrivalApprovalRouteV2
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.types.Revision

internal fun arrivalUiActionV2(action: LocalArrivalActionV1): LocalAction = when (action) {
    LocalArrivalActionV1.RECORD -> LocalAction.ARRIVAL_RECORD
    LocalArrivalActionV1.QUALIFY -> LocalAction.ARRIVAL_QUALIFY
    LocalArrivalActionV1.REJECT -> LocalAction.ARRIVAL_REJECT
    LocalArrivalActionV1.DISPATCH -> LocalAction.ARRIVAL_DISPATCH
}

internal fun arrivalPhysicalActionV2(action: LocalAction): LocalArrivalActionV1 = when (action) {
    LocalAction.ARRIVAL_RECORD -> LocalArrivalActionV1.RECORD
    LocalAction.ARRIVAL_QUALIFY -> LocalArrivalActionV1.QUALIFY
    LocalAction.ARRIVAL_REJECT -> LocalArrivalActionV1.REJECT
    LocalAction.ARRIVAL_DISPATCH -> LocalArrivalActionV1.DISPATCH
    else -> invalid()
}

private fun LocalArrivalSourceSelectionV2.uiCoordinate(): LocalArrivalSourceCoordinateV2 =
    LocalArrivalSourceCoordinateV2(kind, id, storageRevision, digest)

private fun LocalArrivalSourceCoordinateV2.storeCoordinate(): LocalArrivalSourceSelectionV2 =
    LocalArrivalSourceSelectionV2(kind, id, storageRevision, digest)

internal fun arrivalEvidenceOptionsV2(value: LocalArrivalPurchasingViewV2, requester: Revision): LocalArrivalEvidenceOptionsV2 =
    LocalArrivalEvidenceOptionsV2(
        value.policy.let { LocalArrivalPolicyCoordinateV2(it.scope, it.policyId, it.publicationId, it.publicationRevision,
            it.activePointer.uiCoordinate(), it.publication.uiCoordinate(), it.validFrom, it.validUntil) },
        value.catalog.let { LocalArrivalCatalogCoordinateV2(it.scope, it.catalogId, it.publicationId, it.revision,
            it.activePointer.uiCoordinate(), it.publication.uiCoordinate()) }, requester,
        value.rules.map { LocalArrivalRuleOptionV2(it.ruleId, arrivalUiActionV2(it.action), it.directOnly,
            it.minimumDocuments, it.maximumDocuments, it.media.map(PurchasingEvidenceMediaV1::valueOf),
            it.purposes.map(PurchasingEvidencePurposeV1::valueOf),
            LocalArrivalRetentionCoordinateV2(it.retention.classId, it.retention.revision),
            it.approvalRoute?.uiRouteV2(), it.ruleDetails) },
        value.documents.map { LocalArrivalOriginalCoordinateV2(it.scope, it.supplierId, it.documentId,
            it.storageRevision, it.exactBytesDigest, it.byteLength, PurchasingEvidenceMediaV1.valueOf(it.media),
            PurchasingEvidencePurposeV1.valueOf(it.purpose), it.parentDocumentId, it.parentHead?.uiCoordinate()) })

/** Converts the frozen complete user selection, not a current-row reconstruction. */
internal fun LocalArrivalEvidenceInputV2.storeSelection(observation: String): LocalArrivalEvidenceSelectionV2 =
    LocalArrivalEvidenceSelectionV2(observation,
        policy.let { LocalArrivalPolicySelectionV2(it.scope, it.policyId, it.publicationId, it.publicationRevision,
            it.activePointer.storeCoordinate(), it.publication.storeCoordinate(), it.validFrom, it.validUntil) },
        catalog.let { LocalArrivalCatalogSelectionV2(it.scope, it.catalogId, it.publicationId, it.revision,
            it.activePointer.storeCoordinate(), it.publication.storeCoordinate()) }, rule.ruleId,
        documents.map { LocalArrivalDocumentSelectionV2(it.scope, it.supplierId, it.documentId, it.storageRevision,
            it.exactBytesDigest, it.byteLength, it.media.name, it.purpose.name, it.parentDocumentId, it.parentHead?.storeCoordinate()) },
        LocalArrivalRetentionSelectionV2(retention.classId, retention.revision))

internal fun arrivalRuleMatchesV2(selected: LocalArrivalRuleOptionV2, actual: LocalArrivalRuleViewV2): Boolean =
    selected.ruleId == actual.ruleId && selected.action == arrivalUiActionV2(actual.action) &&
        selected.directOnly == actual.directOnly && selected.minimumDocuments == actual.minimumDocuments &&
        selected.maximumDocuments == actual.maximumDocuments && selected.media.map { it.name } == actual.media &&
        selected.purposes.map { it.name } == actual.purposes &&
        selected.retention == LocalArrivalRetentionCoordinateV2(actual.retention.classId, actual.retention.revision) &&
        selected.approvalRoute == actual.approvalRoute?.uiRouteV2() && selected.ruleDetails == actual.ruleDetails

private fun com.ukkera.pos.inventory.store.LocalArrivalActorSelectionV2.uiActorsV2(): LocalArrivalActorSelectionV2 =
    LocalArrivalActorSelectionV2(LocalArrivalActorSelectionModeV2.valueOf(mode.name), employeeIds.toList())

private fun com.ukkera.pos.inventory.store.LocalArrivalApprovalRouteV2.uiRouteV2(): LocalArrivalApprovalRouteV2 =
    LocalArrivalApprovalRouteV2(LocalArrivalApprovalModeV2.valueOf(mode.name), performers?.uiActorsV2(),
        requesters?.uiActorsV2(), approvers?.uiActorsV2(), namedEmployeeIds.toList())

internal fun arrivalApprovalModeLabelV2(rule: LocalArrivalRuleOptionV2): LocalText = when (rule.approvalRoute?.mode) {
    LocalArrivalApprovalModeV2.DIRECT -> text("Direct — current performer authority", "مباشر — صلاحية المنفذ الحالية")
    LocalArrivalApprovalModeV2.SEPARATE -> text("Separate — requester's command; distinct approver authenticates personally",
        "منفصل — أمر مقدم الطلب؛ يتوثق معتمد مختلف بنفسه")
    LocalArrivalApprovalModeV2.NAMED_SELF -> text("NamedSelf — separate approval with an explicit named equality exception; distinct eligible approvers remain allowed",
        "اعتماد ذاتي مسمى — اعتماد منفصل مع استثناء تساوي مسمى صريح؛ يبقى المعتمدون المختلفون المؤهلون مسموحين")
    null -> if (rule.directOnly) text("Direct only", "مباشر فقط") else text("Complete approval rule unavailable", "قاعدة الاعتماد الكاملة غير متاحة")
}

internal fun arrivalOriginalDescriptionV2(document: LocalArrivalOriginalCoordinateV2): String = buildString {
    append("Original ${document.documentId.value}; storage ${document.storageRevision.value}")
    append("\nOrganization ${document.scope.organizationId.value}; company ${document.scope.companyId.value}; branch ${document.scope.branchId.value}; supplier ${document.supplierId.value}")
    append("\n${document.media.name} / ${document.purpose.name}; ${document.byteLength} bytes; ${document.exactBytesDigest.value}")
    append(document.parentDocumentId?.let { "\nOriginal parent ${it.value}" } ?: "\nNot supplemental")
    document.parentHead?.let { append("\nParent head ${it.kind} / ${it.id.value} / storage ${it.storageRevision.value} / ${it.digest.value}") }
}

internal fun arrivalEvidenceFieldsV2(options: LocalArrivalEvidenceOptionsV2): List<LocalFormField> = listOf(
    scalar(LocalField.ARRIVAL_SELECTION_DIGEST, LocalInputKind.TEXT, LocalScalarValue.Text(options.equalityDigest.value),
        readOnly = true, maximum = 64),
    scalar(LocalField.ARRIVAL_SELECTION_DETAILS, LocalInputKind.MULTILINE, LocalScalarValue.Text(options.sourceDescription),
        readOnly = true, maximum = 4096),
    revisionField(LocalField.EXPECTED_REQUESTER_POLICY_REVISION, options.requesterPolicyRevision),
    choiceField(LocalField.ARRIVAL_RULE_ID, options.rules.map { rule -> LocalChoice(rule.ruleId.value, text(
        "${rule.ruleId.value} — ${rule.minimumDocuments}..${rule.maximumDocuments} originals; " +
            arrivalApprovalModeLabelV2(rule).english +
            "\nAllowed media ${rule.media.joinToString { it.name }}; purposes ${rule.purposes.joinToString { it.name }}" +
            "\nRetention ${rule.retention.classId.value} / revision ${rule.retention.revision.value}",
        "${rule.ruleId.value} — ${rule.minimumDocuments}..${rule.maximumDocuments} أصول؛ " +
            arrivalApprovalModeLabelV2(rule).arabic +
            "\nالوسائط المسموحة ${rule.media.joinToString { it.name }}؛ الأغراض ${rule.purposes.joinToString { it.name }}" +
            "\nالاحتفاظ ${rule.retention.classId.value} / المراجعة ${rule.retention.revision.value}")) }),
    choiceField(LocalField.ARRIVAL_RETENTION, options.rules.map { it.retention }.distinct().map { retention ->
        LocalChoice(retention.choiceCode, text("Class ${retention.classId.value}; revision ${retention.revision.value}",
            "الفئة ${retention.classId.value}؛ المراجعة ${retention.revision.value}")) }),
    choiceField(LocalField.ARRIVAL_DOCUMENT_SELECTION, listOf(
        LocalChoice("NONE", text("Explicitly choose zero optional originals", "اختيار صفر من الأصول الاختيارية صراحةً")),
        LocalChoice("SELECTED", text("Use the exact originals in my selected order", "استخدام الأصول الدقيقة بترتيب اختياري")))),
    scalar(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS, LocalInputKind.MULTI_REFERENCE, required = false,
        help = text("Select only the displayed exact manifests, in operation order. Use the v2 document-ID read control to discover other originals; no file is uploaded here.",
            "اختر فقط قوائم الأصول الدقيقة المعروضة بترتيب العملية. استخدم تحكم قراءة معرفات المستندات v2 لاكتشاف أصول أخرى؛ لا يُرفع ملف هنا.")).copy(
        references = options.documents.map { document -> LocalReferenceChoice(document.reference,
            literal(arrivalOriginalDescriptionV2(document))) }),
    scalar(LocalField.OBSERVATION_REFERENCE, LocalInputKind.TEXT, maximum = 160,
        help = text("Actual new physical observation/departure reference; never an attachment or authority.",
            "مرجع الملاحظة أو المغادرة الفعلية الجديدة؛ ليس مرفقًا أو صلاحيةً.")))
