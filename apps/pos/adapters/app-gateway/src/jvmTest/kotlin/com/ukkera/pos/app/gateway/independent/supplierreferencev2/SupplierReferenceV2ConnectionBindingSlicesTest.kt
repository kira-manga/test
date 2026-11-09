package com.ukkera.pos.app.gateway.independent.supplierreferencev2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/**
 * Supporting binding of existing V1 compatibility and explicit V2 selection requirements.
 * Uses actual connection-derived installation and shared gateway/handler/journal behavior.
 * Health, lifetime, credentials and Master remain controlled; this is not branch sign-in,
 * installed Android composition, remote capability negotiation or admitted native RPC.
 * Current V1 grammar checks are not genuinely retained pre-transition byte goldens.
 */
class SupplierReferenceV2ConnectionBindingSlicesTest {
    private val f = SupplierReferenceV2GatewayInputs

    @Test
    fun sref2003_007_010_originalSupplierAndDirectoryConstructorsKeepV1ReadFormsAndPendingRequestGrammar() = runTest {
        for (shape in LegacyConnectionShape.entries) {
            withSupplierReferenceV2Gateway { h ->
                val supplier = f.supplier()
                h.master.createV2(supplier, f.operation(201))
                val master = SupplierConnectionMaster(h.master)
                val connection = when (shape) {
                    LegacyConnectionShape.SUPPLIER_ALIAS -> LocalSupplierClientConnection(
                        master, f.id(70), deviceOwner(), labels()) { true }
                    LegacyConnectionShape.DIRECTORY_PROFILE -> LocalDirectoryClientConnection(
                        master, f.id(70), deviceOwner(), labels(), LocalMasterRpcWorkflowProfile.SUPPLIERS) { true }
                    LegacyConnectionShape.EXPLICIT_FALSE -> LocalDirectoryClientConnection(
                        master, f.id(70), deviceOwner(), labels(), LocalMasterRpcWorkflowProfile.SUPPLIERS,
                        supplierReferences = false) { true }
                }
                val gateway = connectionGateway(h, connection)
                val browse = assertIs<LocalReadResult.Available>(gateway.read(f.query())).page
                assertEquals(listOf(supplier.id), browse.records.map { it.reference.id }, shape.name)
                assertEquals(listOf(LocalText(f.initialNames.english, f.initialNames.arabic)),
                    browse.records.map { it.title }, shape.name)
                assertEquals(LocalAction.SUPPLIER_CREATE, browse.forms.single().action)
                assertEquals(1, browse.forms.single().version)
                val detail = detailPage(h, gateway, supplier.id)
                assertDetailNamesAndActive(detail, f.initialNames, true)
                assertEquals(LocalAction.SUPPLIER_UPDATE, detail.forms.single().action)
                assertEquals(1, detail.forms.single().version)
                assertTrue((browse.forms + detail.forms).all { form ->
                    form.fields.none { it.field == LocalField.SUPPLIER_REFERENCE }
                })
                assertEquals(listOf(LocalConfigurationProfile.QUERY, LocalConfigurationProfile.QUERY),
                    h.master.reads.map { it.query })
                assertTrue(h.master.reads.all {
                    LocalConfigurationCodec.decodeQuery(it.payload).kind == LocalConfigurationProfile.SUPPLIER
                })
                assertTrue(h.master.executions.isEmpty())
                assertTrue(h.host.pending().isEmpty())
                assertEquals(1, h.master.rowCount)

                val referenceWrite = f.intent(f.operation(202), f.supplier(id = f.id(103)))
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                    assertIs<LocalStageResult.NotStaged>(gateway.stage(referenceWrite)).problem)
                assertTrue(h.host.pending().isEmpty())
                assertTrue(h.master.executions.isEmpty())

                val storage = h.master.storage(supplier.id)
                val edited = f.supplier(names = f.editedNames, active = false)
                val legacyEdit = f.intent(f.operation(203), edited, storage, formVersion = 1)
                assertIs<LocalStageResult.Staged>(gateway.stage(legacyEdit))
                val saved = h.host.find(legacyEdit.operationId)
                assertEquals(1, saved.formVersion)
                assertEquals(LocalConfigurationProfile.COMMAND, saved.request().command)
                assertEquals(LocalConfigurationRequestV1.ConfigureSupplier(storage,
                    LocalSupplierV1(edited.id, edited.names, false)),
                    LocalConfigurationCodec.decode(saved.request().payload))
                val body = Json.parseToJsonElement(saved.request().payload.copyBytes().decodeToString()).jsonObject
                assertEquals(setOf("id", "names", "active"), body.getValue("supplier").jsonObject.keys)
                assertTrue(h.master.executions.isEmpty())
                assertEquals(1, h.master.rowCount)
            }
        }
    }

    @Test
    fun sref2002_003_007_010_explicitSupplierConnectionOptInSelectsV2BeforeReadsAndKeepsBothOriginalCommandVersions() = runTest {
        withSupplierReferenceV2Gateway { h ->
            val supplier = f.supplier()
            h.master.createV2(supplier, f.operation(201))
            val connection = referenceConnection(SupplierConnectionMaster(h.master))
            val gateway = connectionGateway(h, connection)
            val browse = assertIs<LocalReadResult.Available>(gateway.read(f.query())).page
            assertEquals(listOf(supplier.id), browse.records.map { it.reference.id })
            assertEquals(LocalAction.SUPPLIER_CREATE, browse.forms.single().action)
            assertEquals(2, browse.forms.single().version)
            val detail = detailPage(h, gateway, supplier.id)
            assertDetailNamesAndActive(detail, f.initialNames, true)
            val form = detail.forms.single()
            assertEquals(LocalAction.SUPPLIER_UPDATE, form.action)
            assertEquals(2, form.version)
            val reference = scalar(form, LocalField.SUPPLIER_REFERENCE)
            assertEquals(LocalScalarValue.Text(f.literal), reference.initialValue)
            val preloaded = assertIs<LocalScalarValue.Text>(reference.initialValue).value
            assertEquals(listOf(LocalSupplierProfileV2.QUERY, LocalSupplierProfileV2.QUERY), h.master.reads.map { it.query })
            assertTrue(h.master.executions.isEmpty())
            assertTrue(h.host.pending().isEmpty())

            val storage = h.master.storage(supplier.id)
            val edited = f.supplier(text = preloaded, names = f.editedNames, active = false)
            val intent = f.intent(f.operation(202), edited, storage)
            assertIs<LocalStageResult.Staged>(gateway.stage(intent))
            val saved = h.host.find(intent.operationId)
            assertEquals(2, saved.formVersion)
            assertEquals(LocalSupplierProfileV2.COMMAND, saved.request().command)
            assertEquals(LocalSupplierRequestV2(storage, edited), LocalSupplierCodecV2.decode(saved.request().payload))
            assertTrue(h.master.executions.isEmpty())
            assertEquals(LocalRecordedDecision.APPLIED,
                assertIs<LocalOperationResult.Recorded>(gateway.submit(intent.operationId)).decision)
            val afterV2 = detailPage(h, gateway, supplier.id)
            assertDetailNamesAndActive(afterV2, f.editedNames, false)
            assertEquals(LocalScalarValue.Text(f.literal),
                scalar(afterV2.forms.single(), LocalField.SUPPLIER_REFERENCE).initialValue)

            // Explicitly settle the displayed V2 original before staging distinct work.
            val recordedV2 = h.host.find(intent.operationId)
            assertNotNull(recordedV2.recorded)
            assertIs<LocalAcknowledgementResult.Acknowledged>(gateway.acknowledgeRecorded(intent.operationId))
            val acknowledgedV2 = h.host.find(intent.operationId)
            assertTrue(acknowledgedV2.acknowledged)
            assertEquals(saved.request().payload, acknowledgedV2.request().payload)
            assertEquals(recordedV2.recorded, acknowledgedV2.recorded)

            // Additive support must still accept an intentional original V1 edit; it is
            // not a fallback from failure of a pending V2 form or command.
            val legacyStorage = h.master.storage(supplier.id)
            val legacyEdit = f.intent(f.operation(203), f.supplier(text = null), legacyStorage, formVersion = 1)
            assertIs<LocalStageResult.Staged>(gateway.stage(legacyEdit))
            val legacySaved = h.host.find(legacyEdit.operationId)
            assertEquals(1, legacySaved.formVersion)
            assertEquals(LocalConfigurationProfile.COMMAND, legacySaved.request().command)
            assertEquals(LocalConfigurationRequestV1.ConfigureSupplier(legacyStorage,
                LocalSupplierV1(supplier.id, f.initialNames, true)), LocalConfigurationCodec.decode(legacySaved.request().payload))
            assertEquals(LocalRecordedDecision.APPLIED,
                assertIs<LocalOperationResult.Recorded>(gateway.submit(legacyEdit.operationId)).decision)
            val afterV1 = detailPage(h, gateway, supplier.id)
            assertDetailNamesAndActive(afterV1, f.initialNames, true)
            assertEquals(LocalScalarValue.Text(f.literal),
                scalar(afterV1.forms.single(), LocalField.SUPPLIER_REFERENCE).initialValue)
            assertEquals(listOf(LocalSupplierProfileV2.COMMAND, LocalConfigurationProfile.COMMAND),
                h.master.executions.map { it.command })
            assertTrue(h.master.reads.all { it.query == LocalSupplierProfileV2.QUERY })
            assertEquals(saved.request().payload, h.host.find(intent.operationId).request().payload)
            val archivedV2 = h.host.find(intent.operationId)
            assertTrue(archivedV2.acknowledged)
            assertEquals(recordedV2.recorded, archivedV2.recorded)
            assertEquals(1, h.master.rowCount)
        }
    }

    @Test
    fun sref2007_009_selectedV2ConnectionReadFailureNeverQueriesV1OrStagesFallback() = runTest {
        val failures = listOf<ReadReply>(
            ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED),
            ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY),
            ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN),
            ReadReply.Data(StoredBytes("{\"unsupported\":true}".encodeToByteArray()), Revision(0), f.at),
        )
        for (failure in failures) {
            withSupplierReferenceV2Gateway { h ->
                val connection = referenceConnection(SupplierConnectionMaster(h.master))
                val gateway = connectionGateway(h, connection)
                h.master.readOverride = { failure }
                assertIs<LocalReadResult.Unavailable>(gateway.read(f.query()))
                assertIs<LocalReadResult.Unavailable>(gateway.read(f.query(
                    LocalRecordReference(LocalRecordKind.SUPPLIER, f.id(102)))))
                assertEquals(listOf(LocalSupplierProfileV2.QUERY, LocalSupplierProfileV2.QUERY),
                    h.master.reads.map { it.query })
                assertTrue(h.master.executions.isEmpty())
                assertTrue(h.host.pending().isEmpty())
                assertEquals(0, h.master.rowCount)
            }
        }
    }

    @Test
    fun sref2007_supplierReferenceOptInCannotBroadenAnyOtherConnectionProfile() {
        val master = SupplierConnectionMaster(SupplierReferenceV2ControlledMaster())
        for (profile in LocalMasterRpcWorkflowProfile.entries.filter { it != LocalMasterRpcWorkflowProfile.SUPPLIERS }) {
            assertFailsWith<IllegalArgumentException>(profile.name) {
                LocalDirectoryClientConnection(master, f.id(70), deviceOwner(), labels(), profile,
                    supplierReferences = true) { true }
            }
        }
    }

    private fun deviceOwner() = LocalClientDeviceIdentity(f.scope, f.device)
    private fun labels() = LocalBranchDisplayLabels(f.scope, "Synthetic supplier company", "Synthetic supplier branch")
    private fun referenceConnection(master: LocalMasterClient) = LocalDirectoryClientConnection(
        master, f.id(70), deviceOwner(), labels(), LocalMasterRpcWorkflowProfile.SUPPLIERS,
        supplierReferences = true) { true }

    private fun connectionGateway(h: SupplierReferenceV2GatewayFixture,
        connection: LocalDirectoryClientConnection): LocalEndpointWorkflowGateway {
        assertTrue(connection.isCurrent())
        return LocalEndpointWorkflowGateway(connection.endpoint, h.host.journal,
            LocalWorkflowCredentialSource {
                LocalWorkflowCredentialLease(LocalGatewayCredentials(connection.connectionId, OpaqueCredential(ByteArray(16)))) {
                    connection.isCurrent()
                }
            }, connection.installed)
    }

    private suspend fun detailPage(h: SupplierReferenceV2GatewayFixture, gateway: LocalWorkflowGateway,
        id: DomainId): LocalWorkflowPage = assertIs<LocalReadResult.Available>(gateway.read(f.query(
        LocalRecordReference(LocalRecordKind.SUPPLIER, id, Revision(h.master.storage(id).value - 1))))).page

    private fun scalar(form: LocalActionForm, field: LocalField) =
        form.fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == field }

    private fun assertDetailNamesAndActive(page: LocalWorkflowPage, names: LocalNamesV1, active: Boolean) {
        assertEquals(LocalText(names.english, names.arabic), page.records.single().title)
        val form = page.forms.single()
        assertEquals(LocalScalarValue.Text(names.english), scalar(form, LocalField.NAME_ENGLISH).initialValue)
        assertEquals(LocalScalarValue.Text(names.arabic), scalar(form, LocalField.NAME_ARABIC).initialValue)
        assertEquals(LocalScalarValue.Flag(active), scalar(form, LocalField.ACTIVE).initialValue)
    }

    private enum class LegacyConnectionShape { SUPPLIER_ALIAS, DIRECTORY_PROFILE, EXPLICIT_FALSE }
}

/** Only the immutable installation carrier needs controlled health; handlers remain genuine. */
private class SupplierConnectionMaster(delegate: SupplierReferenceV2ControlledMaster) : LocalMasterClient by delegate {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
}
