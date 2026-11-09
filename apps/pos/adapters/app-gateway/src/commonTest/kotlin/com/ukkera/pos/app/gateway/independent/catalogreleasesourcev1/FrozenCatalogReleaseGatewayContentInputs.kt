package com.ukkera.pos.app.gateway.independent.catalogreleasesourcev1

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalScalarInput
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.catalog.store.LocalActivateCatalogReleaseV1
import com.ukkera.pos.catalog.store.LocalCatalogReleaseCodecV1
import com.ukkera.pos.catalog.store.LocalCatalogReleaseMemberV1
import com.ukkera.pos.catalog.store.LocalCatalogReleaseReferenceV1
import com.ukkera.pos.catalog.store.LocalPublishedCatalogReleaseV1
import com.ukkera.pos.catalog.store.LocalSignedCatalogReleaseV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/**
 * CSR-FROZEN-1 syntax/client-custody input projection. Business IDs, revisions and
 * ordered member/name values are frozen semantic inputs. df12… publication/key/actor
 * IDs and member marker digests are explicitly UNBOUND equality tokens. The zero-filled
 * signature is envelope syntax, NOT a valid signature or native alias binding.
 * No native owner/action/output/observation or authenticated Master is constructed.
 */
internal object FrozenCatalogReleaseGatewayContentInputs {
    val company = CompanyScope(token(700), DomainId("10000000-0000-4000-8000-000000000001"))
    val scope = BranchScope(company.organizationId, company.companyId,
        DomainId("11000000-0000-4000-8000-000000000001"))
    val catalog = DomainId("30000000-0000-4000-8000-000000000001")
    private val shirt = DomainId("50000000-0000-4000-8000-000000000001")
    private val bag = DomainId("60000000-0000-4000-8000-000000000001")
    private val blueSku = DomainId("70000000-0000-4000-8000-000000000001")
    private val blackSku = DomainId("80000000-0000-4000-8000-000000000001")
    private val bagSku = DomainId("90000000-0000-4000-8000-000000000001")
    val owner = LocalClientIdentity(scope, token(500), token(501))

    val blue3 = member(1, blueSku, 3, shirt, 2)
    val blue4 = member(2, blueSku, 4, shirt, 2)
    val black2 = member(3, blackSku, 2, shirt, 2)
    val bag0 = member(4, bagSku, 0, bag, 0)
    val bag1 = member(5, bagSku, 1, bag, 0)

    fun token(number: Int): DomainId =
        DomainId("df120000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun member(number: Int, sku: DomainId, skuRevision: Long, product: DomainId,
        productRevision: Long): LocalCatalogReleaseMemberV1 = LocalCatalogReleaseMemberV1(token(number),
        sha256("UNBOUND CSR-FROZEN-1 gateway content equality token $number".encodeToByteArray()),
        sku, Revision(skuRevision), product, Revision(productRevision))

    fun orderedMembers(revision: Int): List<LocalCatalogReleaseMemberV1> = when (revision) {
        0 -> listOf(blue3, black2, bag0)
        1 -> listOf(bag1, black2, blue3)
        2 -> listOf(blue3, bag1, black2)
        3 -> listOf(bag1, blue4)
        4 -> listOf(blue4, bag1)
        else -> error("Only frozen semantic revisions are supplied")
    }

    fun definition(revision: Int): LocalPublishedCatalogReleaseV1 = LocalPublishedCatalogReleaseV1(company,
        catalog, token(100 + revision), Revision(revision.toLong()),
        if (revision == 0) null else reference(definition(revision - 1)), orderedMembers(revision))

    /** Digest of this actual supplied canonical content, not proof of an authenticated original. */
    fun contentDigest(value: LocalPublishedCatalogReleaseV1): PayloadDigest {
        val bytes = LocalCatalogReleaseCodecV1.canonicalPublication(value)
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }

    private fun reference(value: LocalPublishedCatalogReleaseV1): LocalCatalogReleaseReferenceV1 =
        LocalCatalogReleaseReferenceV1(value.catalogId, value.publicationId, value.revision, contentDigest(value))

    fun activation(value: LocalPublishedCatalogReleaseV1 = definition(0), expected: Revision? = Revision(10)):
        LocalActivateCatalogReleaseV1 = LocalActivateCatalogReleaseV1(
        LocalSignedCatalogReleaseV1(value, token(600), "00".repeat(64)), expected)

    fun intent(operationId: OperationId, value: LocalPublishedCatalogReleaseV1, expected: Revision?): LocalCommandIntent =
        LocalCommandIntent(operationId, LocalAction.CATALOG_RELEASE_ACTIVATE, 1,
            LocalRecordReference(LocalRecordKind.CATALOG_RELEASE_PUBLICATION, value.publicationId), listOfNotNull(
                LocalScalarInput(LocalField.DOCUMENT_ID, LocalScalarValue.Identifier(value.catalogId)),
                LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(value.publicationId)),
                LocalScalarInput(LocalField.EXPECTED_REVISION, LocalScalarValue.Integer(value.revision.value)),
                LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Text(contentDigest(value).value)),
                expected?.let { LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(it.value)) },
                LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true))))

    /** Supplied UI summary literals, not the private production native-stage summary mapper. */
    fun summary(value: LocalPublishedCatalogReleaseV1): List<LocalRecordValue> = value.members.mapIndexed { position, member ->
        LocalRecordValue(LocalText("Ordered member ${position + 1}", "العضو المرتب ${position + 1}"), names(member))
    }

    private fun names(member: LocalCatalogReleaseMemberV1): LocalText = when (member.skuId) {
        blueSku -> LocalText("Blue shirt / M", "قميص أزرق / M")
        blackSku -> LocalText("Black shirt / L", "قميص أسود / L")
        bagSku -> if (member.skuRevision == Revision(0)) LocalText("Cotton bag", "حقيبة قطنية")
            else LocalText("Cotton bag, natural", "حقيبة قطنية طبيعية")
        else -> error("No name is inferred for an unknown SKU")
    }

    fun entry(operationId: OperationId = OperationId.random(), value: LocalPublishedCatalogReleaseV1 = definition(0),
        expected: Revision? = Revision(10), clientOwner: LocalClientIdentity = owner): StoredClientIntentV1 =
        StoredClientIntentV1.staged(clientOwner, intent(operationId, value, expected),
            LocalCatalogReleaseCodecV1.activate(operationId, activation(value, expected)),
            LocalText("Supplied catalog-release content", "محتوى إصدار كتالوج مقدم"), summary(value))
}
