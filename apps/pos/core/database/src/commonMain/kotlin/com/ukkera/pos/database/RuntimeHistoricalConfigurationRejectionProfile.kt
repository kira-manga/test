package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Revision

/** CLOSED local-data refusal candidate DATA. The installed
 * command producer proves the exact canonical subtype, first refusal, requirement,
 * result and resources from genuine native BEFORE rows and already joined originals.
 * A command/action/code marker supplies no business proof or current authority. */
internal enum class RuntimeHistoricalConfigurationRejectionProfile(val command: CommandKey) {
    CONFIGURATION(CommandKey("local.configuration.write", 1)),
    SUPPLIER_V2(CommandKey("local.configuration.write", 2)),
    PRICING(CommandKey("local.catalog.pricing", 1)),
    CATALOG_RELEASE(CommandKey("local.catalog.release.activate", 1)),
    DOCUMENT_BEGIN(CommandKey("purchasing.document.begin", 1)),
    DOCUMENT_CHUNK(CommandKey("purchasing.document.chunk", 1)),
    DOCUMENT_COMPLETE(CommandKey("purchasing.document.complete", 1)),
    ARRIVAL_RECORD(CommandKey("local.stock.arrival.record", 2)),
    ARRIVAL_QUALIFY(CommandKey("local.stock.arrival.qualify", 2)),
    ARRIVAL_REJECT(CommandKey("local.stock.arrival.reject", 2)),
    ARRIVAL_DISPATCH(CommandKey("local.stock.arrival.dispatch", 2)),
    CASH_CLOSING(CommandKey("sales.cash.control", 1)),
    FOOD_TABLE_ASSIGNMENT(CommandKey("sales.food.table.assignment", 1)),
    PAYMENT_CORRECTION(CommandKey("sales.payment-correction.write", 1));

    val rejectionCodes: Set<String> get() = when (this) {
        CONFIGURATION -> CONFIGURATION_CODES
        SUPPLIER_V2 -> SUPPLIER_V2_CODES
        PRICING -> PRICING_CODES
        CATALOG_RELEASE -> CATALOG_RELEASE_CODES
        DOCUMENT_BEGIN -> DOCUMENT_BEGIN_CODES
        DOCUMENT_CHUNK -> DOCUMENT_CHUNK_CODES
        DOCUMENT_COMPLETE -> DOCUMENT_COMPLETE_CODES
        ARRIVAL_RECORD, ARRIVAL_QUALIFY, ARRIVAL_REJECT, ARRIVAL_DISPATCH -> ARRIVAL_CODES
        CASH_CLOSING -> CASH_CLOSING_CODES
        FOOD_TABLE_ASSIGNMENT -> FOOD_TABLE_ASSIGNMENT_CODES
        PAYMENT_CORRECTION -> PAYMENT_CORRECTION_CODES
    }

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            matchesCommand(value) && when (this) {
                CONFIGURATION -> value.requirement.action in setOf(SecurityAction.WAREHOUSE_CONFIGURE,
                    SecurityAction.TENDER_CONFIGURE, SecurityAction.SUPPLIER_ADMINISTRATION, SecurityAction.CATALOG_ACTIVATE) &&
                    value.requirement.verb == PermissionVerb.ADMINISTER &&
                    value.requirement.reasonCode == "local.configuration" && hasNoLimit(value.requirement)
                SUPPLIER_V2 -> matchesSupplierV2Requirement(value.requirement)
                PRICING -> matchesPricingRequirement(value.requirement)
                CATALOG_RELEASE -> matchesCatalogReleaseActivationRequirement(value)
                DOCUMENT_BEGIN, DOCUMENT_CHUNK, DOCUMENT_COMPLETE -> matchesDocumentRequirement(value.requirement)
                ARRIVAL_RECORD, ARRIVAL_QUALIFY, ARRIVAL_REJECT, ARRIVAL_DISPATCH ->
                    RuntimeHistoricalUnpricedArrivalProfileV2.matches(value)
                CASH_CLOSING -> matchesCashClosingRequirement(value.requirement)
                FOOD_TABLE_ASSIGNMENT -> matchesFoodTableAssignmentRequirement(value.requirement)
                PAYMENT_CORRECTION -> matchesPaymentCorrectionRequirement(value.requirement)
            }

    /** Refusal codes remain bound to the exact original action/reason/limit shape.
     * Basis failure cannot accompany a successfully captured proportional requirement;
     * capacity comes after a valid basis. The producer separately proves that precedence. */
    fun acceptsRejection(value: RuntimeCommandAuthorizationEvidenceV1, code: String): Boolean = when (this) {
        CONFIGURATION -> code in CONFIGURATION_CODES
        SUPPLIER_V2 -> matchesCommand(value) && matchesSupplierV2Requirement(value.requirement) && code in SUPPLIER_V2_CODES
        PRICING -> matchesCommand(value) && matchesPricingRequirement(value.requirement) &&
            code in pricingCodes(value.requirement)
        CATALOG_RELEASE -> matchesCatalogReleaseActivationRequirement(value) && code in CATALOG_RELEASE_CODES
        DOCUMENT_BEGIN, DOCUMENT_CHUNK, DOCUMENT_COMPLETE -> matchesCommand(value) &&
            matchesDocumentRequirement(value.requirement) && code in rejectionCodes
        ARRIVAL_RECORD, ARRIVAL_QUALIFY, ARRIVAL_REJECT, ARRIVAL_DISPATCH ->
            matchesCommand(value) && RuntimeHistoricalUnpricedArrivalProfileV2.matches(value) && code in ARRIVAL_CODES
        CASH_CLOSING -> matchesCommand(value) && matchesCashClosingRequirement(value.requirement) && code in CASH_CLOSING_CODES
        FOOD_TABLE_ASSIGNMENT -> matchesCommand(value) && matchesFoodTableAssignmentRequirement(value.requirement) &&
            code in FOOD_TABLE_ASSIGNMENT_CODES
        PAYMENT_CORRECTION -> matchesCommand(value) && matchesPaymentCorrectionRequirement(value.requirement) &&
            code in PAYMENT_CORRECTION_CODES
    }

    /** Candidate only: V2 cannot borrow warehouse/catalog/tender requirements or refusal codes. */
    private fun matchesSupplierV2Requirement(value: RuntimeCommandRequirementV1): Boolean =
        value.action == SecurityAction.SUPPLIER_ADMINISTRATION && value.verb == PermissionVerb.ADMINISTER &&
            value.ownerId == null && value.reasonCode == "local.configuration" && hasNoLimit(value)

    /** Candidate DATA only. The correction producer independently proves the actual
     * original payment amount, immutable close and deterministic first refusal. */
    private fun matchesPaymentCorrectionRequirement(value: RuntimeCommandRequirementV1): Boolean =
        value.action == SecurityAction.PAYMENT_CLASSIFICATION_CORRECT && value.verb == PermissionVerb.PERFORM &&
            value.ownerId == null && value.reasonCode == "payment.classification.correct" &&
            hasMonetaryLimit(value) && value.amount?.minor?.let { it > 0 } == true

    /** Candidate only: the installed producer must prove the exact original order,
     * owner, binding and destination BEFORE sources and deterministic first refusal. */
    private fun matchesFoodTableAssignmentRequirement(value: RuntimeCommandRequirementV1): Boolean =
        value.action == SecurityAction.ORDER_EDIT && value.verb == PermissionVerb.PERFORM &&
            value.ownerId != null && value.reasonCode == "food.table.assignment" && hasNoLimit(value)

    /** Only counted-closing subtypes and retained combined-close compatibility are
     * candidates. The installed cash producer must
     * still prove the exact normalized subtype, actual custodian/amount/revision and first
     * refusal from the original native cut. Owner-private close/transfer requirements are
     * not interchangeable with unrestricted review or any configuration requirement. */
    private fun matchesCashClosingRequirement(value: RuntimeCommandRequirementV1): Boolean {
        if (value.verb != PermissionVerb.PERFORM) return false
        return when (value.reasonCode) {
            "cash.close.begin", "cash.close.cancel" -> value.action == SecurityAction.CASH_CLOSE &&
                value.ownerId != null && hasNoLimit(value)
            "cash.close.count", "cash.close.submit-counted", "cash.close.submit" ->
                value.action == SecurityAction.CASH_CLOSE && value.ownerId != null && hasMonetaryLimit(value)
            "cash.close.deliver" -> value.action == SecurityAction.CASH_TRANSFER &&
                value.ownerId != null && hasMonetaryLimit(value)
            "cash.safe.receive" -> value.action == SecurityAction.CASH_RECEIVE &&
                value.ownerId != null && hasMonetaryLimit(value)
            "cash.close.review" -> value.action == SecurityAction.CASH_REVIEW &&
                value.ownerId == null && hasNoLimit(value)
            else -> false
        }
    }

    /** This is only a closed candidate shape. The documentary producer must independently
     * derive the exact request subtype, original Begin actor/revision and first refusal
     * from native BEFORE records. Neither an action name nor a document ID proves that. */
    private fun matchesDocumentRequirement(value: RuntimeCommandRequirementV1): Boolean {
        val reason = when (this) {
            DOCUMENT_BEGIN -> "document.begin"
            DOCUMENT_CHUNK -> "document.chunk"
            DOCUMENT_COMPLETE -> "document.complete"
            else -> return false
        }
        return value.action in setOf(SecurityAction.PURCHASING_DOCUMENT_CAPTURE, SecurityAction.PURCHASING_DOCUMENT_ATTACH) &&
            value.verb == PermissionVerb.PERFORM && hasNoLimit(value) && value.reasonCode == reason
    }

    /** Only this pricing receipt requirement can consume a native publication cut.
     * The original normalized producer still proves the actual ReceivePolicy subtype. */
    fun matchesPricingReceiptRequirement(value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        this == PRICING && matchesCommand(value) && value.requirement.action == SecurityAction.CATALOG_ACTIVATE &&
            value.requirement.verb == PermissionVerb.ADMINISTER &&
            value.requirement.reasonCode == "catalog.receive_price_policy" &&
            value.requirement.documentRevision == Revision(0) && hasNoLimit(value.requirement)

    /** Only this distinct release activation requirement can consume its native publication cut.
     * The installed producer separately binds the catalog ID and expected active storage revision
     * to the whole normalized request and genuine BEFORE sources; this marker is not that proof. */
    fun matchesCatalogReleaseActivationRequirement(value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        this == CATALOG_RELEASE && matchesCommand(value) && value.requirement.action == SecurityAction.CATALOG_ACTIVATE &&
            value.requirement.verb == PermissionVerb.ADMINISTER &&
            value.requirement.reasonCode == "catalog.release.activate" && hasNoLimit(value.requirement)

    private fun matchesCommand(value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.scope == value.authorization.scope &&
            value.requirement.operationId == value.operationId && value.requirement.payloadDigest == value.semanticDigest &&
            (this == CASH_CLOSING || this == FOOD_TABLE_ASSIGNMENT || value.requirement.ownerId == null)

    private fun matchesPricingRequirement(value: RuntimeCommandRequirementV1): Boolean = when (value.action) {
        SecurityAction.CATALOG_ACTIVATE -> value.verb == PermissionVerb.ADMINISTER && hasNoLimit(value) &&
            value.reasonCode in PRICING_ADMINISTRATION_REASONS &&
            (value.reasonCode != "catalog.receive_price_policy" || value.documentRevision == Revision(0)) &&
            (value.reasonCode != "catalog.index_price_history" || value.documentId == value.scope.branchId)
        SecurityAction.BRANCH_PRICE_EDIT -> value.verb == PermissionVerb.PERFORM &&
            value.reasonCode.length in 1..96 &&
            value.reasonCode.all { it.code in 0x21..0x7e && (it.isLetterOrDigit() || it in "._-") } &&
            (hasNoLimit(value) || value.limitKind == "proportional" &&
                value.amount?.let { it.currency == Currency.EGP && it.minor >= 0 } == true &&
                value.baseAmount?.let { it.currency == Currency.EGP && it.minor > 0 } == true)
        else -> false
    }

    private fun pricingCodes(value: RuntimeCommandRequirementV1): Set<String> = when (value.action) {
        SecurityAction.CATALOG_ACTIVATE -> when (value.reasonCode) {
            "catalog.receive_price_policy" -> PRICING_RECEIPT_CODES
            "catalog.activate_price_policy" -> PRICING_ACTIVATION_CODES
            "catalog.index_price_history" -> PRICING_INDEX_CODES
            else -> emptySet()
        }
        SecurityAction.BRANCH_PRICE_EDIT -> when (value.limitKind) {
            "none" -> PRICING_CHANGE_WITHOUT_BASIS_CODES
            "proportional" -> PRICING_CHANGE_WITH_BASIS_CODES
            else -> emptySet()
        }
        else -> emptySet()
    }

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalConfigurationRejectionProfile? =
            entries.singleOrNull { it.command == command }

        private fun hasNoLimit(value: RuntimeCommandRequirementV1): Boolean =
            value.limitKind == "none" && value.amount == null && value.baseAmount == null

        private fun hasMonetaryLimit(value: RuntimeCommandRequirementV1): Boolean =
            value.limitKind == "monetary" && value.amount?.minor?.let { it >= 0 } == true && value.baseAmount == null

        private val FOOD_TABLE_ASSIGNMENT_CODES: Set<String> = setOf(
            "food.table.assignment.order_unavailable", "food.table.assignment.order_stale",
            "food.table.assignment.order_closed", "food.table.assignment.channel_required",
            "food.table.assignment.handoff_pending", "food.table.assignment.stale",
            "food.table.assignment.floor_required", "food.table.assignment.table_required",
            "food.table.assignment.table_floor_mismatch", "food.table.assignment.floor_stale",
            "food.table.assignment.table_stale", "food.table.assignment.inactive",
            "food.table.assignment.capacity_required",
        )

        private val CASH_CLOSING_CODES: Set<String> = setOf("sales.domain_rejected", "sales.profile_unavailable")
        private val PAYMENT_CORRECTION_CODES: Set<String> = setOf("sales.payment_correction.refused")

        private val SUPPLIER_V2_CODES: Set<String> = setOf("configuration.stale_revision", "configuration.capacity_required")

        private val CONFIGURATION_CODES: Set<String> = setOf("configuration.stale_revision",
            "warehouse.controlled_retirement_required", "configuration.capacity_required",
            "catalog.publication_required", "catalog.price_conflict", "catalog.price_version_changed",
            "catalog.stale_publication", "catalog.controlled_migration_required", "catalog.unit_definitions_required",
            "catalog.unit_precision_required", "catalog.serial_unit_required", "catalog.cross_dimension_unavailable",
            "catalog.conversion_evidence_invalid", "catalog.unit_definition_conflict", "catalog.conversion_identity_changed",
            "catalog.conversion_version_changed", "catalog.legacy_unit_identity", "catalog.price_history_index_required",
            "catalog.price_history_index_in_progress", "catalog.price_history_capacity_required",
            "catalog.price_history_conflict", "catalog.publication_already_installed", "catalog.duplicate_identifier")
        // Literal additive-v2 codes only. A code never proves a missing source, a first
        // refusal or capacity exhaustion: the installed typed producer must derive it
        // from the exact request and original native BEFORE cut before joining.
        private val ARRIVAL_CODES: Set<String> = setOf("arrival.unsupported_policy", "arrival.evidence_required",
            "arrival.evidence_unavailable", "arrival.source_missing", "arrival.source_already_used",
            "arrival.stale_revision", "arrival.supplier_unavailable", "arrival.item_unavailable",
            "arrival.warehouse_unavailable", "arrival.invalid_quantity", "arrival.invalid_unit",
            "arrival.invalid_tracking", "arrival.invalid_serial", "arrival.serial_registration_required",
            "arrival.invalid_lot", "arrival.known_metadata_immutable", "arrival.invalid_disposition",
            "arrival.wrong_recipient", "arrival.no_new_observation", "arrival.hydration_capacity_unavailable",
            "arrival.capacity_exceeded", "arrival.policy_mismatch", "arrival.approval_history_unavailable",
            "arrival.stale_requester_policy", "arrival.documentary_effects_unavailable")
        // The producer proves positive native membership, the complete sequence and every
        // actual BEFORE source before deriving the first refusal. Trust/history/sequence
        // failures and capacity saturation remain unavailable, never a known refusal here.
        private val CATALOG_RELEASE_CODES: Set<String> = setOf("catalog.release.stale_revision",
            "catalog.release.publication_already_used", "catalog.release.catalog_identity_changed",
            "catalog.release.stale_release", "catalog.release.member_source_changed")
        private val DOCUMENT_BEGIN_CODES: Set<String> = setOf("purchasing.document.identity_already_used",
            "purchasing.document.supplier_required", "purchasing.document.original_required",
            "purchasing.document.stale_revision", "purchasing.document.correction_source_required")
        private val DOCUMENT_CHUNK_CODES: Set<String> = setOf("purchasing.document.original_required",
            "purchasing.document.begin_operation_mismatch", "purchasing.document.original_actor_required",
            "purchasing.document.chunk_out_of_range", "purchasing.document.chunk_length_mismatch",
            "purchasing.document.chunk_integrity_mismatch", "purchasing.document.chunk_already_used")
        private val DOCUMENT_COMPLETE_CODES: Set<String> = setOf("purchasing.document.original_required",
            "purchasing.document.begin_operation_mismatch", "purchasing.document.original_actor_required",
            "purchasing.document.incomplete_original", "purchasing.document.integrity_mismatch",
            "purchasing.document.media_mismatch", "purchasing.document.stale_revision",
            "purchasing.document.attachment_capacity")
        private val PRICING_RECEIPT_CODES: Set<String> = setOf("catalog.company_policy_required",
            "catalog.policy_publication_already_received")
        private val PRICING_ADMINISTRATION_REASONS: Set<String> = setOf("catalog.receive_price_policy",
            "catalog.activate_price_policy", "catalog.index_price_history")
        private val PRICING_ACTIVATION_CODES: Set<String> = setOf("catalog.current_price_and_received_policy_required",
            "catalog.price_history_index_required", "catalog.stale_price_policy", "catalog.price_policy_version_conflict",
            "catalog.price_policy_basis_unit_mismatch", "catalog.price_policy_not_yet_effective", "catalog.price_policy_expired",
            "catalog.price_history_capacity_required")
        private val PRICING_CHANGE_BEFORE_BASIS_CODES: Set<String> = setOf("catalog.price_source_required",
            "catalog.price_history_index_required", "catalog.sku_unavailable", "catalog.stale_price_context",
            "catalog.price_context_not_effective", "catalog.price_policy_not_effective", "catalog.branch_prices_disabled",
            "catalog.no_branch_price_to_reset", "catalog.branch_price_expired")
        private val PRICING_CHANGE_BASIS_CODES: Set<String> = setOf("catalog.price_policy_basis_unit_mismatch",
            "catalog.zero_price_override_basis_unsupported", "catalog.price_override_basis_unrepresentable",
            "catalog.price_override_basis_required")
        // Action approvals have no recoverable original authority in this direct-only lane.
        private val PRICING_CHANGE_WITHOUT_BASIS_CODES: Set<String> = PRICING_CHANGE_BEFORE_BASIS_CODES + PRICING_CHANGE_BASIS_CODES
        private val PRICING_CHANGE_WITH_BASIS_CODES: Set<String> = PRICING_CHANGE_BEFORE_BASIS_CODES +
            "catalog.price_history_capacity_required"
        private val PRICING_INDEX_CODES: Set<String> = setOf("catalog.stale_history_index", "catalog.price_history_already_indexed",
            "catalog.price_history_source_changed", "catalog.price_history_capacity_required", "catalog.legacy_price_history_conflict")
        private val PRICING_CODES: Set<String> = PRICING_RECEIPT_CODES + PRICING_ACTIVATION_CODES +
            PRICING_CHANGE_WITHOUT_BASIS_CODES + PRICING_CHANGE_WITH_BASIS_CODES + PRICING_INDEX_CODES
    }
}
