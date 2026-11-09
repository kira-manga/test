package com.ukkera.pos.database.independent.setupacknowledgementsv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.setup.LocalSetupAcknowledgementRecordV1
import com.ukkera.pos.database.setup.LocalSetupGoLiveRecordV1
import com.ukkera.pos.database.setup.LocalSetupPrerequisiteCodeV1
import com.ukkera.pos.database.setup.LocalSetupPrerequisiteObservationV1
import com.ukkera.pos.database.setup.LocalSetupResultCodecV1
import com.ukkera.pos.database.setup.LocalSetupResultOutcomeV1
import com.ukkera.pos.database.setup.LocalSetupResultProfileV1
import com.ukkera.pos.database.setup.LocalSetupResultProjectionV1
import com.ukkera.pos.database.setup.LocalSetupResultRequestV1
import com.ukkera.pos.database.setup.LocalSetupResultStatusV1
import com.ukkera.pos.database.setup.LocalSetupSavedOriginalResultV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Supplemental Linux/JVM coverage of the additive setup-result grammar only. It runs the real
 * commonMain production codecs, but no master store, authority, native original, durable
 * fact/audit commit or protected read. It is therefore not evidence for any SAS case PASS.
 */
class LocalSetupResultGrammarSupportingTest {
    private val scope = BranchScope(DomainId.random(), DomainId.random(), DomainId.random())
    private val installationId = DomainId.random()
    private val assignmentId = DomainId.random()
    private val deviceId = DomainId.random()
    private val firstActorId = DomainId.random()
    private val originalRequestId = DomainId.random()
    private val issuedAttemptId = DomainId.random()
    private val definitionDigest = PayloadDigest("a".repeat(64))
    private val genesisDigest = PayloadDigest("b".repeat(64))
    private val receiptDigest = PayloadDigest("c".repeat(64))
    private val installedAt = UtcInstant.parseCanonical("2026-11-09T00:00:00Z")

    @Test
    fun requestsRoundTripCanonicallyAndCarryOnlyTheClaimedInstallation() {
        val operationId = OperationId.random()
        val acknowledge = LocalSetupResultCodecV1.acknowledge(operationId, installationId)
        assertEquals(LocalSetupResultProfileV1.ACKNOWLEDGE, acknowledge.command)
        assertEquals(operationId, acknowledge.operationId)
        assertEquals(LocalSetupResultRequestV1(installationId),
            LocalSetupResultCodecV1.decodeRequest(acknowledge.payload))

        val goLive = LocalSetupResultCodecV1.goLive(operationId, installationId)
        assertEquals(LocalSetupResultProfileV1.GO_LIVE, goLive.command)
        assertEquals(operationId, goLive.operationId)
        assertEquals(LocalSetupResultRequestV1(installationId),
            LocalSetupResultCodecV1.decodeRequest(goLive.payload))

        val read = LocalSetupResultCodecV1.read(installationId)
        assertEquals(LocalSetupResultProfileV1.READ, read.query)
        assertEquals(LocalSetupResultRequestV1(installationId),
            LocalSetupResultCodecV1.decodeRequest(read.payload))
    }

    @Test
    fun requestDecoderFailsClosedForUnknownNoncanonicalEmptyAndOversizeBytes() {
        val canonical = LocalSetupResultCodecV1.encodeRequest(installationId)
        val text = canonical.copyBytes().decodeToString()
        assertTrue(text.startsWith("{") && text.endsWith("}"))
        val unknown = text.substring(0, 1) + "\"ready\":true," + text.substring(1)
        assertFailsWith<IllegalArgumentException> {
            LocalSetupResultCodecV1.decodeRequest(StoredBytes(unknown.encodeToByteArray()))
        }
        val noncanonical = text.replace("\"installationId\":", "\"installationId\" :")
        assertFailsWith<IllegalArgumentException> {
            LocalSetupResultCodecV1.decodeRequest(StoredBytes(noncanonical.encodeToByteArray()))
        }
        assertFailsWith<IllegalArgumentException> {
            LocalSetupResultCodecV1.decodeRequest(StoredBytes(ByteArray(0)))
        }
        assertFailsWith<IllegalArgumentException> {
            LocalSetupResultCodecV1.decodeRequest(
                StoredBytes(ByteArray(LocalSetupResultProfileV1.MAX_REQUEST_BYTES + 1)))
        }
    }

    @Test
    fun prerequisitesRequireTheCompleteCanonicalSetAndBoundedBindings() {
        val settled = LocalSetupPrerequisiteCodeV1.entries.map { code ->
            LocalSetupPrerequisiteObservationV1.settled(code, binding(code))
        }
        LocalSetupPrerequisiteObservationV1.requireComplete(settled)
        assertFailsWith<IllegalArgumentException> {
            LocalSetupPrerequisiteObservationV1.requireComplete(settled.drop(1))
        }
        assertFailsWith<IllegalArgumentException> {
            LocalSetupPrerequisiteObservationV1.requireComplete(settled + settled.first())
        }
        assertFailsWith<IllegalArgumentException> {
            LocalSetupPrerequisiteObservationV1.requireComplete(settled.reversed())
        }
        assertFailsWith<IllegalArgumentException> {
            LocalSetupPrerequisiteObservationV1.unavailable(
                LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT, "unavailable:bad binding")
        }
    }

    @Test
    fun outcomeAndProjectionRoundTripWithExactAckAndLiveFacts() {
        val acknowledgement = acknowledgement()
        val ackOutcome = LocalSetupResultOutcomeV1(LocalSetupResultStatusV1.ACKNOWLEDGED,
            LocalSetupResultProfileV1.ACKNOWLEDGED_CODE, acknowledgement = acknowledgement)
        assertEquals(ackOutcome, LocalSetupResultCodecV1.decodeOutcome(LocalSetupResultCodecV1.encodeOutcome(ackOutcome)))

        val goLive = LocalSetupGoLiveRecordV1(
            organizationId = scope.organizationId, companyId = scope.companyId, branchId = scope.branchId,
            installationId = installationId, originalRequestId = originalRequestId,
            issuedAttemptId = issuedAttemptId, attemptSequence = 1, receiptDigest = receiptDigest, definitionDigest = definitionDigest,
            genesisDigest = genesisDigest, actorId = firstActorId, occurredAt = installedAt,
            operationId = OperationId.random(), policyRevision = Revision(2),
            masterAuthorityEpoch = Revision(1), prerequisites = settledPrerequisites())
        val liveOutcome = LocalSetupResultOutcomeV1(LocalSetupResultStatusV1.LIVE,
            LocalSetupResultProfileV1.LIVE_CODE, goLive = goLive, prerequisites = goLive.prerequisites)
        assertEquals(liveOutcome, LocalSetupResultCodecV1.decodeOutcome(LocalSetupResultCodecV1.encodeOutcome(liveOutcome)))

        val projection = LocalSetupResultProjectionV1(savedOriginal(), acknowledgement, goLive,
            settledPrerequisites())
        assertEquals(projection, LocalSetupResultCodecV1.decodeProjection(
            LocalSetupResultCodecV1.encodeProjection(projection)))
    }

    @Test
    fun outcomeFailsClosedForMismatchedStatusAndMissingFacts() {
        assertFailsWith<IllegalArgumentException> {
            LocalSetupResultOutcomeV1(LocalSetupResultStatusV1.ACKNOWLEDGED,
                LocalSetupResultProfileV1.ACKNOWLEDGED_CODE)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalSetupResultOutcomeV1(LocalSetupResultStatusV1.LIVE, LocalSetupResultProfileV1.LIVE_CODE)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalSetupResultOutcomeV1(LocalSetupResultStatusV1.ACKNOWLEDGED,
                LocalSetupResultProfileV1.ACKNOWLEDGED_CODE, goLive = goLiveRecord())
        }
        assertFailsWith<IllegalArgumentException> {
            LocalSetupResultOutcomeV1(LocalSetupResultStatusV1.ACKNOWLEDGED, "UPPERCASE")
        }
    }

    @Test
    fun savedOriginalProjectionRejectsUnstableAttemptSequenceAndForeignFactBinding() {
        assertFailsWith<IllegalArgumentException> { savedOriginal(attemptSequence = 0) }
        val acknowledgement = acknowledgement()
        assertFailsWith<IllegalArgumentException> {
            LocalSetupResultProjectionV1(savedOriginal(), acknowledgement.copy(installationId = DomainId.random()),
                null, settledPrerequisites())
        }
    }

    private fun acknowledgement(): LocalSetupAcknowledgementRecordV1 = LocalSetupAcknowledgementRecordV1(
        organizationId = scope.organizationId, companyId = scope.companyId, branchId = scope.branchId,
        installationId = installationId, originalRequestId = originalRequestId,
        issuedAttemptId = issuedAttemptId, attemptSequence = 1, receiptDigest = receiptDigest, definitionDigest = definitionDigest,
        genesisDigest = genesisDigest, actorId = firstActorId, acknowledgedAt = installedAt,
        operationId = OperationId.random(), policyRevision = Revision(1), masterAuthorityEpoch = Revision(1))

    private fun goLiveRecord(): LocalSetupGoLiveRecordV1 = LocalSetupGoLiveRecordV1(
        organizationId = scope.organizationId, companyId = scope.companyId, branchId = scope.branchId,
        installationId = installationId, originalRequestId = originalRequestId,
        issuedAttemptId = issuedAttemptId, attemptSequence = 1, receiptDigest = receiptDigest, definitionDigest = definitionDigest,
        genesisDigest = genesisDigest, actorId = firstActorId, occurredAt = installedAt,
        operationId = OperationId.random(), policyRevision = Revision(1),
        masterAuthorityEpoch = Revision(1), prerequisites = settledPrerequisites())

    private fun savedOriginal(attemptSequence: Long = 1L) = LocalSetupSavedOriginalResultV1(
        organizationId = scope.organizationId, companyId = scope.companyId, branchId = scope.branchId,
        installationId = installationId, assignmentId = assignmentId, deviceId = deviceId,
        firstActorId = firstActorId, originalRequestId = originalRequestId,
        issuedAttemptId = issuedAttemptId, attemptSequence = attemptSequence,
        definitionDigest = definitionDigest, genesisDigest = genesisDigest,
        receiptDigest = receiptDigest, installationAuthorityEpoch = Revision(1),
        currentAuthorityEpoch = Revision(1), installedAt = installedAt, nativeSettlement = true)

    private fun settledPrerequisites(): List<LocalSetupPrerequisiteObservationV1> =
        LocalSetupPrerequisiteCodeV1.entries.map { code ->
            LocalSetupPrerequisiteObservationV1.settled(code, binding(code))
        }

    private fun binding(code: LocalSetupPrerequisiteCodeV1): String =
        "test.original-setup:${code.name.lowercase().replace('_', '-')}"
}
