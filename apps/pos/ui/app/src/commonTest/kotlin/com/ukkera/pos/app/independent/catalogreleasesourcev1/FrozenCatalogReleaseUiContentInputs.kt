package com.ukkera.pos.app.independent.catalogreleasesourcev1

import com.ukkera.pos.app.LocalCatalogReleaseAuthoringSource
import com.ukkera.pos.app.LocalCatalogReleaseMemberInput
import com.ukkera.pos.app.LocalCatalogReleaseMemberSource
import com.ukkera.pos.app.LocalCatalogReleasePublicationDraft
import com.ukkera.pos.app.LocalCatalogReleasePublicationReference
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.ui.CatalogReleaseEditor
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * CSR-FROZEN-1 semantic input projection only. Fixed business IDs, revisions, names and
 * orders come from fixtures.json. The df11… IDs and marker hashes below are UNBOUND
 * syntax/content-equality tokens, NOT bindings of any native publication alias.
 * No owner, signature, native action/output or completed observation is constructed.
 */
internal object FrozenCatalogReleaseUiContentInputs {
    val company = DomainId("10000000-0000-4000-8000-000000000001")
    val branch = DomainId("11000000-0000-4000-8000-000000000001")
    val catalog = DomainId("30000000-0000-4000-8000-000000000001")
    val otherCatalog = DomainId("40000000-0000-4000-8000-000000000001")
    val shirt = DomainId("50000000-0000-4000-8000-000000000001")
    val bag = DomainId("60000000-0000-4000-8000-000000000001")
    val blueSku = DomainId("70000000-0000-4000-8000-000000000001")
    val blackSku = DomainId("80000000-0000-4000-8000-000000000001")
    val bagSku = DomainId("90000000-0000-4000-8000-000000000001")
    val bulkProduct = DomainId("b1000000-0000-4000-8000-000000000001")
    val bulkCatalog = DomainId("b2000000-0000-4000-8000-000000000001")

    val blue3 = member(1, blueSku, 3, shirt, 2)
    val blue4 = member(2, blueSku, 4, shirt, 2)
    val black2 = member(3, blackSku, 2, shirt, 2)
    val bag0 = member(4, bagSku, 0, bag, 0)
    val bag1 = member(5, bagSku, 1, bag, 0)

    fun token(number: Int): DomainId =
        DomainId("df110000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun markerDigest(number: Int): PayloadDigest =
        sha256("UNBOUND CSR-FROZEN-1 UI content equality token $number".encodeToByteArray())

    private fun member(number: Int, sku: DomainId, skuRevision: Long, product: DomainId,
        productRevision: Long): LocalCatalogReleaseMemberInput = LocalCatalogReleaseMemberInput(
        token(number), markerDigest(number), sku, Revision(skuRevision), product, Revision(productRevision))

    fun releaseReference(revision: Int): LocalCatalogReleasePublicationReference =
        LocalCatalogReleasePublicationReference(catalog, token(100 + revision), Revision(revision.toLong()),
            markerDigest(100 + revision))

    fun orderedMembers(revision: Int): List<LocalCatalogReleaseMemberInput> = when (revision) {
        0 -> listOf(blue3, black2, bag0)
        1 -> listOf(bag1, black2, blue3)
        2 -> listOf(blue3, bag1, black2)
        3 -> listOf(bag1, blue4)
        4 -> listOf(blue4, bag1)
        else -> error("Only the frozen semantic release revisions are supplied")
    }

    fun draft(revision: Int, members: List<LocalCatalogReleaseMemberInput> = orderedMembers(revision)):
        LocalCatalogReleasePublicationDraft = LocalCatalogReleasePublicationDraft(
        token(100 + revision), catalog, Revision(revision.toLong()),
        if (revision == 0) null else releaseReference(revision - 1), members)

    fun editor(revision: Int, frozen: Boolean = false): CatalogReleaseEditor {
        val definition = draft(revision)
        return CatalogReleaseEditor(definition.publicationId, definition.catalogId.value, definition.revision,
            definition.previous, definition.members, definition.takeIf { frozen }?.snapshot())
    }

    fun bulkMembers(): List<LocalCatalogReleaseMemberInput> = (1..65).map { number ->
        member(200 + number,
            DomainId("b3000000-0000-4000-8000-${number.toString().padStart(12, '0')}"), 0, bulkProduct, 0)
    }

    fun skuNames(member: LocalCatalogReleaseMemberInput): LocalText = when (member) {
        blue3, blue4 -> LocalText("Blue shirt / M", "قميص أزرق / M")
        black2 -> LocalText("Black shirt / L", "قميص أسود / L")
        bag0 -> LocalText("Cotton bag", "حقيبة قطنية")
        bag1 -> LocalText("Cotton bag, natural", "حقيبة قطنية طبيعية")
        else -> error("No original name is inferred for an unknown coordinate")
    }

    fun memberSource(member: LocalCatalogReleaseMemberInput): LocalCatalogReleaseMemberSource =
        LocalCatalogReleaseMemberSource(member, skuNames(member),
            if (member.productId == shirt) LocalText("Linen shirt, relaxed fit", "قميص كتان بقصة واسعة")
            else LocalText("Cotton bag", "حقيبة قطنية"),
            "UNBOUND-${member.publicationId.value}", active = true)

    /** Controlled UI source data, not a protected/native source observation. */
    fun authoringSource(revision: Int): LocalCatalogReleaseAuthoringSource {
        val definition = draft(revision)
        return LocalCatalogReleaseAuthoringSource(catalog, definition.revision, definition.previous,
            activeStorageRevision = null, active = null, members = definition.members.map(::memberSource))
    }
}
