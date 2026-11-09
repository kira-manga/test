package com.ukkera.pos.app.gateway.independent.catalogreleasesourcev1

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.actionMatches
import com.ukkera.pos.app.gateway.actionPermission
import com.ukkera.pos.app.gateway.journalJson
import com.ukkera.pos.app.gateway.normalizeLocalRequest
import com.ukkera.pos.catalog.store.LocalCatalogReleaseCodecV1
import com.ukkera.pos.catalog.store.LocalCatalogReleaseProfileV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Canonical supplied-envelope, action projection and client-intent mapping only; no authorization/native result. */
class CatalogReleaseFrozenGatewayMappingSlicesTest {
    private val inputs = FrozenCatalogReleaseGatewayContentInputs

    @Test
    fun csr001And009ReleaseActionIsSeparateAndProjectsCatalogAdministerOnly() {
        val request = inputs.entry().request()
        assertEquals(LocalCatalogReleaseProfileV1.ACTIVATE, request.command)
        assertEquals(LocalWorkflow.CATALOG, LocalAction.CATALOG_RELEASE_ACTIVATE.workflow)
        assertTrue(actionMatches(LocalAction.CATALOG_RELEASE_ACTIVATE, request))
        for (unrelated in listOf(LocalAction.CATALOG_ACTIVATE, LocalAction.RETURN_RIGHTS_ACTIVATE,
                LocalAction.PRICE_POLICY_RECEIVE, LocalAction.PURCHASE_APPROVE, LocalAction.RECEIPT_POST)) {
            assertFalse(actionMatches(unrelated, request), unrelated.name)
        }
        assertEquals(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER),
            actionPermission(LocalAction.CATALOG_RELEASE_ACTIVATE))
        assertNull(request.actionApprovalId)
        assertFalse(actionMatches(LocalAction.CATALOG_RELEASE_ACTIVATE,
            MutationRequest(request.operationId, request.command, request.payload, inputs.token(601))))
        // Matching syntax/projected permission does not establish any current Master grant or completion.
    }

    @Test
    fun csr002OrderedCoordinatesAndLiteralSummaryInputsSurviveActualNormalizationAndStagingMapping() {
        val expectedNames = listOf(
            listOf("Blue shirt / M", "Black shirt / L", "Cotton bag"),
            listOf("Cotton bag, natural", "Black shirt / L", "Blue shirt / M"),
            listOf("Blue shirt / M", "Cotton bag, natural", "Black shirt / L"))
        for (revision in 0..2) {
            val definition = inputs.definition(revision)
            val entry = inputs.entry(value = definition)
            val request = entry.request()
            val normalized = normalizeLocalRequest(request.command, request.payload)
            assertEquals(request.payload, normalized)
            val recovered = LocalCatalogReleaseCodecV1.decode(normalized)
            assertEquals(inputs.orderedMembers(revision), recovered.publication.definition.members)
            assertEquals(definition.previous, recovered.publication.definition.previous)
            assertEquals(definition.publicationId, recovered.publication.definition.publicationId)
            assertEquals(Revision(revision.toLong()), recovered.publication.definition.revision)
            assertEquals(expectedNames[revision], entry.summary.map { it.value.english })
            assertEquals(3, entry.summary.size)
        }
        // Summary input preservation is not native original-name resolution or the private native stage mapper.
    }

    @Test
    fun csr003EveryMissingMemberCoordinateRefusesAtTheActualCanonicalRequestBoundary() {
        val request = inputs.entry().request()
        for (field in listOf("publicationId", "contentDigest", "skuId", "skuRevision", "productId", "productRevision")) {
            val malformed = mutateDefinition(request.payload) { definition ->
                val members = definition.getValue("members").jsonArray.toMutableList()
                members[0] = JsonObject(members[0].jsonObject - field)
                JsonObject(definition + ("members" to JsonArray(members)))
            }
            assertFailsWith<IllegalArgumentException>(field) {
                normalizeLocalRequest(request.command, malformed)
            }
            assertFailsWith<IllegalArgumentException>(field) {
                actionMatches(LocalAction.CATALOG_RELEASE_ACTIVATE,
                    MutationRequest(request.operationId, request.command, malformed))
            }
        }
        assertEquals(inputs.definition(0), LocalCatalogReleaseCodecV1.decode(request.payload).publication.definition)
        // Only missing-field syntax refusal is bound; no handler dispatch/domain delta/native authentication is measured.
    }

    @Test
    fun csr006NegativeAndInt64OverflowingReleaseScalarsCannotNormalizeOrWrap() {
        val request = inputs.entry().request()
        val originalWire = journalJson.parseToJsonElement(request.payload.copyBytes().decodeToString()).jsonObject
        val originalPublication = originalWire.getValue("publication").jsonObject
        val originalDefinition = originalPublication.getValue("definition").jsonObject
        val originalRevision = originalDefinition.getValue("revision").jsonPrimitive
        assertTrue(originalRevision.isString)
        assertEquals("0", originalRevision.content)
        for (raw in listOf("-1", "9223372036854775808")) {
            // Keep the frozen raw numeric value, not a coerced string/default or wrapped Long.
            val rawToken = journalJson.parseToJsonElement(raw)
            assertFalse(rawToken.jsonPrimitive.isString)
            assertEquals(raw, rawToken.toString())
            val malformed = mutateDefinition(request.payload) { definition ->
                JsonObject(definition + ("revision" to rawToken))
            }
            val changedWire = journalJson.parseToJsonElement(malformed.copyBytes().decodeToString()).jsonObject
            val changedPublication = changedWire.getValue("publication").jsonObject
            val changedDefinition = changedPublication.getValue("definition").jsonObject
            val changedRevision = changedDefinition.getValue("revision")
            assertNotEquals(request.payload, malformed, raw)
            assertNotEquals(originalRevision, changedRevision, raw)
            assertEquals(rawToken, changedRevision, raw)
            assertFalse(changedRevision.jsonPrimitive.isString, raw)
            assertEquals(raw, changedRevision.toString(), raw)
            assertEquals(originalDefinition - "revision", changedDefinition - "revision", raw)
            assertEquals(originalPublication - "definition", changedPublication - "definition", raw)
            assertEquals(originalWire - "publication", changedWire - "publication", raw)
            assertFailsWith<IllegalArgumentException>(raw) {
                normalizeLocalRequest(request.command, malformed)
            }
        }
        // Canonical revisions are JSON strings: these probes bind raw-wire refusal, not its range classification.
        // Numeric range validation, successor overflow and full authenticated history remain unbound here.
    }

    @Test
    fun csr017And018RealStoredIntentMappingPreservesOriginalAcrossSerializableDeliveryStates() {
        val original = inputs.entry()
        for (state in listOf(original, original.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name))) {
            val wire = journalJson.encodeToString(StoredClientIntentV1.serializer(), state)
            val reopened = journalJson.decodeFromString(StoredClientIntentV1.serializer(), wire)
            reopened.validate(inputs.owner)
            assertEquals(original.owner, reopened.owner)
            assertEquals(original.operationId, reopened.operationId)
            assertEquals(original.identity(), reopened.identity())
            val expectedRequest = original.request()
            val actualRequest = reopened.request()
            assertEquals(expectedRequest.operationId, actualRequest.operationId)
            assertEquals(expectedRequest.command, actualRequest.command)
            assertEquals(expectedRequest.payload, actualRequest.payload)
            assertEquals(expectedRequest.actionApprovalId, actualRequest.actionApprovalId)
            assertEquals(original.semanticSha256, reopened.semanticSha256)
            assertEquals(original.inputDigest, reopened.inputDigest)
            assertEquals(original.summary, reopened.summary)
            assertEquals(state.phase, reopened.phase)
            assertNull(reopened.recorded)
            assertFalse(reopened.acknowledged)
            assertTrue(original.sameIntent(reopened))
        }
        // No manufactured committed result, closed refusal or successful recovery is represented.
    }

    @Test
    fun csr018SemanticCasOrderBodyAndActorMutationsDoNotBecomeTheSameSavedIntent() {
        val definition = inputs.definition(0)
        val original = inputs.entry(value = definition)
        val mutations = listOf(
            inputs.entry(original.operationId, definition.copy(publicationId = inputs.token(104))),
            inputs.entry(original.operationId, definition, Revision(9)),
            inputs.entry(original.operationId, definition.copy(members = definition.members.reversed())),
            inputs.entry(original.operationId, definition.copy(members = definition.members.mapIndexed { index, member ->
                if (index == 0) member.copy(skuRevision = Revision(4)) else member
            })))
        for (changed in mutations) {
            assertFalse(original.sameIntent(changed))
            assertNotEquals(original.semanticSha256, changed.semanticSha256)
        }
        val changedActor = inputs.entry(original.operationId, definition,
            clientOwner = inputs.owner.copy(actorId = inputs.token(502)))
        assertFailsWith<IllegalArgumentException> { changedActor.validate(inputs.owner) }
        assertNotEquals(original.semanticSha256, changedActor.semanticSha256)
        assertEquals(inputs.owner, original.owner)
        assertEquals(definition, LocalCatalogReleaseCodecV1.decode(original.request().payload).publication.definition)
        // This is client identity/conflict material, not current Master access or operation replay.
    }

    private fun mutateDefinition(payload: StoredBytes, transform: (JsonObject) -> JsonObject): StoredBytes {
        val request = journalJson.parseToJsonElement(payload.copyBytes().decodeToString()).jsonObject
        val publication = request.getValue("publication").jsonObject
        val changedPublication = JsonObject(publication + ("definition" to transform(publication.getValue("definition").jsonObject)))
        return StoredBytes(JsonObject(request + ("publication" to changedPublication)).toString().encodeToByteArray())
    }
}
