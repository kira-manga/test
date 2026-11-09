package com.ukkera.pos.security.independent.catalogoriginalpublicationmembershipv1

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.NativeBusinessPublicationObservationWire
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import com.ukkera.pos.security.NativeHistoryOriginalIntentV2
import com.ukkera.pos.security.NativeHistoryRecordedHeadV2
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Frozen semantic inputs adapted only to inert typed codec data, never native evidence. */
internal class FrozenCatalogOriginalPublicationMembershipInputs private constructor(val input: JsonObject) {
    val binding: JsonObject = input.getValue("originalBindingInputs").jsonObject
    val scope = BranchScope(DomainId("70c16e68-c0c6-4da9-a33b-bb313c958400"),
        DomainId(value("companyId")), DomainId("70c16e68-c0c6-4da9-a33b-bb313c958401"))
    val root: String = tokenDigest(value("rootIdentityToken")).value
    val generation: Long = value("generationToken").substringAfterLast('-').toLong()
    val outputInputs: List<JsonObject> = input.getValue("completedAndPendingOutputInputs").jsonArray.map { it.jsonObject }

    fun value(name: String): String = binding.getValue(name).jsonPrimitive.content
    fun group(id: String): JsonObject = input.getValue("portableDataCodecBindingCases").jsonArray
        .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
    fun outputInput(ref: String): JsonObject = outputInputs.single { it.getValue("fixtureId").jsonPrimitive.content == ref }
    fun challenge(dimension: String): String = group("CPM-P04").getValue("singleDimensionChallenges").jsonArray
        .map { it.jsonObject }.single { it.getValue("dimension").jsonPrimitive.content == dimension }
        .getValue("replacement").jsonPrimitive.content
    fun originalIdentities(): List<DomainId> = group("CPM-P03").getValue("expectedOriginalIdentities").jsonArray
        .map { DomainId(it.jsonPrimitive.content) }

    fun originalOutputs(company: CompanyScope = scope.company(), root: String = this.root): List<CompanyBusinessPublicationOutput> =
        originalIdentities().map { id -> output(outputInputs.single { it.getValue("identity").jsonPrimitive.content == id.value }, company, root) }

    fun content(item: JsonObject): ByteArray = content(item.getValue("contentToken").jsonPrimitive.content,
        item.getValue("opaquePortableContentHex").jsonPrimitive.content)
    fun content(token: String, hex: String = ""): ByteArray =
        CompanyBusinessPublicationKind.CATALOG_SKU_V1.domain.encodeToByteArray() + token.encodeToByteArray() + hexBytes(hex)

    fun output(item: JsonObject, company: CompanyScope = scope.company(), root: String = this.root,
        bytes: ByteArray = content(item)): CompanyBusinessPublicationOutput =
        output(DomainId(item.getValue("identity").jsonPrimitive.content), bytes, company, root)

    private fun output(id: DomainId, bytes: ByteArray, company: CompanyScope, root: String): CompanyBusinessPublicationOutput =
        CompanyBusinessPublicationOutput(id,
            CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
                tokenId("publication:${id.value}"), tokenId("entity:${id.value}"), bytes),
            tokenId("key:${id.value}"), "00".repeat(64), root)

    fun boundaryOutputs(count: Int): List<CompanyBusinessPublicationOutput> {
        require(count in 0..257)
        val generator = input.getValue("boundaryGenerator").jsonObject
        val prefix = generator.getValue("identityPrefix").jsonPrimitive.content
        return (1..count).map { index ->
            val id = DomainId(prefix + index.toString(16).padStart(12, '0'))
            output(id, content("portable-completed-output-$index"), scope.company(), root)
        }
    }

    fun snapshot(outputs: List<CompanyBusinessPublicationOutput> = originalOutputs(),
        company: CompanyScope = scope.company(), root: String = this.root, generation: Long = this.generation,
        present: Boolean = true): NativeCompanyBusinessPublicationSnapshot =
        NativeCompanyBusinessPublicationSnapshot(company, root, generation, outputs, present)

    fun parent(): NativeHistoryRecordedHeadV2 = head(value("nativeParentId"), 17, 1)
    fun child(): NativeHistoryRecordedHeadV2 = head(value("signedChildId"), 18, 2)
    private fun head(token: String, sequence: Long, command: Long): NativeHistoryRecordedHeadV2 =
        NativeHistoryRecordedHeadV2(sequence, tokenDigest(token), tokenDigest("inert-installation-genesis"),
            0, command, tokenDigest("command:$token"), tokenDigest("inert-role"), tokenDigest("inert-recovery"))

    fun intent(purpose: Int = 2, replacements: Map<String, String> = emptyMap()): NativeHistoryOriginalIntentV2 {
        fun field(name: String): String = replacements[name] ?: value(name)
        return NativeHistoryOriginalIntentV2(purpose, DomainId(field("operationId")), tokenDigest(field("semanticPayloadToken")),
            DomainId(field("operationId")), DomainId(field("actorId")),
            bytesDigest(hexBytes(field("opaquePortableDependencyBytesHex"))), tokenDigest(field("intentId")), emptyList())
    }

    fun dependencyFields(frame: DomainId, observation: PayloadDigest): List<String> =
        listOf(NativeBusinessPublicationObservationWire.DEPENDENCY_DOMAIN, frame.value, observation.value)

    companion object {
        const val FIXTURE_SHA256 = "88eacda70a0fc312a26259db5770ba029ef6cc25f0bcab91d7c0f97371b6124a"
        fun load(): FrozenCatalogOriginalPublicationMembershipInputs {
            val bytes = checkNotNull(FrozenCatalogOriginalPublicationMembershipInputs::class.java
                .getResourceAsStream("/catalog-original-publication-membership-v1.json")).use { it.readBytes() }
            check(bytes.size == 22990 && bytesDigest(bytes).value == FIXTURE_SHA256)
            val input = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            check(input.getValue("designVersion").jsonPrimitive.content == "catalog-original-publication-membership-v1")
            check(input.getValue("executionStatus").jsonPrimitive.content == "NOT_RUN")
            check(input.getValue("portableGroupCount").jsonPrimitive.int == 6 && input.getValue("nativeGroupCount").jsonPrimitive.int == 4)
            return FrozenCatalogOriginalPublicationMembershipInputs(input)
        }
        fun bytesDigest(bytes: ByteArray): PayloadDigest = PayloadDigest(MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
        fun tokenDigest(token: String): PayloadDigest = bytesDigest(token.encodeToByteArray())
        fun tokenId(token: String): DomainId {
            val h = tokenDigest(token).value.take(32)
            return DomainId("${h.take(8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}")
        }
        fun hexBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
