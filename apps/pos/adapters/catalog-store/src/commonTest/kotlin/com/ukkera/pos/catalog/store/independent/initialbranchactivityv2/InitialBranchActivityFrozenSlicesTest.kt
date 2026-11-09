package com.ukkera.pos.catalog.store.independent.initialbranchactivityv2

import com.ukkera.pos.catalog.store.LocalActivateInitialBranchActivityV1
import com.ukkera.pos.catalog.store.LocalBranchActivityCodecV1
import com.ukkera.pos.catalog.store.LocalBranchActivityProfileV1
import com.ukkera.pos.catalog.store.LocalBranchActivityServices
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.LocalCompanyActivityPublicationCodecV1
import com.ukkera.pos.catalog.store.LocalPublishedCompanyActivityProfileV1
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.security.CompanyBusinessPublicationProfileV1
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Bounded definition, malformed-input, registration and declared-identity slices of
 * initial-branch-activity-activation-v2. Expected publication content is transcribed
 * from the frozen literals, not produced by an expected-value encoder.
 *
 * No signed envelope/key fixture, server context or authorized action is fabricated.
 * Registration and normalization do not exercise activation preparation/evaluation,
 * current authorization, source deferral, applied/results, persistence, native history,
 * restart, races, business effects or connected FOOD workflow. Those observations and
 * all 18 whole cases remain UNBOUND/NOT_RUN by these bounded slices.
 */
class InitialBranchActivityFrozenSlicesTest {
    private val f = FrozenInitialBranchActivityInputs

    @Test
    fun IAA_V2_01_SixteenFrozenDefinitionsRetainExactSetsAndCanonicalContent() {
        assertEquals(16, f.initialSets.size)
        for (fixture in f.initialSets) assertDefinition(fixture, decodeFrozen(fixture))
    }

    @Test
    fun IAA_V2_01_CompanyRevisionZeroAndExplicitEmptyRemainPublicationData() {
        val zero = f.publication("INITIAL_COMPANY_REVISION_ZERO")
        val zeroDefinition = decodeFrozen(zero)
        assertDefinition(zero, zeroDefinition)
        assertEquals(Revision(0), zeroDefinition.revision)

        val empty = f.publication("INITIAL_EMPTY")
        val emptyDefinition = decodeFrozen(empty)
        assertDefinition(empty, emptyDefinition)
        assertTrue(emptyDefinition.activities.isEmpty())
        // Neither decoded content establishes initial application or local revision 1.
    }

    @Test
    fun IAA_V2_02_SameAndOtherCompanyLiteralDefinitionsRetainCoordinates() {
        for (fixture in f.publications) assertDefinition(fixture, decodeFrozen(fixture))
        // Other-company syntax is content, not a trusted candidate or branch admission.
    }

    @Test
    fun IAA_V2_01_02_ActivitySnapshotMutationCannotChangeFrozenContent() {
        for (fixture in f.initialSets + f.publications) {
            val definition = decodeFrozen(fixture)
            mutateIfWritable(definition.activities) { it.clear() }
            assertDefinition(fixture, definition)
            mutateIfWritable(definition.activities) { it.add(LocalSalesActivity.CAFE) }
            assertDefinition(fixture, definition)
        }
    }

    @Test
    fun IAA_V2_02_DeclaredMalformedDefinitionCategoriesAreRejected() {
        val food = f.publication("INITIAL_FOOD")
        val activities = "\"activities\":[\"CAFE\",\"RESTAURANT\"]"
        val variants = listOf(
            "unknown activity" to replaceLiteral(food.definitionJson, activities,
                "\"activities\":[\"UNKNOWN_ACTIVITY\",\"RESTAURANT\"]"),
            "duplicate activity" to replaceLiteral(food.definitionJson, activities,
                "\"activities\":[\"CAFE\",\"CAFE\"]"),
            "noncanonical activity order" to replaceLiteral(food.definitionJson, activities,
                "\"activities\":[\"RESTAURANT\",\"CAFE\"]"),
            "missing required activity field" to replaceLiteral(food.definitionJson, ",$activities", ""),
            "malformed publication UUID" to replaceLiteral(food.definitionJson, food.publicationId,
                "malformed-publication-id"),
            "malformed revision" to replaceLiteral(food.definitionJson, "\"revision\":\"7\"", "\"revision\":\"-1\""),
            "noncanonical whitespace" to " ${food.definitionJson}",
            "noncanonical BOM" to "\uFEFF${food.definitionJson}",
            "caller capability field" to appendMember(food.definitionJson, "\"callerCapabilities\":[\"CAFE\"]"),
        )
        for ((label, definitionJson) in variants) {
            val bytes = (f.prefix + definitionJson).encodeToByteArray(throwOnInvalidSequence = true)
            val original = bytes.copyOf()
            assertFails(label) { LocalCompanyActivityPublicationCodecV1.decodeCanonicalPublication(bytes) }
            assertContentEquals(original, bytes, label)
        }
        // Probe member names above are unsupported input, not a proposed payload grammar.
    }

    @Test
    fun IAA_V2_02_RawUtf8AndOversizeInputsRemainMalformed() {
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        val publicationInputs = listOf(
            f.prefix.encodeToByteArray() + invalidUtf8,
            ByteArray(CompanyBusinessPublicationProfileV1.MAXIMUM_CONTENT_BYTES + 1) { ' '.code.toByte() },
        )
        for (bytes in publicationInputs) {
            val original = bytes.copyOf()
            assertFails { LocalCompanyActivityPublicationCodecV1.decodeCanonicalPublication(bytes) }
            assertContentEquals(original, bytes)
        }

        val services = LocalBranchActivityServices(UnavailableCompanyPublicationSignatureVerifier)
        val command = services.commands.single()
        val read = services.reads.single()
        for (bytes in listOf(ByteArray(0), invalidUtf8,
            ByteArray(LocalBranchActivityProfileV1.MAX_INPUT_BYTES + 1) { ' '.code.toByte() })) {
            assertMalformedStoredInput(bytes, command::normalize)
        }
        for (bytes in listOf(ByteArray(0), invalidUtf8,
            ByteArray(LocalBranchActivityProfileV1.MAX_QUERY_BYTES + 1) { ' '.code.toByte() })) {
            assertMalformedStoredInput(bytes, read::normalize)
        }
        // Oversize probes bind to public capacities; no new frozen capacity is asserted.
    }

    @Test
    fun IAA_V2_02_UnsignedDefinitionsAreNotSignedInitialRequests() {
        val command = LocalBranchActivityServices(UnavailableCompanyPublicationSignatureVerifier).commands.single()
        for (fixture in f.initialSets + f.publications) {
            assertFails(fixture.label) { LocalActivateInitialBranchActivityV1(fixture.definitionJson) }
            assertFails(fixture.label) { LocalActivateInitialBranchActivityV1(f.prefix + fixture.definitionJson) }
            assertMalformedStoredInput(fixture.definitionJson.encodeToByteArray(), command::normalize)
            assertMalformedStoredInput(fixture.contentBytes(), command::normalize)
        }
        // Only unsigned/wrong-shape syntax is supplied; no signature or trust is tested.
    }

    @Test
    fun IAA_V2_03_13_17_PublicIdentitiesMatchFrozenContract() {
        assertEquals("local.branch.activity.activate-initial", LocalBranchActivityProfileV1.ACTIVATE_INITIAL.name)
        assertEquals(1, LocalBranchActivityProfileV1.ACTIVATE_INITIAL.version)
        assertEquals("local.branch.activity.read-current", LocalBranchActivityProfileV1.READ_CURRENT.name)
        assertEquals(1, LocalBranchActivityProfileV1.READ_CURRENT.version)
        val permission = Permission(SecurityAction.BRANCH_ACTIVITY_ACTIVATE, PermissionVerb.ADMINISTER)
        assertEquals("BRANCH_ACTIVITY_ACTIVATE", permission.action.name)
        assertEquals("ADMINISTER", permission.verb.name)
        assertEquals("branch.activity.initial_activated", LocalBranchActivityProfileV1.ACTIVATED)
        assertEquals("branch.activity.initial_already_activated", LocalBranchActivityProfileV1.ALREADY_ACTIVATED)
        assertEquals("CURRENT_SOURCE_UNAVAILABLE", DeferredReason.CURRENT_SOURCE_UNAVAILABLE.name)
        // Names are checked, not successful/refused/deferred outcomes or direct authority.
    }

    @Test
    fun IAA_V2_03_ProductionRegistryRegistersFrozenInitialCommandAndCurrentRead() {
        val expectedCommand = CommandKey("local.branch.activity.activate-initial", 1)
        val expectedRead = ReadKey("local.branch.activity.read-current", 1)
        val services = LocalBranchActivityServices(UnavailableCompanyPublicationSignatureVerifier)
        assertEquals(listOf(expectedCommand), services.commands.map { it.key })
        assertEquals(listOf(expectedRead), services.reads.map { it.key })

        val registry = LocalBranchCommerceRegistry(
            signatures = UnavailableCompanyPublicationSignatureVerifier,
            returnPolicy = UnavailableSalesReturnPolicy,
            recordingCapacity = UnavailableSalesRecordingCapacity,
            physicalRecordingCapacity = UnavailableSalesPhysicalRecordingCapacityV2,
        )
        assertEquals(listOf(expectedCommand), registry.commands.filter { it.key.name == expectedCommand.name }.map { it.key })
        assertEquals(listOf(expectedRead), registry.reads.filter { it.key.name == expectedRead.name }.map { it.key })
        assertTrue(expectedCommand in registry.commandKeys)
        assertTrue(expectedRead in registry.readKeys)
        // Explicit unavailable ports permit assembly only; no source/native/storage path runs.
    }

    @Test
    fun IAA_V2_02_03_CurrentReadNormalizerRejectsNoncanonicalAndCallerFields() {
        val request = LocalBranchActivityCodecV1.readCurrent()
        val read = LocalBranchActivityServices(UnavailableCompanyPublicationSignatureVerifier).reads.single()
        assertEquals(ReadKey("local.branch.activity.read-current", 1), request.query)
        assertEquals(request.payload, read.normalize(request.payload))
        val currentInput = request.payload.copyBytes().decodeToString(throwOnInvalidSequence = true)
        val malformed = listOf(
            "{}",
            "$currentInput\n",
            "\uFEFF$currentInput",
            appendMember(currentInput, "\"callerCapabilities\":[\"CAFE\",\"RESTAURANT\"]"),
            appendMember(currentInput, "\"scope\":\"50000000-0000-4000-8000-000000000003\""),
            appendMember(currentInput, "\"actorId\":\"70000000-0000-4000-8000-000000000002\""),
        )
        for (input in malformed) assertMalformedStoredInput(input.encodeToByteArray(), read::normalize)
        // The codec supplies input only, not an expected wire oracle or protected read result.
        // Unknown-member probes do not exercise actual employee/resource admission.
    }

    private fun decodeFrozen(fixture: FrozenActivityDefinition): LocalPublishedCompanyActivityProfileV1 {
        val bytes = fixture.contentBytes()
        val original = bytes.copyOf()
        val definition = LocalCompanyActivityPublicationCodecV1.decodeCanonicalPublication(bytes)
        assertContentEquals(original, bytes, fixture.label)
        return definition
    }

    private fun assertDefinition(fixture: FrozenActivityDefinition, actual: LocalPublishedCompanyActivityProfileV1) {
        assertEquals(CompanyScope(DomainId(fixture.organizationId), DomainId(fixture.companyId)), actual.company, fixture.label)
        assertEquals(DomainId(fixture.publicationId), actual.publicationId, fixture.label)
        assertEquals(DomainId(fixture.profileId), actual.profileId, fixture.label)
        assertEquals(Revision(fixture.companyRevision), actual.revision, fixture.label)
        assertEquals(fixture.activityNames, actual.activities.map { it.name }.sorted(), fixture.label)
        assertEquals(fixture.enabledCount, actual.activities.size, fixture.label)
        val expectedBytes = fixture.contentBytes()
        assertEquals(fixture.contentByteCount, expectedBytes.size, fixture.label)
        assertContentEquals(expectedBytes, LocalCompanyActivityPublicationCodecV1.canonicalPublication(actual), fixture.label)
    }

    private fun assertMalformedStoredInput(bytes: ByteArray, normalize: (StoredBytes) -> StoredBytes) {
        val original = bytes.copyOf()
        var input: StoredBytes? = null
        // The transport's bounded value can reject before handler normalization.
        // The frozen malformed-input expectation applies to the complete input path.
        assertFails {
            input = StoredBytes(bytes)
            normalize(checkNotNull(input))
        }
        assertContentEquals(original, bytes)
        input?.let { assertContentEquals(original, it.copyBytes()) }
    }

    private fun replaceLiteral(value: String, old: String, replacement: String): String {
        check(old in value && old != replacement)
        return value.replace(old, replacement)
    }

    private fun appendMember(value: String, member: String): String {
        check(value.endsWith('}'))
        return value.dropLast(1) + "," + member + "}"
    }

    private fun mutateIfWritable(values: Set<LocalSalesActivity>, mutation: (MutableSet<LocalSalesActivity>) -> Unit) {
        val mutable = values as? MutableSet<LocalSalesActivity> ?: return
        try { mutation(mutable) } catch (_: UnsupportedOperationException) {
            // An unmodifiable snapshot is also allowed; its frozen source still must be exact.
        }
    }
}

private data class FrozenActivityDefinition(
    val label: String,
    val organizationId: String,
    val companyId: String,
    val publicationId: String,
    val profileId: String,
    val companyRevision: Long,
    val activityNames: List<String>,
    val enabledCount: Int,
    val contentByteCount: Int,
    val definitionJson: String,
) {
    fun contentBytes(): ByteArray =
        (FrozenInitialBranchActivityInputs.prefix + definitionJson).encodeToByteArray(throwOnInvalidSequence = true)
}

private object FrozenInitialBranchActivityInputs {
    const val prefix: String = "ukkera.company-activity-profile/v1\n"
    private const val O1: String = "00000000-0000-4000-8000-000000000001"
    private const val O2: String = "00000000-0000-4000-8000-000000000002"
    private const val C1: String = "10000000-0000-4000-8000-000000000001"
    private const val C2: String = "10000000-0000-4000-8000-000000000002"
    private const val C3: String = "10000000-0000-4000-8000-000000000003"
    private const val F1: String = "30000000-0000-4000-8000-000000000001"
    private const val F2: String = "30000000-0000-4000-8000-000000000002"
    private const val F3: String = "30000000-0000-4000-8000-000000000003"

    val initialSets: List<FrozenActivityDefinition> = listOf(
        FrozenActivityDefinition("SET-00", O1, C1, "20000000-0000-4000-8000-000000000064", F1, 7L, emptyList(), 0, 292,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000064","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":[]}"""),
        FrozenActivityDefinition("SET-01", O1, C1, "20000000-0000-4000-8000-000000000065", F1, 7L, listOf("CAFE"), 1, 298,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000065","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CAFE"]}"""),
        FrozenActivityDefinition("SET-02", O1, C1, "20000000-0000-4000-8000-000000000066", F1, 7L, listOf("CLOTHING"), 1, 302,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000066","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CLOTHING"]}"""),
        FrozenActivityDefinition("SET-03", O1, C1, "20000000-0000-4000-8000-000000000067", F1, 7L, listOf("RESTAURANT"), 1, 304,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000067","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["RESTAURANT"]}"""),
        FrozenActivityDefinition("SET-04", O1, C1, "20000000-0000-4000-8000-000000000068", F1, 7L, listOf("SUPERMARKET"), 1, 305,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000068","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["SUPERMARKET"]}"""),
        FrozenActivityDefinition("SET-05", O1, C1, "20000000-0000-4000-8000-000000000069", F1, 7L, listOf("CAFE", "CLOTHING"), 2, 309,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000069","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CAFE","CLOTHING"]}"""),
        FrozenActivityDefinition("SET-06", O1, C1, "20000000-0000-4000-8000-00000000006a", F1, 7L, listOf("CAFE", "RESTAURANT"), 2, 311,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-00000000006a","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CAFE","RESTAURANT"]}"""),
        FrozenActivityDefinition("SET-07", O1, C1, "20000000-0000-4000-8000-00000000006b", F1, 7L, listOf("CAFE", "SUPERMARKET"), 2, 312,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-00000000006b","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CAFE","SUPERMARKET"]}"""),
        FrozenActivityDefinition("SET-08", O1, C1, "20000000-0000-4000-8000-00000000006c", F1, 7L, listOf("CLOTHING", "RESTAURANT"), 2, 315,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-00000000006c","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CLOTHING","RESTAURANT"]}"""),
        FrozenActivityDefinition("SET-09", O1, C1, "20000000-0000-4000-8000-00000000006d", F1, 7L, listOf("CLOTHING", "SUPERMARKET"), 2, 316,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-00000000006d","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CLOTHING","SUPERMARKET"]}"""),
        FrozenActivityDefinition("SET-10", O1, C1, "20000000-0000-4000-8000-00000000006e", F1, 7L, listOf("RESTAURANT", "SUPERMARKET"), 2, 318,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-00000000006e","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["RESTAURANT","SUPERMARKET"]}"""),
        FrozenActivityDefinition("SET-11", O1, C1, "20000000-0000-4000-8000-00000000006f", F1, 7L, listOf("CAFE", "CLOTHING", "RESTAURANT"), 3, 322,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-00000000006f","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CAFE","CLOTHING","RESTAURANT"]}"""),
        FrozenActivityDefinition("SET-12", O1, C1, "20000000-0000-4000-8000-000000000070", F1, 7L, listOf("CAFE", "CLOTHING", "SUPERMARKET"), 3, 323,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000070","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CAFE","CLOTHING","SUPERMARKET"]}"""),
        FrozenActivityDefinition("SET-13", O1, C1, "20000000-0000-4000-8000-000000000071", F1, 7L, listOf("CAFE", "RESTAURANT", "SUPERMARKET"), 3, 325,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000071","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CAFE","RESTAURANT","SUPERMARKET"]}"""),
        FrozenActivityDefinition("SET-14", O1, C1, "20000000-0000-4000-8000-000000000072", F1, 7L, listOf("CLOTHING", "RESTAURANT", "SUPERMARKET"), 3, 329,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000072","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CLOTHING","RESTAURANT","SUPERMARKET"]}"""),
        FrozenActivityDefinition("SET-15", O1, C1, "20000000-0000-4000-8000-000000000073", F1, 7L, listOf("CAFE", "CLOTHING", "RESTAURANT", "SUPERMARKET"), 4, 336,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000073","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CAFE","CLOTHING","RESTAURANT","SUPERMARKET"]}"""),
    )

    val publications: List<FrozenActivityDefinition> = listOf(
        FrozenActivityDefinition("INITIAL_FOOD", O1, C1, "20000000-0000-4000-8000-000000000001", F1, 7L, listOf("CAFE", "RESTAURANT"), 2, 311,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000001","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":["CAFE","RESTAURANT"]}"""),
        FrozenActivityDefinition("LATER_CLOTHING", O1, C1, "20000000-0000-4000-8000-000000000002", F1, 8L, listOf("CLOTHING"), 1, 302,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000002","profileId":"30000000-0000-4000-8000-000000000001","revision":"8","activities":["CLOTHING"]}"""),
        FrozenActivityDefinition("INITIAL_EMPTY", O1, C1, "20000000-0000-4000-8000-000000000004", F1, 7L, emptyList(), 0, 292,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000004","profileId":"30000000-0000-4000-8000-000000000001","revision":"7","activities":[]}"""),
        FrozenActivityDefinition("WRONG_COMPANY", O1, C2, "20000000-0000-4000-8000-000000000005", F2, 7L, listOf("CAFE", "RESTAURANT"), 2, 311,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000002"},"publicationId":"20000000-0000-4000-8000-000000000005","profileId":"30000000-0000-4000-8000-000000000002","revision":"7","activities":["CAFE","RESTAURANT"]}"""),
        FrozenActivityDefinition("WRONG_ORGANIZATION", O2, C3, "20000000-0000-4000-8000-000000000006", F3, 7L, listOf("CAFE", "RESTAURANT"), 2, 311,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000002","companyId":"10000000-0000-4000-8000-000000000003"},"publicationId":"20000000-0000-4000-8000-000000000006","profileId":"30000000-0000-4000-8000-000000000003","revision":"7","activities":["CAFE","RESTAURANT"]}"""),
        FrozenActivityDefinition("INITIAL_COMPANY_REVISION_ZERO", O1, C1, "20000000-0000-4000-8000-000000000007", F1, 0L, listOf("CAFE", "RESTAURANT"), 2, 311,
            """{"company":{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"10000000-0000-4000-8000-000000000001"},"publicationId":"20000000-0000-4000-8000-000000000007","profileId":"30000000-0000-4000-8000-000000000001","revision":"0","activities":["CAFE","RESTAURANT"]}"""),
    )

    fun publication(label: String): FrozenActivityDefinition = publications.single { it.label == label }
}
