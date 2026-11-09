package com.ukkera.pos.sales.store.independent.returnpendingprofilecompatibilityv1

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.store.LocalReturnCaseActionV1
import com.ukkera.pos.sales.store.LocalReturnCaseAssessmentV1
import com.ukkera.pos.sales.store.LocalReturnCaseCommandCodecV1
import com.ukkera.pos.sales.store.LocalReturnCaseEventV1
import com.ukkera.pos.sales.store.LocalReturnCaseProfileV1
import com.ukkera.pos.sales.store.LocalReturnCaseStageV1
import com.ukkera.pos.sales.store.LocalReturnCommercialWindowStatusV1
import com.ukkera.pos.sales.store.LocalReturnOrdinaryAssessmentV1
import com.ukkera.pos.sales.store.localSalesDecode
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.sales.store.returnCaseHistoryPendingQualification
import com.ukkera.pos.sales.store.returnCaseHistoryUnavailableQualification
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bounded DATA/serialization support for frozen RPHC-01–04 only. These call the
 * real retained-profile predicates with synthetic DTOs; true is a discriminant,
 * never a successful join/replay or proof of genuine original history.
 * Complete source preparation, native/core anchors, access and no-effect balances
 * remain unbound. No historical command or accepting security object is constructed.
 */
class ReturnPendingProfileCompatibilityFrozenDataTest {
    private val data = ReturnPendingProfileDataFixture
    private val frozen = FrozenReturnPendingProfileInputs

    @Test
    fun rphc01_legacyShape_retainsAbsentProfileAndCurrentSerializationOnly() {
        val profile = data.profile("L")
        val retained = data.retained(profile)
        val requestBefore = retained.normalized.copyBytes()
        val resultBefore = retained.result.copyBytes()
        val eventBefore = retained.aggregateWrites.single().payload.copyBytes()

        assertTrue(returnCaseHistoryPendingQualification(retained, profile.request))
        assertTrue(returnCaseHistoryUnavailableQualification(retained, profile.request, emptySet()))
        assertFalse("recordOriginalCommercialTiming" in json(retained.normalized))
        val decoded = decode(retained.aggregateWrites.single().payload)
        val assessment = assertNotNull(decoded.assessment)
        assertEquals(LocalReturnOrdinaryAssessmentV1.PENDING, assessment.outcome)
        assertTrue(decoded.qualificationSources.isEmpty())
        assertTrue(decoded.sourceRecoveryResources.isEmpty())
        assertNull(assessment.salePolicyCapture)
        assertTrue(assessment.applicableRights.isEmpty())
        assertTrue(assessment.customerReceipts.isEmpty())
        assertNull(assessment.originalCommercialTiming)
        assertFalse("originalCommercialTiming" in json(localSalesEncode(assessment, LocalReturnCaseAssessmentV1.serializer())))
        assertContentEquals(eventBefore, localSalesEncode(decoded, LocalReturnCaseEventV1.serializer()).copyBytes())
        assertContentEquals(requestBefore, retained.normalized.copyBytes())
        assertContentEquals(resultBefore, retained.result.copyBytes())
        assertContentEquals(eventBefore, retained.aggregateWrites.single().payload.copyBytes())
        // No newer source is installed or invoked, and these are not historical byte goldens.
    }

    @Test
    fun rphc02_s0RetainedReferences_withoutTiming_neverSelectUnavailableProfileDataOnly() {
        val profile = data.profile("S0")
        val retained = data.retained(profile)
        assertTrue(returnCaseHistoryPendingQualification(retained, profile.request))
        assertFalse(returnCaseHistoryUnavailableQualification(retained, profile.request, emptySet()))
        val decoded = decode(retained.aggregateWrites.single().payload)
        assertNull(assertNotNull(decoded.assessment).originalCommercialTiming)
        assertEquals(profile.event.qualificationSources, decoded.qualificationSources)
        assertEquals(profile.event.sourceRecoveryResources, decoded.sourceRecoveryResources)
        assertEquals(DomainId(frozen.sources.text("retainedRightsPublication")),
            assertNotNull(decoded.assessment).applicableRights.single().id)
    }

    @Test
    fun rphc02_s1RetainedTiming_exactFrozenNumbersAndReferencesDataOnly() {
        val profile = data.profile("S1")
        val retained = data.retained(profile)
        assertTrue(returnCaseHistoryPendingQualification(retained, profile.request))
        assertFalse(returnCaseHistoryUnavailableQualification(retained, profile.request, emptySet()))
        val bytes = retained.aggregateWrites.single().payload
        val decoded = decode(bytes)
        val assessment = assertNotNull(decoded.assessment)
        val timing = assertNotNull(assessment.originalCommercialTiming)
        assertEquals(frozen.sources.text("capturedOrdinaryCommercialDays").toInt(), timing.days)
        assertEquals(UtcInstant.parseCanonical(frozen.sources.text("customerReceiptAt")), timing.receivedAt)
        assertEquals(frozen.sources.text("customerReceiptZone"), timing.receiptTimeZoneId)
        assertEquals(UtcInstant.parseCanonical(frozen.sources.text("originalMasterSubmissionAt")), timing.submittedAt)
        assertEquals(UtcInstant.parseCanonical(frozen.sources.text("exactInclusiveExpiry")), timing.expiresAt)
        assertEquals(LocalReturnCommercialWindowStatusV1.WITHIN, timing.status)
        val seconds = assertNotNull(timing.expiresAt).value.epochSeconds - timing.receivedAt.value.epochSeconds
        assertEquals(frozen.sources.text("elapsedSeconds").toLong(), seconds)
        assertEquals(frozen.sources.text("elapsedHours").toLong(), seconds / 3_600)
        assertEquals(DomainId(frozen.sources.text("receiptEventS1")), timing.receiptEventId)
        assertEquals(profile.event.after.originalOperationId, timing.submissionOperationId)
        assertNotEquals(UtcInstant.parseCanonical(frozen.current.text("laterProcessingAt")), timing.submittedAt)
        assertNotEquals(frozen.current.text("currentOrdinaryCommercialDays").toInt(), timing.days)
        assertEquals(DomainId(frozen.sources.text("retainedRightsPublication")), assessment.applicableRights.single().id)
        assertNotEquals(DomainId(frozen.current.text("rightsPublication")), assessment.applicableRights.single().id)
        assertEquals(profile.event.qualificationSources, decoded.qualificationSources)
        assertEquals(profile.event.sourceRecoveryResources, decoded.sourceRecoveryResources)
        assertContentEquals(bytes.copyBytes(), localSalesEncode(decoded, LocalReturnCaseEventV1.serializer()).copyBytes())
        // Current pointer/time facts are comparison literals, not a changed production store.
    }

    @Test
    fun rphc03h_exactBeforeResources_addedDroppedOrForeignNeverSelectUnavailableDataOnly() {
        val profile = data.profile("L")
        val originalSale = DomainId(frozen.original("L").text("sale"))
        val expected = setOf(RecoveryResourceKey(RecoveryResourceKind.REFUND_ENTITLEMENT, originalSale),
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, data.actor))
        val baseline = profile.event.copy(sourceRecoveryResources = data.sortedResources(expected))
        val retainedBaseline = data.retained(profile, baseline)
        assertTrue(returnCaseHistoryPendingQualification(retainedBaseline, profile.request))
        assertTrue(returnCaseHistoryUnavailableQualification(retainedBaseline, profile.request, expected))
        val foreign = RecoveryResourceKey(RecoveryResourceKind.REFUND_ENTITLEMENT,
            DomainId(frozen.foreign.text("sale")))
        val variants = listOf(
            baseline.copy(sourceRecoveryResources = data.sortedResources(expected + foreign)),
            baseline.copy(sourceRecoveryResources = data.sortedResources(expected.filterNot { it.resourceId == originalSale })),
            baseline.copy(sourceRecoveryResources = data.sortedResources(expected.filterNot { it.resourceId == originalSale } + foreign)),
        )
        for (event in variants) assertFalse(returnCaseHistoryUnavailableQualification(
            data.retained(profile, event), profile.request, expected))
        assertEquals(data.sortedResources(expected), decode(retainedBaseline.aggregateWrites.single().payload).sourceRecoveryResources)
        // The supplied expected set is DATA; genuine BEFORE entitlement derivation is unbound.
    }

    @Test
    fun rphc03h_droppedEventReference_cannotSelectPendingOrUnavailableProfileDataOnly() {
        for (logicalId in listOf("S0", "S1")) {
            val profile = data.profile(logicalId)
            val untouched = data.retained(profile)
            for (reference in profile.event.qualificationSources) {
                val changed = profile.event.copy(qualificationSources = profile.event.qualificationSources - reference)
                val retained = data.retained(profile, changed)
                assertFalse(returnCaseHistoryPendingQualification(retained, profile.request))
                assertFalse(returnCaseHistoryUnavailableQualification(retained, profile.request, emptySet()))
            }
            assertEquals(profile.event.qualificationSources, decode(untouched.aggregateWrites.single().payload).qualificationSources)
        }
        // No referenced source row/producer is removed or validated in this DATA slice.
    }

    @Test
    fun rphc03h_droppedTiming_withRetainedSourcesNeverSelectsUnavailableProfileDataOnly() {
        val profile = data.profile("S1")
        val changed = profile.event.copy(assessment = assertNotNull(profile.event.assessment).copy(originalCommercialTiming = null))
        assertFalse(returnCaseHistoryUnavailableQualification(data.retained(profile, changed), profile.request, emptySet()))
        assertNotNull(profile.event.assessment?.originalCommercialTiming)
        // Literal classification does not establish complete core refusal of the changed bundle.
    }

    @Test
    fun rphc03h_emptyReferencesAndUnavailableAssessment_residualResourcesNeverSelectLegacyDataOnly() {
        val profile = data.profile("L")
        for (logicalId in listOf("S0", "S1")) {
            for (resource in data.profile(logicalId).event.sourceRecoveryResources) {
                val changed = profile.event.copy(sourceRecoveryResources = listOf(resource))
                assertTrue(changed.qualificationSources.isEmpty())
                assertFalse(returnCaseHistoryUnavailableQualification(data.retained(profile, changed), profile.request, emptySet()))
            }
        }
        // One resource-list mutation per DTO. Genuine S0/S1 missing-source recovery is unbound.
    }

    @Test
    fun rphc03h_changedResultStageActionOrRevision_cannotSelectEitherLiteralProfileDataOnly() {
        val profile = data.profile("L")
        val variants = listOf(
            profile.event.copy(result = profile.event.result.copy(stage = LocalReturnCaseStageV1.BOUND_UNACCEPTED)),
            profile.event.copy(result = profile.event.result.copy(action = LocalReturnCaseActionV1.RECORDED)),
            profile.event.copy(after = profile.event.after.copy(revision = profile.event.after.revision.next())),
        )
        for (event in variants) {
            val retained = data.retained(profile, event)
            assertFalse(returnCaseHistoryPendingQualification(retained, profile.request))
            assertFalse(returnCaseHistoryUnavailableQualification(retained, profile.request, emptySet()))
        }
        // Unchecked result/before/write/requirement anchors remain unbound, not assumed rejected.
    }

    @Test
    fun rphc03h_missingOrChangedEventWriteCoordinates_cannotSelectEitherLiteralProfileDataOnly() {
        val profile = data.profile("L")
        val variants = listOf(
            emptyList(),
            listOf(data.write(profile, generation = Revision(2))),
            listOf(data.write(profile, schema = "sales.return.case-event/v2")),
            listOf(data.write(profile, key = AggregateKey(LocalReturnCaseProfileV1.EVENT, DomainId(frozen.foreign.text("receiptEvent"))))),
        )
        for (writes in variants) {
            val retained = data.retained(profile, writes = writes)
            assertFalse(returnCaseHistoryPendingQualification(retained, profile.request))
            assertFalse(returnCaseHistoryUnavailableQualification(retained, profile.request, emptySet()))
        }
    }

    @Test
    fun rphc04_changedIntentOrActor_distinctCanonicalDataAndSemanticDigestOnly() {
        val profile = data.profile("S1")
        val original = LocalReturnCaseCommandCodecV1.encode(profile.request)
        val changedIntent = LocalReturnCaseCommandCodecV1.encode(profile.request.copy(recordOriginalCommercialTiming = false))
        assertNotEquals(original, changedIntent)
        assertFalse("recordOriginalCommercialTiming" in json(changedIntent))
        val digest = semanticDigest(data.scope, data.actor, LocalReturnCaseProfileV1.COMMAND, original)
        assertNotEquals(digest, semanticDigest(data.scope, data.actor, LocalReturnCaseProfileV1.COMMAND, changedIntent))
        assertNotEquals(digest, semanticDigest(data.scope, DomainId(frozen.scope.text("differentActor")),
            LocalReturnCaseProfileV1.COMMAND, original))
        assertContentEquals(original.copyBytes(), LocalReturnCaseCommandCodecV1.encode(profile.request).copyBytes())
        // No staged retry conflict, current access, historical false form or grant is exercised.
    }

    private fun decode(bytes: StoredBytes): LocalReturnCaseEventV1 = localSalesDecode(bytes, LocalReturnCaseEventV1.serializer())
    private fun json(bytes: StoredBytes) = Json.parseToJsonElement(bytes.copyBytes().decodeToString()).jsonObject
}
