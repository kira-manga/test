package com.ukkera.pos.recovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class RecoveryFixtureFileTest {
    @Serializable
    private data class FixtureFile(val schemaVersion: Int, val evidenceLevel: String, val cases: List<FixtureCase>)

    @Serializable
    private data class FixtureCase(
        val name: String,
        val history: String,
        val known: String,
        val readmitted: List<RecoveryResourceKind>,
        val resource: RecoveryResourceKind,
        val expectedOutcome: OperationOutcomeClassification,
        val expectedAdmission: ResourceAdmissionDecision,
    )

    @Test
    fun exactVersionOneOutcomeAndHoldFixtures() {
        val stream = javaClass.getResourceAsStream("/recovery/outcome-hold-v1.json")
        assertNotNull(stream, "Versioned exact fixture must be packaged on the test classpath")
        val fixture = stream.bufferedReader().use { Json.decodeFromString<FixtureFile>(it.readText()) }
        assertEquals(1, fixture.schemaVersion)
        assertEquals("L1_MODEL_ONLY_NOT_PHYSICAL_RECOVERY_PROOF", fixture.evidenceLevel)
        assertEquals(12, fixture.cases.size)
        assertEquals(fixture.cases.size, fixture.cases.map { it.name }.toSet().size)
        val f = RecoveryFixtures
        for (case in fixture.cases) {
            val history = when (case.history) {
                "HEALTHY" -> f.healthyHistory()
                "UNKNOWN_TAIL" -> f.incompleteHistory()
                else -> error("Unknown fixture history")
            }
            val known = if (case.known == "NONE") null else f.known(KnownOperationState.valueOf(case.known))
            assertEquals(case.expectedOutcome, OperationOutcomePolicy(ModelEvidenceVerifier()).classify(
                f.identity, known, history, f.lookup(history)), case.name)
            var state = if (case.history == "HEALTHY") RecoveryAdmissionState.initiallyProvisioned(f.branch) else f.restored()
            val machine = RecoveryAdmissionMachine(ModelEvidenceVerifier())
            for (kind in case.readmitted) state = f.admit(machine, state, if (kind == RecoveryResourceKind.AUTHORIZATION) f.auth else f.resource(kind))
            val resource = if (case.resource == RecoveryResourceKind.AUTHORIZATION) f.auth else f.resource(case.resource)
            assertEquals(case.expectedAdmission, RecoveryAdmissionPolicy.evaluate(state, f.branch, setOf(resource, f.auth)), case.name)
        }
    }
}
