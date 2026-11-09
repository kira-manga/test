package com.ukkera.pos.recovery

import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class HistoryCoverageTest {
    private val f = RecoveryFixtures

    @Test
    fun absenceRequiresVerifiedGenesisEveryRangeTerminalKnowledgeAndCoordinatedLookup() {
        val history = f.healthyHistory()
        val verifier = ModelEvidenceVerifier()
        assertEquals(OperationOutcomeClassification.NOT_RECORDED,
            OperationOutcomePolicy(verifier).classify(f.identity, null, history, f.lookup(history)))
        assertTrue(verifier.assertions.any { it is HistoryChainAssertion })
        assertTrue(verifier.assertions.any { it is HistoryRangeAssertion })
        assertTrue(verifier.assertions.any { it is HistoryTailAssertion })
        assertTrue(verifier.assertions.any { it is CoordinatedAbsenceAssertion })
        for (reject in listOf<(RecoveryEvidenceAssertion) -> Boolean>(
            { it is HistoryChainAssertion }, { it is HistoryRangeAssertion },
            { it is HistoryTailAssertion }, { it is CoordinatedAbsenceAssertion },
        )) {
            assertEquals(OperationOutcomeClassification.HISTORY_INCOMPLETE,
                OperationOutcomePolicy(ModelEvidenceVerifier { !reject(it) }).classify(f.identity, null, history, f.lookup(history)))
        }
        assertEquals(OperationOutcomeClassification.HISTORY_INCOMPLETE,
            OperationOutcomePolicy().classify(f.identity, null, history, f.lookup(history)))
    }

    @Test
    fun positiveOriginalResultsRemainRecoverableDespiteUnknownHistoryWithoutReattribution() {
        val incomplete = f.incompleteHistory().copy(genesisAndChainEvidence = null)
        for (state in KnownOperationState.entries) {
            val verifier = ModelEvidenceVerifier { it is KnownOperationAssertion }
            val decision = OperationOutcomePolicy(verifier).classify(f.identity, f.known(state), incomplete, f.lookup(incomplete))
            assertEquals(state.name, decision.name)
            assertEquals(f.cashier, (verifier.assertions.single() as KnownOperationAssertion).record.identity.originalActorId)
        }
        assertEquals(OperationOutcomeClassification.HISTORY_INCOMPLETE,
            OperationOutcomePolicy().classify(f.identity, f.known(), incomplete, f.lookup(incomplete)))
    }

    @Test
    fun changedActorPayloadDigestOrOperationNeverAdoptsAnExistingResult() {
        val history = f.healthyHistory()
        val original = f.known()
        for (changed in listOf(
            f.identity.copy(originalActorId = f.approver),
            f.identity.copy(semanticSha256 = f.hash(999)),
            f.identity.copy(semanticDigestVersion = 2),
            f.identity.copy(operationId = f.operationId(999)),
            f.identity.copy(branchId = f.id(999)),
        )) {
            assertEquals(OperationOutcomeClassification.IDENTITY_CONFLICT,
                OperationOutcomePolicy(ModelEvidenceVerifier()).classify(changed, original, history, f.lookup(history, changed.operationId)))
        }
    }

    @Test
    fun maximumRestoredSequenceIsNotATerminalCutAndArchivesMustActuallyBeAvailable() {
        val healthy = f.healthyHistory()
        val epoch = healthy.epochs.single()
        val variants = listOf(
            epoch.copy(tail = HistoryTail.Unknown),
            epoch.copy(knownGaps = listOf(SequenceRange(JournalSequence(3), JournalSequence(3)))),
            epoch.copy(unavailableArchives = listOf(SequenceRange(JournalSequence(1), JournalSequence(5)))),
            epoch.copy(availableRanges = listOf(f.range(2, 5))),
            epoch.copy(availableRanges = listOf(f.range(1, 4))),
        )
        for (missing in variants) {
            val history = healthy.copy(epochs = listOf(missing))
            assertEquals(OperationOutcomeClassification.HISTORY_INCOMPLETE,
                OperationOutcomePolicy(ModelEvidenceVerifier()).classify(f.identity, null, history, f.lookup(history)))
        }
        val archive = healthy.copy(epochs = listOf(epoch.copy(availableRanges = listOf(
            f.range(1, 5).copy(source = AvailableHistorySource.VERIFIED_PROTECTED_ARCHIVE)))))
        assertEquals(CoverageAssessment.VERIFIED_COMPLETE, HistoryCoveragePolicy(ModelEvidenceVerifier()).assess(archive, f.lookup(archive)))
        assertEquals(CoverageAssessment.HISTORY_INCOMPLETE,
            HistoryCoveragePolicy(ModelEvidenceVerifier { it !is HistoryRangeAssertion }).assess(archive, f.lookup(archive)))
    }

    @Test
    fun everyPriorAuthorityMustBeCoveredAndLinkedWithoutEpochSkipping() {
        val old = f.healthyHistory().epochs.single().copy(tail = HistoryTail.SealedFinalCut(f.evidence(99)))
        val currentCheckpoint = f.checkpoint(f.target, 2)
        val current = EpochHistoryCoverage(f.target, old.checkpoint, currentCheckpoint, listOf(f.range(1, 2)),
            emptyList(), emptyList(), HistoryTail.LiveWriterHead(f.transaction, f.evidence(100)))
        val history = HistoryCoverage(f.branch, listOf(old, current), f.evidence(101))
        val policy = HistoryCoveragePolicy(ModelEvidenceVerifier())
        assertEquals(CoverageAssessment.VERIFIED_COMPLETE, policy.assess(history, f.lookup(history)))
        assertEquals(CoverageAssessment.HISTORY_INCOMPLETE, policy.assess(history.copy(epochs =
            listOf(old.copy(tail = HistoryTail.Unknown), current)), f.lookup(history)))
        assertEquals(CoverageAssessment.HISTORY_INCOMPLETE, policy.assess(history.copy(epochs =
            listOf(old.copy(unavailableArchives = listOf(SequenceRange(JournalSequence(1), JournalSequence(5)))), current)), f.lookup(history)))
        assertEquals(CoverageAssessment.INVALID_CHAIN_OR_SCOPE, policy.assess(history.copy(epochs = listOf(current)), f.lookup(history)))
        val wrongEpoch = f.target.copy(epoch = Revision(3))
        val invalidCurrent = current.copy(authority = wrongEpoch, checkpoint = currentCheckpoint.copy(
            position = currentCheckpoint.position.copy(authority = wrongEpoch)))
        val skipped = history.copy(epochs = listOf(old, invalidCurrent))
        assertEquals(CoverageAssessment.INVALID_CHAIN_OR_SCOPE, policy.assess(skipped, f.lookup(skipped)))
    }

    @Test
    fun liveWriterHeadCannotBeReusedFromAnotherTransactionOrPredecessor() {
        val history = f.healthyHistory()
        assertEquals(CoverageAssessment.HISTORY_INCOMPLETE,
            HistoryCoveragePolicy(ModelEvidenceVerifier()).assess(history, f.lookup(history).copy(transactionId = f.id(999))))
        assertEquals(CoverageAssessment.INVALID_CHAIN_OR_SCOPE,
            HistoryCoveragePolicy(ModelEvidenceVerifier()).assess(history, f.lookup(history).copy(currentAuthority = f.target)))
        assertEquals(CoverageAssessment.INVALID_CHAIN_OR_SCOPE,
            HistoryCoveragePolicy(ModelEvidenceVerifier()).assess(history, f.lookup(history).copy(
                visibleCheckpoint = f.checkpoint.copy(position = f.checkpoint.position.copy(sequence = JournalSequence(6))))))
    }

    @Test
    fun exhaustiveSmallRangeMasksNeverTurnMissingCoverageIntoSafeAbsence() {
        val policy = HistoryCoveragePolicy(ModelEvidenceVerifier())
        var examined = 0
        for (cut in 0..8) {
            val healthy = f.healthyHistory(cut.toLong())
            for (mask in 0 until (1 shl cut)) {
                val ranges = (1..cut).filter { mask and (1 shl (it - 1)) != 0 }
                    .map { f.range(it.toLong(), it.toLong()) }.reversed()
                val history = healthy.copy(epochs = listOf(healthy.epochs.single().copy(availableRanges = ranges)))
                val expected = if (mask == (1 shl cut) - 1) CoverageAssessment.VERIFIED_COMPLETE else CoverageAssessment.HISTORY_INCOMPLETE
                assertEquals(expected, policy.assess(history, f.lookup(history)), "cut=$cut mask=$mask")
                examined++
            }
        }
        assertEquals(511, examined)
    }

    @Test
    fun rangeAndCounterEdgesRejectOverlapOrOverflowWithoutDroppingHistory() {
        assertFailsWith<IllegalArgumentException> { SequenceRange(JournalSequence(0), JournalSequence(1)) }
        assertFailsWith<IllegalArgumentException> { SequenceRange(JournalSequence(2), JournalSequence(1)) }
        assertFailsWith<IllegalArgumentException> { f.evidence(1).copy(contentSha256 = "not-a-digest") }
        val huge = f.healthyHistory(Long.MAX_VALUE)
        assertEquals(CoverageAssessment.VERIFIED_COMPLETE,
            HistoryCoveragePolicy(ModelEvidenceVerifier()).assess(huge, f.lookup(huge)))
        val healthy = f.healthyHistory()
        val overlap = healthy.copy(epochs = listOf(healthy.epochs.single().copy(availableRanges = listOf(f.range(1, 3), f.range(3, 5)))))
        assertEquals(CoverageAssessment.INVALID_CHAIN_OR_SCOPE,
            HistoryCoveragePolicy(ModelEvidenceVerifier()).assess(overlap, f.lookup(overlap)))
        val beyondCut = healthy.copy(epochs = listOf(healthy.epochs.single().copy(availableRanges = listOf(f.range(1, 6)))))
        assertEquals(CoverageAssessment.INVALID_CHAIN_OR_SCOPE,
            HistoryCoveragePolicy(ModelEvidenceVerifier()).assess(beyondCut, f.lookup(beyondCut)))
    }

    @Test
    fun serializableCoveragePreservesUnknownTailGapsAndDecimalStringCursors() {
        val history = f.incompleteHistory().let { it.copy(epochs = listOf(it.epochs.single().copy(
            knownGaps = listOf(SequenceRange(JournalSequence(2), JournalSequence(3))),
            unavailableArchives = listOf(SequenceRange(JournalSequence(4), JournalSequence(4))),
        ))) }
        val encoded = Json.encodeToString(history)
        assertEquals(history, Json.decodeFromString<HistoryCoverage>(encoded))
        assertTrue(encoded.contains("\"epoch\":\"1\""))
        assertTrue(encoded.contains("\"sequence\":\"5\""))
        assertFalse(encoded.contains("bearer"))
        assertFalse(encoded.contains("resultBody"))
        assertEquals(OperationOutcomeClassification.HISTORY_INCOMPLETE,
            OperationOutcomePolicy(ModelEvidenceVerifier()).classify(f.identity, null, Json.decodeFromString(encoded), f.lookup(history)))
    }

    @Test
    fun lookupSubmissionAndRetryPermutationsDoNotCacheNegativeResultsOrRepeatEffects() {
        val schedules = permutations(listOf("lookup", "initial", "retry", "commit"))
            .filter { it.indexOf("commit") > minOf(it.indexOf("initial"), it.indexOf("retry")) }
        for (schedule in schedules) {
            var stored: KnownOperationMetadata? = null
            var effects = 0
            val history = f.healthyHistory()
            val policy = OperationOutcomePolicy(ModelEvidenceVerifier())
            for (event in schedule) {
                val expected = when (stored?.state) {
                    null -> OperationOutcomeClassification.NOT_RECORDED
                    KnownOperationState.IN_PROGRESS -> OperationOutcomeClassification.IN_PROGRESS
                    KnownOperationState.COMMITTED -> OperationOutcomeClassification.COMMITTED
                    KnownOperationState.REJECTED -> error("No rejection in fixture")
                }
                assertEquals(expected, policy.classify(f.identity, stored, history, f.lookup(history)), schedule.toString())
                when (event) {
                    "initial", "retry" -> if (stored == null) stored = f.known(KnownOperationState.IN_PROGRESS)
                    "commit" -> if (stored?.state == KnownOperationState.IN_PROGRESS) {
                        effects++
                        stored = f.known()
                    }
                    "lookup" -> Unit
                }
            }
            assertEquals(1, effects, schedule.toString())
            assertEquals(OperationOutcomeClassification.COMMITTED,
                policy.classify(f.identity, stored, history, f.lookup(history)), schedule.toString())
        }
        assertEquals(16, schedules.size)
    }
}
