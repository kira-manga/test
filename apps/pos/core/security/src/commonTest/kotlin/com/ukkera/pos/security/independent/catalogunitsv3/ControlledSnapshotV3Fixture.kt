package com.ukkera.pos.security.independent.catalogunitsv3

import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompanyBusinessPublicationController
import com.ukkera.pos.security.LocalCompanyBusinessPublications
import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationReference
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import com.ukkera.pos.security.NativeCompanyPublicationTrust
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.VerifiedNativeCompanyPublication
import com.ukkera.pos.types.DomainId

/** Controlled storage projection of frozen F-CUA-HISTORY/SNAPSHOT; no native authenticity. */
internal class ControlledSnapshotV3Fixture {
    companion object {
        const val FIRST_CONTENT = "ukkera.company-sku-publication/v1\n{\"company\":{\"organizationId\":\"10000000-aaaa-4000-8000-000000000001\",\"companyId\":\"10000000-aaaa-4000-8000-000000000002\"},\"publicationId\":\"30000000-cccc-4000-8000-000000000001\",\"skuId\":\"20000000-bbbb-4000-8000-000000000002\",\"productId\":\"20000000-bbbb-4000-8000-000000000001\",\"catalogRevision\":\"1\",\"skuRevision\":\"1\",\"names\":{\"english\":\"Fixture\",\"arabic\":\"Fixture\"},\"code\":\"000012\",\"barcodes\":[],\"baseUnitId\":\"40000000-dddd-4000-8000-000000000001\",\"quantityScale\":0,\"tracking\":\"QUANTITY\",\"fulfillment\":\"RETAIL\",\"requiresExpiry\":false,\"canPurchase\":true,\"canSell\":false,\"active\":true,\"conversions\":[{\"id\":\"50000000-eeee-4000-8000-000000000001\",\"revision\":\"1\",\"sourceUnitId\":\"40000000-dddd-4000-8000-000000000002\",\"targetUnitId\":\"40000000-dddd-4000-8000-000000000001\",\"rate\":\"12/1\",\"dimensionalEvidenceId\":null}],\"prices\":[],\"unitDefinitions\":[{\"id\":\"40000000-dddd-4000-8000-000000000001\",\"code\":{\"original\":\"piece\",\"normalizedKey\":\"piece\",\"normalization\":\"EXACT_V1\"},\"dimension\":\"COUNT\",\"maximumScale\":0},{\"id\":\"40000000-dddd-4000-8000-000000000002\",\"code\":{\"original\":\"carton\",\"normalizedKey\":\"carton\",\"normalization\":\"EXACT_V1\"},\"dimension\":\"COUNT\",\"maximumScale\":0}]}"
        const val SECOND_CONTENT = "ukkera.company-sku-publication/v1\n{\"company\":{\"organizationId\":\"10000000-aaaa-4000-8000-000000000001\",\"companyId\":\"10000000-aaaa-4000-8000-000000000002\"},\"publicationId\":\"30000000-cccc-4000-8000-000000000002\",\"skuId\":\"20000000-bbbb-4000-8000-000000000002\",\"productId\":\"20000000-bbbb-4000-8000-000000000001\",\"catalogRevision\":\"2\",\"skuRevision\":\"2\",\"names\":{\"english\":\"Fixture\",\"arabic\":\"Fixture\"},\"code\":\"000012\",\"barcodes\":[],\"baseUnitId\":\"40000000-dddd-4000-8000-000000000001\",\"quantityScale\":0,\"tracking\":\"QUANTITY\",\"fulfillment\":\"RETAIL\",\"requiresExpiry\":false,\"canPurchase\":true,\"canSell\":false,\"active\":true,\"conversions\":[{\"id\":\"50000000-eeee-4000-8000-000000000001\",\"revision\":\"2\",\"sourceUnitId\":\"40000000-dddd-4000-8000-000000000002\",\"targetUnitId\":\"40000000-dddd-4000-8000-000000000001\",\"rate\":\"10/1\",\"dimensionalEvidenceId\":null}],\"prices\":[],\"unitDefinitions\":[{\"id\":\"40000000-dddd-4000-8000-000000000001\",\"code\":{\"original\":\"piece\",\"normalizedKey\":\"piece\",\"normalization\":\"EXACT_V1\"},\"dimension\":\"COUNT\",\"maximumScale\":0},{\"id\":\"40000000-dddd-4000-8000-000000000002\",\"code\":{\"original\":\"carton\",\"normalizedKey\":\"carton\",\"normalization\":\"EXACT_V1\"},\"dimension\":\"COUNT\",\"maximumScale\":0}]}"
    }

    val company = CompanyScope(DomainId("10000000-aaaa-4000-8000-000000000001"), DomainId("10000000-aaaa-4000-8000-000000000002"))
    val root = "60000000-ffff-4000-8000-000000000001"
    val publication1 = DomainId("30000000-cccc-4000-8000-000000000001")
    val publication2 = DomainId("30000000-cccc-4000-8000-000000000002")
    val sku = DomainId("20000000-bbbb-4000-8000-000000000002")
    // Action/key/signature values are inert wiring metadata, not signing or verification evidence.
    val action1 = DomainId("80000000-aaaa-4000-8000-000000000001")
    val action2 = DomainId("80000000-aaaa-4000-8000-000000000002")
    val key1 = DomainId("90000000-aaaa-4000-8000-000000000001")
    val key2 = DomainId("90000000-aaaa-4000-8000-000000000002")
    val input1 = FIRST_CONTENT.encodeToByteArray()
    val input2 = SECOND_CONTENT.encodeToByteArray()
    val proposal1 = CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1, publication1, sku, input1)
    val proposal2 = CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1, publication2, sku, input2)
    val output1 = CompanyBusinessPublicationOutput(action1, proposal1, key1, "01".repeat(64), root)
    val output2 = CompanyBusinessPublicationOutput(action2, proposal2, key2, "02".repeat(64), root)
    val inputOutputs = mutableListOf(output1, output2)
    val snapshot = NativeCompanyBusinessPublicationSnapshot(company, root, 7, inputOutputs)
    val controller = NeverCalledPublicationController()
    val trust = NativeCompanyPublicationTrust(company, root)
    val publications = LocalCompanyBusinessPublications(controller, trust)

    fun retainControlledSnapshot() {
        // Exercise storage/read/invalidation primitives only. No verifier or protected operation
        // is invoked, and these unauthenticated entries must never serve an authority fixture.
        trust.retainVerified(listOf(output1, output2).map {
            VerifiedNativeCompanyPublication(it.signingKeyId, it.proposal.kind, it.proposal.contentDigest,
                SelfOwnedOctetsV1(ByteArray(32)))
        })
        trust.retainCompletedSnapshot(snapshot)
    }
}

internal class NeverCalledPublicationController : LocalCompanyBusinessPublicationController {
    var calls = 0
        private set

    private fun unexpected(): Nothing {
        calls++
        error("Read-only snapshot access invoked a publication controller operation")
    }

    override suspend fun prepare(proposal: CompanyBusinessPublicationProposal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> = unexpected()
    override suspend fun approve(action: CompanyBusinessPublicationAction, headLogin: String, headSecret: CharArray): LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> = unexpected()
    override suspend fun recover(originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> = unexpected()
    override suspend fun review(originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> = unexpected()
    override suspend fun retained(): LocalCompanyAuthorityResult<List<CompanyBusinessPublicationReference>> = unexpected()
}
