package com.ukkera.pos.security.independent.localserialregistration

import com.ukkera.pos.security.CompanyBusinessPublicationPhase
import com.ukkera.pos.security.LocalPublishedSerialRegistrationV1
import com.ukkera.pos.security.LocalSerialRegistrationPublicationCodecV1
import com.ukkera.pos.security.NativeCompanyBusinessPublicationStateV1
import com.ukkera.pos.security.NativeCompanyBusinessPublicationWireV1
import com.ukkera.pos.security.readSerialAuthorityDefinition
import com.ukkera.pos.security.readSerialRegistrationDefinition
import com.ukkera.pos.security.requireNativeSerialRegistrationReservationsV1
import com.ukkera.pos.security.reservedIdentityIdsV1
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Pure permanent-reservation comparisons on explicitly unauthenticated inert records. */
class LocalSerialRegistrationReservationSlicesTest {
    private val input = LocalSerialRegistrationInertInputs

    @Test
    fun aSecondAuthorityCannotReplaceAnyRetainedInertAuthorityPhase() {
        // LSR-02.b/c/e: retained bookkeeping exclusion, not genuine issuer provisioning.
        val original = input.authority()
        val other = original.copy(
            authorityId = input.auxiliaryId(60),
            publicationId = input.auxiliaryId(61),
            origin = original.origin.copy(headAuthorityId = input.auxiliaryId(62), originalIssuerId = input.auxiliaryId(65)),
        )
        for (phase in CompanyBusinessPublicationPhase.entries) {
            val first = input.authorityRecord(phase = phase)
            val second = input.authorityRecord(phase = CompanyBusinessPublicationPhase.PREPARED,
                definition = other, actionId = input.auxiliaryId(63), keyId = input.auxiliaryId(64))
            assertFails("LSR-02: retained $phase") { requireNativeSerialRegistrationReservationsV1(listOf(first, second)) }
            assertEquals(original, first.proposal().readSerialAuthorityDefinition())
        }
    }

    @Test
    fun absentIncompleteOrLaterAuthorityCannotSupplyRegistrationConsumption() {
        // LSR-01.a/d: no authority/native readback is supplied by this harness.
        val registration = input.registrationRecord(input.registration())
        val sku = input.skuRecord()
        val invalid = listOf(
            listOf(sku, registration),
            listOf(input.authorityRecord(CompanyBusinessPublicationPhase.PREPARED), sku, registration),
            listOf(input.authorityRecord(CompanyBusinessPublicationPhase.SIGNING_INTENT), sku, registration),
            listOf(sku, registration, input.authorityRecord()),
        )
        for (records in invalid) assertFails { requireNativeSerialRegistrationReservationsV1(records) }
        assertEquals("000AbC-07", registration.proposal().readSerialRegistrationDefinition().serialCode)
    }

    @Test
    fun duplicateExactCodeIsRefusedWithinAndAcrossSkusForEveryRetainedPhase() {
        // LSR-05.a/b: exact-code collision checks; genuine completed baseline stays UNBOUND.
        for (phase in CompanyBusinessPublicationPhase.entries) {
            for (otherSku in listOf(false, true)) {
                val original = input.registrationRecord(input.registration(), phase)
                val candidate = input.registrationRecord(input.registration(
                    registrationId = input.freshRegistrationId, serialId = input.freshSerialId,
                    code = "000AbC-07", otherSku = otherSku, publicationId = input.auxiliaryId(60)),
                    actionId = input.auxiliaryId(61), keyId = input.auxiliaryId(62))
                assertFails("LSR-05.a/b: $phase otherSku=$otherSku") {
                    requireNativeSerialRegistrationReservationsV1(input.prefixRecords() + original + candidate)
                }
                assertEquals("000AbC-07", original.proposal().readSerialRegistrationDefinition().serialCode)
            }
        }
    }

    @Test
    fun duplicateSerialIdIsRefusedEvenWhenCodeOrSkuDiffers() {
        // LSR-05.c/d: a changed exact code or SKU cannot free the permanent serial ID.
        for (phase in CompanyBusinessPublicationPhase.entries) {
            for (otherSku in listOf(false, true)) {
                val original = input.registrationRecord(input.registration(), phase)
                val candidate = input.registrationRecord(input.registration(
                    registrationId = input.freshRegistrationId, serialId = input.serialId,
                    code = "000AbC-08", otherSku = otherSku, publicationId = input.auxiliaryId(60)),
                    actionId = input.auxiliaryId(61), keyId = input.auxiliaryId(62))
                assertFails("LSR-05.c/d: $phase otherSku=$otherSku") {
                    requireNativeSerialRegistrationReservationsV1(input.prefixRecords() + original + candidate)
                }
                assertEquals(input.serialId, original.proposal().readSerialRegistrationDefinition().serialId)
            }
        }
    }

    @Test
    fun originalSerialAndCodeCannotBeAssignedToAnotherSku() {
        // LSR-05.e: second inert identity, same exact serial/code, different SKU.
        val original = input.registrationRecord(input.registration(), CompanyBusinessPublicationPhase.COMPLETED)
        val candidate = input.registrationRecord(input.registration(
            registrationId = input.freshRegistrationId, serialId = input.serialId,
            code = "000AbC-07", otherSku = true, publicationId = input.auxiliaryId(60)),
            actionId = input.auxiliaryId(61), keyId = input.auxiliaryId(62))
        assertFails { requireNativeSerialRegistrationReservationsV1(input.prefixRecords() + original + candidate) }
        assertEquals(input.skuId, original.proposal().readSerialRegistrationDefinition().skuId)
    }

    @Test
    fun caseAndLeadingZeroVariantsStayDistinctInTheRepresentationComparison() {
        // LSR-05.f/g: distinct-input comparison only; conditional native success is NOT exercised.
        for (code in listOf("000abc-07", "0AbC-07")) {
            val original = input.registrationRecord(input.registration(), CompanyBusinessPublicationPhase.COMPLETED)
            val candidate = input.registrationRecord(input.registration(
                registrationId = input.freshRegistrationId, serialId = input.freshSerialId,
                code = code, otherSku = true, publicationId = input.auxiliaryId(60)),
                actionId = input.auxiliaryId(61), keyId = input.auxiliaryId(62))
            requireNativeSerialRegistrationReservationsV1(input.prefixRecords() + original + candidate)
            assertEquals("000AbC-07", original.proposal().readSerialRegistrationDefinition().serialCode)
            assertEquals(code, candidate.proposal().readSerialRegistrationDefinition().serialCode)
        }
    }

    @Test
    fun successorReservationRefusesEveryChangedPermanentCoordinate() {
        // LSR-06.a-e / LSR-07.g: content or reservation refusal, not native membership/CAS.
        val before = input.historyThroughDesiredRevisionThree().last()
        val request = input.successor(before)
        val mutations = listOf<Pair<String, (LocalPublishedSerialRegistrationV1) -> LocalPublishedSerialRegistrationV1>>(
            "LSR-06.a" to { it: LocalPublishedSerialRegistrationV1 -> it.copy(registrationId = input.freshRegistrationId) },
            "LSR-06.b" to { it: LocalPublishedSerialRegistrationV1 -> it.copy(serialId = input.freshSerialId) },
            "LSR-06.c" to { it: LocalPublishedSerialRegistrationV1 -> it.copy(serialCode = "000abc-07") },
            "LSR-06.d" to { it: LocalPublishedSerialRegistrationV1 -> it.copy(serialCode = "0AbC-07") },
            "LSR-06.e/LSR-07.g" to { it: LocalPublishedSerialRegistrationV1 -> it.copy(skuId = input.otherSkuId,
                skuPublicationId = input.otherSkuPublicationId, skuPublicationDigest = input.skuRecord(otherSku = true).digest) },
            "LSR-06.e namespace" to { it: LocalPublishedSerialRegistrationV1 -> it.copy(serialAuthority = it.serialAuthority.copy(contentDigest = input.differentInertDigest)) },
        )
        for ((variant, mutate) in mutations) {
            assertFails(variant) {
                val candidate = input.registrationRecord(mutate(request))
                requireNativeSerialRegistrationReservationsV1(input.prefixRecords() + input.completedShapeHistory() + candidate)
            }
        }
        assertEquals(input.registrationId, before.registrationId)
        assertEquals("000AbC-07", before.serialCode)
        assertEquals(Revision(3), before.revision)
    }

    @Test
    fun ancestorOrEqualFieldPredecessorDigestCannotExtendTheCurrentInertHistory() {
        // LSR-07.d/e / LSR-12.d: exact predecessor comparison; no native original is claimed.
        val history = input.historyThroughDesiredRevisionThree()
        val ancestor = history[1]
        val current = history[2]
        val stale = input.registration(publicationId = input.successorPublicationId, predecessor = ancestor)
        assertEquals(Revision(2), ancestor.revision)
        assertEquals(Revision(3), stale.revision)
        val wrongDigest = input.successor(current).copy(previous =
            LocalSerialRegistrationPublicationCodecV1.reference(current).copy(contentDigest = input.differentInertDigest))
        for (request in listOf(stale, wrongDigest)) {
            assertFails {
                requireNativeSerialRegistrationReservationsV1(input.prefixRecords() + input.completedShapeHistory() + input.registrationRecord(request))
            }
        }
        assertEquals(Revision(3), current.revision)
        assertEquals(input.predecessorPublicationId, current.publicationId)
    }

    @Test
    fun incompletePredecessorCannotAuthorizeAnotherSuccessor() {
        // LSR-07.e: PREPARED/SIGNING_INTENT shapes are not completed predecessor content.
        val request = input.successor(input.historyThroughDesiredRevisionThree().last())
        for (phase in listOf(CompanyBusinessPublicationPhase.PREPARED, CompanyBusinessPublicationPhase.SIGNING_INTENT)) {
            assertFails("LSR-07.e: $phase") {
                requireNativeSerialRegistrationReservationsV1(input.prefixRecords() + input.completedShapeHistory(phase) + input.registrationRecord(request))
            }
        }
        assertEquals(Revision(4), request.revision)
    }

    @Test
    fun twoInertSuccessorsCannotBothExtendOneImmediatePredecessor() {
        // LSR-15.a exclusion slice ONLY: no concurrency, healthy positive winner or Master CAS.
        val current = input.historyThroughDesiredRevisionThree().last()
        val first = input.registrationRecord(input.successor(current))
        val second = input.registrationRecord(input.successor(current).copy(publicationId = input.auxiliaryId(60)),
            actionId = input.auxiliaryId(61), keyId = input.auxiliaryId(62))
        assertFails {
            requireNativeSerialRegistrationReservationsV1(input.prefixRecords() + input.completedShapeHistory() + first + second)
        }
        assertEquals(input.predecessorPublicationId, current.publicationId)
        assertEquals(Revision(3), current.revision)
    }

    @Test
    fun selectedSkuPublicationMustPrecedeRegistrationAndMatchExactCoordinates() {
        // LSR-10.i: missing/incomplete/wrong original-coordinate refusal only.
        val request = input.registration()
        val registration = input.registrationRecord(request)
        val authority = input.authorityRecord()
        val invalid = listOf(
            listOf(authority, registration),
            listOf(authority, input.skuRecord(phase = CompanyBusinessPublicationPhase.PREPARED), registration),
            listOf(authority, input.skuRecord(otherSku = true), registration),
            listOf(authority, registration, input.skuRecord()),
            listOf(authority, input.skuRecord(), input.registrationRecord(request.copy(skuPublicationDigest = input.differentInertDigest))),
        )
        for (records in invalid) assertFails { requireNativeSerialRegistrationReservationsV1(records) }
        assertEquals(input.skuId, request.skuId)
        assertEquals(input.earlierSkuPublicationId, request.skuPublicationId)
    }

    @Test
    fun reservationCoordinatesIncludeTheSerialIdInEveryRetainedPhase() {
        // LSR-05.c/d / D1-B: supplemental identity-coordinate inspection, not native reservation.
        for (phase in CompanyBusinessPublicationPhase.entries) {
            val record = input.registrationRecord(input.registration(), phase)
            val reserved = record.reservedIdentityIdsV1()
            assertTrue(input.serialId in reserved)
            assertTrue(input.registrationId in reserved)
        }
    }

    @Test
    fun completedInertContentCannotBeEditedInPlaceUnderItsOldActionAndPublication() {
        // LSR-12.a: transition refusal only; no genuine completed native original is fabricated.
        val definition = input.registration()
        val record = input.registrationRecord(definition, CompanyBusinessPublicationPhase.COMPLETED)
        val before = NativeCompanyBusinessPublicationStateV1(0, input.prefixRecords() + record)
        val changed = input.registrationRecord(definition.copy(serialCode = "000abc-07"), CompanyBusinessPublicationPhase.COMPLETED)
        val next = NativeCompanyBusinessPublicationStateV1(1, input.prefixRecords() + changed)
        val originalBytes = NativeCompanyBusinessPublicationWireV1.encode(before)
        try {
            assertFails { NativeCompanyBusinessPublicationWireV1.validateTransition(before, next) }
            val retainedBytes = NativeCompanyBusinessPublicationWireV1.encode(before)
            try { assertContentEquals(originalBytes, retainedBytes) } finally { retainedBytes.fill(0) }
            assertEquals("000AbC-07", record.proposal().readSerialRegistrationDefinition().serialCode)
        } finally { originalBytes.fill(0) }
    }
}
