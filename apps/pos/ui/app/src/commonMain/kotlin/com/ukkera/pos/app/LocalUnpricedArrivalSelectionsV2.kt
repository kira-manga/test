package com.ukkera.pos.app

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalCompanyPolicyFormatV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** Exact read selections only. These values neither authenticate sources nor grant an action. */
@Serializable
data class LocalArrivalSourceCoordinateV2(
    val kind: String,
    val id: DomainId,
    val storageRevision: Revision,
    val digest: PayloadDigest,
) {
    init { require(kind.length in 1..96 && kind.all { it in 'a'..'z' || it in '0'..'9' || it in "._/-" }) }
}

@Serializable
data class LocalArrivalPolicyCoordinateV2(
    val scope: BranchScope,
    val policyId: DomainId,
    val publicationId: DomainId,
    val publicationRevision: Revision,
    val activePointer: LocalArrivalSourceCoordinateV2,
    val publication: LocalArrivalSourceCoordinateV2,
    val validFrom: UtcInstant,
    val validUntil: UtcInstant?,
) {
    init {
        require(publicationRevision.value >= 1 && publication.id == publicationId)
        require(validUntil == null || validUntil > validFrom)
    }
}

@Serializable
data class LocalArrivalCatalogCoordinateV2(
    val scope: BranchScope,
    val catalogId: DomainId,
    val publicationId: DomainId,
    val revision: Revision,
    val activePointer: LocalArrivalSourceCoordinateV2,
    val publication: LocalArrivalSourceCoordinateV2,
) {
    init { require(publication.id == publicationId) }
}

@Serializable
data class LocalArrivalRetentionCoordinateV2(val classId: DomainId, val revision: Revision) {
    init { require(revision.value >= 1) }
    val choiceCode: String get() = "${classId.value}:${revision.value}"
}

/** Ordered original manifests, including an exact supplemental parent/head when supplied. */
@Serializable
data class LocalArrivalOriginalCoordinateV2(
    val scope: BranchScope,
    val supplierId: DomainId,
    val documentId: DomainId,
    val storageRevision: Revision,
    val exactBytesDigest: PayloadDigest,
    val byteLength: Int,
    val media: PurchasingEvidenceMediaV1,
    val purpose: PurchasingEvidencePurposeV1,
    val parentDocumentId: DomainId? = null,
    val parentHead: LocalArrivalSourceCoordinateV2? = null,
) {
    init {
        require(storageRevision.value >= 1 && byteLength > 0)
        require((parentDocumentId == null) == (parentHead == null))
        require(parentDocumentId == null || parentDocumentId != documentId)
    }

    val reference: LocalRecordReference get() = LocalRecordReference(
        LocalRecordKind.PURCHASING_DOCUMENT, documentId, storageRevision, parentDocumentId)
}

enum class LocalArrivalApprovalModeV2 { DIRECT, SEPARATE, NAMED_SELF }
enum class LocalArrivalActorSelectionModeV2 { CURRENT_BRANCH_GRANTEES, NAMED_EMPLOYEES }

/** A rule's complete named/current selection. Current grants remain a separate Master check. */
@Serializable
data class LocalArrivalActorSelectionV2(val mode: LocalArrivalActorSelectionModeV2,
    val employeeIds: List<DomainId> = emptyList()) {
    init {
        require(if (mode == LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES) employeeIds.isEmpty()
            else employeeIds.size in 1..LocalCompanyPolicyFormatV1.MAX_NAMED_EMPLOYEES &&
                employeeIds.zipWithNext().all { (a, b) -> a.value < b.value })
    }
}

/** NamedSelf keeps the separate route and adds only its explicitly named equality exception. */
@Serializable
data class LocalArrivalApprovalRouteV2(
    val mode: LocalArrivalApprovalModeV2,
    val performers: LocalArrivalActorSelectionV2? = null,
    val requesters: LocalArrivalActorSelectionV2? = null,
    val approvers: LocalArrivalActorSelectionV2? = null,
    val namedEmployeeIds: List<DomainId> = emptyList(),
) {
    init {
        if (mode == LocalArrivalApprovalModeV2.DIRECT) require(performers != null && requesters == null && approvers == null)
        else require(performers == null && requesters != null && approvers != null)
        if (mode == LocalArrivalApprovalModeV2.NAMED_SELF) {
            require(namedEmployeeIds.size in 1..LocalCompanyPolicyFormatV1.MAX_NAMED_EMPLOYEES &&
                namedEmployeeIds.zipWithNext().all { (a, b) -> a.value < b.value })
            listOfNotNull(requesters, approvers).forEach { selection ->
                require(selection.mode != LocalArrivalActorSelectionModeV2.NAMED_EMPLOYEES ||
                    namedEmployeeIds.all { it in selection.employeeIds })
            }
        } else require(namedEmployeeIds.isEmpty())
    }

    internal fun snapshot(): LocalArrivalApprovalRouteV2 = copy(
        performers = performers?.copy(employeeIds = performers.employeeIds.toList()),
        requesters = requesters?.copy(employeeIds = requesters.employeeIds.toList()),
        approvers = approvers?.copy(employeeIds = approvers.employeeIds.toList()),
        namedEmployeeIds = namedEmployeeIds.toList())
}

/** A documentary rule projection, not a replacement for the selected signed policy. */
class LocalArrivalRuleOptionV2(
    val ruleId: DomainId,
    val action: LocalAction,
    val directOnly: Boolean,
    val minimumDocuments: Int,
    val maximumDocuments: Int,
    media: List<PurchasingEvidenceMediaV1>,
    purposes: List<PurchasingEvidencePurposeV1>,
    val retention: LocalArrivalRetentionCoordinateV2,
    approvalRoute: LocalArrivalApprovalRouteV2? = null,
    /** Complete documentary signed-rule description; null is missing, never a default rule. */
    val ruleDetails: String? = null,
) {
    private val mediaEntries = media.toList()
    private val purposeEntries = purposes.toList()
    val media: List<PurchasingEvidenceMediaV1> get() = mediaEntries.toList()
    val purposes: List<PurchasingEvidencePurposeV1> get() = purposeEntries.toList()
    private val approvalEntries = approvalRoute?.snapshot()
    val approvalRoute: LocalArrivalApprovalRouteV2? get() = approvalEntries?.snapshot()

    init {
        require(action in localArrivalActionsV2)
        require(minimumDocuments in 0..maximumDocuments &&
            maximumDocuments in 1..LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS)
        require(mediaEntries.isNotEmpty() && mediaEntries.distinct().size == mediaEntries.size)
        require(purposeEntries.isNotEmpty() && purposeEntries.distinct().size == purposeEntries.size)
        require(approvalEntries == null || directOnly == (approvalEntries.mode == LocalArrivalApprovalModeV2.DIRECT))
        require(ruleDetails == null || ruleDetails.isNotEmpty() && ruleDetails.length <= 32_768 &&
            ruleDetails.encodeToByteArray(throwOnInvalidSequence = true).size <= 32_768)
    }
}

/** Fresh protected form sources. The digest is an equality-only refresh refusal pin. */
class LocalArrivalEvidenceOptionsV2(
    val policy: LocalArrivalPolicyCoordinateV2,
    val catalog: LocalArrivalCatalogCoordinateV2,
    val requesterPolicyRevision: Revision,
    rules: List<LocalArrivalRuleOptionV2>,
    documents: List<LocalArrivalOriginalCoordinateV2>,
) {
    private val ruleEntries = rules.toList()
    private val documentEntries = documents.toList()
    val rules: List<LocalArrivalRuleOptionV2> get() = ruleEntries.toList()
    val documents: List<LocalArrivalOriginalCoordinateV2> get() = documentEntries.toList()

    init {
        require(policy.scope == catalog.scope)
        require(ruleEntries.size <= LocalCompanyPolicyFormatV1.MAX_RULES &&
            ruleEntries.map { it.ruleId }.distinct().size == ruleEntries.size)
        require(documentEntries.size <= LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS &&
            documentEntries.map { it.documentId }.distinct().size == documentEntries.size &&
            documentEntries.all { it.scope == policy.scope })
    }

    val equalityDigest: PayloadDigest = sha256(arrivalSelectionJsonV2.encodeToString(
        ArrivalOptionsWireV2.serializer(), ArrivalOptionsWireV2(policy, catalog, requesterPolicyRevision,
            ruleEntries.map { it.wire() }, documentEntries)).encodeToByteArray())

    val sourceDescription: String get() = arrivalSourceDescriptionV2(policy, catalog, requesterPolicyRevision)
}

/** Captured from the user's actual form selection before staging; never rehydrated at save. */
class LocalArrivalEvidenceInputV2(
    val policy: LocalArrivalPolicyCoordinateV2,
    val catalog: LocalArrivalCatalogCoordinateV2,
    val requesterPolicyRevision: Revision,
    val rule: LocalArrivalRuleOptionV2,
    val retention: LocalArrivalRetentionCoordinateV2,
    documents: List<LocalArrivalOriginalCoordinateV2>,
    val formSourceDigest: PayloadDigest,
) {
    private val documentEntries = documents.toList()
    val documents: List<LocalArrivalOriginalCoordinateV2> get() = documentEntries.toList()

    init {
        require(policy.scope == catalog.scope && retention == rule.retention)
        require(documentEntries.size <= LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS &&
            documentEntries.map { it.documentId }.distinct().size == documentEntries.size &&
            documentEntries.all { it.scope == policy.scope })
    }

    val sourceDescription: String get() = arrivalSourceDescriptionV2(policy, catalog, requesterPolicyRevision)

    /** Identity of the complete supplied carrier, not its caller-provided refresh pin. */
    val equalityDigest: PayloadDigest = sha256(encodedInput().encodeToByteArray(throwOnInvalidSequence = true))

    internal fun inputBytes(): Int = encodedInput().encodeToByteArray(throwOnInvalidSequence = true).size
    private fun encodedInput(): String = arrivalSelectionJsonV2.encodeToString(ArrivalInputWireV2.serializer(),
        ArrivalInputWireV2(policy, catalog, requesterPolicyRevision, rule.wire(), retention,
            documentEntries, formSourceDigest))
}

internal val localArrivalActionsV2: Set<LocalAction> = setOf(LocalAction.ARRIVAL_RECORD,
    LocalAction.ARRIVAL_QUALIFY, LocalAction.ARRIVAL_REJECT, LocalAction.ARRIVAL_DISPATCH)

internal class LocalArrivalSelectionInputFailure(val field: LocalField) : IllegalArgumentException()

/** Uses only the currently displayed form's complete coordinates and explicit picker order. */
internal fun localArrivalEvidenceInputV2(form: LocalActionForm, values: List<LocalScalarInput>): LocalArrivalEvidenceInputV2? {
    val options = form.arrivalEvidence
    if (form.version != 2 || form.action !in localArrivalActionsV2) {
        require(options == null)
        return null
    }
    fun fail(field: LocalField): Nothing = throw LocalArrivalSelectionInputFailure(field)
    val actual = options ?: fail(LocalField.ARRIVAL_SELECTION_DIGEST)
    val inputs = values.associate { it.field to it.value }
    if ((inputs[LocalField.ARRIVAL_SELECTION_DIGEST] as? LocalScalarValue.Text)?.value != actual.equalityDigest.value ||
        (inputs[LocalField.ARRIVAL_SELECTION_DETAILS] as? LocalScalarValue.Text)?.value != actual.sourceDescription)
        fail(LocalField.ARRIVAL_SELECTION_DIGEST)
    if ((inputs[LocalField.EXPECTED_REQUESTER_POLICY_REVISION] as? LocalScalarValue.Integer)?.value !=
        actual.requesterPolicyRevision.value) fail(LocalField.EXPECTED_REQUESTER_POLICY_REVISION)
    val ruleCode = (inputs[LocalField.ARRIVAL_RULE_ID] as? LocalScalarValue.Choice)?.code ?: fail(LocalField.ARRIVAL_RULE_ID)
    val rule = actual.rules.singleOrNull { it.ruleId.value == ruleCode && it.action == form.action }
        ?: fail(LocalField.ARRIVAL_RULE_ID)
    if (!rule.directOnly && (rule.approvalRoute == null || rule.ruleDetails == null)) fail(LocalField.ARRIVAL_RULE_ID)
    val retentionCode = (inputs[LocalField.ARRIVAL_RETENTION] as? LocalScalarValue.Choice)?.code
        ?: fail(LocalField.ARRIVAL_RETENTION)
    if (retentionCode != rule.retention.choiceCode) fail(LocalField.ARRIVAL_RETENTION)
    val references = when (val supplied = inputs[LocalField.ARRIVAL_ORIGINAL_DOCUMENTS]) {
        null -> emptyList()
        is LocalScalarValue.References -> supplied.values
        else -> fail(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS)
    }
    val mode = (inputs[LocalField.ARRIVAL_DOCUMENT_SELECTION] as? LocalScalarValue.Choice)?.code
        ?: fail(LocalField.ARRIVAL_DOCUMENT_SELECTION)
    if (mode != "NONE" && mode != "SELECTED" || (mode == "NONE") != references.isEmpty())
        fail(LocalField.ARRIVAL_DOCUMENT_SELECTION)
    val documents = references.map { reference -> actual.documents.singleOrNull { it.reference == reference }
        ?: fail(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS) }
    if (documents.size !in rule.minimumDocuments..rule.maximumDocuments ||
        documents.any { it.media !in rule.media || it.purpose !in rule.purposes }) fail(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS)
    return LocalArrivalEvidenceInputV2(actual.policy, actual.catalog, actual.requesterPolicyRevision,
        rule, rule.retention, documents, actual.equalityDigest)
}

private val arrivalSelectionJsonV2 = Json { encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable
@OptIn(ExperimentalSerializationApi::class)
private data class ArrivalRuleWireV2(val ruleId: DomainId, val action: String, val directOnly: Boolean,
    val minimumDocuments: Int, val maximumDocuments: Int, val media: List<PurchasingEvidenceMediaV1>,
    val purposes: List<PurchasingEvidencePurposeV1>, val retention: LocalArrivalRetentionCoordinateV2,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val approvalRoute: LocalArrivalApprovalRouteV2? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ruleDetails: String? = null)

private fun LocalArrivalRuleOptionV2.wire(): ArrivalRuleWireV2 = ArrivalRuleWireV2(ruleId, action.name,
    directOnly, minimumDocuments, maximumDocuments, media, purposes, retention, approvalRoute, ruleDetails)

@Serializable
private data class ArrivalOptionsWireV2(val policy: LocalArrivalPolicyCoordinateV2,
    val catalog: LocalArrivalCatalogCoordinateV2, val requesterPolicyRevision: Revision,
    val rules: List<ArrivalRuleWireV2>, val documents: List<LocalArrivalOriginalCoordinateV2>)

@Serializable
private data class ArrivalInputWireV2(val policy: LocalArrivalPolicyCoordinateV2,
    val catalog: LocalArrivalCatalogCoordinateV2, val requesterPolicyRevision: Revision,
    val rule: ArrivalRuleWireV2, val retention: LocalArrivalRetentionCoordinateV2,
    val documents: List<LocalArrivalOriginalCoordinateV2>, val formSourceDigest: PayloadDigest)

private fun arrivalSourceDescriptionV2(policy: LocalArrivalPolicyCoordinateV2,
    catalog: LocalArrivalCatalogCoordinateV2, requester: Revision): String = buildString {
    fun source(label: String, value: LocalArrivalSourceCoordinateV2) {
        append("\n$label: ${value.kind} / ${value.id.value} / storage ${value.storageRevision.value} / ${value.digest.value}")
    }
    append("Organization ${policy.scope.organizationId.value}; company ${policy.scope.companyId.value}; branch ${policy.scope.branchId.value}")
    append("\nRequester employee-policy revision ${requester.value}")
    append("\nPurchasing policy ${policy.policyId.value}; publication ${policy.publicationId.value}; revision ${policy.publicationRevision.value}")
    append("\nValid ${policy.validFrom.canonicalString()} until ${policy.validUntil?.canonicalString() ?: "no supplied end"}")
    source("Policy active pointer", policy.activePointer)
    source("Policy publication", policy.publication)
    append("\nCatalog ${catalog.catalogId.value}; publication ${catalog.publicationId.value}; revision ${catalog.revision.value}")
    source("Catalog active pointer", catalog.activePointer)
    source("Catalog publication", catalog.publication)
}.also { require(it.length <= 4096) }
