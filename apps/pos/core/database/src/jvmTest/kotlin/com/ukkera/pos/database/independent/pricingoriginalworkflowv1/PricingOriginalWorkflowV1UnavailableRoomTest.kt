package com.ukkera.pos.database.independent.pricingoriginalworkflowv1

import com.ukkera.pos.catalog.store.JvmCompanyPublicationKeys
import com.ukkera.pos.catalog.store.LocalPricePolicyEntryV1
import com.ukkera.pos.catalog.store.LocalPricingCodec
import com.ukkera.pos.catalog.store.LocalPricingHandler
import com.ukkera.pos.catalog.store.LocalPricingProfile
import com.ukkera.pos.catalog.store.LocalPricingRequestV1
import com.ukkera.pos.catalog.store.LocalSignedPricePolicyEntryV1
import com.ukkera.pos.catalog.store.PinnedCompanyPublicationKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.PreparedMutationAdmissionRestriction
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.DetachedCatalogActivationRoom
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.RecordingDetachedCatalogVerifier
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.RecordingDetachedCatalogView
import com.ukkera.pos.database.independent.catalogactivationmembershipv1.catalogChangeCount
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Actual file-backed Room, bundled SQLite durable writer, production TransactionView,
 * codec, pricing source reader and handler preparation. No admitted employee,
 * company-native owner, native publication output or accepting grant is installed.
 * The synthetic signature is independently verified with JCA; prepare does not
 * call that verifier when the original native observation is unavailable.
 * No evaluate, protected submission, commit, recovery or RPC is performed.
 */
class PricingOriginalWorkflowV1UnavailableRoomTest {
    @Test
    fun validDetachedPolicyWithoutNativeSourceIsHeldWithoutWritesAcrossReopen() = runTest {
        val bytes = javaClass.getResourceAsStream(
            "/pricingoriginalworkflowv1/local-pricing-original-workflow-v1.json",
        )!!.use { it.readBytes() }
        assertEquals("49a7150665eff31cfbd8b067aa2a819956f1083e938d4e07b9e4b7179fd4c23c", sha256(bytes).value)
        val document = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("1", document["design_version"]!!.jsonPrimitive.content)
        assertEquals("NOT_RUN", document["case_inventory"]!!.jsonObject["all_actual_results"]!!.jsonPrimitive.content)
        val fixtures = document["fixtures"]!!.jsonObject
        val base = fixtures["F-BASE"]!!.jsonObject
        val newPolicy = fixtures["F-ATOMIC"]!!.jsonObject["new"]!!.jsonObject
        val company = CompanyScope(id(base["organization"]!!.jsonPrimitive.content), id(base["company"]!!.jsonPrimitive.content))
        val skuId = id("P-RED/V-RED")
        // Both candidates use one stable rule/entry identity; only source revision/publication changes.
        val candidates = listOf(
            base["policy_revision"]!!.jsonPrimitive.content.toLong() to true,
            newPolicy["policy_revision"]!!.jsonPrimitive.content.toLong() to newPolicy["E_A1_branch_set_allowed"]!!.jsonPrimitive.boolean,
        ).map { (revision, enabled) -> LocalPricePolicyEntryV1(company, id("PRICE-POLICY"),
            id("DETACHED-POLICY-$revision"), Revision(revision), skuId, id("C-STD"), enabled,
            Quantity.parse(id("BASE-EACH"), "1", 0), UtcInstant.parseCanonical("1970-01-01T00:00:00Z"), null) }
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val keyId = id("DISPOSABLE-POLICY-KEY")
        val keys = JvmCompanyPublicationKeys(listOf(PinnedCompanyPublicationKey(company, keyId, pair.public.encoded)))
        val room = DetachedCatalogActivationRoom.create(company)
        try {
            val baseline = room.snapshot()
            assertTrue(baseline.values.all { it.isEmpty() }, "Detached empty baseline, not an activated 120/r5 fixture")
            for (definition in candidates) {
                val canonical = LocalPricingCodec.canonicalPolicy(definition)
                val signature = try {
                    Signature.getInstance("Ed25519").run { initSign(pair.private); update(canonical); sign() }
                } finally { canonical.fill(0) }
                val signatureHex = signature.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                signature.fill(0)
                val signed = LocalSignedPricePolicyEntryV1(definition, keyId, signatureHex)
                val verifiedBytes = LocalPricingCodec.canonicalPolicy(definition)
                try { assertTrue(keys.verify(company, keyId, verifiedBytes, signatureHex)) }
                finally { verifiedBytes.fill(0) }

                val verifier = RecordingDetachedCatalogVerifier(keys)
                val handler = LocalPricingHandler(verifier)
                val request = LocalPricingRequestV1.ReceivePolicy(signed)
                val normalized = handler.normalize(LocalPricingCodec.encode(request))
                assertEquals(request, LocalPricingCodec.decode(normalized))
                val actorId = id("E-A1") // Detached attribution DATA, not an admitted employee.
                val operationId = OperationId(id("UNAVAILABLE-POLICY-${definition.revision.value}").value)
                val context = CommandContext(room.scope, actorId, operationId, handler.key,
                    semanticDigest(room.scope, actorId, handler.key, normalized))

                suspend fun prepareHeld() {
                    room.withView { connection, actual ->
                        assertNull(actual.aggregate(LocalPricingProfile.key(LocalPricingProfile.POLICY_PUBLICATION, definition.publicationId)))
                        assertNull(actual.originalNativeBusinessPublications())
                        val beforeChanges = connection.catalogChangeCount()
                        val observed = RecordingDetachedCatalogView(actual)
                        val prepared = handler.prepare(normalized, observed, context)
                        assertTrue(observed.nativeObservationReads > 0)
                        assertEquals(observed.nativeObservationReads, observed.unavailableObservationReads)
                        assertEquals(PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE, prepared.admissionRestriction)
                        assertTrue(verifier.decisions.isEmpty(), "Missing native source is consumed before any supplied verifier")
                        assertEquals(beforeChanges, connection.catalogChangeCount())
                    }
                    assertEquals(baseline, room.snapshot())
                }
                prepareHeld()
                room.reopen()
                assertEquals(baseline, room.snapshot())
                prepareHeld()
            }
        } finally { room.close() }
    }

    private fun id(label: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        "pricing-original-workflow-v1/$label".encodeToByteArray(),
    ).toString())
}
