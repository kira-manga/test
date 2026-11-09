package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Static fixture-binding checks required by the E3-002 execution brief. These are not
 * runtime cases: they prove that the immutable v3 files, the v3-errata-v1 overlay and the
 * reviewed v3-errata-v2 candidate resolve to the accepted 41-case/168-variant inventory and
 * that every launch-record vector and named coordinate profile rebuilds from its frozen
 * builder before any coordinator behavior is executed.
 */
public class ExistingOnlyStartupFixtureIntegrityTest {
    private val fixtures = ExistingOnlyStartupFixtures

    @Test
    public fun effectiveInventoryResolvesToFortyOneCasesAndOneHundredSixtyEightVariants() {
        assertEquals(41, fixtures.effectiveCases.size, "effective case count")
        assertEquals(168, fixtures.variants.size, "effective variant count")
        assertEquals(168, fixtures.effectiveCases.flatMap { it.variants }.size, "flat variant count")
        assertEquals(168, fixtures.effectiveCases.flatMap { it.variants }.map { it.variantId }.distinct().size,
            "unique variant IDs")
        assertEquals(35, fixtures.templateCount, "effective template count")
        val expectedClasses = fixtures.effectiveCases.flatMap { it.variants }
            .groupingBy { it.string("expected") }.eachCount()
        assertEquals(44, expectedClasses.getValue("MASTER_HELD"), "MASTER_HELD count")
        assertEquals(28, expectedClasses.getValue("NO_ACQUISITION"), "NO_ACQUISITION count")
        assertEquals(24, expectedClasses.getValue("RETIRED_CLOSED"), "RETIRED_CLOSED count")
        assertEquals(22, expectedClasses.getValue("RETIRED_UNRESOLVED"), "RETIRED_UNRESOLVED count")
        assertEquals(14, expectedClasses.getValue("TARGET_HELD_MISMATCH"), "TARGET_HELD_MISMATCH count")
        assertEquals(12, expectedClasses.getValue("PUBLISHED"), "PUBLISHED count")
        assertEquals(12, expectedClasses.getValue("STALE_REJECTED"), "STALE_REJECTED count")
        assertEquals(7, expectedClasses.getValue("TARGET_FAILED"), "TARGET_FAILED count")
        assertEquals(3, expectedClasses.getValue("CLIENT_HELD"), "CLIENT_HELD count")
        assertEquals(2, expectedClasses.getValue("RETIRED_DEFENSE_CONDITIONAL"), "defense-conditional count")
        val custody = fixtures.effectiveCases.flatMap { it.variants }
            .groupingBy { fixtures.custodyClass(it.variantId) }.eachCount()
        assertEquals(mapOf(
            "MASTER_NATIVE_TARGET_STALE" to 84,
            "NO_NATIVE_OWNER" to 28,
            "NO_TARGET" to 7,
            "TARGET_CUSTODY_INDETERMINATE" to 8,
            "TARGET_NATIVE_PRE_TRANSFER" to 41,
        ), custody, "custody class census")
    }

    @Test
    public fun everyLaunchRecordVectorRebuildsToItsFrozenLengthAndHash() {
        assertEquals(34, fixtures.records.size, "launch-record vector count")
        val census = fixtures.records.values.groupingBy { it.classification }.eachCount()
        assertEquals(mapOf("RUN" to 14, "STOP" to 1, "ABSENT" to 1, "READ_ERROR" to 1, "UNRESOLVED" to 17),
            census, "launch-record classification census")
        assertEquals(478, fixtures.record("REC-RUN-CANONICAL").bytes!!.size, "canonical RUN length")
        assertEquals(35, fixtures.record("REC-STOP").bytes!!.size, "STOP length")
        assertEquals(0, fixtures.record("REC-EMPTY").bytes!!.size, "empty record length")
        assertTrue((fixtures.record("REC-OVERSIZE").bytes?.size ?: 0) > 1024, "oversize vector")
        assertTrue(fixtures.record("REC-READ-THROW").readThrows, "read-throw vector")
        assertEquals(null, fixtures.record("REC-ABSENT").bytes, "absent vector")
        val canonical = fixtures.canonical
        val foreign = fixtures.foreign
        assertEquals(12, canonical.distinct().size, "canonical distinctness")
        assertEquals(12, foreign.distinct().size, "foreign distinctness")
        assertTrue(canonical.toSet().intersect(foreign.toSet()).isEmpty(), "canonical/foreign disjointness")
        for (index in 0..11) {
            val vector = fixtures.record("REC-RUN-FOREIGN-${(index + 1).toString().padStart(2, '0')}").coordinates
                ?: error("foreign RUN vector is missing")
            assertEquals(listOf(index), canonical.indices.filter { canonical[it] != vector[it] },
                "foreign RUN vector $index must differ at exactly one coordinate")
        }
    }

    @Test
    public fun namedCoordinateProfilesAndOptionalDiagnosticsAreBound() {
        for (index in 0..11) {
            val suffix = (index + 1).toString().padStart(2, '0')
            val expectedName = fixtures.coordinateOrder[index].uppercase()
            val targetProfile = fixtures.profile("targets", "TGT-FOREIGN-$suffix")
            assertEquals(index, targetProfile.getValue("coordinates").jsonObject.getValue("replace_index").jsonPrimitive.int,
                "TGT-FOREIGN-$suffix replace_index")
            assertEquals("canonical", targetProfile.getValue("coordinates").jsonObject.requiredString("source"),
                "TGT-FOREIGN-$suffix coordinate source")
            assertEquals("foreign", targetProfile.getValue("coordinates").jsonObject.requiredString("replacement_source"),
                "TGT-FOREIGN-$suffix replacement source")
            val bindingProfile = fixtures.profile("bindings", "BND-CHANGED-$suffix")
            assertEquals(index, bindingProfile.requiredInt("foreign_index"), "BND-CHANGED-$suffix foreign_index")
            assertEquals("canonical", bindingProfile.requiredString("material_source"),
                "BND-CHANGED-$suffix material source")
            assertEquals("canonical_with_foreign_at", bindingProfile.requiredString("binding_source"),
                "BND-CHANGED-$suffix binding source")
            val mismatch = fixtures.variants.getValue("E3V3-D3-02.V${suffix}-MISMATCH-$suffix-$expectedName")
            assertEquals("TGT-FOREIGN-$suffix", mismatch.string("target"), "D3-02 $suffix target profile")
            val changed = fixtures.variants.getValue("E3V3-D3-03.V${suffix}-BINDING-CHANGED-$suffix-$expectedName")
            assertEquals("BND-CHANGED-$suffix", changed.string("binding"), "D3-03 $suffix binding profile")
            val built = ExistingOnlyStartupData.coordinatesWith("canonical", index)
            val canonical = ExistingOnlyStartupData.canonicalCoordinates
            assertEquals(listOf(index), (0..11).filter { built.component(it) != canonical.component(it) },
                "TGT-FOREIGN-$suffix built coordinates")
            assertEquals(fixtures.foreign[index], built.component(index), "TGT-FOREIGN-$suffix foreign value")
        }
        val optional = fixtures.variants.values.filter {
            val id = it.string("close_target")
            id != "NONE" && fixtures.profile("closes", id)["optional"]?.jsonPrimitive?.boolean == true
        }
        assertEquals(setOf("E3V3-D8-06.V01-TARGET-CLOSE-UNIT", "E3V3-D8-06.V02-TARGET-CLOSE-THROW"),
            optional.map { it.variantId }.toSet(), "optional target-close diagnostics")
        assertEquals(3, fixtures.case("E3V3-D8-06").variants.size, "D8-06 variant count")
        assertEquals(setOf("E3V3-D8-06.V01-TARGET-CLOSE-UNIT", "E3V3-D8-06.V02-TARGET-CLOSE-THROW",
            "E3V3-D8-06.V03-TARGET-CLOSE-NOT-ENTERED"),
            fixtures.case("E3V3-D8-06").variants.map { it.variantId }.toSet(), "D8-06 variants")
    }

    @Test
    public fun c9CombinationsAndConditionalDefenseProfilesAreEffective() {
        val c9 = listOf("SET-VALID", "SET-DEF-NULL-RECEIPT-PRESENT", "SET-RECEIPT-NULL", "SET-BOTH-NULL")
        for (setup in c9) {
            val published = fixtures.variants.values.filter {
                it.string("setup") == setup && it.string("expected") == "PUBLISHED"
            }
            assertTrue(published.isNotEmpty(), "$setup must resolve to a PUBLISHED variant")
        }
        val defense = fixtures.variants.values.filter { it.string("withdraw") == "WD-DEFENSIVE-THROW" }
        assertEquals(2, defense.size, "WD-DEFENSIVE-THROW selection count")
        assertEquals(setOf("E3V3-D7-08.V01-DEFENSIVE-THROW", "E3V3-D7-08.V02-DEFENSIVE-THROW-REPEAT"),
            defense.map { it.variantId }.toSet(), "defensive-throw variants")
        val forbidden = fixtures.variants.values.filter { it.string("withdraw") == "WD-FORBIDDEN" }
        assertEquals(28, forbidden.size, "WD-FORBIDDEN is confined to the non-RUN inventory")
        assertTrue(forbidden.all { fixtures.record(it.string("record")).classification != "RUN" },
            "WD-FORBIDDEN must not appear on a RUN disposition")
    }
}
