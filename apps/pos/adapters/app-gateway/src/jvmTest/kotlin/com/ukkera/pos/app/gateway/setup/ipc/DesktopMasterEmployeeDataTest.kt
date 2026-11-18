package com.ukkera.pos.app.gateway.setup.ipc

import com.ukkera.pos.app.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlin.test.*
import kotlinx.serialization.json.*

/** Self-authored, NOT independent. Frozen bounded grammar/review expectations precede execution. */
class DesktopMasterEmployeeDataTest {
    @Test fun boundedGrammarRefusesUnknownObjectsTagsAndOversizedLists() {
        assertFailsWith<IllegalStateException> { DesktopMasterEmployeeData.encode(Any()) }
        assertFailsWith<IllegalStateException> { DesktopMasterEmployeeData.decode(JsonArray(listOf(JsonPrimitive(999_999)))) }
        assertFailsWith<IllegalArgumentException> { DesktopMasterEmployeeData.encode(List(2049) { "item" }) }
        assertFailsWith<IllegalArgumentException> { DesktopMasterEmployeeData.encode("x".repeat(128 * 1024 + 1)) }
        assertFails { DesktopMasterEmployeeData.decode(JsonArray(listOf(JsonPrimitive(2), JsonPrimitive("true")))) }
    }
    @Test fun administrationOriginalAndSetupInputsRetainExactTypedCoordinates() {
        val command = LocalAdministrationCommand(DomainId.random(), LocalAdministrationAction.REVOKE_DEVICE,
            DomainId.random(), Revision(12))
        assertEquals(command, DesktopMasterEmployeeData.decode(DesktopMasterEmployeeData.encode(command)))
        val input = LocalSetupConfigurationInput(Revision(4), "EGP", "Africa/Cairo", 180,
            "Synthetic Legal Name", null, true)
        assertEquals(input, DesktopMasterEmployeeData.decode(DesktopMasterEmployeeData.encode(input)))
        val row = LocalSetupCatalogRow(DomainId.random(), DomainId.random(), OperationId.random())
        assertEquals(row, DesktopMasterEmployeeData.decode(DesktopMasterEmployeeData.encode(row)))
    }
    @Test fun anOldServiceHelloDoesNotAdvertiseEmployeeAdministration() {
        val hello = DesktopSetupIpcWire.json.decodeFromString(IpcMessage.serializer(), """{"t":"hello","master":true}""")
        assertNull(assertIs<IpcMessage.Hello>(hello).employeeProtocol)
        assertEquals(1, assertIs<IpcMessage.Hello>(DesktopSetupIpcWire.json.decodeFromString(
            IpcMessage.serializer(), """{"t":"hello","master":true,"employeeProtocol":1}""")).employeeProtocol)
    }
    @Test fun remoteReviewIsOnlyDisplayDataAndCannotYieldNativeOwner() {
        val id = DomainId.random()
        val draft = LocalCompanySkuPreview(DomainId.random(), DomainId.random(), DomainId.random(), DomainId.random(),
            Revision(0), Revision(0), "Synthetic", "اختبار", "TEST", emptyList(), "1.00", "STANDARD", "0",
            LocalCompanyPriceTaxInclusion.INCLUDED, DomainId.random(), DomainId.random(),
            UtcInstant.parseCanonical("2026-01-01T00:00:00Z"), true, 0, LocalCompanyStockTracking.QUANTITY, false)
        val proposal = CompanyBusinessPublicationProposal(CompanyScope(DomainId.random(), DomainId.random()),
            CompanyBusinessPublicationKind.CATALOG_SKU_V1, draft.publicationId, draft.skuId,
            (CompanyBusinessPublicationKind.CATALOG_SKU_V1.domain + "synthetic-data").encodeToByteArray())
        val data = LocalCompanyPublicationReviewData(DomainId.random(), proposal, DomainId.random(), "synthetic-fingerprint",
            "Synthetic Company", "Synthetic Head", "head.one")
        val review = LocalCompanyCatalogReview.remote(data, draft, id)
        val roundTrip = assertIs<LocalCompanyCatalogReview>(DesktopMasterEmployeeData.decode(
            DesktopMasterEmployeeData.encode(review) { id }))
        assertEquals(id, roundTrip.remoteCorrelation)
        assertEquals(data.originalActionId, roundTrip.displayAction.originalActionId)
        assertEquals(proposal.contentDigest, roundTrip.displayAction.proposal.contentDigest)
        assertEquals(draft, roundTrip.preview)
        assertFailsWith<IllegalStateException> { roundTrip.action }
    }
}
