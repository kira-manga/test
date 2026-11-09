package com.ukkera.pos.catalog.store.independent.companyactivitypublicationv1

import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.LocalCompanyActivityPublicationCodecV1
import com.ukkera.pos.catalog.store.LocalPublishedCompanyActivityProfileV1
import com.ukkera.pos.catalog.store.LocalSignedCompanyActivityProfileV1
import com.ukkera.pos.catalog.store.toNativeCompanyActivityProposal
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Bounded wiring of company-activity-publication-v1-design-1 frozen literals.
 * These methods cover codec/public-proposal observations, not whole CAP cases.
 * Original-owner review/output, authentication, guard-before-parser observation,
 * genuine legacy vectors and all CAP012–026 runtime observations remain unbound.
 * Zero signatures are syntax only. A refusal-only verifier proves forwarding only.
 */
class CompanyActivityPublicationFrozenSlicesTest {
    private val codec = LocalCompanyActivityPublicationCodecV1

    @Test
    fun cap001AllSixteenFrozenSetsAndSixtyFiveInsertionOrders() {
        val representations = mutableSetOf<String>()
        var inputs = 0
        assertEquals(16, SETS.size)
        SETS.forEach { vector ->
            val expected = publication(vector.activities)
            val literal = content(vector.definition)
            assertEquals(vector.contentBytes, literal.size, vector.id)
            assertFields(expected, codec.decodeCanonicalPublication(literal), vector.id)
            permutations(vector.activities).forEach { order ->
                val input = linkedSetOf<LocalSalesActivity>().apply { order.forEach { add(activity(it)) } }
                val actual = codec.canonicalPublication(publication(vector.activities, input))
                assertContentEquals(literal, actual, "${vector.id}: $order")
                representations += actual.decodeToString(throwOnInvalidSequence = true)
                inputs++
            }
        }
        assertEquals(65, inputs)
        assertEquals(16, representations.size)
    }

    @Test
    fun cap002ExplicitEmptyAndOtherCompanyStaySeparateAsData() {
        val empty = decode(golden("G-EMPTY"))
        val clothing = decode(golden("G-CLOTHING"))
        val food = decode(golden("G-FOOD"))
        assertTrue(empty.activities.isEmpty())
        assertEquals(setOf(LocalSalesActivity.CLOTHING), clothing.activities)
        assertEquals(setOf(LocalSalesActivity.CAFE, LocalSalesActivity.RESTAURANT), food.activities)
        assertEquals(DomainId(C1), empty.company.companyId)
        assertEquals(DomainId(C2), food.company.companyId)
        assertNotEquals(empty.company, food.company)
        refuseDefinition(omit(golden("G-EMPTY").definition, "activities"), "CAP002 omitted activities")
        // C1-bound genuine review/output refusal of the valid C2 record is UNBOUND.
    }

    @Test
    fun cap003FrozenInvalidActivityValuesTypesAndDuplicateArraysRefuse() {
        val base = golden("G-CLOTHING").definition
        listOf("restaurant", "Restaurant", " RESTAURANT", "RESTAURANT ", "FOOD", "GIFT_CARD", "CAFE\u0000")
            .forEach { refuseDefinition(replaceValue(base, "activities", "[${quoted(it)}]"), "CAP003 value $it") }
        listOf("[\"CAFE\",\"CAFE\"]", "[\"CLOTHING\",\"RESTAURANT\",\"CLOTHING\"]",
            "null", "\"CAFE\"", "{}", "1", "[null]", "[1]").forEach {
            refuseDefinition(replaceValue(base, "activities", it), "CAP003 $it")
        }
        GOLDENS.forEach { decode(it) }
    }

    @Test
    fun cap004RequiredFieldsDuplicateKeysAndExtraFieldsRefuse() {
        val base = golden("G-CLOTHING")
        DEFINITION_KEYS.forEach { key ->
            refuseDefinition(omit(base.definition, key), "CAP004 definition omitted $key")
            refuseDefinition(replaceValue(base.definition, key, "null"), "CAP004 definition null $key")
            val wrongType = when (key) { "company" -> "[]"; "activities" -> "{}"; else -> "0" }
            refuseDefinition(replaceValue(base.definition, key, wrongType), "CAP004 definition type $key")
            listOf(valueOf(base.definition, key), conflictingValue(base, key)).forEachIndexed { variant, value ->
                refuseDefinition(duplicate(base.definition, key, value), "CAP004 definition duplicate $key/$variant")
            }
        }
        ENVELOPE_KEYS.forEach { key ->
            refuseEnvelope(omit(base.envelope, key), "CAP004 envelope omitted $key")
            refuseEnvelope(replaceValue(base.envelope, key, "null"), "CAP004 envelope null $key")
            val wrongType = if (key == "definition") "[]" else "0"
            refuseEnvelope(replaceValue(base.envelope, key, wrongType), "CAP004 envelope type $key")
            listOf(valueOf(base.envelope, key), conflictingValue(base, key)).forEachIndexed { variant, value ->
                refuseEnvelope(duplicate(base.envelope, key, value), "CAP004 envelope duplicate $key/$variant")
            }
        }
        refuseDefinition(addMember(base.definition, null, "enabled", "true"), "CAP004 definition extra")
        refuseDefinition(addMember(base.definition, "company", "branchId", quoted(B1)), "CAP004 company extra")
        refuseEnvelope(addMember(base.envelope, null, "approved", "true"), "CAP004 envelope extra")
        decode(golden("G-EMPTY")) // Explicit revision 0 and [] are not omissions.
    }

    @Test
    fun cap005FrozenUuidAndRevisionGrammarPreservesExactValues() {
        val base = golden("G-CLOTHING")
        val invalidIds = listOf("", "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF", "10000000000040008000000000000001",
            "{10000000-0000-4000-8000-000000000001}", " 10000000-0000-4000-8000-000000000001",
            "10000000-0000-4000-8000-000000000001 ", "10000000-0000-4000-8000-00000000000g",
            "10000000-0000-4000-8000-00000000000", "10000000-0000-4000-8000-0000000000001")
        (IDENTITY_KEYS + "signingKeyId").forEach { key ->
            invalidIds.forEach { value ->
                refuseIdentity(base, key, quoted(value), "CAP005 $key UUID $value")
                assertFails("CAP005 typed $key UUID") { DomainId(value) }
            }
            listOf("0", "1", "false", "null", "{}", "[]").forEach {
                refuseIdentity(base, key, it, "CAP005 $key type $it")
            }
            if (key == "signingKeyId") {
                val raw = replaceValue(base.envelope, key, quoted(VALID_UUID))
                val decoded = codec.decodeSignedPublication(raw.encodeToByteArray())
                assertEquals(DomainId(VALID_UUID), decoded.signingKeyId)
                assertEquals(raw, codec.signedPublication(decoded))
            } else {
                val raw = replaceValue(base.definition, key, quoted(VALID_UUID))
                val decoded = codec.decodeCanonicalPublication(content(raw))
                assertEquals(DomainId(VALID_UUID), identity(decoded, key))
                assertContentEquals(content(raw), codec.canonicalPublication(decoded))
                val envelope = replaceValue(base.envelope, "definition", raw)
                val signed = codec.decodeSignedPublication(envelope.encodeToByteArray())
                assertEquals(DomainId(VALID_UUID), identity(signed.definition, key))
                assertEquals(envelope, codec.signedPublication(signed))
            }
        }
        listOf("0", "1", "9007199254740993", "9223372036854775807").forEach { revision ->
            val raw = replaceValue(base.definition, "revision", quoted(revision))
            val decoded = codec.decodeCanonicalPublication(content(raw))
            assertEquals(revision, decoded.revision.value.toString())
            assertEquals(Revision(revision.toLong()), decoded.revision)
            assertContentEquals(content(raw), codec.canonicalPublication(decoded))
            val envelope = replaceValue(base.envelope, "definition", raw)
            val signed = codec.decodeSignedPublication(envelope.encodeToByteArray())
            assertEquals(revision, signed.definition.revision.value.toString())
            assertEquals(envelope, codec.signedPublication(signed))
        }
        listOf("", "-1", "+1", "-0", "00", "01", "1.0", "1e0", " 1", "1 ", "NaN", "Infinity",
            "9223372036854775808").forEach {
            refuseDefinition(replaceValue(base.definition, "revision", quoted(it)), "CAP005 revision $it")
        }
        listOf("0", "1", "false", "null", "{}", "[]").forEach {
            refuseDefinition(replaceValue(base.definition, "revision", it), "CAP005 revision type $it")
        }
        assertFails { Revision(-1) }
    }

    @Test
    fun cap006NoncanonicalDefinitionsAndExactDomainPrefixRefuse() {
        val base = golden("G-CLOTHING").definition
        val outer = listOf("company", "publicationId", "profileId", "revision", "activities")
        outer.indices.forEach { a -> (a + 1 until outer.size).forEach { b ->
            refuseDefinition(swapMembers(base, outer[a], outer[b]), "CAP006 outer $a/$b")
        } }
        refuseDefinition(swapMembers(base, "organizationId", "companyId"), "CAP006 company order")
        listOf("[\"RESTAURANT\",\"CAFE\"]", "[\"SUPERMARKET\",\"RESTAURANT\",\"CLOTHING\",\"CAFE\"]")
            .forEach { refuseDefinition(replaceValue(base, "activities", it), "CAP006 activity order") }
        listOf(base.replaceFirst("{", "{ "), base.replaceFirst("{", "{\n"),
            replaceValue(base, "publicationId", valueOf(base, "publicationId").replaceFirst("2", "\\u0032")),
            golden("G-ALL").definition.replace("\"CAFE\"", "\"CA\\u0046E\""), "\uFEFF$base")
            .forEach { refuseDefinition(it, "CAP006 noncanonical definition") }
        listOf("\n", "\u0000", "{}").forEach { suffix ->
            refuseContent(content(base) + suffix.encodeToByteArray(), "CAP006 content suffix")
        }
        refuseContent("\uFEFF".encodeToByteArray() + content(base), "CAP006 content BOM")
        listOf("", PREFIX.dropLast(1), PREFIX.dropLast(1) + "\r\n", PREFIX + "\n", PREFIX.dropLast(1) + "\\n")
            .forEach { refuseContent((it + base).encodeToByteArray(), "CAP006 prefix $it") }
        assertEquals(35, PREFIX.encodeToByteArray().size)
    }

    @Test
    fun cap007RawMalformedUtf8AndInvalidStringsRefuseWithoutRepair() {
        val base = golden("G-CLOTHING")
        listOf("80", "c080", "e282", "e228a1", "eda080", "f4908080").forEach { hex ->
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            listOf("publicationId", "companyId", "activities").forEach { key ->
                val definitionOffset = member(base.definition, key).valueStart + if (key == "activities") 2 else 1
                val envelopeOffset = member(base.envelope, key).valueStart + if (key == "activities") 2 else 1
                refuseContent(splice(PREFIX + base.definition, PREFIX.length + definitionOffset, bytes), "CAP007 $key/$hex")
                assertFails("CAP007 envelope $key/$hex") {
                    codec.decodeSignedPublication(splice(base.envelope, envelopeOffset, bytes))
                }
            }
        }
        listOf("\"\\ud800\"", "\"\\udc00\"", "\"\\u0000\"", "\"\\u0001\"", "\"\u0000\"", "\"\u0001\"")
            .forEach { raw ->
                IDENTITY_KEYS.forEach { refuseDefinition(replaceValue(base.definition, it, raw), "CAP007 identity $it") }
                refuseDefinition(replaceValue(base.definition, "activities", "[$raw]"), "CAP007 enum")
                refuseEnvelope(replaceValue(base.envelope, "signingKeyId", raw), "CAP007 key")
            }
        listOf("\uD800", "\uDC00", "\u0000", "\u0001").forEach { value ->
            assertFails("CAP007 typed identity") { DomainId(value) }
            assertFails("CAP007 typed enum name") { LocalSalesActivity.valueOf(value) }
            assertFails("CAP007 strict string overload") {
                codec.decodeSignedPublication(replaceValue(base.envelope, "signingKeyId", "\"$value\""))
            }
        }
    }

    @Test
    fun cap008FrozenIndependentSizeVectorsRefuseButGuardOrderIsUnbound() {
        val max = golden("G-MAX")
        assertEquals(319, max.definition.encodeToByteArray().size)
        assertEquals(354, content(max.definition).size)
        assertEquals(534, max.envelope.encodeToByteArray().size)
        decode(max)
        assertFields(typed(max), codec.decodeSignedPublication(max.envelope.encodeToByteArray()).definition, max.id)
        listOf(48797 to 49151, 48798 to 49152, 48799 to 49153).forEach { (padding, size) ->
            val raw = content(max.definition) + ByteArray(padding) { 0x20 }
            assertEquals(size, raw.size)
            refuseContent(raw, "CAP008 B-C-$size")
        }
        listOf(48617 to 49151, 48618 to 49152, 48619 to 49153).forEach { (padding, size) ->
            val raw = max.envelope.encodeToByteArray() + ByteArray(padding) { 0x20 }
            assertEquals(size, raw.size)
            assertFails("CAP008 B-E-$size") { codec.decodeSignedPublication(raw) }
        }
        val wide = replaceValue(golden("G-EMPTY").envelope, "signingKeyId", quoted("a".repeat(50000)))
        assertEquals(50436, wide.encodeToByteArray().size)
        refuseEnvelope(wide, "CAP008 B-E-WIDE")
        // All padded inputs are invalid. Generic refusal does not observe guard-before-parser order.
    }

    @Test
    fun cap009PublicProposalDescribesOnlyItsOwnCanonicalRecord() {
        val base = golden("G-CLOTHING")
        assertProposal(typed(base).toNativeCompanyActivityProposal(), typed(base), content(base.definition), "CAP009 matching request")
        listOf("organizationId" to quoted(O2), "companyId" to quoted(C2), "publicationId" to quoted(P2),
            "profileId" to quoted(F2), "revision" to "\"1\"", "activities" to "[\"RESTAURANT\"]")
            .forEach { (field, value) ->
                val raw = replaceValue(base.definition, field, value)
                val altered = codec.decodeCanonicalPublication(content(raw))
                assertContentEquals(content(raw), codec.canonicalPublication(altered))
                assertNotEquals(typed(base), altered)
                assertProposal(altered.toNativeCompanyActivityProposal(), altered, content(raw), "CAP009 request $field")
            }
        // A public proposal is descriptive, not an original review. All original-review/output
        // mismatches, including canonical revision/activity changes and signing-key binding, are UNBOUND.
    }

    @Test
    fun cap010ActivityWrongDomainAndPublicProposalPrefixRefuseOnly() {
        val base = golden("G-CLOTHING")
        val definition = typed(base)
        listOf(CompanyBusinessPublicationKind.CATALOG_SKU_V1 to "ukkera.company-sku-publication/v1\n",
            CompanyBusinessPublicationKind.FOOD_RECIPE_V1 to "ukkera.company-food-recipe/v1\n").forEach { (kind, domain) ->
            refuseContent((domain + base.definition).encodeToByteArray(), "CAP010 activity under $kind")
            assertFails("CAP010 proposal wrong kind $kind") {
                CompanyBusinessPublicationProposal(definition.company, kind, definition.publicationId,
                    definition.profileId, content(base.definition))
            }
        }
        // Genuine valid legacy opposite-direction vectors, unchanged legacy decoding, founding/
        // installation boundaries and separately authenticated wrong-domain signatures are UNBOUND.
    }

    @Test
    fun cap011FrozenSyntaxEnvelopesAndSignatureMutations() {
        GOLDENS.forEach { fixture ->
            assertEquals(fixture.definitionBytes, fixture.definition.encodeToByteArray().size, fixture.id)
            assertEquals(fixture.contentBytes, content(fixture.definition).size, fixture.id)
            assertEquals(fixture.envelopeBytes, fixture.envelope.encodeToByteArray().size, fixture.id)
            val expected = LocalSignedCompanyActivityProfileV1(typed(fixture), DomainId(K1), ZERO_SIGNATURE)
            assertEquals(expected, codec.decodeSignedPublication(fixture.envelope))
            assertEquals(expected, codec.decodeSignedPublication(fixture.envelope.encodeToByteArray()))
            assertEquals(fixture.envelope, codec.signedPublication(expected))
        }
        val base = golden("G-CLOTHING").envelope
        ENVELOPE_KEYS.indices.forEach { a -> (a + 1 until ENVELOPE_KEYS.size).forEach { b ->
            refuseEnvelope(swapMembers(base, ENVELOPE_KEYS[a], ENVELOPE_KEYS[b]), "CAP011 envelope order $a/$b")
        } }
        listOf(base.replaceFirst("{", "{ "), base.replaceFirst("{", "{\n"), "\uFEFF$base",
            "$base\n", "$base\u0000", "$base{}").forEach { refuseEnvelope(it, "CAP011 noncanonical envelope") }
        listOf("a".repeat(127), "a".repeat(129), "A".repeat(128), "a".repeat(127) + "g",
            " $ZERO_SIGNATURE", "$ZERO_SIGNATURE ", "0x$ZERO_SIGNATURE").forEach { signature ->
            refuseEnvelope(replaceValue(base, "signatureHex", quoted(signature)), "CAP011 signature syntax")
            assertFails("CAP011 typed signature syntax") {
                LocalSignedCompanyActivityProfileV1(typed(golden("G-CLOTHING")), DomainId(K1), signature)
            }
        }
        listOf("null", "0").forEach { refuseEnvelope(replaceValue(base, "signatureHex", it), "CAP011 signature type") }
        refuseEnvelope(omit(base, "signatureHex"), "CAP011 omitted signature")
    }

    @Test
    fun cap011RefusalOnlyVerifierReceivesExactFrozenContent() {
        GOLDENS.forEach { fixture ->
            var calls = 0
            var observedCompany: CompanyScope? = null
            var observedKey: DomainId? = null
            var observedBytes: ByteArray? = null
            var observedSignature: String? = null
            val refusalOnly = CompanyPublicationSignatureVerifier { company, key, bytes, signature ->
                calls++
                observedCompany = company
                observedKey = key
                observedBytes = bytes.copyOf()
                observedSignature = signature
                false
            }
            assertFalse(codec.verify(codec.decodeSignedPublication(fixture.envelope), refusalOnly), fixture.id)
            assertEquals(1, calls, fixture.id)
            assertEquals(typed(fixture).company, observedCompany, fixture.id)
            assertEquals(DomainId(K1), observedKey, fixture.id)
            assertContentEquals(content(fixture.definition), observedBytes, fixture.id)
            assertEquals(ZERO_SIGNATURE, observedSignature, fixture.id)
        }
        // This is exact forwarding and negative-result propagation, not independent cryptography.
    }

    @Test
    fun cap027CallerAndReturnedCollectionsKeepFrozenRecordsStable() {
        STABILITY.forEach { (goldenId, callerAfter) ->
            val fixture = golden(goldenId)
            checkStability(fixture, "CAP027 $goldenId/caller") { caller, _, _, _, _ ->
                caller.clear()
                caller.addAll(callerAfter.map(::activity))
            }
            listOf("publication", "envelope", "decodedPublication", "decodedEnvelope").forEach { target ->
                listOf("clear", "add", "remove").forEach { operation ->
                    checkStability(fixture, "CAP027 $goldenId/$target/$operation") { _, publication, envelope, decoded, decodedEnvelope ->
                        val returned = when (target) {
                            "publication" -> publication.activities
                            "envelope" -> envelope.definition.activities
                            "decodedPublication" -> decoded.activities
                            else -> decodedEnvelope.definition.activities
                        }
                        // Refusal or mutation of detached data are both allowed by CAP027.
                        runCatching {
                            val mutable = returned as MutableSet<LocalSalesActivity>
                            when (operation) {
                                "clear" -> mutable.clear()
                                "add" -> mutable.add(LocalSalesActivity.CAFE)
                                else -> mutable.remove(LocalSalesActivity.CLOTHING)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun cap027PublicProposalContentCopiesAreDetached() {
        STABILITY.forEach { (goldenId, _) ->
            val fixture = golden(goldenId)
            listOf("callerBytes", "returnedBytes").forEach { variant ->
                val expected = content(fixture.definition)
                val caller = expected.copyOf()
                val definition = typed(fixture)
                val proposal = CompanyBusinessPublicationProposal(definition.company,
                    CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1, definition.publicationId, definition.profileId, caller)
                val digestBefore = proposal.contentDigest
                if (variant == "callerBytes") caller.fill(0) else proposal.copyCanonicalContent().fill(0)
                assertProposal(proposal, definition, expected, "CAP027 $goldenId/$variant")
                assertEquals(digestBefore, proposal.contentDigest)
            }
        }
        // No genuine owner review/signing or retained-storage stability is claimed.
    }

    private fun checkStability(fixture: Golden, label: String, mutation: (MutableSet<LocalSalesActivity>, LocalPublishedCompanyActivityProfileV1,
        LocalSignedCompanyActivityProfileV1, LocalPublishedCompanyActivityProfileV1, LocalSignedCompanyActivityProfileV1) -> Unit) {
        val caller = fixture.activities.map(::activity).toMutableSet()
        val constructed = typed(fixture, caller)
        val signed = LocalSignedCompanyActivityProfileV1(constructed, DomainId(K1), ZERO_SIGNATURE)
        val decoded = codec.decodeCanonicalPublication(content(fixture.definition))
        val decodedSigned = codec.decodeSignedPublication(fixture.envelope)
        val comparison = typed(fixture)
        val signedComparison = LocalSignedCompanyActivityProfileV1(comparison, DomainId(K1), ZERO_SIGNATURE)
        val publications = listOf(constructed, signed.definition, decoded, decodedSigned.definition)
        val envelopes = listOf(signed, decodedSigned)
        val publicationHashes = publications.map { it.hashCode() }
        val envelopeHashes = envelopes.map { it.hashCode() }
        val publicationEqualities = publications.map { it == comparison }
        val envelopeEqualities = envelopes.map { it == signedComparison }
        val publicationMaps = publications.map { hashMapOf(it to "retained") }
        val publicationSets = publications.map { hashSetOf(it) }
        val envelopeMaps = envelopes.map { hashMapOf(it to "retained") }
        val envelopeSets = envelopes.map { hashSetOf(it) }
        val proposal = constructed.toNativeCompanyActivityProposal()
        val proposalDigest = proposal.contentDigest
        publications.forEachIndexed { i, record ->
            assertFields(comparison, record, label)
            assertContentEquals(content(fixture.definition), codec.canonicalPublication(record), label)
            assertTrue(publicationEqualities[i], label)
            assertEquals("retained", publicationMaps[i][record], label)
            assertTrue(record in publicationSets[i], label)
        }
        envelopes.forEachIndexed { i, record ->
            assertEquals(fixture.envelope, codec.signedPublication(record), label)
            assertTrue(envelopeEqualities[i], label)
            assertEquals("retained", envelopeMaps[i][record], label)
            assertTrue(record in envelopeSets[i], label)
        }
        mutation(caller, constructed, signed, decoded, decodedSigned)
        publications.forEachIndexed { i, record ->
            assertFields(comparison, record, label)
            assertContentEquals(content(fixture.definition), codec.canonicalPublication(record), label)
            assertEquals(publicationEqualities[i], record == comparison, label)
            assertEquals(publicationHashes[i], record.hashCode(), label)
            assertEquals("retained", publicationMaps[i][record], label)
            assertEquals("retained", publicationMaps[i][comparison], label)
            assertTrue(record in publicationSets[i], label)
            assertTrue(comparison in publicationSets[i], label)
        }
        envelopes.forEachIndexed { i, record ->
            assertEquals(DomainId(K1), record.signingKeyId)
            assertEquals(ZERO_SIGNATURE, record.signatureHex)
            assertEquals(fixture.envelope, codec.signedPublication(record), label)
            assertEquals(envelopeEqualities[i], record == signedComparison, label)
            assertEquals(envelopeHashes[i], record.hashCode(), label)
            assertEquals("retained", envelopeMaps[i][record], label)
            assertEquals("retained", envelopeMaps[i][signedComparison], label)
            assertTrue(record in envelopeSets[i], label)
            assertTrue(signedComparison in envelopeSets[i], label)
        }
        assertProposal(proposal, comparison, content(fixture.definition), label)
        assertEquals(proposalDigest, proposal.contentDigest)
    }

    private fun assertFields(expected: LocalPublishedCompanyActivityProfileV1, actual: LocalPublishedCompanyActivityProfileV1, label: String) {
        assertEquals(expected.company.organizationId, actual.company.organizationId, label)
        assertEquals(expected.company.companyId, actual.company.companyId, label)
        assertEquals(expected.publicationId, actual.publicationId, label)
        assertEquals(expected.profileId, actual.profileId, label)
        assertEquals(expected.revision, actual.revision, label)
        assertEquals(expected.activities, actual.activities, label)
        assertEquals(expected, actual, label)
    }

    private fun assertProposal(proposal: CompanyBusinessPublicationProposal, definition: LocalPublishedCompanyActivityProfileV1,
        expectedContent: ByteArray, label: String) {
        assertEquals(definition.company, proposal.company, label)
        assertEquals(CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1, proposal.kind, label)
        assertEquals(definition.publicationId, proposal.publicationId, label)
        assertEquals(definition.profileId, proposal.entityId, label)
        assertContentEquals(expectedContent, proposal.copyCanonicalContent(), label)
    }

    private fun decode(fixture: Golden): LocalPublishedCompanyActivityProfileV1 =
        codec.decodeCanonicalPublication(content(fixture.definition)).also { assertFields(typed(fixture), it, fixture.id) }

    private fun typed(fixture: Golden, activities: Set<LocalSalesActivity> = fixture.activities.map(::activity).toSet()) =
        LocalPublishedCompanyActivityProfileV1(CompanyScope(DomainId(O1), DomainId(fixture.companyId)),
            DomainId(fixture.publicationId), DomainId(fixture.profileId), Revision(fixture.revision.toLong()), activities)

    private fun publication(names: List<String>, activities: Set<LocalSalesActivity> = names.map(::activity).toSet()) =
        LocalPublishedCompanyActivityProfileV1(CompanyScope(DomainId(O1), DomainId(C1)), DomainId(P1), DomainId(F1), Revision(0), activities)

    private fun identity(value: LocalPublishedCompanyActivityProfileV1, key: String): DomainId = when (key) {
        "organizationId" -> value.company.organizationId
        "companyId" -> value.company.companyId
        "publicationId" -> value.publicationId
        else -> value.profileId
    }

    private fun refuseIdentity(base: Golden, key: String, raw: String, label: String) {
        if (key == "signingKeyId") refuseEnvelope(replaceValue(base.envelope, key, raw), label)
        else refuseDefinition(replaceValue(base.definition, key, raw), label)
    }

    private fun refuseDefinition(raw: String, label: String) {
        refuseContent(content(raw), label)
        refuseEnvelope(replaceValue(golden("G-CLOTHING").envelope, "definition", raw), "$label envelope definition")
    }
    private fun refuseContent(raw: ByteArray, label: String) { assertFails(label) { codec.decodeCanonicalPublication(raw) } }
    private fun refuseEnvelope(raw: String, label: String) {
        assertFails("$label bytes") { codec.decodeSignedPublication(raw.encodeToByteArray()) }
        assertFails("$label string") { codec.decodeSignedPublication(raw) }
    }

    private fun conflictingValue(base: Golden, key: String): String = when (key) {
        "company" -> replaceValue(valueOf(base.definition, key), "companyId", quoted(C2))
        "organizationId" -> quoted(O2)
        "companyId" -> quoted(C2)
        "publicationId" -> quoted(P2)
        "profileId" -> quoted(F2)
        "revision" -> "\"1\""
        "activities" -> "[\"RESTAURANT\"]"
        "definition" -> golden("G-FOOD").definition
        "signingKeyId" -> quoted(K2)
        else -> quoted("a".repeat(128))
    }

    /** Raw member spans preserve duplicate keys/order; corrupted inputs are never parsed or repaired. */
    private data class Member(val start: Int, val valueStart: Int, val end: Int)
    private fun member(raw: String, key: String): Member {
        val marker = "\"$key\":"
        val start = raw.indexOf(marker)
        require(start >= 0)
        val valueStart = start + marker.length
        var position = valueStart
        var nesting = 0
        var inString = false
        var escaped = false
        while (position < raw.length) {
            val char = raw[position]
            if (inString) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '{', '[' -> nesting++
                    '}', ']' -> { if (nesting == 0) break; nesting-- }
                    ',' -> if (nesting == 0) break
                }
            }
            position++
            if (!inString && nesting == 0 && raw[valueStart] in listOf('"', '{', '[')) break
        }
        return Member(start, valueStart, position)
    }
    private fun valueOf(raw: String, key: String): String = member(raw, key).let { raw.substring(it.valueStart, it.end) }
    private fun replaceValue(raw: String, key: String, value: String): String = member(raw, key).let {
        raw.substring(0, it.valueStart) + value + raw.substring(it.end)
    }
    private fun omit(raw: String, key: String): String = member(raw, key).let {
        when {
            raw.getOrNull(it.end) == ',' -> raw.removeRange(it.start, it.end + 1)
            raw.getOrNull(it.start - 1) == ',' -> raw.removeRange(it.start - 1, it.end)
            else -> raw.removeRange(it.start, it.end)
        }
    }
    private fun duplicate(raw: String, key: String, value: String): String = member(raw, key).let {
        raw.substring(0, it.end) + "," + raw.substring(it.start, it.valueStart) + value + raw.substring(it.end)
    }
    private fun addMember(raw: String, container: String?, key: String, value: String): String {
        val end = if (container == null) raw.length - 1 else member(raw, container).end - 1
        return raw.substring(0, end) + ",\"$key\":$value" + raw.substring(end)
    }
    private fun swapMembers(raw: String, first: String, second: String): String {
        val a = member(raw, first)
        val b = member(raw, second)
        val left = if (a.start < b.start) a else b
        val right = if (a.start < b.start) b else a
        return raw.substring(0, left.start) + raw.substring(right.start, right.end) + raw.substring(left.end, right.start) +
            raw.substring(left.start, left.end) + raw.substring(right.end)
    }
    private fun splice(rawAscii: String, offset: Int, replacement: ByteArray): ByteArray {
        val bytes = rawAscii.encodeToByteArray()
        return bytes.copyOfRange(0, offset) + replacement + bytes.copyOfRange(offset + 1, bytes.size)
    }
    private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\u0000", "\\u0000").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    private fun permutations(values: List<String>): List<List<String>> = if (values.isEmpty()) listOf(emptyList()) else
        values.flatMap { value -> permutations(values - value).map { listOf(value) + it } }
    private fun activity(value: String): LocalSalesActivity = LocalSalesActivity.valueOf(value)
    private fun content(definition: String): ByteArray = (PREFIX + definition).encodeToByteArray()
    private fun golden(id: String): Golden = GOLDENS.single { it.id == id }

    private data class SetVector(val id: String, val activities: List<String>, val definition: String, val contentBytes: Int)
    private data class Golden(val id: String, val definition: String, val envelope: String, val companyId: String,
        val publicationId: String, val profileId: String, val revision: String, val activities: List<String>,
        val definitionBytes: Int, val contentBytes: Int, val envelopeBytes: Int)

    private companion object {
        const val PREFIX = "ukkera.company-activity-profile/v1\n"
        const val O1 = "00000000-0000-4000-8000-000000000001"
        const val O2 = "00000000-0000-4000-8000-000000000002"
        const val C1 = "10000000-0000-4000-8000-000000000001"
        const val C2 = "10000000-0000-4000-8000-000000000002"
        const val P1 = "20000000-0000-4000-8000-000000000001"
        const val P2 = "20000000-0000-4000-8000-000000000002"
        const val F1 = "30000000-0000-4000-8000-000000000001"
        const val F2 = "30000000-0000-4000-8000-000000000002"
        const val K1 = "40000000-0000-4000-8000-000000000001"
        const val K2 = "40000000-0000-4000-8000-000000000002"
        const val B1 = "50000000-0000-4000-8000-000000000001"
        const val VALID_UUID = "abcdefab-cdef-4abc-8def-abcdefabcdef"
        const val ZERO_SIGNATURE = "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        val IDENTITY_KEYS = listOf("organizationId", "companyId", "publicationId", "profileId")
        val DEFINITION_KEYS = listOf("company", "organizationId", "companyId", "publicationId", "profileId", "revision", "activities")
        val ENVELOPE_KEYS = listOf("definition", "signingKeyId", "signatureHex")
        val STABILITY = listOf("G-CLOTHING" to listOf("CAFE", "RESTAURANT"),
            "G-EMPTY" to listOf("CAFE", "CLOTHING", "RESTAURANT", "SUPERMARKET"), "G-ALL" to emptyList())

        // Literal definition/envelope text copied from the frozen fixtures, never production encoders.
        val SETS = listOf(
            SetVector("S-00", listOf(),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":[]}""", 292),
            SetVector("S-01", listOf("CAFE"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE"]}""", 298),
            SetVector("S-02", listOf("CLOTHING"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CLOTHING"]}""", 302),
            SetVector("S-03", listOf("RESTAURANT"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["RESTAURANT"]}""", 304),
            SetVector("S-04", listOf("SUPERMARKET"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["SUPERMARKET"]}""", 305),
            SetVector("S-05", listOf("CAFE", "CLOTHING"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE","CLOTHING"]}""", 309),
            SetVector("S-06", listOf("CAFE", "RESTAURANT"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE","RESTAURANT"]}""", 311),
            SetVector("S-07", listOf("CAFE", "SUPERMARKET"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE","SUPERMARKET"]}""", 312),
            SetVector("S-08", listOf("CLOTHING", "RESTAURANT"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CLOTHING","RESTAURANT"]}""", 315),
            SetVector("S-09", listOf("CLOTHING", "SUPERMARKET"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CLOTHING","SUPERMARKET"]}""", 316),
            SetVector("S-10", listOf("RESTAURANT", "SUPERMARKET"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["RESTAURANT","SUPERMARKET"]}""", 318),
            SetVector("S-11", listOf("CAFE", "CLOTHING", "RESTAURANT"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE","CLOTHING","RESTAURANT"]}""", 322),
            SetVector("S-12", listOf("CAFE", "CLOTHING", "SUPERMARKET"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE","CLOTHING","SUPERMARKET"]}""", 323),
            SetVector("S-13", listOf("CAFE", "RESTAURANT", "SUPERMARKET"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE","RESTAURANT","SUPERMARKET"]}""", 325),
            SetVector("S-14", listOf("CLOTHING", "RESTAURANT", "SUPERMARKET"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CLOTHING","RESTAURANT","SUPERMARKET"]}""", 329),
            SetVector("S-15", listOf("CAFE", "CLOTHING", "RESTAURANT", "SUPERMARKET"),
                """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE","CLOTHING","RESTAURANT","SUPERMARKET"]}""", 336),
        )
        val GOLDENS = listOf(
            Golden("G-EMPTY", SETS[0].definition,
                """{"definition":{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":[]},"signingKeyId":"40000000-0000-4000-8000-000000000001","signatureHex":"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}""",
                "10000000-0000-4000-8000-000000000001", "20000000-0000-4000-8000-000000000001", "30000000-0000-4000-8000-000000000001",
                "0", listOf(), 257, 292, 472),
            Golden("G-CLOTHING", SETS[2].definition,
                """{"definition":{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CLOTHING"]},"signingKeyId":"40000000-0000-4000-8000-000000000001","signatureHex":"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}""",
                "10000000-0000-4000-8000-000000000001", "20000000-0000-4000-8000-000000000001", "30000000-0000-4000-8000-000000000001",
                "0", listOf("CLOTHING"), 267, 302, 482),
            Golden("G-FOOD", """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000002"},"publicationId":"20000000-0000-4000-8000-000000000002","profileId":"30000000-0000-4000-8000-000000000002","revision":"3","activities":["CAFE","RESTAURANT"]}""",
                """{"definition":{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000002"},"publicationId":"20000000-0000-4000-8000-000000000002","profileId":"30000000-0000-4000-8000-000000000002","revision":"3","activities":["CAFE","RESTAURANT"]},"signingKeyId":"40000000-0000-4000-8000-000000000001","signatureHex":"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}""",
                "10000000-0000-4000-8000-000000000002", "20000000-0000-4000-8000-000000000002", "30000000-0000-4000-8000-000000000002",
                "3", listOf("CAFE", "RESTAURANT"), 276, 311, 491),
            Golden("G-ALL", SETS[15].definition,
                """{"definition":{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE","CLOTHING","RESTAURANT","SUPERMARKET"]},"signingKeyId":"40000000-0000-4000-8000-000000000001","signatureHex":"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}""",
                "10000000-0000-4000-8000-000000000001", "20000000-0000-4000-8000-000000000001", "30000000-0000-4000-8000-000000000001",
                "0", listOf("CAFE", "CLOTHING", "RESTAURANT", "SUPERMARKET"), 301, 336, 516),
            Golden("G-MAX", """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"9223372036854775807","activities":["CAFE","CLOTHING","RESTAURANT","SUPERMARKET"]}""",
                """{"definition":{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"9223372036854775807","activities":["CAFE","CLOTHING","RESTAURANT","SUPERMARKET"]},"signingKeyId":"40000000-0000-4000-8000-000000000001","signatureHex":"00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"}""",
                "10000000-0000-4000-8000-000000000001", "20000000-0000-4000-8000-000000000001", "30000000-0000-4000-8000-000000000001",
                "9223372036854775807", listOf("CAFE", "CLOTHING", "RESTAURANT", "SUPERMARKET"), 319, 354, 534),
        )
    }
}
