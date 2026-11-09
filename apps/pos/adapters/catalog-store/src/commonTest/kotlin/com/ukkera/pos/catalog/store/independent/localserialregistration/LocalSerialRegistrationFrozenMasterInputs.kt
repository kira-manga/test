package com.ukkera.pos.catalog.store.independent.localserialregistration

import com.ukkera.pos.catalog.store.LocalActivateSerialRegistrationV1
import com.ukkera.pos.catalog.store.LocalSerialRegistrationCodecV1
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalPublishedSerialRegistrationV1
import com.ukkera.pos.security.LocalSerialAuthorityReferenceV1
import com.ukkera.pos.security.LocalSerialRegistrationReferenceV1
import com.ukkera.pos.security.LocalSignedSerialRegistrationV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Exact requested coordinates from local-serial-registration-v1 F-LSR-SCOPE,
 * F-LSR-IDENTITY, F-LSR-SUCCESSOR, F-LSR-TIME and F-LSR-PENDING.
 *
 * Namespace/allocation/digest/key fields and zero signature-shaped text are inert
 * syntax carriers for the public command. They are never authenticated native
 * originals, a completed predecessor, a qualified clock, current Master state or
 * successful stock/custody facts. No accepting verifier or native snapshot exists
 * in this binding. Null expected storage revision is a command-shape carrier;
 * it does not establish genuine current-row absence or a valid activation CAS.
 */
internal object LocalSerialRegistrationFrozenMasterInputs {
    val scope = BranchScope(
        id("11000000-0000-4000-8000-000000000001"),
        id("31000000-0000-4000-8000-000000000001"),
        id("41000000-0000-4000-8000-000000000001"),
    )
    val actorId = id("51000000-0000-4000-8000-000000000001")
    val otherActorId = id("51000000-0000-4000-8000-000000000002")
    val operationId = OperationId("f1000000-0000-4000-8000-000000000001")
    val registrationId = id("a1000000-0000-4000-8000-000000000001")
    val publicationId = id("b1000000-0000-4000-8000-000000000004")
    val serialId = id("c1000000-0000-4000-8000-000000000001")
    val skuId = id("d1000000-0000-4000-8000-000000000001")
    val skuPublicationId = id("e1000000-0000-4000-8000-000000000003")
    val validFrom = UtcInstant.parseCanonical("2031-04-05T12:00:00Z")
    val validUntil = UtcInstant.parseCanonical("2031-04-05T13:00:00Z")
    const val exactCode = "000AbC-07"
    private val inertDigest = PayloadDigest("01".repeat(32))

    fun signedShape(): LocalSignedSerialRegistrationV1 = LocalSignedSerialRegistrationV1(
        definition = LocalPublishedSerialRegistrationV1(
            scope = scope,
            allocationId = auxiliaryId(1),
            allocationDigest = inertDigest,
            serialAuthority = LocalSerialAuthorityReferenceV1(
                authorityId = auxiliaryId(2),
                publicationId = auxiliaryId(3),
                contentDigest = inertDigest,
                issuerOriginDigest = inertDigest,
            ),
            publicationId = publicationId,
            registrationId = registrationId,
            revision = Revision(4),
            previous = LocalSerialRegistrationReferenceV1(
                registrationId = registrationId,
                publicationId = id("b1000000-0000-4000-8000-000000000003"),
                revision = Revision(3),
                contentDigest = inertDigest,
            ),
            serialId = serialId,
            serialCode = exactCode,
            skuId = skuId,
            skuPublicationId = skuPublicationId,
            skuPublicationDigest = inertDigest,
            validFrom = validFrom,
            validUntil = validUntil,
        ),
        signingKeyId = auxiliaryId(4),
        signatureHex = "0".repeat(128),
    )

    fun request(): LocalActivateSerialRegistrationV1 = LocalActivateSerialRegistrationV1(signedShape(), null)

    fun encodedRequest(): StoredBytes = LocalSerialRegistrationCodecV1.encode(request())

    fun changedDefinition(field: String, value: JsonPrimitive?): StoredBytes = changedRequest { root ->
        val publication = root.getValue("publication").jsonObject
        val definition = publication.getValue("definition").jsonObject
        val changed = JsonObject(definition.toMutableMap().apply {
            if (value == null) remove(field) else put(field, value)
        })
        JsonObject(root.toMutableMap().apply {
            put("publication", JsonObject(publication.toMutableMap().apply { put("definition", changed) }))
        })
    }

    fun changedRequest(change: (JsonObject) -> JsonObject): StoredBytes {
        val original = encodedRequest().copyBytes()
        return try {
            val root = Json.parseToJsonElement(original.decodeToString()).jsonObject
            StoredBytes(change(root).toString().encodeToByteArray())
        } finally { original.fill(0) }
    }

    private fun auxiliaryId(number: Int): DomainId =
        id("72000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    private fun id(value: String): DomainId = DomainId(value)
}
