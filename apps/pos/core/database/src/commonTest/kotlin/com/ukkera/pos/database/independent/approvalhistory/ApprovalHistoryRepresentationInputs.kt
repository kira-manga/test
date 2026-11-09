package com.ukkera.pos.database

import com.ukkera.pos.security.ActionApprovalRecord
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthEvent
import com.ukkera.pos.security.LocalAuthEventKind
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.ProtectedLocalActionApproval
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/**
 * Inert, ephemeral representation carriers for the linked OH v1 freeze, NOT issued approvals.
 * No authority, database insertion, native origin, throttle MATCH or service is created here.
 * AP/event/issuer/boot identities, incarnation numbers, fingerprints and the command/reason code
 * are carrier-only. Fixture semantic digests supply same/different comparisons, not wire hashes.
 * The same-device approver carrier does NOT bind the freeze's distinct DEV-M alias.
 */
internal object ApprovalHistoryRepresentationInputs {
    val scope = BranchScope(id("10000000", 1), id("20000000", 1), id("30000000", 1))
    val otherOrganization = id("10000000", 2)
    val otherCompany = id("20000000", 2)
    val otherBranch = id("30000000", 2)
    val requestor = id("40000000", 1)
    val otherActor = id("40000000", 2)
    val manager = id("40000000", 3)
    val otherApprover = id("40000000", 5)
    val requestorDevice = id("50000000", 1)
    val fixtureManagerDevice = id("50000000", 2)
    val requestorSession = id("60000000", 1)
    val otherSession = id("60000000", 3)
    val document = id("70000000", 1)
    val otherDocument = id("70000000", 2)
    val operation = OperationId(id("80000000", 1).value)
    val otherOperation = OperationId(id("80000000", 2).value)
    val unknownApproval = id("90000000", 63)
    val separatePolicy = Revision(7)
    val deniedPolicy = Revision(8)
    val namedSelfPolicy = Revision(9)
    val documentRevision = Revision(17)
    val discount = Money(Currency.EGP, 1_000)
    val changedDiscount = Money(Currency.EGP, 1_100)
    val subtotal = Money(Currency.EGP, 20_000)
    const val validForMillis: Long = 300_000

    // Frozen fixture-semantic-v1 values, deliberately not asserted as production hashes.
    val semanticDigest = PayloadDigest("5aaab7800c516cc0e11f1ab8bee445654d11b957579e3be1bd245270e4fa524c")
    val namedSelfSemanticDigest = PayloadDigest("87251eea4ed2271220df8a3ef117af0a9a330396ce9a9bce8a5003236e2260f5")
    val changedAmountSemanticDigest = PayloadDigest("721b889d27638553342099ce09091ac2fcd363423e3f75f6edf78bdf066f6a80")
    val changedLineSemanticDigest = PayloadDigest("7f3e990fc33e80b57b5fdb7035886f92f65d32df4f1c26c32e5d726b120504f5")

    val inertApprovalId = id("a1000000", 1)
    val inertEventId = id("a2000000", 1)
    val otherInertEventId = id("a2000000", 2)
    val inertIssuer = id("a3000000", 1)
    val otherInertIssuer = id("a3000000", 2)
    val inertBoot = id("a4000000", 1)
    val otherInertBoot = id("a4000000", 2)
    val issuedAt = MonotonicMoment(inertBoot, 2_000)
    val observed = RuntimeApprovalObservationV1(inertIssuer, MonotonicMoment(inertBoot, 5_000))
    val inertDifferentDigest = PayloadDigest("e".repeat(64))

    fun requirement(namedSelf: Boolean = false): RuntimeCommandRequirementV1 = RuntimeCommandRequirementV1(
        action = SecurityAction.DISCOUNT, verb = PermissionVerb.PERFORM, scope = scope,
        documentId = document, documentRevision = documentRevision,
        payloadDigest = if (namedSelf) namedSelfSemanticDigest else semanticDigest,
        operationId = operation, reasonCode = "fixture.oh.reason", limitKind = "proportional",
        amount = discount, baseAmount = subtotal, ownerId = requestor,
    )

    fun requestorSnapshot(namedSelf: Boolean = false): RuntimeCommandAuthorizationSnapshotV1 =
        RuntimeCommandAuthorizationSnapshotV1(
            scope = scope, actorId = requestor, sessionId = requestorSession, deviceId = requestorDevice,
            generation = SessionGeneration(1), principalKind = PrincipalKind.LOCAL_EMPLOYEE,
            authenticationPurpose = AuthenticationPurpose.INTERACTIVE, accountRevision = Revision(3),
            credentialRevision = Revision(2), admissionRevision = Revision(4),
            policyRevision = if (namedSelf) namedSelfPolicy else separatePolicy,
            access = RuntimeCommandAccessWindowV1(inertIssuer, MonotonicMoment(inertBoot, 1_000),
                issuedAt, 400_000, Revision(2), Revision(4)),
            dependencies = RuntimeCommandAuthorizationDependenciesV1(PayloadDigest("1".repeat(64)),
                PayloadDigest("2".repeat(64)), PayloadDigest("3".repeat(64)), PayloadDigest("4".repeat(64))),
        )

    fun approverSnapshot(namedSelf: Boolean = false): RuntimeApprovalApproverSnapshotV1 =
        RuntimeApprovalApproverSnapshotV1(
            scope = scope, actorId = if (namedSelf) requestor else manager,
            deviceId = requestorDevice, accountRevision = Revision(if (namedSelf) 3L else 6L),
            generation = SessionGeneration(if (namedSelf) 1L else 2L),
            credentialRevision = Revision(if (namedSelf) 2L else 5L), admissionRevision = Revision(4),
            policyRevision = if (namedSelf) namedSelfPolicy else separatePolicy,
            dependencies = RuntimeApprovalApproverDependenciesV1(PayloadDigest((if (namedSelf) "1" else "5").repeat(64)),
                PayloadDigest((if (namedSelf) "2" else "6").repeat(64)), PayloadDigest("3".repeat(64))),
        )

    fun issuedCarrier(namedSelf: Boolean = false): ProtectedLocalActionApproval {
        val requestor = requestorSnapshot(namedSelf)
        val approver = approverSnapshot(namedSelf)
        val record = ActionApprovalRecord(inertApprovalId, requestor.actorId, approver.actorId,
            requirement(namedSelf).value(), requestor.policyRevision, approver.policyRevision,
            approver.policyRevision, issuedAt, validForMillis, Revision(0), null)
        return ProtectedLocalActionApproval(record, inertIssuer, requestor.sessionId, requestor.generation,
            requestor.deviceId, requestor.credentialRevision, requestor.admissionRevision, approver.deviceId,
            approver.credentialRevision, approver.admissionRevision,
            LocalActionApprovalCodec.confirmationDigest(requestor.actorId, requestor.policyRevision, record.requirement),
            approver.accountRevision, approver.generation, PayloadDigest("8".repeat(64)))
    }

    fun command(kind: RuntimeActionApprovalUseKindV1 = RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED,
        namedSelf: Boolean = false): RuntimeCommandAuthorizationEvidenceV1 = RuntimeCommandAuthorizationEvidenceV1(
        purposeTag = if (kind == RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED)
            RuntimeWritePurposeV2.COMMAND_APPLY.tag else RuntimeWritePurposeV2.COMMAND_REJECT.tag,
        operationId = operation, commandName = "fixture.oh.discount", commandVersion = 1,
        semanticDigest = if (namedSelf) namedSelfSemanticDigest else semanticDigest,
        requirement = requirement(namedSelf), authorization = requestorSnapshot(namedSelf),
        actionApprovalId = inertApprovalId, nativeRecordDigest = PayloadDigest("f".repeat(64)),
        capturedChanges = 3, // Carrier-only count; no native mutation census is supplied.
    )

    fun consumedCarrier(issued: ProtectedLocalActionApproval): ProtectedLocalActionApproval =
        issued.consumed(issued.record.copy(revision = Revision(1), consumedByOperationId = operation))

    fun eventCarrier(consumed: ProtectedLocalActionApproval, at: MonotonicMoment = observed.moment): LocalAuthEvent =
        LocalAuthEvent(scope, inertEventId, LocalAuthEventKind.ACTION_APPROVAL_CONSUMED,
            document, requestor, requestorSession, Revision(3), at, approvalDigest(consumed))

    fun use(kind: RuntimeActionApprovalUseKindV1 = RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED,
        namedSelf: Boolean = false): RuntimeActionApprovalUseEvidenceV1 {
        val issued = issuedCarrier(namedSelf)
        val consumed = if (kind == RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED) consumedCarrier(issued) else null
        val event = consumed?.let { eventCarrier(it) }
        return RuntimeActionApprovalUseEvidenceV1(kind, inertApprovalId, approvalText(issued),
            approverSnapshot(namedSelf), observed, consumed?.let(::approvalText), event?.eventId,
            event?.let(::eventText), if (consumed != null && event != null) expectedImages(consumed, event) else emptyList())
    }

    fun envelope(kind: RuntimeActionApprovalUseKindV1 = RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED,
        namedSelf: Boolean = false): RuntimeApprovedCommandEvidenceV1 =
        RuntimeApprovedCommandEvidenceV1(commandEvidence = commandText(command(kind, namedSelf)), approvalUse = use(kind, namedSelf))

    fun commandText(value: RuntimeCommandAuthorizationEvidenceV1): String =
        textAndWipe(RuntimeCommandAuthorizationEvidenceCodec.encode(value))
    fun approvalText(value: ProtectedLocalActionApproval): String = textAndWipe(LocalActionApprovalCodec.encode(value))
    fun eventText(value: LocalAuthEvent): String = textAndWipe(LocalAuthenticationCodec.encodeEvent(value))
    fun approvalDigest(value: ProtectedLocalActionApproval): PayloadDigest = digestAndWipe(LocalActionApprovalCodec.encode(value))

    /** Expected fields are constructed directly, not through the image/capture helper under test. */
    fun expectedImages(consumed: ProtectedLocalActionApproval, event: LocalAuthEvent): List<RuntimeApprovalImageV1> = listOf(
        RuntimeApprovalImageV1(RuntimeHistoryTableV2.APPROVAL.tag, consumed.record.approvalId,
            RuntimeHistoryMutationKindV2.UPDATE.tag, Revision(1), approvalDigest(consumed)),
        RuntimeApprovalImageV1(RuntimeHistoryTableV2.AUTH_EVENT.tag, event.eventId,
            RuntimeHistoryMutationKindV2.INSERT.tag, Revision(0), digestAndWipe(LocalAuthenticationCodec.encodeEvent(event))),
    )

    fun withCommand(envelope: RuntimeApprovedCommandEvidenceV1, command: RuntimeCommandAuthorizationEvidenceV1):
        RuntimeApprovedCommandEvidenceV1 = envelope.copy(commandEvidence = commandText(command))

    fun withEvent(use: RuntimeActionApprovalUseEvidenceV1, event: LocalAuthEvent): RuntimeActionApprovalUseEvidenceV1 =
        use.copy(consumptionEventId = event.eventId, consumptionEvent = eventText(event),
            images = expectedImages(requireNotNull(use.consumed(scope)), event))

    fun copyEvent(original: LocalAuthEvent, scope: BranchScope = original.scope, eventId: DomainId = original.eventId,
        kind: LocalAuthEventKind = original.kind, targetId: DomainId = original.targetId,
        operatorId: DomainId? = original.operatorId, sessionId: DomainId? = original.sessionId,
        accountRevision: Revision? = original.accountRevision, at: MonotonicMoment = original.at,
        contentDigest: PayloadDigest? = original.contentDigest): LocalAuthEvent =
        LocalAuthEvent(scope, eventId, kind, targetId, operatorId, sessionId, accountRevision, at, contentDigest)

    fun copyApproval(original: ProtectedLocalActionApproval, record: ActionApprovalRecord = original.record,
        issuerContextId: DomainId = original.issuerContextId, requestorSessionId: DomainId = original.requestorSessionId,
        confirmationDigest: PayloadDigest = original.confirmationDigest,
        approverAccountRevision: Revision? = original.approverAccountRevision,
        approverGeneration: SessionGeneration? = original.approverGeneration,
        issueRequestDigest: PayloadDigest? = original.issueRequestDigest): ProtectedLocalActionApproval =
        ProtectedLocalActionApproval(record, issuerContextId, requestorSessionId, original.requestorGeneration,
            original.requestorDeviceId, original.requestorCredentialRevision, original.requestorAdmissionRevision,
            original.approverDeviceId, original.approverCredentialRevision, original.approverAdmissionRevision,
            confirmationDigest, approverAccountRevision, approverGeneration, issueRequestDigest)

    private fun id(prefix: String, ordinal: Int): DomainId =
        DomainId(prefix + "-0000-4000-8000-" + ordinal.toString().padStart(12, '0'))
    private fun textAndWipe(bytes: ByteArray): String =
        try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
    private fun digestAndWipe(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
}
