package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Static binding of the accepted supplemental-v8 design. This test proves only that the exact
 * eleven reviewed files, the 11/52 inventory, the eight surviving R3 alternates and the five
 * withdrawn rows are present and internally consistent. It executes no coordinator behavior.
 */
public class SupplementalV8FixtureIntegrityTest {
    private val fixtures = SupplementalV8Fixtures

    @Test
    public fun exactElevenFrozenFilesAndManifestAreBound() {
        val design = designDirectory()
        val listed = Files.readAllLines(design.resolve("artifact-sha256.txt"))
            .filter { it.isNotBlank() }
        assertEquals(10, listed.size, "artifact manifest must list ten files")
        listed.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            assertEquals(2, parts.size, "malformed artifact line $line")
            val name = parts[1]
            val digest = sha256(Files.readAllBytes(design.resolve(name)))
            assertEquals(parts[0], digest, "artifact digest differs for $name")
        }
        assertEquals(11, design.toFile().listFiles()?.count { it.isFile },
            "frozen supplemental directory must contain exactly eleven files")
    }

    @Test
    public fun inventoryCountsAndClassesMatchTheFrozenSupplement() {
        assertEquals(11, fixtures.cases.size, "supplement case count")
        assertEquals(52, fixtures.variants.size, "supplement variant count")
        assertEquals(52, fixtures.cases.flatMap { it.variants }.map { it.variantId }.distinct().size,
            "unique supplement variant IDs")
        val counts = fixtures.variants.values.groupingBy { it.expectedClass }.eachCount()
        assertEquals(mapOf(
            "REFRESH_SAME_ORIGINAL" to 6,
            "REFRESH_REFUSED_ACCOUNTED" to 28,
            "REFRESH_REFUSED_RETIRED" to 1,
            "RETIRED_CLOSED" to 5,
            "RETIRED_UNRESOLVED" to 12,
        ), counts, "supplement expected-class census")
        assertEquals(setOf(
            "E3RA-R3.V01-TARGET-UNRESOLVED",
            "E3RA-R3.V02-TARGET-THROW",
            "E3RA-R3.V03-MASTER-UNRESOLVED",
            "E3RA-R3.V04-MASTER-THROW",
            "E3RA-R3.V05-MASTER-NONOPERATIONAL",
            "E3RA-R3.V07-CLIENT-NULL",
            "E3RA-R3.V08-CLIENT-THROW",
            "E3RA-R3.V09-SETUP-THROW",
        ), fixtures.survivingAlternates, "exact eight surviving R3 non-entry alternates")
        assertEquals(setOf(
            "E3RA-R4.V04-RETIRE-DURING-REFRESH",
            "E3RA-R5.V02-REFRESH-DURING-RETIREMENT",
            "E3RA-P1.V01-BOTH-DENIALS-CONFIRMED-CLOSE-UNIT",
            "E3RA-P3.V01-PROVISIONER-CLOSE-PENDING-UNIT",
            "E3RA-P4.V01-LATE-PROVISIONER-CONFIRMED",
        ), fixtures.withdrawn, "exact five withdrawn unresolved alternates")
        fixtures.withdrawn.forEach { id ->
            val variant = fixtures.variants.getValue(id)
            assertTrue(variant.alternateOutcomes.isEmpty(), "$id must carry no alternate")
            assertEquals("RETIRED_CLOSED", variant.expectedClass, "$id terminal class changed")
        }
        fixtures.survivingAlternates.forEach { id ->
            val variant = fixtures.variants.getValue(id)
            assertEquals(1, variant.alternateOutcomes.size, "$id must carry one positive alternate")
            assertEquals("REFRESH_SAME_ORIGINAL",
                variant.alternateOutcomes.single().requiredString("expected_class"),
                "$id alternate class changed")
        }
        fixtures.variants.values.forEach { variant ->
            assertEquals("NOT RUN", variant.status, "${variant.variantId} frozen status changed")
            assertEquals("new", variant.disposition, "${variant.variantId} disposition changed")
        }
    }

    @Test
    public fun humanCasesCrossReferenceEveryMachineVariant() {
        val casesText = Files.readString(designDirectory().resolve("cases-addendum.md"))
        for (case in fixtures.cases) {
            assertTrue("### ${case.caseId}" in casesText, "missing human heading ${case.caseId}")
            for (variant in case.variants) {
                val line = casesText.lineSequence().firstOrNull { variant.variantId in it } ?: ""
                assertTrue(line.isNotEmpty(), "missing human row ${variant.variantId}")
                assertTrue(variant.expectedClass in line,
                    "human row ${variant.variantId} does not show ${variant.expectedClass}")
            }
        }
        val fixtureText = Files.readString(designDirectory().resolve("fixtures.json"))
        assertTrue("\"withdrawn_conditional_alternates\"" in fixtureText,
            "withdrawn alternatives must remain recorded in the fixtures")
        assertTrue("\"runtime\": \"NOT RUN\"" in fixtureText ||
            "\"status\": \"NOT RUN\"" in fixtureText,
            "fixtures must remain NOT RUN")
    }

    private fun designDirectory(): Path {
        val start = Path.of(System.getProperty("user.dir") ?: ".").toAbsolutePath().normalize()
        var current: Path? = start
        while (current != null) {
            val candidate = current.resolve(
                "qa/pos/designs/foundation-existing-only-startup-review-addendum-v8")
            if (Files.isRegularFile(candidate.resolve("inventory.json"))) return candidate
            current = current.parent
        }
        error("supplemental v8 design directory not found")
    }
}
