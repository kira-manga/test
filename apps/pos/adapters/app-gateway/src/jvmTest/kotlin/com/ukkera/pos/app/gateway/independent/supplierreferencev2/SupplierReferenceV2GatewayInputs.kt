package com.ukkera.pos.app.gateway.independent.supplierreferencev2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Selected supplier base 1.0.0 + clarification 1.0.1; exact synthetic inputs only. */
internal object SupplierReferenceV2GatewayInputs {
    fun id(serial: Int) = DomainId("b21f0000-0000-4000-8000-${serial.toString().padStart(12, '0')}")
    fun operation(serial: Int) = OperationId(id(serial).value)
    val scope = BranchScope(id(1), id(10), id(11))
    val actor = id(31)
    val device = id(42)
    val owner = LocalClientIdentity(scope, actor, device)
    val initialNames = LocalNamesV1("Atlas Supply", "توريد أطلس")
    val editedNames = LocalNamesV1("Atlas Supply Updated", "توريد أطلس محدث")
    val filterNames = LocalNamesV1("Cedar Supply", "توريد الأرز")
    const val literal = " 0007-Ab  "
    const val mixed = "مورد-0007-A😀 "
    const val nfc = "Café-01"
    const val nfd = "Cafe\u0301-01"
    val at = UtcInstant.parseCanonical("2026-11-06T01:00:00Z")
    val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    val read = Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM)
    val edit = Permission(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER)
    fun <T> body(value: T, serializer: KSerializer<T>) = StoredBytes(wire.encodeToString(serializer, value).encodeToByteArray())
    fun supplier(id: DomainId = id(102), text: String? = literal, names: LocalNamesV1 = initialNames, active: Boolean = true) =
        LocalSupplierV2(id, names, active, text?.let(Code::exact))
    fun query(reference: LocalRecordReference? = null, search: String = "", cursor: String? = null) =
        LocalWorkflowQuery(LocalWorkflow.PURCHASING, reference = reference, search = search, cursor = cursor,
            purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.SUPPLIERS, includeCosts = false))
    fun intent(operation: OperationId, supplier: LocalSupplierV2, storage: Revision? = null, formVersion: Int = 2): LocalCommandIntent =
        LocalCommandIntent(operation, if (storage == null) LocalAction.SUPPLIER_CREATE else LocalAction.SUPPLIER_UPDATE,
            formVersion, storage?.let { LocalRecordReference(LocalRecordKind.SUPPLIER, supplier.id, Revision(it.value - 1)) },
            buildList {
                add(LocalScalarInput(LocalField.SUPPLIER_ID, LocalScalarValue.Identifier(supplier.id)))
                add(LocalScalarInput(LocalField.NEW_ITEM, LocalScalarValue.Flag(storage == null)))
                storage?.let { add(LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(it.value))) }
                add(LocalScalarInput(LocalField.NAME_ENGLISH, LocalScalarValue.Text(supplier.names.english)))
                add(LocalScalarInput(LocalField.NAME_ARABIC, LocalScalarValue.Text(supplier.names.arabic)))
                add(LocalScalarInput(LocalField.ACTIVE, LocalScalarValue.Flag(supplier.active)))
                if (formVersion == 2) add(LocalScalarInput(LocalField.SUPPLIER_REFERENCE, LocalScalarValue.Text(supplier.reference?.original.orEmpty())))
            })
}
