package com.ukkera.pos.catalog.store.independent.returnrightsactivationv1

import com.ukkera.pos.catalog.store.LocalActivateReturnRightsV1
import com.ukkera.pos.catalog.store.LocalActivatedReturnRightsPolicyV1
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationCodecV1
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationProfileV1
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationResultV1
import com.ukkera.pos.catalog.store.LocalReturnRightsPublicationCodec
import com.ukkera.pos.catalog.store.LocalReturnRightsQueryV1
import com.ukkera.pos.catalog.store.LocalReturnRightsReadKindV1
import com.ukkera.pos.catalog.store.LocalReturnRightsReadResultV1
import com.ukkera.pos.catalog.store.LocalReturnRightsReadStatusV1
import com.ukkera.pos.catalog.store.LocalReturnRightsReferenceV1
import com.ukkera.pos.catalog.store.returnRightsDecode
import com.ukkera.pos.catalog.store.returnRightsEncode
import com.ukkera.pos.catalog.store.returnRightsReference
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Exactly 37 L0/L1 wire/reference slices from the immutable activation-v1 freeze.
 * The request/query calls exercise the real shared strict decoder used by production
 * normalization, not Master dispatch or protected authority. Zero-signature values are
 * format oracles only, never authenticated activations or hand-authored storage producers.
 * W02/W03/W04 runtime refusal, trust, persistence and unchanged-effect assertions remain
 * unbound. Both A06 variants and all other runtime cases/obligations remain unbound.
 */
class ReturnRightsActivationFrozenSlicesTest {
    private val fixtures = ReturnRightsActivationFrozenFixturesV1
    private val rawJson = ReturnRightsActivationFrozenJsonV1
    private val codec = LocalReturnRightsActivationCodecV1
    private val publication = LocalReturnRightsPublicationCodec
    private val baseEnvelope: String get() = fixtures.golden("F-RRA-D0").text("canonical_syntax_envelope_json")
    private val baseRequest: String get() = fixtures.wire("F-RRA-REQUEST0").text("canonical_json")
    private val operationId: OperationId get() = OperationId(fixtures.ids.text("OP0"))
    private val expectedRevision: Revision get() =
        Revision(fixtures.wire("F-RRA-REQUEST0").getValue("value").jsonObject.text("expectedActiveRevision").toLong())

    @Test
    fun rraW01ExactFrozenWireAndDomainReferenceAreFormatOnly() = each("RRA-W01", 6) { variant ->
        when (variant.name) {
            "F-RRA-REQUEST0" -> assertRequestGolden(variant.input.text("wire_fixture"), variant.id)
            "F-RRA-QUERY-CURRENT" -> assertQueryGolden(variant.input.text("wire_fixture"), variant.id)
            "F-RRA-READ-P0" -> assertReadGolden(variant.input.text("wire_fixture"), variant.id)
            "F-RRA-RESULT0", "F-RRA-REFUSAL-CAS" ->
                assertResultGolden(variant.input.text("wire_fixture"), variant.id)
            "digest_domain" -> assertDigestDomain(variant)
            else -> error("Unmapped frozen wire variant ${variant.id}")
        }
    }

    @Test
    fun rraW02UnchangedMalformedOuterBytesReachTheActualStrictDecoder() = each("RRA-W02", 10) { variant ->
        val received = mutationRequestBytes(variant)
        refuseRequest(received, variant)
        // No Master, native trust, storage or business-effect fixture is supplied here.
    }

    @Test
    fun rraW03ExactSelectorsAndAllNineInvalidQueriesUseTheProductionDecoder() {
        // Frozen valid controls are not additional named variants or protected read evidence.
        assertQueryGolden("F-RRA-QUERY-CURRENT", "RRA-W03 canonical CURRENT control")
        assertQueryGolden("F-RRA-QUERY-P0", "RRA-W03 canonical PUBLICATION control")
        each("RRA-W03", 9) { variant ->
            val received = invalidQueryBytes(variant)
            assertFails(variant.id + " strict query decoder: " + variant.expected) { decodeQuery(received) }
            // Scope/authority and no-durable-mutation assertions remain unbound.
        }
    }

    @Test
    fun rraW04FrozenInnerByteCeilingsAreNotReplacedByTheOuterLength() = each("RRA-W04", 5) { variant ->
        if ("size_recipe" in variant.input) {
            val recipe = fixtures.recipe(variant.input.text("size_recipe"))
            val expanded = expandRecipe(recipe)
            assertRecipeInput(recipe, expanded, variant.id)

            if (recipe.text("id") == "F-RRA-SIZE-CONTENT-ABOVE") {
                assertFails(variant.id + " domain-prefixed content ceiling") {
                    publication.decodeCanonicalPublication(expanded.signingContent)
                }
            } else {
                val definition = publication.decodeCanonicalPublication(expanded.signingContent)
                assertContentEquals(expanded.signingContent, publication.canonicalPublication(definition), variant.id)
            }

            if (variant.expected.startsWith("Inner syntax/size accepted")) {
                val signed = publication.decodeSignedPublication(expanded.signedEnvelope)
                assertContentEquals(expanded.signedEnvelope.utf8(), publication.signedPublication(signed).utf8(), variant.id)
                val decoded = decodeRequest(expanded.outerRequest.utf8())
                assertEquals(expectedRevision, decoded.expectedActiveRevision, variant.id)
                assertContentEquals(expanded.signedEnvelope.utf8(), decoded.signedPublication.utf8(), variant.id)
                val encoded = codec.activate(operationId, decoded)
                assertEquals(recipe.number("expected_outer_request_bytes"), encoded.payload.size, variant.id)
                assertContentEquals(expanded.outerRequest.utf8(), encoded.payload.copyBytes(), variant.id)
            } else {
                assertFails(variant.id + " inner envelope ceiling") {
                    publication.decodeSignedPublication(expanded.signedEnvelope)
                }
                assertFails(variant.id + " public request value refuses the same inner envelope") {
                    LocalActivateReturnRightsV1(expectedRevision, expanded.signedEnvelope)
                }
                refuseRequest(expanded.outerRequest.utf8(), variant)
            }
        } else {
            assertEquals("inner_trailing_space", variant.name, variant.id)
            assertEquals("true", variant.input.getValue("reencode_outer_only").jsonPrimitive.content, variant.id)
            val suffix = rawJson.hexBytes(variant.input.text("append_to_inner_envelope_utf8_hex"))
            val envelope = baseEnvelope + suffix.decodeToString(throwOnInvalidSequence = true)
            val outer = rawJson.replace(baseRequest, "/signedPublication", rawJson.quoted(envelope))
            assertContentEquals(baseEnvelope.utf8() + suffix, envelope.utf8(), variant.id)
            assertOuterRetainsInner(outer, envelope, variant.id)
            assertFails(variant.id + " noncanonical inner trailing byte") { publication.decodeSignedPublication(envelope) }
            refuseRequest(outer.utf8(), variant)
        }
        // Syntax/size acceptance supplies neither native trust nor protected activation authority.
        // All genuine runtime activation, durability and unchanged-effect assertions remain unbound.
    }

    @Test
    fun rraW05FalseReadAndMinimalResultShapesReachThePublicClientDecoder() {
        // These exact null-activation controls are format checks, not production absence/unavailability.
        assertReadGolden("F-RRA-READ-ABSENT", "RRA-W05 ABSENT format control")
        assertReadGolden("F-RRA-READ-UNAVAILABLE", "RRA-W05 SOURCE_UNAVAILABLE format control")
        each("RRA-W05", 7) { variant ->
            val received = StoredBytes(responseBytes(variant))
            if (variant.name == "minimal_signed_terms") {
                assertFails(variant.id + " public minimal-result decoder: " + variant.expected) { codec.decodeResult(received) }
            } else {
                assertFails(variant.id + " public read decoder: " + variant.expected) { codec.decodeRead(received) }
            }
            // Client decoding cannot qualify authenticated storage, policy applicability or an activation.
        }
    }

    private fun each(caseId: String, expectedCount: Int, action: (ReturnRightsActivationFrozenVariantV1) -> Unit) {
        val variants = fixtures.variants(caseId)
        assertEquals(expectedCount, variants.size, "$caseId frozen named-variant count")
        variants.forEach(action)
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
    private fun String.utf8(): ByteArray = encodeToByteArray(throwOnInvalidSequence = true)

    /** Exactly the production normalize/prepare decoder stage, with its real input bound. */
    private fun decodeRequest(received: ByteArray): LocalActivateReturnRightsV1 =
        returnRightsDecode(StoredBytes(received), LocalActivateReturnRightsV1.serializer(),
            LocalReturnRightsActivationProfileV1.MAX_INPUT_BYTES)

    /** Exactly the production read normalize/prepare decoder stage, with its real query bound. */
    private fun decodeQuery(received: ByteArray): LocalReturnRightsQueryV1 =
        returnRightsDecode(StoredBytes(received), LocalReturnRightsQueryV1.serializer(),
            LocalReturnRightsActivationProfileV1.MAX_QUERY_BYTES)

    private fun refuseRequest(received: ByteArray, variant: ReturnRightsActivationFrozenVariantV1) {
        assertFails(variant.id + " strict request decoder only: " + variant.expected) { decodeRequest(received) }
    }

    private fun assertWire(fixtureId: String, actual: StoredBytes, label: String) {
        val wire = fixtures.wire(fixtureId)
        val expected = wire.text("canonical_json").utf8()
        assertEquals(wire.number("expected_utf8_bytes"), expected.size, label + " frozen literal length")
        assertEquals(wire.number("expected_utf8_bytes"), actual.size, label + " production length")
        assertContentEquals(expected, actual.copyBytes(), label + " exact ordered wire bytes")
    }

    private fun assertRequestGolden(fixtureId: String, label: String) {
        val value = fixtures.wire(fixtureId).getValue("value").jsonObject
        val typed = LocalActivateReturnRightsV1(Revision(value.text("expectedActiveRevision").toLong()), value.text("signedPublication"))
        val encoded = codec.activate(operationId, typed)
        assertEquals(operationId, encoded.operationId, label)
        assertEquals(fixtures.command.text("name"), encoded.command.name, label)
        assertEquals(fixtures.command.text("version").toInt(), encoded.command.version, label)
        assertWire(fixtureId, encoded.payload, label)
        val decoded = decodeRequest(fixtures.wire(fixtureId).text("canonical_json").utf8())
        assertEquals(typed.expectedActiveRevision, decoded.expectedActiveRevision, label)
        assertContentEquals(baseEnvelope.utf8(), decoded.signedPublication.utf8(), label + " one outer layer retains exact envelope")
        assertWire(fixtureId, codec.activate(operationId, decoded).payload, label + " decoded value")
    }

    private fun assertQueryGolden(fixtureId: String, label: String) {
        val value = fixtures.wire(fixtureId).getValue("value").jsonObject
        val id = value.getValue("id").let { if (it == JsonNull) null else DomainId(it.jsonPrimitive.content) }
        val typed = LocalReturnRightsQueryV1(LocalReturnRightsReadKindV1.valueOf(value.text("kind")), id)
        val encoded = codec.query(typed)
        assertEquals(fixtures.read.text("name"), encoded.query.name, label)
        assertEquals(fixtures.read.text("version").toInt(), encoded.query.version, label)
        assertWire(fixtureId, encoded.payload, label)
        val shortcut = if (id == null) codec.readCurrent() else codec.readPublication(id)
        assertWire(fixtureId, shortcut.payload, label + " selector shortcut")
        val decoded = decodeQuery(fixtures.wire(fixtureId).text("canonical_json").utf8())
        assertEquals(typed, decoded, label + " exact selector")
        assertWire(fixtureId, codec.query(decoded).payload, label + " decoded value")
    }

    private fun assertReadGolden(fixtureId: String, label: String) {
        val value = fixtures.wire(fixtureId).getValue("value").jsonObject
        val typed = fixtureRead(value)
        assertWire(fixtureId, returnRightsEncode(typed, LocalReturnRightsReadResultV1.serializer()), label + " shared response encoder")
        val decoded = codec.decodeRead(StoredBytes(fixtures.wire(fixtureId).text("canonical_json").utf8()))
        assertEquals(value.text("status"), decoded.status.name, label)
        val expectedActivation = value.getValue("activation")
        if (expectedActivation == JsonNull) assertEquals(null, decoded.activation, label)
        else assertActivationFields(expectedActivation.jsonObject, checkNotNull(decoded.activation), label)
        assertWire(fixtureId, returnRightsEncode(decoded, LocalReturnRightsReadResultV1.serializer()), label + " public decoded value")
    }

    private fun assertResultGolden(fixtureId: String, label: String) {
        val value = fixtures.wire(fixtureId).getValue("value").jsonObject
        val typed = fixtureResult(value)
        val encoded = returnRightsEncode(typed, LocalReturnRightsActivationResultV1.serializer())
        assertWire(fixtureId, encoded, label + " shared minimal-result encoder")
        assertFalse("\"signedPublication\"" in encoded.copyBytes().decodeToString(throwOnInvalidSequence = true), label + " no signed terms")
        val decoded = codec.decodeResult(StoredBytes(fixtures.wire(fixtureId).text("canonical_json").utf8()))
        assertEquals(value.text("code"), decoded.code, label)
        assertEquals(value.text("policyId"), decoded.policyId.value, label)
        assertEquals(value.text("publicationId"), decoded.publicationId.value, label)
        val revision = value.getValue("activeRevision").let { if (it == JsonNull) null else it.jsonPrimitive.content }
        assertEquals(revision, decoded.activeRevision?.value?.toString(), label)
        assertReferenceFields(value.getValue("reference").jsonObject, decoded.reference, label)
        assertWire(fixtureId, returnRightsEncode(decoded, LocalReturnRightsActivationResultV1.serializer()), label + " public decoded value")
    }

    private fun assertDigestDomain(variant: ReturnRightsActivationFrozenVariantV1) {
        val golden = fixtures.golden(variant.input.text("definition"))
        val definition = golden.text("canonical_definition_json")
        val envelope = golden.text("canonical_syntax_envelope_json")
        val domain = fixtures.domain.text("literal").utf8()
        val expectedContent = golden.text("canonical_signing_content").utf8()
        assertEquals(fixtures.domain.number("bytes"), domain.size, variant.id)
        assertContentEquals(rawJson.hexBytes(fixtures.domain.text("utf8_hex")), domain, variant.id)
        assertEquals(golden.number("expected_definition_bytes"), definition.utf8().size, variant.id)
        assertEquals(golden.number("expected_signing_content_bytes"), expectedContent.size, variant.id)
        assertEquals(golden.number("expected_signed_envelope_bytes"), envelope.utf8().size, variant.id)
        assertContentEquals(domain + definition.utf8(), expectedContent, variant.id + " exact independently supplied hash input")
        val expectedDigest = golden.text("expected_body_digest_sha256")
        assertEquals(expectedDigest, sha256(expectedContent).value, variant.id + " frozen digest oracle")
        val signed = publication.decodeSignedPublication(envelope)
        assertContentEquals(expectedContent, publication.canonicalPublication(signed.definition), variant.id)
        val readValue = fixtures.wire("F-RRA-READ-P0").getValue("value").jsonObject.getValue("activation").jsonObject
        val referenceFields = readValue.getValue("reference").jsonObject
        val actualReference = returnRightsReference(signed)
        assertReferenceFields(referenceFields, actualReference, variant.id)
        assertEquals(expectedDigest, actualReference.bodyDigest.value, variant.id)

        val bareDigest = sha256(definition.utf8())
        val envelopeDigest = sha256(envelope.utf8())
        assertNotEquals(expectedDigest, bareDigest.value, variant.id + " bare JSON is not the reference")
        assertNotEquals(expectedDigest, envelopeDigest.value, variant.id + " envelope is not the reference")
        val formatValue = fixtureActivation(readValue)
        assertFails(variant.id + " public documentary value refuses bare-JSON digest") {
            formatValue.copy(reference = formatValue.reference.copy(bodyDigest = bareDigest))
        }
        assertFails(variant.id + " public documentary value refuses envelope digest") {
            formatValue.copy(reference = formatValue.reference.copy(bodyDigest = envelopeDigest))
        }
        // The fixture and both constructor refusals are inert format values, not protected history.
    }

    private fun fixtureRead(value: JsonObject): LocalReturnRightsReadResultV1 = LocalReturnRightsReadResultV1(
        LocalReturnRightsReadStatusV1.valueOf(value.text("status")),
        value.getValue("activation").let { if (it == JsonNull) null else fixtureActivation(it.jsonObject) },
    )

    private fun fixtureActivation(value: JsonObject): LocalActivatedReturnRightsPolicyV1 {
        val scope = value.getValue("scope").jsonObject
        return LocalActivatedReturnRightsPolicyV1(
            BranchScope(DomainId(scope.text("organizationId")), DomainId(scope.text("companyId")), DomainId(scope.text("branchId"))),
            fixtureReference(value.getValue("reference").jsonObject), value.text("signedPublication"),
            Revision(value.text("activeRevision").toLong()), OperationId(value.text("operationId")), DomainId(value.text("actorId")),
            Revision(value.text("permissionPolicyRevision").toLong()), UtcInstant.parseCanonical(value.text("recordedAt")),
        )
    }

    private fun fixtureResult(value: JsonObject): LocalReturnRightsActivationResultV1 = LocalReturnRightsActivationResultV1(
        value.text("code"), DomainId(value.text("policyId")), DomainId(value.text("publicationId")),
        value.getValue("activeRevision").let { if (it == JsonNull) null else Revision(it.jsonPrimitive.content.toLong()) },
        fixtureReference(value.getValue("reference").jsonObject),
    )

    private fun fixtureReference(value: JsonObject): LocalReturnRightsReferenceV1 {
        val company = value.getValue("company").jsonObject
        return LocalReturnRightsReferenceV1(
            CompanyScope(DomainId(company.text("organizationId")), DomainId(company.text("companyId"))),
            DomainId(value.text("publicationId")), DomainId(value.text("policyId")), Revision(value.text("companyRevision").toLong()),
            PayloadDigest(value.text("bodyDigest")), DomainId(value.text("signingKeyId")),
        )
    }

    private fun assertActivationFields(value: JsonObject, actual: LocalActivatedReturnRightsPolicyV1, label: String) {
        val scope = value.getValue("scope").jsonObject
        assertEquals(scope.text("organizationId"), actual.scope.organizationId.value, label)
        assertEquals(scope.text("companyId"), actual.scope.companyId.value, label)
        assertEquals(scope.text("branchId"), actual.scope.branchId.value, label)
        assertReferenceFields(value.getValue("reference").jsonObject, actual.reference, label)
        assertContentEquals(value.text("signedPublication").utf8(), actual.signedPublication.utf8(), label + " retained exact envelope")
        assertEquals(value.text("activeRevision"), actual.activeRevision.value.toString(), label)
        assertEquals(value.text("operationId"), actual.operationId.value, label)
        assertEquals(value.text("actorId"), actual.actorId.value, label)
        assertEquals(value.text("permissionPolicyRevision"), actual.permissionPolicyRevision.value.toString(), label)
        assertEquals(value.text("recordedAt"), actual.recordedAt.canonicalString(), label)
        assertContentEquals(fixtures.golden("F-RRA-D0").text("canonical_signing_content").utf8(),
            publication.canonicalPublication(actual.definition), label + " exact documentary definition")
    }

    private fun assertReferenceFields(value: JsonObject, actual: LocalReturnRightsReferenceV1, label: String) {
        val company = value.getValue("company").jsonObject
        assertEquals(company.text("organizationId"), actual.company.organizationId.value, label)
        assertEquals(company.text("companyId"), actual.company.companyId.value, label)
        assertEquals(value.text("publicationId"), actual.publicationId.value, label)
        assertEquals(value.text("policyId"), actual.policyId.value, label)
        assertEquals(value.text("companyRevision"), actual.companyRevision.value.toString(), label)
        assertEquals(value.text("bodyDigest"), actual.bodyDigest.value, label)
        assertEquals(value.text("signingKeyId"), actual.signingKeyId.value, label)
        assertEquals(64, actual.bodyDigest.value.length, label)
        assertTrue(actual.bodyDigest.value.all { it in '0'..'9' || it in 'a'..'f' }, label)
    }

    private fun mutationRequestBytes(variant: ReturnRightsActivationFrozenVariantV1): ByteArray = when (variant.name) {
        "embedded_envelope_object" -> rawJson.replace(baseRequest, "/signedPublication", baseEnvelope).utf8()
        "numeric_expected_revision", "leading_zero_revision", "overflow_revision" ->
            rawJson.replace(baseRequest, "/expectedActiveRevision", variant.input.text("replace_expectedActiveRevision_json_token")).utf8()
        "reordered" -> variant.input.getValue("swap_fields").jsonArray.let {
            rawJson.swap(baseRequest, "", it[0].jsonPrimitive.content, it[1].jsonPrimitive.content).utf8()
        }
        "whitespace" -> rawJson.insertBytes(baseRequest.utf8(), rawJson.byteOffset(baseRequest, baseRequest.indexOf(':') + 1),
            rawJson.hexBytes(variant.input.text("insert_utf8_hex_after_first_colon")))
        "unknown_actor" -> rawJson.insert(baseRequest, "", variant.input.text("append_field"), rawJson.token(variant.input.getValue("value"))).utf8()
        "duplicate_revision" -> rawJson.duplicate(baseRequest, "", variant.input.text("repeat_exact_field")).utf8()
        "omitted_signed" -> rawJson.omit(baseRequest, "/" + variant.input.text("remove_field")).utf8()
        "alternate_outer_escape" -> {
            val index = baseRequest.indexOfFirst { it.code in 0x0600..0x06ff }
            require(index >= 0)
            assertEquals('ا', baseRequest[index], variant.id + " first frozen raw Arabic character")
            baseRequest.replaceRange(index, index + 1, variant.input.text("replace_first_raw_Arabic_character_with_json_bytes")).utf8()
        }
        else -> error("Unmapped frozen request mutation ${variant.id}")
    }

    private fun invalidQueryBytes(variant: ReturnRightsActivationFrozenVariantV1): ByteArray {
        if ("literal_json" in variant.input) return variant.input.text("literal_json").utf8()
        var raw = fixtures.wire("F-RRA-QUERY-CURRENT").text("canonical_json")
        raw = rawJson.replace(raw, "/kind", rawJson.token(variant.input.getValue("kind")))
        raw = rawJson.replace(raw, "/id", rawJson.token(variant.input.getValue("id")))
        variant.input["branchId"]?.let { raw = rawJson.insert(raw, "", "branchId", rawJson.token(it)) }
        return raw.utf8()
    }

    private data class ExpandedSizeInput(
        val definition: String,
        val signingContent: ByteArray,
        val signedEnvelope: String,
        val outerRequest: String,
    )

    private fun expandRecipe(recipe: JsonObject): ExpandedSizeInput {
        fun notice(key: String): String = recipe.getValue(key).jsonArray.joinToString("") {
            it.jsonObject.text("literal").repeat(it.jsonObject.number("repeat"))
        }
        var definition = fixtures.golden(recipe.text("definition_base")).text("canonical_definition_json")
        // The freeze supplies 128 literal rules, not a replacement expression or generated oracle.
        definition = rawJson.replace(definition, "/rules", recipe.getValue("replace_rules").toString())
        definition = rawJson.replace(definition, "/continuingRightsEnglish", rawJson.quoted(notice("replace_continuingRightsEnglish")))
        definition = rawJson.replace(definition, "/continuingRightsArabic", rawJson.quoted(notice("replace_continuingRightsArabic")))
        val envelope = rawJson.replace(baseEnvelope, "/definition", definition)
        val outer = rawJson.replace(baseRequest, "/signedPublication", rawJson.quoted(envelope))
        return ExpandedSizeInput(definition, (fixtures.domain.text("literal") + definition).utf8(), envelope, outer)
    }

    private fun assertRecipeInput(recipe: JsonObject, expanded: ExpandedSizeInput, label: String) {
        val fields = Json.parseToJsonElement(expanded.definition).jsonObject
        assertEquals(128, fields.getValue("rules").jsonArray.size, label)
        assertEquals(recipe.getValue("replace_rules"), fields.getValue("rules"), label + " exact frozen literal rules")
        assertEquals(recipe.number("each_notice_characters"), fields.text("continuingRightsEnglish").length, label)
        assertEquals(recipe.number("each_notice_characters"), fields.text("continuingRightsArabic").length, label)
        assertEquals(recipe.number("expected_signing_content_bytes"), expanded.signingContent.size, label)
        assertEquals(recipe.number("expected_signed_envelope_bytes"), expanded.signedEnvelope.utf8().size, label)
        assertEquals(recipe.number("expected_outer_request_bytes"), expanded.outerRequest.utf8().size, label)
        assertOuterRetainsInner(expanded.outerRequest, expanded.signedEnvelope, label)
    }

    /** Test-side inspection of a generated size input only; never normalizes bytes sent to production. */
    private fun assertOuterRetainsInner(outer: String, inner: String, label: String) {
        val retained = Json.parseToJsonElement(outer).jsonObject.getValue("signedPublication").jsonPrimitive
        assertTrue(retained.isString, label + " signedPublication remains a string")
        assertContentEquals(inner.utf8(), retained.content.utf8(), label + " exactly one outer decode")
    }

    private fun responseBytes(variant: ReturnRightsActivationFrozenVariantV1): ByteArray {
        val read = fixtures.wire("F-RRA-READ-P0").text("canonical_json")
        return when (variant.name) {
            "absent_with_activation" -> rawJson.replace(fixtures.wire(variant.input.text("fixture")).text("canonical_json"),
                "/activation", rawJson.value(read, "/activation"))
            "published_null", "unknown_status" -> variant.input.text("literal_json")
            "numeric_active" -> rawJson.replace(read, variant.input.text("replace_path"), variant.input.text("json_token"))
            "missing_attribution" -> rawJson.omit(read, variant.input.text("remove_path"))
            "minimal_signed_terms" -> rawJson.insert(fixtures.wire(variant.input.text("fixture")).text("canonical_json"),
                "", variant.input.text("append_field"), rawJson.quoted(baseEnvelope))
            "noncanonical_reference_order" -> variant.input.getValue("swap_reference_fields").jsonArray.let {
                rawJson.swap(read, "/activation/reference", it[0].jsonPrimitive.content, it[1].jsonPrimitive.content)
            }
            else -> error("Unmapped frozen response mutation ${variant.id}")
        }.utf8()
    }
}
