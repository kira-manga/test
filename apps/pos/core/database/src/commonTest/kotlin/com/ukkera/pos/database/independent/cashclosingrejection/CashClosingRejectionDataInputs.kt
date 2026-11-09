package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** CCJR-v1 semantic carriers only. No native original, writer, grant, command or cash state
 * is created. UUIDs, revisions, monotonic coordinates and hexadecimal markers are inert
 * representation choices, not issued identity, original time or authenticated fingerprints. */
internal object CashClosingRejectionDataInputs {
    val scope = BranchScope(id("10000000", 1), id("20000000", 1), id("30000000", 1))
    val otherOrganization = id("10000000", 2)
    val otherCompany = id("20000000", 2)
    val otherBranch = id("30000000", 2)
    val employee = id("40000000", 1) // E_A
    val otherEmployee = id("40000000", 2) // E_B
    val reviewer = id("40000000", 3) // E_R
    val receiver = id("40000000", 4) // E_S
    val cashSession = id("63000000", 1) // S1; not an employee authentication session.
    val transfer = id("74000000", 1) // T1; no actual transfer or receipt is supplied.
    val operation = OperationId(id("80000000", 1).value)
    val otherOperation = OperationId(id("80000000", 2).value)
    val approvalLocator = id("90000000", 1) // An unissued locator, never approval authority.
    val semanticDigest = marker('1')
    val otherDigest = marker('2')

    val opening = money(100_000)
    val received = money(500_000)
    val changeGiven = money(40_000)
    val refundsPaid = money(20_000)
    val payIn = money(10_000)
    val payOut = money(15_000)
    val safeDrop = money(300_000)
    val externalTender = money(400_000)
    val firstCount = money(235_500)
    val finalCount = money(236_000)
    val retainedFloat = money(50_000)
    val delivered = money(186_000)

    /** Seven counted phases plus the retained combined-submit compatibility shape.
     * Amounts are fixture DATA, not a proved action-specific native limit calculation. */
    val shapes: List<CashClosingRequirementData> = listOf(
        CashClosingRequirementData("BeginClosing", "CCJR-001", SecurityAction.CASH_CLOSE,
            "cash.close.begin", employee, employee, cashSession, null),
        CashClosingRequirementData("CancelClosing", "CCJR-002", SecurityAction.CASH_CLOSE,
            "cash.close.cancel", employee, employee, cashSession, null),
        CashClosingRequirementData("RecordClosingCount", "CCJR-003", SecurityAction.CASH_CLOSE,
            "cash.close.count", employee, employee, cashSession, finalCount),
        CashClosingRequirementData("DeliverCountedClose", "CCJR-004", SecurityAction.CASH_TRANSFER,
            "cash.close.deliver", employee, employee, cashSession, delivered),
        CashClosingRequirementData("SubmitCountedClose", "CCJR-005", SecurityAction.CASH_CLOSE,
            "cash.close.submit-counted", employee, employee, cashSession, finalCount),
        CashClosingRequirementData("Review", "CCJR-006", SecurityAction.CASH_REVIEW,
            "cash.close.review", reviewer, null, cashSession, null),
        CashClosingRequirementData("ReceiveSafe", "CCJR-028", SecurityAction.CASH_RECEIVE,
            "cash.safe.receive", receiver, receiver, transfer, delivered),
        CashClosingRequirementData("SubmitClose", "CCJR-007", SecurityAction.CASH_CLOSE,
            "cash.close.submit", employee, employee, cashSession, finalCount),
    )

    fun requirement(shape: CashClosingRequirementData): RuntimeCommandRequirementV1 = RuntimeCommandRequirementV1(
        action = shape.action, verb = PermissionVerb.PERFORM, scope = scope,
        documentId = shape.documentId, documentRevision = Revision(1),
        payloadDigest = semanticDigest, operationId = operation, reasonCode = shape.reason,
        limitKind = if (shape.amount == null) "none" else "monetary",
        amount = shape.amount, baseAmount = null, ownerId = shape.owner,
    )

    fun evidence(shape: CashClosingRequirementData = shapes.first()): RuntimeCommandAuthorizationEvidenceV1 =
        RuntimeCommandAuthorizationEvidenceV1(
            purposeTag = RuntimeWritePurposeV2.COMMAND_REJECT.tag,
            operationId = operation, commandName = "sales.cash.control", commandVersion = 1,
            semanticDigest = semanticDigest, requirement = requirement(shape),
            authorization = snapshot(shape.actor), actionApprovalId = null,
            nativeRecordDigest = marker('e'), capturedChanges = 5,
        )

    fun snapshot(actor: DomainId = employee): RuntimeCommandAuthorizationSnapshotV1 =
        RuntimeCommandAuthorizationSnapshotV1(
            scope = scope, actorId = actor, sessionId = id("60000000", 1),
            deviceId = id("50000000", 1), generation = SessionGeneration(1),
            principalKind = PrincipalKind.LOCAL_EMPLOYEE,
            authenticationPurpose = AuthenticationPurpose.INTERACTIVE,
            accountRevision = Revision(1), credentialRevision = Revision(2),
            admissionRevision = Revision(3), policyRevision = Revision(4),
            access = RuntimeCommandAccessWindowV1(
                id("a3000000", 1), MonotonicMoment(id("a4000000", 1), 1_000),
                MonotonicMoment(id("a4000000", 1), 2_000), 60_000, Revision(2), Revision(3),
            ),
            dependencies = RuntimeCommandAuthorizationDependenciesV1(
                marker('d'), marker('e'), marker('f'), marker('0'),
            ),
        )

    /** Constructor-valid header DATA; there is no payload, original intent or mutation set.
     * Five is only a census candidate, not evidence that five permitted rows were captured. */
    fun header(): RuntimeHistoryEventHeaderV2 = RuntimeHistoryEventHeaderV2(
        scope = listOf(scope.organizationId, scope.companyId, scope.branchId).joinToString("/") { it.value },
        storageId = id("b1000000", 1), bindingDigest = marker('3'),
        originalAllocationDigest = marker('4'), definitionDigest = marker('5'),
        installationAttemptId = id("b2000000", 1), installationAttemptSequence = 1,
        installationGenesisDigest = marker('6'), sequence = 2,
        parentKind = RuntimeHistoryParentKindV2.RECORDED_EVENT.tag, parentSequence = 1,
        parentDigest = marker('7'), transactionId = id("b3000000", 1), assignmentId = id("b4000000", 1),
        purpose = RuntimeWritePurposeV2.COMMAND_REJECT, commandEpoch = 1, commandSequence = 1,
        commandDigest = semanticDigest, roleCommitment = marker('8'), recoveryCommitment = marker('9'),
        changeCount = 5, chunkCount = 1, imageBytes = 0, intentBytes = 1, intentDigest = marker('a'),
        dependencyBytes = 0, dependencyDigest = marker('b'), factBytes = 0, factDigest = marker('c'),
    )

    /** Fixed controls for the unchanged owner-null guard in the ten original CCJR-v1 profiles.
     * This is not an enumeration of present or future noncash profiles. These are classifier
     * controls only, not new noncash workflow or historical rejection fixtures. */
    fun nonCashOwnerNullCandidates(): List<Pair<RuntimeHistoricalConfigurationRejectionProfile,
        RuntimeCommandAuthorizationEvidenceV1>> = listOf(
            RuntimeHistoricalConfigurationRejectionProfile.CONFIGURATION,
            RuntimeHistoricalConfigurationRejectionProfile.PRICING,
            RuntimeHistoricalConfigurationRejectionProfile.CATALOG_RELEASE,
            RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_BEGIN,
            RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_CHUNK,
            RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_COMPLETE,
            RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_RECORD,
            RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_QUALIFY,
            RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_REJECT,
            RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_DISPATCH,
        ).map { profile ->
            val action: SecurityAction
            val verb: PermissionVerb
            val reason: String
            when (profile) {
                RuntimeHistoricalConfigurationRejectionProfile.CONFIGURATION -> {
                    action = SecurityAction.WAREHOUSE_CONFIGURE; verb = PermissionVerb.ADMINISTER
                    reason = "local.configuration"
                }
                RuntimeHistoricalConfigurationRejectionProfile.PRICING -> {
                    action = SecurityAction.CATALOG_ACTIVATE; verb = PermissionVerb.ADMINISTER
                    reason = "catalog.activate_price_policy"
                }
                RuntimeHistoricalConfigurationRejectionProfile.CATALOG_RELEASE -> {
                    action = SecurityAction.CATALOG_ACTIVATE; verb = PermissionVerb.ADMINISTER
                    reason = "catalog.release.activate"
                }
                RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_BEGIN,
                RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_CHUNK,
                RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_COMPLETE -> {
                    action = SecurityAction.PURCHASING_DOCUMENT_CAPTURE; verb = PermissionVerb.PERFORM
                    reason = when (profile) {
                        RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_BEGIN -> "document.begin"
                        RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_CHUNK -> "document.chunk"
                        else -> "document.complete"
                    }
                }
                RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_RECORD,
                RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_QUALIFY,
                RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_REJECT,
                RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_DISPATCH -> {
                    action = SecurityAction.INVENTORY_ADJUST; verb = PermissionVerb.PERFORM
                    reason = "arrival.fixture"
                }
                RuntimeHistoricalConfigurationRejectionProfile.SUPPLIER_V2,
                RuntimeHistoricalConfigurationRejectionProfile.CASH_CLOSING,
                RuntimeHistoricalConfigurationRejectionProfile.FOOD_TABLE_ASSIGNMENT,
                RuntimeHistoricalConfigurationRejectionProfile.PAYMENT_CORRECTION ->
                    error("Not an original CCJR-v1 owner-null control")
            }
            val base = evidence()
            val requirement = base.requirement.copy(
                action = action, verb = verb, reasonCode = reason, ownerId = null,
                documentRevision = if (profile == RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_RECORD)
                    Revision(0) else Revision(1),
            )
            profile to base.copy(commandName = profile.command.name,
                commandVersion = profile.command.version, requirement = requirement)
        }

    fun money(minor: Long): Money = Money(Currency.EGP, minor)
    private fun marker(value: Char): PayloadDigest = PayloadDigest(value.toString().repeat(64))
    private fun id(prefix: String, ordinal: Int): DomainId =
        DomainId(prefix + "-0000-4000-8000-" + ordinal.toString().padStart(12, '0'))
}

internal data class CashClosingRequirementData(
    val family: String,
    val frozenCase: String,
    val action: SecurityAction,
    val reason: String,
    val actor: DomainId,
    val owner: DomainId?,
    val documentId: DomainId,
    val amount: Money?,
)
