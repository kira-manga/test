package com.ukkera.pos.catalog.store.independent.returnrightspublicationv1

import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.LocalPublishedReturnRightsPolicy
import com.ukkera.pos.catalog.store.LocalReturnRightsExceptionBasis
import com.ukkera.pos.catalog.store.LocalReturnRightsPublicationCodec
import com.ukkera.pos.catalog.store.LocalReturnRightsReason
import com.ukkera.pos.catalog.store.LocalReturnRightsRule
import com.ukkera.pos.catalog.store.LocalReturnRightsTreatment
import com.ukkera.pos.catalog.store.LocalSignedReturnRightsPolicy
import com.ukkera.pos.catalog.store.toNativeCompanyReturnRightsProposal
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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
 * Public assertion slices of the immutable return-rights-publication-v1 freeze.
 * 201 RR-D variants, 112 RR-C variants and RR-N01-V01 are mapped here, not whole-case passes.
 * Original native owner/review/output/trust, protected Master, persistence, business-effect
 * cuts, external-effect monitoring, recovery, RPC and device qualification remain unbound.
 * RR-D22 covers literal notices/rules only. RR-C12 uses a refusal-only forwarding observation,
 * not installed trust; its native trust/effect assertions remain unbound. The other 80 native
 * variants and the eight separate downstream obligations are not represented by substitutes.
 */
class ReturnRightsPublicationFrozenSlicesTest {
    private val codec = LocalReturnRightsPublicationCodec
    private val fixtures = ReturnRightsFrozenFixturesV1
    private val rawJson = ReturnRightsFrozenJsonV1
    private val domain: String get() = fixtures.domain.text("literal")
    private val base: String get() = goldenDefinition("F-RR-BASE")
    private val baseEnvelope: String get() = fixtures.golden("F-RR-BASE").text("canonical_syntax_only_envelope_json")

    @Test
    fun rrD01MinimumExtendedAndMaximumInt32Periods() = each("RR-D01", 3) { variant ->
        var raw = rawJson.replace(base, "/rules/0/receiptWindowDays", variant.input.getValue("change_days").toString())
        raw = rawJson.replace(raw, "/rules/1/receiptWindowDays", variant.input.getValue("defect_days").toString())
        raw = rawJson.replace(raw, "/rules/1/coveredResolutionDays", variant.input.getValue("resolution_days").toString())
        assertAccepted(raw, variant.id)
    }

    @Test
    fun rrD02ShortMissingAndOverflowChangePeriodsRefuse() = each("RR-D02", 6) { variant ->
        val raw = rawJson.patch(base, "/rules/0", variant.input)
        refuseTypedAndWire(raw, variant.id)
    }

    @Test
    fun rrD03DefectReceiptAndResolutionBoundsRefuse() = each("RR-D03", 10) { variant ->
        val raw = rawJson.patch(base, "/rules/1", variant.input)
        refuseTypedAndWire(raw, variant.id)
    }

    @Test
    fun rrD04ReceiptTreatmentIncompatibleFieldsRefuse() = each("RR-D04", 3) { variant ->
        val index = if (variant.input.text("reason") == "CHANGE_OF_MIND") 0 else 1
        val raw = rawJson.patch(base, "/rules/$index", variant.input)
        refuseTypedAndWire(raw, variant.id)
    }

    @Test
    fun rrD05EveryReasonRetainsSeparateUnresolvedReview() {
        each("RR-D05", 7) { variant ->
            val rule = rawJson.patch(rawJson.value(base, "/rules/0"), "", variant.input)
            val raw = rawJson.replace(goldenDefinition("F-RR-REASONS"), "/rules", "[$rule]")
            assertAccepted(raw, variant.id)
        }
        assertGolden("F-RR-REASONS", "RR-D05 seven-rule fixture")
    }

    @Test
    fun rrD06NoInventedReceiptWindowsForOtherReasons() = each("RR-D06", 5) { variant ->
        val rule = rawJson.patch(rawJson.value(base, "/rules/0"), "", variant.input)
        refuseTypedAndWire(rawJson.replace(base, "/rules", "[$rule]"), variant.id)
    }

    @Test
    fun rrD07EachSpecificChangeOfMindExceptionIsDocumentary() {
        each("RR-D07", 3) { variant ->
            val exception = exceptionRule(variant.input.text("exceptionBasis"))
            val rule = rawJson.patch(exception, "", variant.input)
            assertAccepted(rawJson.replace(goldenDefinition("F-RR-EXCEPTIONS"), "/rules", "[$rule]"), variant.id)
        }
        assertGolden("F-RR-EXCEPTIONS", "RR-D07 exception fixture")
    }

    @Test
    fun rrD08AllEighteenFaultReasonExceptionCombinationsRefuse() = each("RR-D08", 18) { variant ->
        val exception = exceptionRule(variant.input.text("exceptionBasis"))
        val rule = rawJson.patch(exception, "", variant.input)
        refuseTypedAndWire(rawJson.replace(goldenDefinition("F-RR-EXCEPTIONS"), "/rules", "[$rule]"), variant.id)
    }

    @Test
    fun rrD09ExceptionAndEvidenceReviewFieldSetsAreNotRepaired() = each("RR-D09", 6) { variant ->
        val fixture = if (variant.input.text("treatment") == "CATEGORY_EXCEPTION_REVIEW")
            "F-RR-EXCEPTIONS" else "F-RR-REASONS"
        val source = goldenDefinition(fixture)
        val rule = rawJson.patch(rawJson.value(source, "/rules/0"), "", variant.input)
        refuseTypedAndWire(rawJson.replace(source, "/rules", "[$rule]"), variant.id)
    }

    @Test
    fun rrD10OnlyExactEgyptJurisdictionIsSupported() = each("RR-D10", 8) { variant ->
        val raw = rawJson.patch(base, "", variant.input)
        if (variant.acceptsValue()) assertAccepted(raw, variant.id) else refuseTypedAndWire(raw, variant.id)
    }

    @Test
    fun rrD11ValidCategoryCodesRemainExact() {
        each("RR-D11", 6) { variant ->
            val rule = rawJson.patch(rawJson.value(base, "/rules/0"), "", variant.input)
            assertAccepted(rawJson.replace(base, "/rules", "[$rule]"), variant.id)
        }
        assertGolden("F-RR-CATEGORY-ORDER", "RR-D11 exact category fixture")
    }

    @Test
    fun rrD12InvalidCategoryCodesAreNotNormalized() = each("RR-D12", 14) { variant ->
        refuseTypedAndWire(rawJson.patch(base, "/rules/0", variant.input), variant.id)
    }

    @Test
    fun rrD13CardinalityAndExactPairUniqueness() = each("RR-D13", 8) { variant ->
        val rules = if ("rule_count" in variant.input) {
            val review = rawJson.value(goldenDefinition("F-RR-REASONS"), "/rules/0")
            (0 until variant.input.number("rule_count")).joinToString(",", "[", "]") { index ->
                rawJson.replace(review, "/categoryCode", rawJson.quoted("C" + index.toString().padStart(3, '0')))
            }
        } else variant.input.getValue("rules").jsonArray.joinToString(",", "[", "]") { element ->
            rawJson.patch(rawJson.value(base, "/rules/0"), "", element.jsonObject)
        }
        val raw = rawJson.replace(base, "/rules", rules)
        if (variant.acceptsValue()) assertAccepted(raw, variant.id) else refuseTypedAndWire(raw, variant.id)
    }

    @Test
    fun rrD14CallerAndReturnedRuleCollectionsCannotChangeOwnedBytes() = each("RR-D14", 5) { variant ->
        val caller = fixtureRules(fields(base)).toMutableList()
        val owned = typed(base, caller)
        assertAccepted(base, variant.id + " before mutation", owned)
        when (variant.name) {
            "append" -> caller += typed(goldenDefinition("F-RR-REASONS")).rules.single {
                it.reason == LocalReturnRightsReason.QUALITY_COMPLAINT
            }
            "clear" -> caller.clear()
            "reverse" -> caller.reverse()
            "replace_element" -> caller[0] = caller[0].copy(receiptWindowDays = 45)
            "exposed_collection" -> {
                val exposed = owned.rules
                @Suppress("UNCHECKED_CAST")
                val mutable = exposed as? MutableList<LocalReturnRightsRule>
                if (mutable != null) runCatching { mutable.clear() }
            }
            else -> error("Unmapped frozen mutation ${variant.id}")
        }
        assertAccepted(base, variant.id + " after mutation", owned)
        assertEquals(fixtures.golden("F-RR-BASE").number("expected_definition_utf8_bytes"),
            codec.canonicalPublication(owned).size - fixtures.domain.number("bytes"), variant.id)
    }

    @Test
    fun rrD15AllSixteenNoncanonicalDefinitionIdentitiesRefuse() = each("RR-D15", 16) { variant ->
        val value = variant.input.text("value")
        val raw = rawJson.replace(base, variant.input.text("path"), rawJson.quoted(value))
        refuseDefinition(raw, variant.id)
        assertFails(variant.id + " public scalar") { DomainId(value) }
    }

    @Test
    fun rrD16ExactNonnegativeInt64RevisionRange() = each("RR-D16", 5) { variant ->
        val revision = variant.input.text("revision")
        val raw = rawJson.replace(base, "/revision", rawJson.quoted(revision))
        if (variant.acceptsValue()) {
            val fixture = fixtures.goldens.single {
                it.getValue("definition").jsonObject.text("revision") == revision
            }
            assertGolden(fixture.text("id"), variant.id)
            assertEquals(revision, assertAccepted(raw, variant.id).revision.value.toString(), variant.id)
        } else {
            refuseDefinition(raw, variant.id)
            val representable = revision.toLongOrNull()
            if (representable != null) assertFails(variant.id + " public scalar") { Revision(representable) }
        }
    }

    @Test
    fun rrD17AccessDateUsesExactRealGregorianCalendar() = each("RR-D17", 17) { variant ->
        val raw = rawJson.patch(base, "", variant.input)
        if (variant.acceptsValue()) assertAccepted(raw, variant.id) else refuseTypedAndWire(raw, variant.id)
    }

    @Test
    fun rrD18HttpsReferencesRetainOriginalSpellingAsDocumentaryData() {
        each("RR-D18", 8) { variant ->
            when {
                "fixture" in variant.input -> assertGolden(variant.input.text("fixture"), variant.id)
                "legalSourceUri_fixture" in variant.input -> {
                    val source = fixtures.uri(variant.input.text("legalSourceUri_fixture"))
                    val uri = source.text("literal")
                    assertEquals(source.number("characters"), uri.length, variant.id)
                    assertAccepted(rawJson.replace(base, "/legalSourceUri", rawJson.quoted(uri)), variant.id)
                }
                else -> assertAccepted(rawJson.patch(base, "", variant.input), variant.id)
            }
        }
        val iri = goldenDefinition("F-RR-CPA-IRI")
        val uri = goldenDefinition("F-RR-CPA-URI")
        assertNotEquals(iri, uri, "RR-D18 original IRI is not percent-normalized")
        assertFalse(content(iri).contentEquals(content(uri)), "RR-D18 distinct signing bytes")
        // DNS/network/effect monitoring and legal-source authentication remain UNBOUND.
    }

    @Test
    fun rrD19InvalidHttpsComponentsAndOverlengthRefuseWithoutRepair() = each("RR-D19", 24) { variant ->
        val raw = if ("legalSourceUri_fixture" in variant.input) {
            val source = fixtures.uri(variant.input.text("legalSourceUri_fixture"))
            val uri = source.text("literal")
            assertEquals(source.number("characters"), uri.length, variant.id)
            rawJson.replace(base, "/legalSourceUri", rawJson.quoted(uri))
        } else rawJson.patch(base, "", variant.input)
        refuseTypedAndWire(raw, variant.id)
    }

    @Test
    fun rrD20BothNoticesHaveIndependentNonblankCharacterLimits() = each("RR-D20", 10) { variant ->
        val raw = rawJson.replace(base, variant.input.text("path"), rawJson.quoted(variant.input.text("literal")))
        if (variant.acceptsValue()) assertAccepted(raw, variant.id) else refuseTypedAndWire(raw, variant.id)
    }

    @Test
    fun rrD21LiteralUnicodeSurvivesButControlsAndLoneSurrogatesRefuse() {
        each("RR-D21", 16) { variant ->
            val path = variant.input.text("path")
            val notice = if ("literal" in variant.input) variant.input.text("literal") else
                variant.input.getValue("code_units_hex").jsonArray.joinToString("") {
                    it.jsonPrimitive.content.toInt(16).toChar().toString()
                }
            val raw = rawJson.replace(base, path, rawJson.quoted(notice))
            if (variant.acceptsValue()) assertAccepted(raw, variant.id) else {
                refuseDefinition(raw, variant.id)
                // Pass the actual code units to the public constructor, not a replacement-encoded copy.
                assertFails(variant.id + " typed notice") {
                    if (path == "/continuingRightsEnglish") typed(base, english = notice)
                    else typed(base, arabic = notice)
                }
            }
        }
        assertGolden("F-RR-NFC", "RR-D21 combining-form fixture")
        assertGolden("F-RR-NFD", "RR-D21 decomposed-form fixture")
    }

    @Test
    fun rrD22NoticeInstructionsRemainLiteralAndDoNotChangeExplicitRules() = each("RR-D22", 3) { variant ->
        val raw = rawJson.patch(base, "", variant.input)
        val value = assertAccepted(raw, variant.id)
        assertEquals(typed(base).rules, value.rules, variant.id + " explicit rules")
        // Genuine signing and fresh production money/stock/refund/physical-effect cuts remain UNBOUND.
    }

    @Test
    fun rrC01AllSevenLiteralCanonicalGoldensAndExactDomainBytes() {
        val prefix = domain.encodeToByteArray(throwOnInvalidSequence = true)
        assertEquals(fixtures.domain.number("bytes"), prefix.size, "RR-C01 domain length")
        assertContentEquals(rawJson.hexBytes(fixtures.domain.text("utf8_hex")), prefix, "RR-C01 domain bytes")
        assertEquals(rawJson.hexBytes(fixtures.domain.text("terminal_byte")).single(), prefix.last(), "RR-C01 one LF")
        assertEquals(domain, CompanyBusinessPublicationKind.RETURN_RIGHTS_V1.domain, "RR-C01 publication purpose")
        each("RR-C01", 7) { variant -> assertGolden(variant.input.text("fixture"), variant.id) }
    }

    @Test
    fun rrC02TypedRulePermutationsCanonicalizeButReceivedOrderRefuses() = each("RR-C02", 4) { variant ->
        val raw = goldenDefinition(variant.input.text("fixture"))
        if ("wire_mutation" in variant.input) {
            refuseDefinition(rawJson.reverseArray(raw, "/rules"), variant.id)
        } else {
            val sourceRules = fixtureRules(fields(raw))
            val supplied = when {
                "typed_rule_order" in variant.input -> variant.input.getValue("typed_rule_order").jsonArray.map {
                    sourceRules[it.jsonPrimitive.int]
                }
                "typed_reason_order" in variant.input -> variant.input.getValue("typed_reason_order").jsonArray.map { reason ->
                    sourceRules.single { it.reason.name == reason.jsonPrimitive.content }
                }
                "typed_category_order" in variant.input -> variant.input.getValue("typed_category_order").jsonArray.map { category ->
                    sourceRules.single { it.categoryCode == category.jsonPrimitive.content }
                }
                else -> error("Unmapped frozen typed order ${variant.id}")
            }
            assertAccepted(raw, variant.id, typed(raw, supplied))
        }
    }

    @Test
    fun rrC03EveryObjectLevelRefusesAlternateFieldOrder() = each("RR-C03", 4) { variant ->
        val fields = variant.input.getValue("swap_fields").jsonArray.map { it.jsonPrimitive.content }
        val level = variant.input.text("object")
        val source = if (level == "envelope") baseEnvelope else base
        val raw = rawJson.swap(source, objectPath(level), fields[0], fields[1])
        if (level == "envelope") refuseEnvelope(raw, variant.id) else refuseDefinition(raw, variant.id)
    }

    @Test
    fun rrC04AllTwentyOneMandatoryFieldsIncludeUnusedNulls() = each("RR-C04", 21) { variant ->
        if ("remove_definition_path" in variant.input) {
            refuseDefinition(rawJson.omit(base, variant.input.text("remove_definition_path")), variant.id)
        } else refuseEnvelope(rawJson.omit(baseEnvelope, variant.input.text("remove_envelope_path")), variant.id)
    }

    @Test
    fun rrC05UnknownAndIdenticalDuplicateFieldsRefuseAtEveryLevel() = each("RR-C05", 8) { variant ->
        val level = variant.input.text("level")
        val source = if (level == "envelope") baseEnvelope else base
        val path = objectPath(level)
        val raw = if ("insert_field" in variant.input) {
            rawJson.insert(source, path, variant.input.text("insert_field"), rawJson.token(variant.input.getValue("value")))
        } else rawJson.duplicate(source, path, variant.input.text("repeat_exact_field"))
        if (level == "envelope") refuseEnvelope(raw, variant.id) else refuseDefinition(raw, variant.id)
    }

    @Test
    fun rrC06WhitespaceBomTrailingDocumentsAndTruncationRefuse() = each("RR-C06", 9) { variant ->
        fun mutation(source: String): String = when (variant.name) {
            "leading_space" -> " $source"
            "trailing_space" -> "$source "
            "after_colon" -> source.replaceFirst(":", ": ")
            "leading_tab" -> "\t$source"
            "trailing_lf" -> "$source\n"
            "pretty_newline" -> source.replaceFirst(",", ",\n")
            "bom" -> "\uFEFF$source"
            "second_object" -> "$source{}"
            "truncated" -> source.dropLast(1)
            else -> error("Unmapped frozen frame mutation ${variant.id}")
        }
        refuseDefinition(mutation(base), variant.id + " definition")
        refuseEnvelope(mutation(baseEnvelope), variant.id + " envelope")
    }

    @Test
    fun rrC07AllThirteenExactNoncanonicalRevisionTokensRefuse() = each("RR-C07", 13) { variant ->
        val raw = rawJson.replace(base, "/revision", variant.input.text("revision_json_token"))
        refuseDefinition(raw, variant.id)
    }

    @Test
    fun rrC08ExactPeriodAndEnumWireTokensAreNeverGuessed() = each("RR-C08", 11) { variant ->
        val source = if ("fixture" in variant.input) goldenDefinition(variant.input.text("fixture")) else base
        val raw = rawJson.replace(source, variant.input.text("path"), variant.input.text("json_token"))
        refuseDefinition(raw, variant.id)
    }

    @Test
    fun rrC09AlternativeEscapesRefuseAndNormalizationFormsStayDistinct() = each("RR-C09", 6) { variant ->
        if (variant.name == "nfc_nfd") {
            val ids = variant.input.getValue("fixtures").jsonArray.map { it.jsonPrimitive.content }
            val values = ids.map { assertGolden(it, variant.id + " $it") }
            assertNotEquals(values[0].continuingRightsEnglish, values[1].continuingRightsEnglish, variant.id)
            assertFalse(content(goldenDefinition(ids[0])).contentEquals(content(goldenDefinition(ids[1]))), variant.id)
        } else {
            val source = goldenDefinition(variant.input.text("fixture"))
            val raw = when (variant.name) {
                "arabic_unicode_escape" -> source.replaceFirst(variant.input.text("replace_first_literal"),
                    variant.input.text("with_json_bytes"))
                "ascii_unicode_escape" -> {
                    val token = rawJson.value(source, "/continuingRightsEnglish").replaceFirst("O",
                        variant.input.text("replace_first_notice_O_with_json_bytes"))
                    rawJson.replace(source, "/continuingRightsEnglish", token)
                }
                "escaped_slash" -> {
                    val token = rawJson.value(source, "/legalSourceUri").replaceFirst("/",
                        variant.input.text("replace_uri_slash_with_json_bytes"))
                    rawJson.replace(source, "/legalSourceUri", token)
                }
                "quote_u_escape" -> source.replaceFirst("\\\"", variant.input.text("replace_first_quote_escape_with_json_bytes"))
                "backslash_u_escape" -> source.replaceFirst("\\\\", variant.input.text("replace_first_backslash_escape_with_json_bytes"))
                else -> error("Unmapped frozen escape mutation ${variant.id}")
            }
            refuseDefinition(raw, variant.id)
        }
    }

    @Test
    fun rrC10MalformedUtf8AndEscapedLoneSurrogatesAreNotRepaired() = each("RR-C10", 8) { variant ->
        if ("notice_utf8_insertion_hex" in variant.input) {
            val inserted = rawJson.hexBytes(variant.input.text("notice_utf8_insertion_hex"))
            val definitionOffset = rawJson.byteOffset(base, rawJson.valueSpan(base, "/continuingRightsEnglish").start + 1)
            val envelopeOffset = rawJson.byteOffset(baseEnvelope,
                rawJson.valueSpan(baseEnvelope, "/definition/continuingRightsEnglish").start + 1)
            val contentBytes = rawJson.insertBytes(content(base), domain.encodeToByteArray().size + definitionOffset, inserted)
            val envelopeBytes = rawJson.insertBytes(baseEnvelope.encodeToByteArray(), envelopeOffset, inserted)
            refuseContent(contentBytes, variant.id + " content")
            refuseEnvelopeBytes(envelopeBytes, variant.id + " envelope")
        } else {
            refuseDefinition(rawJson.replace(base, "/continuingRightsEnglish", variant.input.text("notice_json_token")), variant.id)
        }
    }

    @Test
    fun rrC11AllSixIndependentContentAndEnvelopeByteBoundaries() = each("RR-C11", 6) { variant ->
        val recipe = fixtures.recipe(variant.input.text("size_recipe"))
        val raw = expandRecipe(recipe)
        val fields = Json.parseToJsonElement(raw).jsonObject
        val rules = fields.getValue("rules").jsonArray
        assertEquals(recipe.number("expected_rule_count"), rules.size, variant.id)
        rules.forEach { rule ->
            assertEquals(recipe.number("expected_category_characters"), rule.jsonObject.text("categoryCode").length, variant.id)
        }
        assertEquals(recipe.number("expected_each_notice_characters"), fields.text("continuingRightsEnglish").length, variant.id)
        assertEquals(recipe.number("expected_each_notice_characters"), fields.text("continuingRightsArabic").length, variant.id)
        val expectedContent = content(raw)
        val expectedEnvelope = syntaxEnvelope(raw)
        assertEquals(recipe.number("expected_definition_utf8_bytes"), raw.encodeToByteArray().size, variant.id)
        assertEquals(recipe.number("expected_signing_content_utf8_bytes"), expectedContent.size, variant.id)
        assertEquals(recipe.number("expected_envelope_utf8_bytes"), expectedEnvelope.encodeToByteArray().size, variant.id)
        val value = typed(raw)
        assertFields(raw, value, variant.id)
        val signed = syntaxSigned(value)
        if (variant.expected.startsWith("Refuse definition/signing content")) {
            assertFails(variant.id + " encode content") { codec.canonicalPublication(value) }
            refuseContent(expectedContent, variant.id + " read content")
        } else {
            assertContentEquals(expectedContent, codec.canonicalPublication(value), variant.id)
            val decoded = codec.decodeCanonicalPublication(expectedContent)
            assertFields(raw, decoded, variant.id)
            assertContentEquals(expectedContent, codec.canonicalPublication(decoded), variant.id)
        }
        if (variant.expected.startsWith("Accept canonical definition/content; accept syntax-only envelope")) {
            assertSigned(expectedEnvelope, signed, variant.id)
        } else {
            assertFails(variant.id + " encode envelope") { codec.signedPublication(signed) }
            refuseEnvelope(expectedEnvelope, variant.id + " read envelope")
        }
        // Byte-boundary coverage is documentary only; native output and production effect cuts remain UNBOUND.
    }

    @Test
    fun rrC12EnvelopeSyntaxAndRefusalForwardingDoNotEstablishInstalledTrust() = each("RR-C12", 12) { variant ->
        val field = if ("signatureHex" in variant.input) "signatureHex" else "signingKeyId"
        val literal = variant.input.text(field)
        val raw = rawJson.replace(baseEnvelope, "/$field", rawJson.quoted(literal))
        if (variant.expected.startsWith("Syntax passes")) {
            val signed = LocalSignedReturnRightsPolicy(typed(base), syntaxKey(), literal)
            assertSigned(raw, signed, variant.id)
            var forwarded = false
            val refusalOnly = CompanyPublicationSignatureVerifier { company, keyId, bytes, signature ->
                forwarded = true
                assertEquals(signed.definition.company, company, variant.id)
                assertEquals(signed.signingKeyId, keyId, variant.id)
                assertContentEquals(content(base), bytes, variant.id)
                assertEquals(literal, signature, variant.id)
                false
            }
            assertFalse(codec.verify(signed, refusalOnly), variant.id + " refusal-only forwarding")
            assertTrue(forwarded, variant.id + " verifier reached")
            // Actual original-owner installed trust and fresh durable zero-effect views are UNBOUND.
        } else {
            refuseEnvelope(raw, variant.id)
            if (field == "signatureHex") {
                val definition = typed(base)
                val key = syntaxKey()
                assertFails(variant.id + " typed signature") { LocalSignedReturnRightsPolicy(definition, key, literal) }
            } else assertFails(variant.id + " typed key") { DomainId(literal) }
        }
    }

    @Test
    fun rrC13CallerAndReturnedBuffersCannotAlterLaterCanonicalOutput() = each("RR-C13", 3) { variant ->
        when (variant.name) {
            "definition_source_buffer" -> {
                val caller = content(base)
                val decoded = codec.decodeCanonicalPublication(caller)
                caller[0] = 0
                assertAccepted(base, variant.id, decoded)
            }
            "envelope_source_buffer" -> {
                val caller = baseEnvelope.encodeToByteArray()
                val decoded = codec.decodeSignedPublication(caller)
                val offset = rawJson.byteOffset(baseEnvelope, rawJson.valueSpan(baseEnvelope, "/signatureHex").start + 1)
                caller[offset] = 0xff.toByte()
                assertSigned(baseEnvelope, decoded, variant.id)
            }
            "returned_bytes" -> {
                val value = typed(base)
                val output = codec.canonicalPublication(value)
                assertContentEquals(content(base), output, variant.id + " initial output")
                output[0] = 0
                assertAccepted(base, variant.id + " subsequent output", value)
            }
            else -> error("Unmapped frozen buffer mutation ${variant.id}")
        }
    }

    @Test
    fun rrN01V01PublicProposalBindsExactFrozenIdentityAndAll839ContentBytes() = each("RR-N01", 1) { variant ->
        val fixture = fixtures.golden(variant.input.text("definition"))
        val raw = fixture.text("canonical_definition_json")
        val value = typed(raw)
        val proposal = value.toNativeCompanyReturnRightsProposal()
        val fields = fixture.getValue("definition").jsonObject
        val company = fields.getValue("company").jsonObject
        assertEquals(DomainId(company.text("organizationId")), proposal.company.organizationId, variant.id)
        assertEquals(DomainId(company.text("companyId")), proposal.company.companyId, variant.id)
        assertEquals(DomainId(fields.text("publicationId")), proposal.publicationId, variant.id)
        assertEquals(DomainId(fields.text("policyId")), proposal.entityId, variant.id)
        assertEquals(CompanyBusinessPublicationKind.RETURN_RIGHTS_V1, proposal.kind, variant.id)
        val expectedContent = fixture.text("canonical_signing_content").encodeToByteArray(throwOnInvalidSequence = true)
        assertEquals(fixture.number("expected_signing_content_utf8_bytes"), expectedContent.size, variant.id)
        assertContentEquals(expectedContent, proposal.copyCanonicalContent(), variant.id)
        assertContentEquals(domain.encodeToByteArray(), expectedContent.copyOfRange(0, fixtures.domain.number("bytes")), variant.id)
        assertEquals(rawJson.hexBytes(variant.input.text("domain_utf8_hex_suffix")).single(),
            expectedContent[fixtures.domain.number("bytes") - 1], variant.id)
        // Public proposal construction supplies no native signature, owner authentication, trust or activation.
    }

    private fun each(caseId: String, expectedCount: Int, action: (ReturnRightsFrozenVariantV1) -> Unit) {
        val variants = fixtures.case(caseId)
        assertEquals(expectedCount, variants.size, "$caseId frozen variant count")
        variants.forEach(action)
    }

    private fun ReturnRightsFrozenVariantV1.acceptsValue(): Boolean = expected.startsWith("Accept documentary value")
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
    private fun JsonObject.optionalNumber(key: String): Int? =
        getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.int }
    private fun JsonObject.optionalText(key: String): String? =
        getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.content }

    private fun fields(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private fun goldenDefinition(id: String): String = fixtures.golden(id).text("canonical_definition_json")
    private fun content(raw: String): ByteArray = (domain + raw).encodeToByteArray(throwOnInvalidSequence = true)
    private fun syntaxEnvelope(raw: String): String = rawJson.replace(baseEnvelope, "/definition", raw)
    private fun syntaxKey(): DomainId = DomainId(fields(baseEnvelope).text("signingKeyId"))
    private fun syntaxSigned(value: LocalPublishedReturnRightsPolicy): LocalSignedReturnRightsPolicy =
        LocalSignedReturnRightsPolicy(value, syntaxKey(), fields(baseEnvelope).text("signatureHex"))

    private fun fixtureRule(value: JsonObject): LocalReturnRightsRule = LocalReturnRightsRule(
        value.text("categoryCode"), LocalReturnRightsReason.valueOf(value.text("reason")),
        LocalReturnRightsTreatment.valueOf(value.text("treatment")), value.optionalNumber("receiptWindowDays"),
        value.optionalNumber("coveredResolutionDays"), value.optionalText("exceptionBasis")?.let {
            LocalReturnRightsExceptionBasis.valueOf(it)
        },
    )

    private fun fixtureRules(value: JsonObject): List<LocalReturnRightsRule> =
        value.getValue("rules").jsonArray.map { fixtureRule(it.jsonObject) }

    private fun typed(raw: String, rules: List<LocalReturnRightsRule>? = null,
        english: String? = null, arabic: String? = null): LocalPublishedReturnRightsPolicy =
        typed(fields(raw), rules, english, arabic)

    private fun typed(value: JsonObject, rules: List<LocalReturnRightsRule>? = null,
        english: String? = null, arabic: String? = null): LocalPublishedReturnRightsPolicy {
        val company = value.getValue("company").jsonObject
        return LocalPublishedReturnRightsPolicy(
            CompanyScope(DomainId(company.text("organizationId")), DomainId(company.text("companyId"))),
            DomainId(value.text("publicationId")), DomainId(value.text("policyId")), Revision(value.text("revision").toLong()),
            value.text("jurisdictionCode"), value.text("legalSourceUri"), value.text("legalSourceAccessDate"),
            rules ?: fixtureRules(value), english ?: value.text("continuingRightsEnglish"), arabic ?: value.text("continuingRightsArabic"),
        )
    }

    private fun assertAccepted(raw: String, label: String, value: LocalPublishedReturnRightsPolicy = typed(raw),
        envelope: String = syntaxEnvelope(raw)): LocalPublishedReturnRightsPolicy {
        val expected = content(raw)
        assertFields(raw, value, label)
        assertContentEquals(expected, codec.canonicalPublication(value), label + " encode")
        val decoded = codec.decodeCanonicalPublication(expected)
        assertFields(raw, decoded, label + " read")
        assertContentEquals(expected, codec.canonicalPublication(decoded), label + " read/encode")
        assertSigned(envelope, syntaxSigned(value), label + " syntax envelope")
        return value
    }

    private fun assertGolden(id: String, label: String): LocalPublishedReturnRightsPolicy {
        val fixture = fixtures.golden(id)
        val raw = fixture.text("canonical_definition_json")
        val expectedContent = fixture.text("canonical_signing_content").encodeToByteArray(throwOnInvalidSequence = true)
        val expectedEnvelope = fixture.text("canonical_syntax_only_envelope_json")
        assertEquals(fixture.number("expected_definition_utf8_bytes"), raw.encodeToByteArray().size, label)
        assertEquals(fixture.number("expected_signing_content_utf8_bytes"), expectedContent.size, label)
        assertEquals(fixture.number("expected_envelope_utf8_bytes"), expectedEnvelope.encodeToByteArray().size, label)
        assertContentEquals(expectedContent, content(raw), label + " literal prefix/definition")
        assertEquals(expectedEnvelope, syntaxEnvelope(raw), label + " literal envelope")
        return assertAccepted(raw, label, typed(fixture.getValue("definition").jsonObject), expectedEnvelope)
    }

    private fun assertSigned(raw: String, value: LocalSignedReturnRightsPolicy, label: String) {
        val expected = fields(raw)
        val expectedDefinition = rawJson.value(raw, "/definition")
        fun retained(record: LocalSignedReturnRightsPolicy, suffix: String) {
            assertFields(expectedDefinition, record.definition, label + suffix)
            assertEquals(DomainId(expected.text("signingKeyId")), record.signingKeyId, label + suffix)
            assertEquals(expected.text("signatureHex"), record.signatureHex, label + suffix)
            assertEquals(raw, codec.signedPublication(record), label + suffix)
        }
        retained(value, " encode")
        retained(codec.decodeSignedPublication(raw.encodeToByteArray(throwOnInvalidSequence = true)), " read bytes")
        retained(codec.decodeSignedPublication(raw), " read string")
    }

    private fun assertFields(raw: String, actual: LocalPublishedReturnRightsPolicy, label: String) {
        val expected = fields(raw)
        val company = expected.getValue("company").jsonObject
        assertEquals(company.text("organizationId"), actual.company.organizationId.value, label)
        assertEquals(company.text("companyId"), actual.company.companyId.value, label)
        assertEquals(expected.text("publicationId"), actual.publicationId.value, label)
        assertEquals(expected.text("policyId"), actual.policyId.value, label)
        assertEquals(expected.text("revision"), actual.revision.value.toString(), label)
        assertEquals(expected.text("jurisdictionCode"), actual.jurisdictionCode, label)
        assertEquals(expected.text("legalSourceUri"), actual.legalSourceUri, label)
        assertEquals(expected.text("legalSourceAccessDate"), actual.legalSourceAccessDate, label)
        assertEquals(expected.text("continuingRightsEnglish"), actual.continuingRightsEnglish, label)
        assertEquals(expected.text("continuingRightsArabic"), actual.continuingRightsArabic, label)
        val expectedRules = expected.getValue("rules").jsonArray
        val actualRules = actual.rules
        assertEquals(expectedRules.size, actualRules.size, label)
        expectedRules.forEachIndexed { index, element ->
            val rule = element.jsonObject
            val retained = actualRules[index]
            assertEquals(rule.text("categoryCode"), retained.categoryCode, label + " rule $index")
            assertEquals(rule.text("reason"), retained.reason.name, label + " rule $index")
            assertEquals(rule.text("treatment"), retained.treatment.name, label + " rule $index")
            assertEquals(rule.optionalNumber("receiptWindowDays"), retained.receiptWindowDays, label + " rule $index")
            assertEquals(rule.optionalNumber("coveredResolutionDays"), retained.coveredResolutionDays, label + " rule $index")
            assertEquals(rule.optionalText("exceptionBasis"), retained.exceptionBasis?.name, label + " rule $index")
        }
    }

    private fun refuseTypedAndWire(raw: String, label: String) {
        refuseDefinition(raw, label)
        val input = fields(raw)
        val periodsRepresentable = input.getValue("rules").jsonArray.all { element ->
            val rule = element.jsonObject
            listOf("receiptWindowDays", "coveredResolutionDays").all { key ->
                val token = rule.getValue(key)
                token == JsonNull || token.jsonPrimitive.intOrNull != null
            }
        }
        // Values outside Int32 can only enter through the real decoder; helper parse failure is not a refusal result.
        if (periodsRepresentable) assertFails(label + " typed value/rule") { typed(input) }
    }

    private fun refuseDefinition(raw: String, label: String) {
        refuseContent(content(raw), label + " content")
        refuseEnvelope(syntaxEnvelope(raw), label + " envelope")
    }

    private fun refuseContent(bytes: ByteArray, label: String) {
        assertFails(label) { codec.decodeCanonicalPublication(bytes) }
    }

    private fun refuseEnvelope(raw: String, label: String) {
        val bytes = raw.encodeToByteArray(throwOnInvalidSequence = true)
        refuseEnvelopeBytes(bytes, label + " bytes")
        assertFails(label + " string") { codec.decodeSignedPublication(raw) }
    }

    private fun refuseEnvelopeBytes(bytes: ByteArray, label: String) {
        assertFails(label) { codec.decodeSignedPublication(bytes) }
    }

    private fun objectPath(level: String): String = when (level) {
        "definition", "envelope" -> ""
        "company" -> "/company"
        "rule", "rules[0]" -> "/rules/0"
        else -> error("Unknown frozen object level $level")
    }

    private fun exceptionRule(basis: String): String =
        rawJson.arrayValues(goldenDefinition("F-RR-EXCEPTIONS"), "/rules").single {
            fields(it).optionalText("exceptionBasis") == basis
        }

    private fun expandRecipe(recipe: JsonObject): String {
        var raw = goldenDefinition(recipe.text("base_fixture"))
        val ruleInput = recipe.getValue("replace_rules").jsonObject
        val bounds = ruleInput.getValue("index_inclusive").jsonArray
        val template = rawJson.patch(rawJson.value(raw, "/rules/0"), "",
            ruleInput.filterKeys { it != "index_inclusive" && it != "categoryCode" })
        val rules = (bounds[0].jsonPrimitive.int..bounds[1].jsonPrimitive.int).joinToString(",", "[", "]") { index ->
            val category = "C" + index.toString().padStart(3, '0') + "X".repeat(92)
            rawJson.replace(template, "/categoryCode", rawJson.quoted(category))
        }
        raw = rawJson.replace(raw, "/rules", rules)
        fun notice(key: String): String = recipe.getValue(key).jsonArray.joinToString("") {
            val part = it.jsonObject
            part.text("literal").repeat(part.number("repeat"))
        }
        raw = rawJson.replace(raw, "/continuingRightsEnglish", rawJson.quoted(notice("replace_continuingRightsEnglish")))
        raw = rawJson.replace(raw, "/continuingRightsArabic", rawJson.quoted(notice("replace_continuingRightsArabic")))
        return raw
    }
}
