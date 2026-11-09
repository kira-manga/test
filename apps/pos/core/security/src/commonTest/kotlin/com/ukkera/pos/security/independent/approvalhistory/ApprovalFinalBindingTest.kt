package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Inert declaration checks mapped to frozen OH-02/04/05/07; no native origin,
 * issued approval, authentication, SQL transaction or commit is constructed. */
class ApprovalFinalBindingTest {
    private val operation = DomainId("80000000-0000-4000-8000-000000000001")
    private val otherOperation = DomainId("80000000-0000-4000-8000-000000000002")
    private val actor = DomainId("40000000-0000-4000-8000-000000000001")
    private val otherActor = DomainId("40000000-0000-4000-8000-000000000002")
    private val session = DomainId("60000000-0000-4000-8000-000000000001")
    private val digest = PayloadDigest("5aaab7800c516cc0e11f1ab8bee445654d11b957579e3be1bd245270e4fa524c")
    private val otherDigest = PayloadDigest("14a3d3cdea2c5055efe3b3b467e7f4c9271e3baa2273ea1155ae26e6bf7baf02")
    // ABI-only carrier values: the frozen packet does not bind native boot/issuer IDs.
    private val boot = DomainId("00000000-0000-4000-8000-000000000081")
    private val issuer = DomainId("00000000-0000-4000-8000-000000000082")
    private val otherNativeId = DomainId("00000000-0000-4000-8000-000000000083")
    private val deadline = UtcInstant.parseCanonical("2026-11-01T12:05:00Z")

    private fun intent(purpose: Int = 2, request: DomainId = operation,
        content: PayloadDigest = digest, originalActor: DomainId = actor,
        target: DomainId? = operation): NativeHistoryOriginalIntentV2 =
        NativeHistoryOriginalIntentV2(purpose, request, content, target, originalActor,
            digest, digest, emptyList())

    private fun protected(): NativeHistoryProtectedFinalDeclaration =
        NativeHistoryProtectedFinalDeclaration(operation, digest, actor, session, issuer,
            MonotonicMoment(boot, 0), MonotonicMoment(boot, 1), 300_000)

    private fun wall(): NativeHistoryFinalDeadlineDeclaration =
        NativeHistoryFinalDeadlineDeclaration(operation, operation, digest, deadline)

    @Test fun sameApplyCoordinatesCanBePairedAsDataWithoutCreatingAuthority() {
        val protected = protected()
        val wall = wall()
        requireNativeApprovalFinalPair(intent(), wall, protected)
        assertEquals(operation, protected.originalRequestId)
        assertEquals(operation, wall.originalOperationId)
        assertEquals(digest, protected.originalRequestDigest)
        assertEquals(digest, wall.originalRequestDigest)
    }

    @Test fun legacyNullDeclarationsRemainAbsent() {
        requireNativeApprovalFinalPair(intent(), null, null)
        requireNativeApprovalFinalPair(intent(purpose = 3), null, null)
    }

    @Test fun unconsumedRejectionHasNoApplyWallPair() {
        requireNativeApprovalFinalPair(intent(purpose = 3), null, protected())
        assertFailsWith<IllegalArgumentException> {
            requireNativeApprovalFinalPair(intent(purpose = 3), wall(), protected())
        }
    }

    @Test fun anotherTargetOperationCannotReceiveOriginalPair() {
        assertFailsWith<IllegalArgumentException> {
            requireNativeApprovalFinalPair(intent(target = otherOperation), wall(), protected())
        }
    }

    @Test fun anotherRequestCannotReceiveOriginalPair() {
        assertFailsWith<IllegalArgumentException> {
            requireNativeApprovalFinalPair(intent(request = otherOperation), wall(), protected())
        }
    }

    @Test fun changedContentCannotReceiveOriginalPair() {
        assertFailsWith<IllegalArgumentException> {
            requireNativeApprovalFinalPair(intent(content = otherDigest), wall(), protected())
        }
    }

    @Test fun changedActorCannotReceiveOriginalPair() {
        assertFailsWith<IllegalArgumentException> {
            requireNativeApprovalFinalPair(intent(originalActor = otherActor), wall(), protected())
        }
    }

    @Test fun observationAtExpiryCannotServeAsFinalProtectedCheck() {
        assertFailsWith<IllegalArgumentException> {
            protected().requireObservation(LocalAuthenticationObservation(issuer, MonotonicMoment(boot, 300_000)))
        }
    }

    @Test fun observationBeforeRecordedCutCannotServeAsFinalProtectedCheck() {
        assertFailsWith<IllegalArgumentException> {
            protected().requireObservation(LocalAuthenticationObservation(issuer, MonotonicMoment(boot, 0)))
        }
    }

    @Test fun observationFromAnotherBootCannotServeAsFinalProtectedCheck() {
        assertFailsWith<IllegalArgumentException> {
            protected().requireObservation(LocalAuthenticationObservation(issuer, MonotonicMoment(otherNativeId, 2)))
        }
    }

    @Test fun observationFromAnotherIssuerCannotServeAsFinalProtectedCheck() {
        assertFailsWith<IllegalArgumentException> {
            protected().requireObservation(LocalAuthenticationObservation(otherNativeId, MonotonicMoment(boot, 2)))
        }
    }

    @Test fun changedProtectedDeclarationCannotBePairedWithOriginalApply() {
        for (changed in listOf(protected().copy(originalRequestId = otherOperation),
            protected().copy(originalRequestDigest = otherDigest), protected().copy(actorId = otherActor))) {
            assertFailsWith<IllegalArgumentException> { requireNativeApprovalFinalPair(intent(), wall(), changed) }
        }
    }
}
