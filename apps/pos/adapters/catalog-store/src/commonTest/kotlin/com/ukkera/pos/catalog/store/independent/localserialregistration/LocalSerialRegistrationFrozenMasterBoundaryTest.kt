@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.catalog.store.independent.localserialregistration

import com.ukkera.pos.catalog.store.LocalSerialRegistrationCodecV1
import com.ukkera.pos.catalog.store.LocalSerialRegistrationProfileV1
import com.ukkera.pos.catalog.store.LocalSerialRegistrationQueryV1
import com.ukkera.pos.catalog.store.LocalSerialRegistrationServices
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Supporting L1 boundary bindings for local-serial-registration-v1, using the real
 * Master handlers and the frozen requested coordinates. The signed-envelope-shaped
 * request is an inert syntax carrier; native originals, time, credentials, grants,
 * rows, writer/CAS, physical custody and successful history remain UNBOUND.
 *
 * Internal contexts carry request coordinates only. No authorization or evaluation
 * is supplied. Admission restriction and normalization/read refusal are unit
 * observations; the full business no-effect vector, L2/L3/L4 production boundaries,
 * historical recovery and whole frozen cases remain NOT_RUN.
 */
class LocalSerialRegistrationFrozenMasterBoundaryTest {
    private val frozen = LocalSerialRegistrationFrozenMasterInputs
    private val services = LocalSerialRegistrationServices(publications = null)
    private val activation = services.commands.single { it.key == LocalSerialRegistrationProfileV1.ACTIVATE }

    /** Missing-source support for LSR-01.a, LSR-03.a/b/c/e and LSR-10.h/j. */
    @Test
    fun lsr01aLsr03Lsr10_missingOriginalsRestrictTheExactRequestedActivation(): Unit = runTest {
        val input = frozen.encodedRequest()
        val supplied = input.copyBytes()
        val normalized = activation.normalize(input)
        val requested = LocalSerialRegistrationCodecV1.decode(normalized).publication.definition

        assertEquals(frozen.scope, requested.scope)
        assertEquals(frozen.publicationId, requested.publicationId)
        assertEquals(frozen.registrationId, requested.registrationId)
        assertEquals(Revision(4), requested.revision)
        val predecessor = assertNotNull(requested.previous)
        assertEquals(DomainId("b1000000-0000-4000-8000-000000000003"), predecessor.publicationId)
        assertEquals(frozen.registrationId, predecessor.registrationId)
        assertEquals(Revision(3), predecessor.revision)
        assertEquals(frozen.serialId, requested.serialId)
        assertEquals(frozen.exactCode, requested.serialCode)
        assertEquals(frozen.skuId, requested.skuId)
        assertEquals(frozen.skuPublicationId, requested.skuPublicationId)
        assertEquals(frozen.validFrom, requested.validFrom)
        assertEquals(frozen.validUntil, requested.validUntil)

        val prepared = activation.prepare(normalized, UnavailableSerialSources(frozen.scope),
            CommandContext(frozen.scope, frozen.actorId, frozen.operationId, activation.key,
                semanticDigest(frozen.scope, frozen.actorId, activation.key, normalized)))

        // A denied preparation cannot be treated as an admitted activation proposal.
        // This observes the handler restriction, without a transaction or ledger claim.
        assertNotNull(prepared.admissionRestriction)
        assertContentEquals(supplied, input.copyBytes())
    }

    /** Fresh consumer's missing-original support for LSR-03.a/b/c/e and LSR-10.j. */
    @Test
    fun lsr03Lsr10_currentConsumerDeclinesUnavailableNativeOriginals(): Unit = runTest {
        assertFailsWith<IllegalStateException> {
            services.current(UnavailableSerialSources(frozen.scope), frozen.serialId)
        }
    }

    /** Missing-linkage/default refusal support only for LSR-19.d/h; no recovery baseline. */
    @Test
    fun lsr19dh_currentReadDeclinesUnavailableNativeHistoryInsteadOfReturningAnEmptyState(): Unit = runTest {
        val reader = services.reads.single { it.key == LocalSerialRegistrationProfileV1.READ }
        val input = LocalSerialRegistrationCodecV1.query(
            LocalSerialRegistrationQueryV1(frozen.registrationId, frozen.publicationId)).payload
        val supplied = input.copyBytes()
        val normalized = reader.normalize(input)

        assertFailsWith<IllegalStateException> {
            reader.prepare(normalized, UnavailableSerialSources(frozen.scope),
                ReadContext(frozen.scope, frozen.actorId, reader.key,
                    queryDigest(frozen.scope, frozen.actorId, reader.key, normalized)))
        }
        assertContentEquals(supplied, input.copyBytes())
    }

    @Test
    fun lsr21a_masterNormalizationRejectsTheFrozenNumericCode(): Unit {
        assertNormalizationRefusal(frozen.changedDefinition("serialCode", JsonPrimitive(12345)))
    }

    @Test
    fun lsr21b_masterNormalizationRejectsTheFrozenFractionalRevisionAndMalformedIdentifier(): Unit {
        // Keep the frozen string token "3.5" and malformed ID together. No invalidity
        // precedence, rounding or replacement identifier is selected by this binding.
        val input = changedDefinition(
            "revision" to JsonPrimitive("3.5"),
            "serialId" to JsonPrimitive("NOT_A_VALID_DOMAIN_ID"),
        )
        assertNormalizationRefusal(input)
    }

    @Test
    fun lsr21d_masterNormalizationRejectsTheFrozenMissingPermanentAndNativeFields(): Unit {
        // Frozen namespace_original absence maps to this command's serialAuthority
        // reference field. All three specified absent observations remain null.
        val input = changedDefinition(
            "registrationId" to JsonNull,
            "serialId" to JsonNull,
            "serialAuthority" to JsonNull,
        )
        assertNormalizationRefusal(input)
    }

    private fun assertNormalizationRefusal(input: StoredBytes) {
        val supplied = input.copyBytes()
        assertFailsWith<IllegalArgumentException> { activation.normalize(input) }
        assertContentEquals(supplied, input.copyBytes())
    }

    private fun changedDefinition(vararg fields: Pair<String, JsonElement>): StoredBytes = frozen.changedRequest { root ->
        val publication = root.getValue("publication").jsonObject
        val definition = publication.getValue("definition").jsonObject
        val changed = JsonObject(definition.toMutableMap().apply { fields.forEach { (field, value) -> put(field, value) } })
        JsonObject(root.toMutableMap().apply {
            put("publication", JsonObject(publication.toMutableMap().apply { put("definition", changed) }))
        })
    }

    /** Unavailability only: no empty/seeded rows, claims, native snapshot or original. */
    private class UnavailableSerialSources(override val scope: BranchScope) : CommandReadView {
        override suspend fun aggregate(key: AggregateKey): StoredAggregate? = error("Serial storage source unavailable")
        override suspend fun claim(key: ClaimKey): DomainId? = error("Serial claim source unavailable")
        override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
            error("Serial original source unavailable")
    }
}
