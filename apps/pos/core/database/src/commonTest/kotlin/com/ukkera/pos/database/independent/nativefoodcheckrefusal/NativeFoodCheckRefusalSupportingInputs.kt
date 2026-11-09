package com.ukkera.pos.database.independent.nativefoodcheckrefusal

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** NRFP-v1 scalar/requirement DATA only. These synthetic coordinates are not genuine
 * native originals, a before-cut, current authority, provenance or a recording fixture. */
internal object NativeFoodCheckRefusalSupportingInputs {
    fun id(number: Int): DomainId =
        DomainId("a6110400-0000-4000-8000-${number.toString().padStart(12, '0')}")

    val scope: BranchScope = BranchScope(id(1), id(2), id(3))
    val actor: DomainId = id(4)
    val beforeOwner: DomainId = id(5)
    val order: DomainId = id(6)
    val otherOrder: DomainId = id(7)
    val operation: OperationId = OperationId(id(8).value)
    val otherOperation: OperationId = OperationId(id(9).value)
    val digest: PayloadDigest = digest('a')
    val otherDigest: PayloadDigest = digest('b')
    val requestedRevision: Revision = Revision(3)

    fun digest(character: Char): PayloadDigest = PayloadDigest(character.toString().repeat(64))

    fun requirement(add: Boolean = false): RuntimeCommandRequirementV1 = RuntimeCommandRequirementV1(
        action = if (add) SecurityAction.ORDER_EDIT else SecurityAction.ORDER_CREATE,
        verb = PermissionVerb.PERFORM,
        scope = scope,
        documentId = order,
        documentRevision = if (add) requestedRevision else Revision(0),
        payloadDigest = digest,
        operationId = operation,
        // Concrete representation used by the production closed FOOD requirement.
        reasonCode = "food.operation",
        limitKind = "none",
        amount = null,
        baseAmount = null,
        ownerId = if (add) beforeOwner else actor,
    )

    fun evidence(add: Boolean = false): RuntimeCommandAuthorizationEvidenceV1 =
        RuntimeCommandAuthorizationEvidenceV1(
            purposeTag = RuntimeWritePurposeV2.COMMAND_REJECT.tag,
            operationId = operation,
            commandName = "sales.food.stock",
            commandVersion = 2,
            semanticDigest = digest,
            requirement = requirement(add),
            authorization = snapshot(),
            actionApprovalId = null,
            nativeRecordDigest = digest('c'),
            capturedChanges = RuntimeHistoricalFoodCheckRejectionProfile.CAPTURED_CHANGES,
        )

    private fun snapshot(): RuntimeCommandAuthorizationSnapshotV1 =
        RuntimeCommandAuthorizationSnapshotV1(
            scope = scope,
            actorId = actor,
            sessionId = id(10),
            deviceId = id(11),
            generation = SessionGeneration(1),
            principalKind = PrincipalKind.LOCAL_EMPLOYEE,
            authenticationPurpose = AuthenticationPurpose.INTERACTIVE,
            accountRevision = Revision(1),
            credentialRevision = Revision(2),
            admissionRevision = Revision(3),
            policyRevision = Revision(4),
            access = RuntimeCommandAccessWindowV1(
                issuerContextId = id(12),
                issuedAt = MonotonicMoment(id(13), 1_000),
                observedAt = MonotonicMoment(id(13), 2_000),
                expiresAtElapsedMillis = 60_000,
                credentialRevisionAtIssue = Revision(2),
                admissionRevisionAtIssue = Revision(3),
            ),
            dependencies = RuntimeCommandAuthorizationDependenciesV1(
                digest('d'), digest('e'), digest('f'), digest('0'),
            ),
        )

    /** Constructor-valid header DATA without an event payload, native reader or mutations.
     * The census value binds only the candidate shape, not permitted bookkeeping counts. */
    fun header(): RuntimeHistoryEventHeaderV2 = RuntimeHistoryEventHeaderV2(
        scope = listOf(scope.organizationId, scope.companyId, scope.branchId).joinToString("/") { it.value },
        storageId = id(14),
        bindingDigest = digest('1'),
        originalAllocationDigest = digest('2'),
        definitionDigest = digest('3'),
        installationAttemptId = id(15),
        installationAttemptSequence = 1,
        installationGenesisDigest = digest('4'),
        sequence = 2,
        parentKind = RuntimeHistoryParentKindV2.RECORDED_EVENT.tag,
        parentSequence = 1,
        parentDigest = digest('5'),
        transactionId = id(16),
        assignmentId = id(17),
        purpose = RuntimeWritePurposeV2.COMMAND_REJECT,
        commandEpoch = 1,
        commandSequence = 1,
        commandDigest = digest,
        roleCommitment = digest('6'),
        recoveryCommitment = digest('7'),
        changeCount = RuntimeHistoricalFoodCheckRejectionProfile.CAPTURED_CHANGES,
        chunkCount = 1,
        imageBytes = 0,
        intentBytes = 1,
        intentDigest = digest('8'),
        dependencyBytes = 0,
        dependencyDigest = digest('9'),
        factBytes = 0,
        factDigest = digest('a'),
    )

    /** Existing native dependency length framing only; no event/signature is manufactured. */
    fun fields(values: List<ByteArray>): ByteArray {
        val bytes = ByteArray(values.sumOf { 4 + it.size })
        var at = 0
        for (value in values) {
            for (shift in listOf(24, 16, 8, 0)) bytes[at++] = (value.size ushr shift).toByte()
            value.copyInto(bytes, at)
            at += value.size
        }
        return bytes
    }

    fun dependencyRows(rows: List<List<String>>): ByteArray = fields(rows.map { row ->
        fields(row.map { it.encodeToByteArray() })
    })
}
