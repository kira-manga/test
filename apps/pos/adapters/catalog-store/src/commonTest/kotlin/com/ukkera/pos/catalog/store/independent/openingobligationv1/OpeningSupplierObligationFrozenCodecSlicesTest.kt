package com.ukkera.pos.catalog.store.independent.openingobligationv1

import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationCodec
import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationCommands
import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationPageV1
import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationProfile
import com.ukkera.pos.catalog.store.LocalOpeningSupplierObligationQueryV1
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Frozen v1 documentary/integer/malformed-wire clauses only. These production codec/builder
 * methods supply no Master authority, persisted balance, native history, RPC or installed UI.
 * All 22 whole ODI cases and their required layers/variants remain separate obligations.
 */
class OpeningSupplierObligationFrozenCodecSlicesTest {
    private val f = OpeningSupplierObligationFrozenInputs

    @Test
    fun odi001And002And016And017ExactDocumentaryOriginalsSurviveRequestWire() {
        for (original in f.originals) {
            val input = StoredBytes(original.rawRequest().encodeToByteArray())
            val normalized = LocalOpeningSupplierObligationCodec.normalize(input)
            val decoded = LocalOpeningSupplierObligationCodec.decodeRequest(normalized)
            assertEquals(original.obligation, decoded.obligationId.value, original.label)
            assertEquals(original.supplier, decoded.supplierId.value, original.label)
            assertEquals(1L, decoded.expectedSupplierRevision.value, original.label)
            assertEquals(original.attachment, decoded.sourceEvidenceId.value, original.label)
            assertEquals(original.reference, decoded.sourceReference, original.label)
            assertEquals(original.date, decoded.openingDate.canonicalString(), original.label)
            assertEquals(original.minor, decoded.amount.minor, original.label)
            assertEquals(Currency.EGP, decoded.amount.currency, original.label)
            assertEquals(original.terms, decoded.paymentTerms, original.label)
            assertEquals(original.source, decoded.sourceDocument.sourceIdentity, original.label)
            assertEquals(original.document, decoded.sourceDocument.text, original.label)
            val retained = decoded.sourceDocument.text.encodeToByteArray(throwOnInvalidSequence = true)
            assertEquals(original.byteSize, retained.size, original.label)
            assertEquals(original.hash, sha256(retained).value, original.label)
            assertEquals(normalized, LocalOpeningSupplierObligationCodec.request(decoded), original.label)
            val money = Json.parseToJsonElement(normalized.copyBytes().decodeToString()).jsonObject.getValue("amount").jsonObject
            assertEquals(JsonPrimitive(original.minor.toString()), money.getValue("minor"), original.label)
        }
    }

    @Test
    fun odi006BuilderKeepsOriginalOperationAndDistinguishesChangedAmount() {
        val original = f.originals.first()
        val operation = OperationId(original.operation)
        val request = original.request()
        val command = LocalOpeningSupplierObligationCommands.record(operation, request)
        val repeated = LocalOpeningSupplierObligationCommands.record(operation, request)
        assertEquals(original.operation, command.operationId.value)
        assertEquals(LocalOpeningSupplierObligationProfile.COMMAND, command.command)
        assertEquals(command.payload, repeated.payload)
        val changed = LocalOpeningSupplierObligationCommands.record(operation, request.copy(amount = Money(Currency.EGP, 125175)))
        assertEquals(operation, changed.operationId)
        assertNotEquals(command.payload, changed.payload)
        assertNotEquals(semanticDigest(f.scope, f.actor, command.command, command.payload),
            semanticDigest(f.scope, f.actor, changed.command, changed.payload))
        assertEquals(125075L, LocalOpeningSupplierObligationCodec.decodeRequest(command.payload).amount.minor)
        assertEquals(original.document, LocalOpeningSupplierObligationCodec.decodeRequest(command.payload).sourceDocument.text)
        // This distinguishes semantic inputs; real Master retry/conflict/result immutability is not executed.
    }

    @Test
    fun odi003And017ReadWireKeepsOriginalEvidenceAndIndependentIntegerTotals() {
        val query = LocalOpeningSupplierObligationQueryV1(limit = 3)
        val page = LocalOpeningSupplierObligationCodec.decodePage(LocalOpeningSupplierObligationCodec.page(
            LocalOpeningSupplierObligationPageV1(query, f.originals.map { it.view() }, null)))
        assertEquals(query, page.query)
        assertEquals(listOf(125075L, 55270L, 99655L), page.entries.map { it.amount.minor })
        assertEquals(180345L, page.entries.filter { it.supplierId.value == "51111111-1111-4111-8111-111111111111" }.sumOf { it.amount.minor })
        assertEquals(99655L, page.entries.filter { it.supplierId.value == "52222222-2222-4222-8222-222222222222" }.sumOf { it.amount.minor })
        assertEquals(280000L, page.entries.sumOf { it.amount.minor })
        for ((original, entry) in f.originals.zip(page.entries)) {
            assertEquals(original.source, entry.sourceDocument.sourceIdentity)
            assertEquals(original.document, entry.sourceDocument.text)
            assertEquals(original.reference, entry.sourceReference)
            assertEquals(original.date, entry.openingDate.canonicalString())
            assertEquals(original.terms, entry.paymentTerms)
            assertEquals(original.operation, entry.operationId.value)
            assertEquals(f.actor, entry.actorId)
            assertEquals(f.recordedAt, entry.recordedAt)
            assertEquals(f.scope, entry.sourceDocumentMetadata.scope)
            assertEquals(original.byteSize, entry.sourceDocumentMetadata.byteSize)
            assertEquals(original.hash, entry.sourceDocumentMetadata.sha256)
            assertEquals(original.attachment, entry.sourceDocumentMetadata.evidenceId.value)
            assertEquals(original.obligation, entry.sourceDocumentMetadata.ownerObligationId.value)
            assertNotEquals(original.date, entry.recordedAt.canonicalString().substring(0, 10))
        }
        // These are supplied read DTOs, never a claimed protected query or stored branch balance.
    }

    @Test
    fun odi016And017FrozenInvalidMinorTokensCannotNormalize() {
        val original = f.originals.first()
        for (invalid in f.invalidMinor) {
            assertFails("Quoted frozen invalid minor $invalid") {
                LocalOpeningSupplierObligationCodec.normalize(StoredBytes(original.rawRequest(f.quote(invalid)).encodeToByteArray()))
            }
            assertFails("Non-string frozen invalid minor $invalid") {
                LocalOpeningSupplierObligationCodec.normalize(StoredBytes(original.rawRequest(invalid).encodeToByteArray()))
            }
        }
    }

    @Test
    fun odi016MissingOrMalformedDocumentaryOriginalCannotNormalize() {
        val original = f.originals.first()
        val valid = Json.parseToJsonElement(original.rawRequest()).jsonObject
        val required = listOf("obligationId", "supplierId", "sourceEvidenceId", "sourceReference", "sourceDocument", "openingDate", "amount")
        for (field in required) {
            val missing = JsonObject(valid.toMutableMap().also { it.remove(field) })
            assertFails("Required original field $field") { LocalOpeningSupplierObligationCodec.normalize(bytes(missing)) }
        }
        val corruptions = listOf(
            "obligationId" to JsonPrimitive("not-a-uuid"),
            "supplierId" to JsonPrimitive("not-a-uuid"),
            "sourceEvidenceId" to JsonPrimitive("not-a-uuid"),
            "sourceReference" to JsonPrimitive(""),
            "openingDate" to JsonPrimitive("2026-02-30"),
            "openingDate" to JsonPrimitive("2026-9-30"),
            "sourceDocument" to JsonObject(mapOf("sourceIdentity" to JsonPrimitive(original.source))),
            "sourceDocument" to JsonObject(mapOf("text" to JsonPrimitive(original.document))),
            "sourceDocument" to JsonObject(mapOf("sourceIdentity" to JsonPrimitive(""), "text" to JsonPrimitive(original.document))),
            "sourceDocument" to JsonObject(mapOf("sourceIdentity" to JsonPrimitive(original.source), "text" to JsonPrimitive(""))),
        )
        for ((field, value) in corruptions) assertFails("Malformed original $field") {
            LocalOpeningSupplierObligationCodec.normalize(bytes(JsonObject(valid.toMutableMap().also { it[field] = value })))
        }
    }

    @Test
    fun odi016AmbiguousDuplicateAndTruncatedOrOversizedWireCannotNormalize() {
        val original = f.originals.first()
        val duplicateAmount = original.rawRequest(tail = ",\"amount\":{\"currency\":\"EGP\",\"minor\":\"125175\"}")
        val duplicateReference = original.rawRequest(tail = ",\"sourceReference\":\"DIFFERENT-ORIGINAL\"")
        val escapedDuplicate = original.rawRequest(tail = ",\"source\\u0052eference\":\"DIFFERENT-ORIGINAL\"")
        val duplicateDocument = original.rawRequest().replace("\"sourceIdentity\":${f.quote(original.source)}",
            "\"sourceIdentity\":${f.quote(original.source)},\"sourceIdentity\":\"different-source\"")
        val truncated = original.rawRequest().dropLast(1)
        val tooLarge = original.rawRequest() + " ".repeat(LocalOpeningSupplierObligationProfile.MAX_INPUT_BYTES)
        val tooDeep = "[".repeat(256) + original.rawRequest() + "]".repeat(256)
        for ((name, payload) in listOf("duplicate amount" to duplicateAmount, "duplicate reference" to duplicateReference,
            "escaped duplicate" to escapedDuplicate, "nested duplicate" to duplicateDocument,
            "truncated" to truncated, "oversized" to tooLarge, "deep wrong root" to tooDeep)) {
            assertTrue(payload.isNotEmpty())
            assertFails(name) { LocalOpeningSupplierObligationCodec.normalize(StoredBytes(payload.encodeToByteArray())) }
        }
    }

    private fun bytes(value: JsonObject): StoredBytes = StoredBytes(value.toString().encodeToByteArray())
}
