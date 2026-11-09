package com.ukkera.pos.app.gateway.independent.returnrightsclientrecoveryv1

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.app.gateway.actionMatches
import com.ukkera.pos.app.gateway.actionPermission
import com.ukkera.pos.app.gateway.journalJson
import com.ukkera.pos.app.gateway.returnRightsContinuationHint
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationCodecV1
import com.ukkera.pos.catalog.store.LocalReturnRightsPublicationCodec
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen L1 syntax and journal slices only; syntactic signatures establish no authentic trust. */
class ReturnRightsClientRecoveryCodecJournalTest {
    private val inputs = FrozenReturnRightsClientRecoveryInputs

    @Test
    fun rrc001ExactRequestEnvelopeAndDomainDigestSurviveNormalization() {
        val requestFixture = inputs.fixture("requestFixtures", "F-REQUEST-0")
        val definition = inputs.fixture("definitionFixtures", requestFixture.rrcString("definitionFixture"))
        val wire = inputs.request("F-REQUEST-0")
        val decoded = LocalReturnRightsActivationCodecV1.decodeActivate(wire)
        assertEquals(requestFixture.rrcString("expectedActiveRevision"), decoded.expectedActiveRevision.value.toString())
        assertEquals(requestFixture.rrcString("utf8Length").toInt(), wire.size)
        assertEquals(definition.rrcString("signedEnvelopeCanonicalJson"), decoded.signedPublication)
        assertEquals(wire, LocalReturnRightsActivationCodecV1.normalizeActivate(wire))
        val encoded = LocalReturnRightsActivationCodecV1.activate(inputs.operationId, decoded)
        assertEquals(wire, encoded.payload)
        assertEquals(inputs.original.rrcString("command"), encoded.command.name)
        assertEquals(inputs.original.rrcString("commandVersion"), encoded.command.version.toString())
        assertNull(encoded.actionApprovalId)
        val signed = LocalReturnRightsPublicationCodec.decodeSignedPublication(decoded.signedPublication)
        assertEquals(decoded.signedPublication, LocalReturnRightsPublicationCodec.signedPublication(signed))
        val content = LocalReturnRightsPublicationCodec.canonicalPublication(signed.definition)
        try {
            assertEquals(definition.rrcString("domainPrefix") + definition.rrcString("canonicalJson"), content.decodeToString())
            assertEquals(definition.rrcString("domainPrefixedUtf8Length").toInt(), content.size)
            assertEquals(definition.rrcString("bodyDigestSha256"), sha256(content).value)
        } finally { content.fill(0) }
        assertEquals(requestFixture.rrcString("canonicalJson"), wire.rrcText())
    }

    @Test
    fun rrc002EveryFrozenNoncanonicalRequestRefusesWithoutRepair() {
        val negatives = inputs.fixtures("requestFixtures").filter { it.first.startsWith("F-REQUEST-BAD-") }
        assertEquals(8, negatives.size)
        for ((name, fixture) in negatives) {
            val wire = inputs.request(name)
            assertEquals("refuse", fixture.rrcString("expectedDecoder"), name)
            assertEquals("refuse_without_repair", fixture.rrcString("expectedNormalizer"), name)
            assertFailsWith<IllegalArgumentException>(name) { LocalReturnRightsActivationCodecV1.decodeActivate(wire) }
            assertFailsWith<IllegalArgumentException>(name) { LocalReturnRightsActivationCodecV1.normalizeActivate(wire) }
            assertEquals(fixture.rrcString("wireJson"), wire.rrcText(), name)
        }
    }

    @Test
    fun rrc003AdditiveActionPairingsAndCatalogPermissionRemainExact() {
        assertEquals(LocalWorkflow.CATALOG, LocalAction.RETURN_RIGHTS_ACTIVATE.workflow)
        val permission = Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER)
        assertEquals(permission, actionPermission(LocalAction.RETURN_RIGHTS_ACTIVATE))
        assertEquals(permission, actionPermission(LocalAction.CATALOG_ACTIVATE))
        for (fixture in inputs.matrix("actionPairingMatrix")) {
            val action = LocalAction.valueOf(fixture.rrcString("action"))
            val request = MutationRequest(inputs.operationId, CommandKey(fixture.rrcString("command"),
                fixture.rrcString("version").toInt()), inputs.request(fixture.rrcString("savedRequestFixture")))
            val matched = try { actionMatches(action, request) } catch (_: IllegalArgumentException) { false }
            assertEquals(fixture.rrcString("expectedPairing") == "match", matched, fixture.toString())
            assertNull(request.actionApprovalId)
        }
        val activation = inputs.entry().request()
        assertFalse(actionMatches(LocalAction.RETURN_RIGHTS_ACTIVATE, MutationRequest(activation.operationId,
            activation.command, activation.payload, inputs.id("policy"))))
        // The frozen old-SKU positive payload is absent; no substitute legacy grammar is supplied.
        assertEquals(kotlinx.serialization.json.JsonNull, inputs.objectValue("oldSkuBaseline")["fullPositiveExistingPayload"])
    }

    @Test
    fun rrc005JournalRepresentationRetainsCapturedOriginalDigestAcrossDeliveryStates() {
        val original = inputs.entry()
        assertFrozenReturnRightsOriginal(original, "original construction")
        val d0 = original.semanticSha256
        assertNotEquals(inputs.fixture("definitionFixtures", "F-DEF-0").rrcString("bodyDigestSha256"), d0.value)
        val recorded = StoredKernelResultV1.from(inputs.recorded("F-RESULT-APPLIED-0"))
        val delivered = original.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = recorded)
        for (state in listOf(original, original.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name), delivered,
            delivered.copy(acknowledged = true))) {
            val serialized = journalJson.encodeToString(StoredClientIntentV1.serializer(), state)
            val loaded = journalJson.decodeFromString(StoredClientIntentV1.serializer(), serialized)
            loaded.validate(inputs.owner)
            assertFrozenReturnRightsOriginal(loaded, state.phase)
            assertReturnRightsOriginalRetained(original, loaded, state.phase)
            assertEquals(d0, loaded.semanticSha256, state.phase)
            assertEquals(state.phase, loaded.phase)
            assertEquals(state.recorded, loaded.recorded)
            assertEquals(state.acknowledged, loaded.acknowledged)
        }
    }

    @Test
    fun rrc006InvalidSavedMetadataAndEveryMalformedPayloadCannotBeAdmittedOrContinued() = runTest {
        val original = inputs.entry()
        val harness = ControlledReturnRightsClientRecoveryHarness(initial = original)
        try {
            val invalid = inputs.matrix("invalidJournalMatrix").map { it.rrcString("id") to it.getValue("overrides").jsonObject } +
                inputs.fixtures("requestFixtures").filter { it.first.startsWith("F-REQUEST-BAD-") }.map {
                    it.first to JsonObject(mapOf("canonicalPayloadFixture" to JsonPrimitive(it.first))) }
            val access = harness.access()
            for ((name, overrides) in invalid) {
                val entry = inputs.invalidEntry(original, overrides)
                assertFailsWith<IllegalArgumentException>(name) { entry.validate(entry.owner) }
                assertFalse(returnRightsContinuationHint(access, entry), name)
                assertFalse(access.canContinue(entry), name)
                assertReturnRightsOriginalRetained(original, kotlin.test.assertNotNull(harness.journal.entry), name)
            }
            val changed = inputs.invalidEntry(original, JsonObject(mapOf("canonicalPayloadFixture" to JsonPrimitive("F-REQUEST-LARGE"))))
            changed.validate(changed.owner)
            assertFalse(original.sameIntent(changed))
            assertNotEquals(original.semanticSha256, changed.semanticSha256)
            assertFailsWith<IllegalArgumentException> { changed.copy(semanticSha256 = original.semanticSha256).validate(changed.owner) }
            harness.assertNoNewWork("saved-entry validation")
            assertTrue(harness.master.lookups.isEmpty())
        } finally { harness.close() }
    }
}
