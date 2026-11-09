package com.ukkera.pos.app.gateway.independent.paymentcorrectionreport

import com.ukkera.pos.app.LocalBranchReportExportCoverage
import com.ukkera.pos.app.LocalBranchReportFileProfile
import com.ukkera.pos.app.LocalBranchReportFileQuery
import com.ukkera.pos.app.LocalBranchReportSection
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Public file-query constructor/serializer SUPPORT bindings for report v1 profile
 * separation and legacy omitted-profile compatibility. No export authority, native
 * host, receipt durability or whole PCR-REP-01–07 outcome is exercised here.
 */
class PaymentCorrectionReportFileProfileSupportingTest {
    private val correction = LocalBranchReportSection.PAYMENT_CORRECTIONS
    private val correctionProfile = LocalBranchReportFileProfile.PAYMENT_CORRECTION_V2
    private val food = LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS
    private val foodProfile = LocalBranchReportFileProfile.FOOD_REFUND_PAYOUT_V2
    private val cursor = DomainId("24000000-0000-4000-8000-000000000001")
    private val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val codecs = listOf(Json.Default, wire)
    private val legacySections = listOf(
        LocalBranchReportSection.CASH_SESSIONS,
        LocalBranchReportSection.COLLECTIONS,
        LocalBranchReportSection.PAID_SALES,
        LocalBranchReportSection.ACCEPTED_RETURNS,
        LocalBranchReportSection.SAFE_TRANSFERS,
        LocalBranchReportSection.FOOD_COLLECTIONS,
        LocalBranchReportSection.FOOD_PAID_SALES,
        LocalBranchReportSection.FOOD_REFUND_OBLIGATIONS,
    )

    @Test
    fun pcrRep05SupportFileCorrectionSectionRequiresItsExactExplicitProfile() {
        val query = LocalBranchReportFileQuery(correction.name, profile = correctionProfile)
        assertEquals(correction.name, query.section)
        assertEquals(correctionProfile, query.profile)
        assertFailsWith<IllegalArgumentException> { LocalBranchReportFileQuery(correction.name) }
        assertFailsWith<IllegalArgumentException> { LocalBranchReportFileQuery(correction.name, profile = null) }
        assertFailsWith<IllegalArgumentException> { LocalBranchReportFileQuery(correction.name, profile = foodProfile) }
        listOf(
            """{"section":"PAYMENT_CORRECTIONS"}""",
            """{"section":"PAYMENT_CORRECTIONS","profile":null}""",
            """{"section":"PAYMENT_CORRECTIONS","profile":"FOOD_REFUND_PAYOUT_V2"}""",
        ).forEach { encoded ->
            assertFails { wire.decodeFromString(LocalBranchReportFileQuery.serializer(), encoded) }
        }
    }

    @Test
    fun pcrRep05SupportFileCorrectionProfileCannotRelabelAnyLegacySection() {
        legacySections.forEach { section ->
            assertFailsWith<IllegalArgumentException> {
                LocalBranchReportFileQuery(section.name, profile = correctionProfile)
            }
            val encoded = """{"section":"${section.name}","profile":"PAYMENT_CORRECTION_V2"}"""
            assertFails { wire.decodeFromString(LocalBranchReportFileQuery.serializer(), encoded) }
        }
    }

    @Test
    fun pcrRep05SupportLegacyFileQueriesAcceptNullAndOmitTheProfileField() {
        legacySections.forEach { section ->
            listOf(null, cursor).forEach { after ->
                val query = LocalBranchReportFileQuery(section.name, after = after)
                assertNull(query.profile)
                codecs.forEach { codec ->
                    val encoded = codec.encodeToString(LocalBranchReportFileQuery.serializer(), query)
                    assertFalse("profile" in codec.parseToJsonElement(encoded).jsonObject)
                    assertEquals(query, codec.decodeFromString(LocalBranchReportFileQuery.serializer(), encoded))
                }
            }
            val omitted = """{"section":"${section.name}"}"""
            assertEquals(LocalBranchReportFileQuery(section.name),
                wire.decodeFromString(LocalBranchReportFileQuery.serializer(), omitted))
        }
    }

    @Test
    fun pcrRep05SupportOriginalFoodFileProfileBelongsOnlyToItsOriginalSection() {
        val query = LocalBranchReportFileQuery(food.name, after = cursor, profile = foodProfile)
        codecs.forEach { codec ->
            val encoded = codec.encodeToString(LocalBranchReportFileQuery.serializer(), query)
            assertEquals(JsonPrimitive("FOOD_REFUND_PAYOUT_V2"), codec.parseToJsonElement(encoded).jsonObject["profile"])
            assertEquals(query, codec.decodeFromString(LocalBranchReportFileQuery.serializer(), encoded))
        }
        (legacySections + correction).filter { it != food }.forEach { section ->
            assertFailsWith<IllegalArgumentException> { LocalBranchReportFileQuery(section.name, profile = foodProfile) }
            val encoded = """{"section":"${section.name}","profile":"FOOD_REFUND_PAYOUT_V2"}"""
            assertFails { wire.decodeFromString(LocalBranchReportFileQuery.serializer(), encoded) }
        }
    }

    @Test
    fun pcrRep05SupportExactCorrectionFileQueryRoundTripsItsProfileAndCursor() {
        val query = LocalBranchReportFileQuery(correction.name, after = cursor, profile = correctionProfile)
        val expected = """{"section":"PAYMENT_CORRECTIONS","after":"24000000-0000-4000-8000-000000000001","profile":"PAYMENT_CORRECTION_V2"}"""
        codecs.forEach { codec ->
            val encoded = codec.encodeToString(LocalBranchReportFileQuery.serializer(), query)
            assertEquals(codec.parseToJsonElement(expected), codec.parseToJsonElement(encoded))
            assertEquals(query, codec.decodeFromString(LocalBranchReportFileQuery.serializer(), expected))
            assertEquals(query, codec.decodeFromString(LocalBranchReportFileQuery.serializer(), encoded))
        }
    }

    @Test
    fun pcrRep05SupportCompleteFileCoverageStartsAtRootAndRejectsAnyCursor() {
        val admitted: List<Pair<LocalBranchReportSection, LocalBranchReportFileProfile?>> =
            legacySections.map { it to null } + listOf(food to foodProfile, correction to correctionProfile)
        admitted.forEach { (section, profile) ->
            val root = LocalBranchReportFileQuery(section.name, profile = profile,
                coverage = LocalBranchReportExportCoverage.COMPLETE_CURRENT_SECTION_V2)
            assertNull(root.after)
            assertEquals(LocalBranchReportExportCoverage.COMPLETE_CURRENT_SECTION_V2, root.coverage)
            assertFailsWith<IllegalArgumentException> {
                LocalBranchReportFileQuery(section.name, after = cursor, profile = profile,
                    coverage = LocalBranchReportExportCoverage.COMPLETE_CURRENT_SECTION_V2)
            }
            codecs.forEach { codec ->
                val encoded = codec.encodeToString(LocalBranchReportFileQuery.serializer(), root)
                assertEquals(root, codec.decodeFromString(LocalBranchReportFileQuery.serializer(), encoded))
                val fields = codec.parseToJsonElement(encoded).jsonObject.toMutableMap()
                fields["after"] = JsonPrimitive(cursor.value)
                assertFails { codec.decodeFromString(LocalBranchReportFileQuery.serializer(), JsonObject(fields).toString()) }
            }
        }
    }
}
