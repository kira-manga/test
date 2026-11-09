package com.ukkera.pos.android.openingobligation

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.types.*
import java.util.UUID
import org.json.JSONObject

/** Separate positive setup DATA; the shared negative plan's default remains supplier-absent. */
internal class NativePositiveOpeningSupplierSetup(
    val namespace: DomainId,
    val original: NativeOpeningSupplierOriginal,
) {
    // Names are synthetic provisioning input, not a new documentary/name requirement.
    val supplier = LocalSupplierV1(original.value.supplierId,
        LocalNamesV1("Synthetic supplier S1", "مورد اصطناعي S1"), active = true)
    val configuration = MutationRequest(OperationId(UUID.nameUUIDFromBytes(
        "positive-opening-supplier-obligation-v1/${namespace.value}/supplier-configuration".encodeToByteArray()).toString()),
        LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(
            LocalConfigurationRequestV1.ConfigureSupplier(expectedStorageRevision = null, supplier = supplier)))

    init { check(configuration.operationId != original.request.operationId) }

    /** No guessed generation: called only with the genuine protected supplier read's value. */
    fun bindSupplierRevision(storageRevision: Revision): NativePositiveOpeningSupplierBound {
        val value = original.value.copy(expectedSupplierRevision = storageRevision)
        return NativePositiveOpeningSupplierBound(
            LocalOpeningSupplierObligationCommands.record(original.request.operationId, value), value)
    }

    fun retainedOriginalInput(): JSONObject = original.retainedInput().also { retained ->
        retained.put("variant", "POSITIVE_ACTIVE_SUPPLIER").put("expectedSupplierRevision", JSONObject.NULL)
            .put("supplierRevisionBinding", "ACTUAL_PROTECTED_SUPPLIER_STORAGE_GENERATION_BEFORE_OPENING_DISPATCH")
            .put("originalDocumentBase64", openingRetentionBase64(original.documentBytes.copyOf()))
        // The negative carrier's revision-1 request is not represented as positive source evidence.
        // Canonical positive A is retained separately after its real storage-revision observation.
        retained.remove("canonicalOriginalRequestBase64")
    }

    fun retainedConfiguration(): JSONObject = JSONObject().put("operationId", configuration.operationId.value)
        .put("commandName", configuration.command.name).put("commandVersion", configuration.command.version)
        .put("expectedStorageRevision", JSONObject.NULL).put("supplierId", supplier.id.value)
        .put("englishName", supplier.names.english).put("arabicName", supplier.names.arabic).put("active", supplier.active)
        .put("canonicalRequestBase64", openingRetentionBase64(configuration.payload.copyBytes()))
}

internal class NativePositiveOpeningSupplierBound(
    val request: MutationRequest,
    val value: LocalRecordOpeningSupplierObligationV1,
) {
    fun retainedRequest(): JSONObject = JSONObject()
        .put("commandName", request.command.name).put("commandVersion", request.command.version)
        .put("operationId", request.operationId.value).put("supplierId", value.supplierId.value)
        .put("expectedSupplierStorageRevision", value.expectedSupplierRevision.value.toString())
        .put("actionApprovalId", JSONObject.NULL)
        .put("canonicalOriginalRequestBase64", openingRetentionBase64(request.payload.copyBytes()))
}
