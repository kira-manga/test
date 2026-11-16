package com.ukkera.pos.database

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.inventory.store.LocalStockRelationalCommandsV2
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Self-authored, NOT independent. #87 production V2 provisioner, native owner, Room and
 * protected endpoint integration over isolated Linux files/software key doubles. No native
 * Windows/macOS custody, physical-device or owner-acceptance claim. */
class DesktopBusinessPublicationIntegrationTest {
    private var sequence = 60_000
    private fun id() = testId(sequence++)
    private fun <T> value(result: LocalCompanyAuthorityResult<T>): T = assertIs<LocalCompanyAuthorityResult.Value<T>>(result,
        "Native result: $result").value
    private suspend fun publish(pub: LocalCompanyBusinessPublications, proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationOutput {
        val action = value(pub.prepare(proposal))
        val review = value(pub.review(action.originalActionId))
        assertEquals(action.originalActionId, review.originalActionId)
        val secret = "synthetic-head-pass-2026".toCharArray()
        val output = value(pub.approve(review, "head.one", secret))
        assertTrue(secret.all { it == '\u0000' })
        assertContentEquals(proposal.copyCanonicalContent(), output.proposal.copyCanonicalContent())
        assertTrue(pub.trust.verify(proposal.company, output.signingKeyId, proposal.copyCanonicalContent(), output.signatureHex))
        assertEquals(output.signatureHex, value(pub.recover(output.originalActionId)).signatureHex)
        return output
    }
    private suspend fun applied(fixture: DepositNativeFixture, command: CommandKey, payload: StoredBytes) {
        val result = assertIs<MutationReply.Recorded>(fixture.execute(MutationRequest(OperationId(id().value), command, payload)))
        assertTrue(result.committed, "${result.code}: ${result.result.copyBytes().decodeToString()}")
    }

    private fun sku(company: CompanyScope): LocalPublishedSkuV1 {
        val each = id()
        return LocalPublishedSkuV1(company, id(), id(), id(), Revision(1), Revision(1),
            LocalNamesV1("Synthetic serial item", "صنف اختبار"), "SYNTHETIC-SERIAL", emptyList(), each, 0,
            LocalTrackingV1.SERIAL, LocalFulfillmentV1.RETAIL, false, true, true, true, emptyList(),
            listOf(LocalPriceV1(id(), Revision(1), null, 0, Quantity(each, 1, 0), effectiveFrom = TEST_TIME,
                unitPrice = UnitPrice.parse(Currency.EGP, "10"), inclusion = TaxInclusion.EXCLUSIVE,
                tax = TaxSnapshot(id(), Revision(1), "SYNTHETIC_ZERO", PercentageRate.parse("0")))),
            unitDefinitions = listOf(CatalogUnit(each, Code.exact("EA"), UnitDimension.COUNT, 0)))
    }

    @Test fun canonicalCatalogPriceAndReturnRightsPublishActivateAndSurviveColdHistoryScan(): Unit = runBlocking {
        val fixture = DepositNativeFixture()
        try {
            fixture.install()
            val pub = fixture.publications
            val definition = sku(CompanyScope(fixture.scope.organizationId, fixture.scope.companyId))
            val bytes = LocalConfigurationCodec.canonicalPublication(definition)
            val catalog = publish(pub, CompanyBusinessPublicationProposal(definition.company,
                CompanyBusinessPublicationKind.CATALOG_SKU_V1, definition.publicationId, definition.skuId, bytes))
            val policy = LocalPricePolicyEntryV1(definition.company, id(), id(), Revision(1), definition.skuId,
                definition.prices.single().ruleId, true, Quantity(definition.baseUnitId, 1, 0), TEST_TIME, null)
            val price = publish(pub, policy.toNativeCompanyPricePolicyProposal())
            assertEquals(policy, price.readNativeCompanyPricePolicy().definition)
            val rights = LocalPublishedReturnRightsPolicy(definition.company, id(), id(), Revision(1), "EG",
                "https://cpa.gov.eg/en-us/Consumer-Rights", "2026-11-14",
                listOf(LocalReturnRightsRule("SYNTHETIC", LocalReturnRightsReason.WRONG_SUPPLIED_ITEM,
                    LocalReturnRightsTreatment.EVIDENCE_OR_POLICY_REVIEW)), "Synthetic continuing rights notice",
                "بيان حقوق اختبار")
            val returns = publish(pub, rights.toNativeCompanyReturnRightsProposal())
            assertEquals(rights, returns.readNativeCompanyReturnRightsPolicy().definition)
            val initialized = assertIs<MutationReply.Recorded>(fixture.execute(LocalStockRelationalCommandsV2.initializeFresh(
                OperationId(id().value), id(), "synthetic.opening")))
            assertTrue(initialized.committed, initialized.code)
            applied(fixture, LocalConfigurationProfile.COMMAND,
                LocalConfigurationCodec.encode(LocalConfigurationRequestV1.InstallCompanySku(catalog.readNativeCompanySku())))
            assertEquals(3, value(pub.retained()).size)
            fixture.reopen()
            for (output in listOf(catalog, price, returns)) {
                val recovered = value(fixture.publications.recover(output.originalActionId))
                assertEquals(output.signatureHex, recovered.signatureHex)
                assertEquals(output.signingKeyId, recovered.signingKeyId)
                assertContentEquals(output.proposal.copyCanonicalContent(), recovered.proposal.copyCanonicalContent())
            }
            val replay = assertIs<MutationReply.Recorded>(fixture.execute(LocalStockRelationalCommandsV2.initializeFresh(
                OperationId(id().value), id(), "synthetic.opening")))
            assertFalse(replay.committed)
        } finally { fixture.close() }
    }

    @Test fun protectedSerialAuthorityAndRegistrationRecoverPrivatelyAfterColdReopen(): Unit = runBlocking {
        val fixture = DepositNativeFixture()
        try {
            fixture.install()
            val pub = fixture.publications
            assertIs<LocalCompanyAuthorityResult.Unavailable>(pub.prepareOriginalSerialAuthority())
            System.err.println("Desktop serial fixture authority prepare entered")
            val authorityAction = value(fixture.serialPublications.prepareSerialAuthority(fixture.credentials, id()))
            System.err.println("Desktop serial fixture authority approval entered")
            val authorityOutput = value(pub.approve(authorityAction, "head.one", "synthetic-head-pass-2026".toCharArray()))
            System.err.println("Desktop serial fixture authority approval completed")
            val authority = authorityOutput.readNativeSerialAuthorityDefinition()
            assertEquals(fixture.scope, authority.origin.originalScope)
            assertIs<LocalCompanyAuthorityResult.Unavailable>(pub.recover(authorityOutput.originalActionId))
            System.err.println("Desktop serial fixture allocation read entered")
            val allocation = value(pub.originalSerialRegistrationAllocation())
            val definition = sku(CompanyScope(fixture.scope.organizationId, fixture.scope.companyId))
            val bytes = LocalConfigurationCodec.canonicalPublication(definition)
            val catalog = publish(pub, CompanyBusinessPublicationProposal(definition.company,
                CompanyBusinessPublicationKind.CATALOG_SKU_V1, definition.publicationId, definition.skuId, bytes))
            val registration = LocalPublishedSerialRegistrationV1(fixture.scope, allocation.allocationId,
                allocation.allocationDigest, allocation.serialAuthority.reference, id(), id(), Revision(1), null, id(),
                "SYNTHETIC-SERIAL-001", definition.skuId, definition.publicationId, catalog.proposal.contentDigest, TEST_TIME, null)
            val proposal = makeNativeSerialRegistrationProposal(registration)
            assertIs<LocalCompanyAuthorityResult.Unavailable>(pub.prepare(proposal))
            System.err.println("Desktop serial fixture registration prepare entered")
            val action = value(fixture.serialPublications.prepareSerialPublication(fixture.credentials, id(), proposal))
            System.err.println("Desktop serial fixture registration census entered")
            val origin = value(fixture.serialPublications.retainedSerialPublications(fixture.credentials))
                .single { it.publication.originalActionId == action.originalActionId }.originRequestId
            val review = value(fixture.serialPublications.reviewSerialPublication(fixture.credentials, id(), origin, action.originalActionId))
            System.err.println("Desktop serial fixture registration approval entered")
            val output = value(pub.approve(review, "head.one", "synthetic-head-pass-2026".toCharArray()))
            System.err.println("Desktop serial fixture registration approval completed")
            assertEquals(registration, output.readNativeSerialRegistration().definition)
            assertTrue(value(pub.retained()).none { it.kind == CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1 ||
                it.kind == CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1 })
            val origins = value(fixture.serialPublications.retainedSerialPublications(fixture.credentials))
            assertEquals(2, origins.size)
            fixture.reopen()
            assertEquals(2, value(fixture.serialPublications.retainedSerialPublications(fixture.credentials)).size)
            System.err.println("Desktop serial fixture cold census completed")
            for (original in origins) {
                System.err.println("Desktop serial fixture private recovery entered: ${original.publication.kind}")
                val recovered = value(fixture.serialPublications.recoverSerialPublication(fixture.credentials, id(),
                    original.originRequestId, original.publication.originalActionId))
                val expected = if (recovered.originalActionId == authorityOutput.originalActionId) authorityOutput else output
                assertEquals(expected.signatureHex, recovered.signatureHex)
                assertEquals(expected.signingKeyId, recovered.signingKeyId)
                System.err.println("Desktop serial fixture private recovery completed: ${original.publication.kind}")
            }
            System.err.println("Desktop serial fixture revocation entered")
            val stale = fixture.credentials
            fixture.logout()
            assertIs<LocalCompanyAuthorityResult.Unavailable>(fixture.serialPublications.recoverSerialPublication(stale,
                id(), origin, output.originalActionId))
            System.err.println("Desktop serial fixture missing companion check entered")
            val reopened = fixture.removeSerialCompanionAndTryReopen()
            when (reopened) {
                is SelfOwnedNativeOpenResult.Unresolved -> Unit
                is SelfOwnedNativeOpenResult.Opened -> try {
                    assertIs<LocalCompanyAuthorityResult.Unavailable>(reopened.context.readOriginalBusinessPublications())
                } finally { reopened.context.close() }
            }
        } finally { fixture.close() }
    }
}
