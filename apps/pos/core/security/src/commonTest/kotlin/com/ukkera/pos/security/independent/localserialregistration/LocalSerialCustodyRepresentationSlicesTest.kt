package com.ukkera.pos.security.independent.localserialregistration

import com.ukkera.pos.security.CompanyBusinessPublicationPhase
import com.ukkera.pos.security.NativeCompanyBusinessPublicationStateV1
import com.ukkera.pos.security.NativeCompanySerialAuthorityCustodyV1
import com.ukkera.pos.security.NativeCompanySerialAuthorityCustodyWireV1
import com.ukkera.pos.security.requireNativeCompanySerialCustodyTransitionV1
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Pure marker representations only; no authenticated storage, native recovery or signing. */
class LocalSerialCustodyRepresentationSlicesTest {
    private val input = LocalSerialRegistrationInertInputs

    @Test
    fun firstPreparedIntentRetainsTheOneOriginalAuthorityWithoutASettledGrant() {
        // LSR-02.c / LSR-01.d: bookkeeping slice, not a native uncertain-write schedule.
        val record = input.authorityRecord(CompanyBusinessPublicationPhase.PREPARED)
        val companion = NativeCompanyBusinessPublicationStateV1(0, listOf(record))
        val marker = NativeCompanySerialAuthorityCustodyV1.firstIntent(null, companion, record)
        assertNull(marker.settled)
        assertEquals(record.originalActionId, marker.originalAuthorityActionId)
        assertEquals(record.originalActionId, assertNotNull(marker.intent).originalActionId)
        assertEquals(record.publicationId, marker.authority.publicationId)
        assertFails { marker.requireSettledSource(companion) }
    }

    @Test
    fun unresolvedIntentCannotBeUsedAsSettledCustodyEvenWithTheNextCompanionPresent() {
        // LSR-01.d / LSR-19.h: presence is not completed/resolved authority.
        val step = input.custodySigningStep()
        assertFails { step.pending.requireSettledSource(step.before) }
        assertFails { step.pending.requireSettledSource(step.next) }
        assertEquals(step.settledBefore.authority, step.pending.authority)
        assertNotNull(step.pending.intent)
    }

    @Test
    fun exactRetainedNextShapeClearsOnlyItsOwnIntentAndPreservesTheOriginalCoordinates() {
        // LSR-02.d / LSR-18: representation transition only, not actual native recovery.
        val step = input.custodySigningStep()
        val resolvedShape = step.pending.settleActualNext(step.next)
        requireNativeCompanySerialCustodyTransitionV1(step.pending, resolvedShape)
        assertNull(resolvedShape.intent)
        assertEquals(assertNotNull(step.pending.intent).next, resolvedShape.settled)
        assertEquals(step.pending.authority, resolvedShape.authority)
        assertEquals(step.pending.originalAuthorityActionId, resolvedShape.originalAuthorityActionId)
        assertEquals(step.pending.authoritySigningKeyId, resolvedShape.authoritySigningKeyId)
    }

    @Test
    fun anOlderCompanionCannotResolveTheRetainedExactNextIntent() {
        // LSR-17.e / LSR-18.b / LSR-19.c/h: no previous-cut accepting fallback.
        val step = input.custodySigningStep()
        val originalIntent = assertNotNull(step.pending.intent)
        assertFails { step.pending.settleActualNext(step.before) }
        assertEquals(originalIntent, step.pending.intent)
        assertEquals(step.settledBefore.settled, step.pending.settled)
    }

    @Test
    fun changedOriginalActionKeyOrFoundingContentCannotStandInForTheRetainedNext() {
        // LSR-02.g / LSR-12.b/g / LSR-19.b/d: mismatched inert target refuses.
        val step = input.custodySigningStep()
        val changedAuthority = step.pending.authority.copy(origin =
            step.pending.authority.origin.copy(foundingActionId = input.auxiliaryId(62)))
        val invalid = listOf(
            step.changed.copy(originalActionId = input.auxiliaryId(60)),
            step.changed.copy(keyId = input.auxiliaryId(61)),
            input.authorityRecord(phase = CompanyBusinessPublicationPhase.SIGNING_INTENT, definition = changedAuthority),
        )
        for (record in invalid) {
            val changedCompanion = NativeCompanyBusinessPublicationStateV1(step.next.generation, listOf(record))
            assertFails { step.pending.settleActualNext(changedCompanion) }
        }
        assertEquals(step.settledBefore.authority, step.pending.authority)
        assertEquals(step.changed.originalActionId, assertNotNull(step.pending.intent).originalActionId)
    }

    @Test
    fun settledMarkerRefusesAbsentOrOlderSerialCompanionInsteadOfAnEmptyHistoryDefault() {
        // LSR-17.e / LSR-19.c/h: marker-source comparisons, not authenticated native reads.
        val step = input.custodySigningStep()
        val settledShape = step.pending.settleActualNext(step.next)
        assertFails { settledShape.requireSettledSource(null) }
        assertFails { settledShape.requireSettledSource(step.before) }
        assertEquals(step.pending.authority, settledShape.authority)
        assertEquals(assertNotNull(step.pending.intent).next, settledShape.settled)
    }

    @Test
    fun permanentMarkerCannotBeRemovedFromIntentOrSettledRepresentations() {
        // LSR-02.c/e / LSR-13.e / LSR-19.h: cancellation/reset cannot free the reservation.
        val step = input.custodySigningStep()
        val settledShape = step.pending.settleActualNext(step.next)
        for (marker in listOf(step.pending, settledShape)) {
            assertFails { requireNativeCompanySerialCustodyTransitionV1(marker, null) }
            assertEquals(step.pending.authority, marker.authority)
            assertEquals(step.pending.originalAuthorityActionId, marker.originalAuthorityActionId)
        }
    }

    @Test
    fun matchingIssuerTextDoesNotPermitChangedFoundingOrHeadBinding() {
        // LSR-02.e/g: immutable-coordinate transition refusal only.
        val step = input.custodySigningStep()
        val settledShape = step.pending.settleActualNext(step.next)
        val authority = settledShape.authority
        val changed = listOf(
            authority.copy(origin = authority.origin.copy(foundingActionId = input.auxiliaryId(60))),
            authority.copy(origin = authority.origin.copy(headAuthorityId = input.auxiliaryId(61))),
        )
        for (candidate in changed) {
            val next = settledShape.copy(revision = settledShape.revision + 1, authority = candidate)
            assertFails { requireNativeCompanySerialCustodyTransitionV1(settledShape, next) }
        }
        assertEquals(authority.origin.originalIssuerId, settledShape.authority.origin.originalIssuerId)
        assertEquals(authority, settledShape.authority)
    }

    @Test
    fun pendingMarkerCannotBeClearedUsingItsPreviousSettledCut() {
        // LSR-18.b / LSR-19.h: no invented conclusive resolution of a pending next cut.
        val step = input.custodySigningStep()
        val acceptingFallback = step.pending.copy(revision = step.pending.revision + 1, intent = null)
        assertFails { requireNativeCompanySerialCustodyTransitionV1(step.pending, acceptingFallback) }
        assertEquals(step.settledBefore.settled, step.pending.settled)
        assertNotNull(step.pending.intent)
    }

    @Test
    fun resolvedMarkerCannotBeRewoundToThePreviousCutAndOriginalIntent() {
        // LSR-19.h: closed marker transition only, not coherent native-storage rollback fencing.
        val step = input.custodySigningStep()
        val settledShape = step.pending.settleActualNext(step.next)
        val rewound = settledShape.copy(revision = settledShape.revision + 1,
            settled = step.pending.settled, intent = step.pending.intent)
        assertFails { requireNativeCompanySerialCustodyTransitionV1(settledShape, rewound) }
        assertEquals(assertNotNull(step.pending.intent).next, settledShape.settled)
    }

    @Test
    fun emptyOrTruncatedCustodyDataDoesNotInventAResolvedMarker() {
        // LSR-19.a/c/h / LSR-21.e: empty/truncated frame slice; no guessed contract maxima.
        val marker = input.custodySigningStep().pending
        val bytes = NativeCompanySerialAuthorityCustodyWireV1.encode(marker)
        val truncated = bytes.copyOf(bytes.size - 1)
        try {
            assertFails { marker.copy(settled = null, intent = null) }
            assertFails { NativeCompanySerialAuthorityCustodyWireV1.decode(ByteArray(0)) }
            assertFails { NativeCompanySerialAuthorityCustodyWireV1.decode(truncated) }
            val retained = NativeCompanySerialAuthorityCustodyWireV1.encode(marker)
            try { assertContentEquals(bytes, retained) } finally { retained.fill(0) }
            assertNotNull(marker.intent)
        } finally { bytes.fill(0); truncated.fill(0) }
    }
}
