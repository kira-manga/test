package com.ukkera.pos.app.gateway.independent.supplierinvoicecapturev2

import com.ukkera.pos.database.StoredBytes
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Frozen business terms only. Labels are mapped to controlled DTO coordinates by the harness. */
internal data class FrozenInvoiceCaptureLine(
    val item: String,
    val supplierItemReference: String,
    val purchaseUnit: String,
    val baseUnit: String,
    val numerator: String,
    val denominator: String,
    val purchaseQuantity: String,
    val purchaseScale: Int,
    val unitPrice: String,
    val baseQuantity: String,
    val minor: Long,
) {
    val rate: String get() = "$numerator/$denominator"
}

internal data class FrozenInvoiceCaptureClaim(
    val name: String,
    val reference: String,
    val date: String,
    val lines: List<FrozenInvoiceCaptureLine>,
    val minor: Long,
)

/**
 * Reads existing expectations after checking every frozen artifact and clarification digest.
 * No fixture number is derived from application output, and no frozen file is rewritten.
 */
internal object FrozenSupplierInvoiceCaptureInputs {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private const val capturePath = "qa/pos/designs/supplier-invoice-discrepancy-capture-v1"
    private const val referencedPath = "qa/pos/designs/supplier-invoice-referenced-discrepancy-v1"
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve(capturePath).resolve("fixtures.json")) }
        ?: error("Frozen supplier invoice capture expectations are unavailable")
    private val captureDirectory = root.resolve(capturePath)
    private val referencedDirectory = root.resolve(referencedPath)

    private val capture: JsonObject
    private val referenced: JsonObject

    init {
        val clarification = read(referencedDirectory.resolve("context-identity-clarification-v1.1.json"))
        check(text(clarification, "version") == "1.1")
        val artifact = clarification.getValue("artifact").jsonObject
        verify(referencedDirectory.resolve(text(artifact, "path")), text(artifact, "sha256"))
        val preservedReferenced = clarification.getValue("preserved_additive_v1").jsonObject
        verify(referencedDirectory.resolve(text(preservedReferenced, "path")), text(preservedReferenced, "sha256"))
        val preservedCapture = clarification.getValue("preserved_original_capture_v1").jsonObject
        verify(root.resolve(text(preservedCapture, "path")), text(preservedCapture, "sha256"))
        capture = frozen(captureDirectory)
        referenced = frozen(referencedDirectory)
    }

    private val common get() = capture.getValue("claim_common").jsonObject
    private val base get() = capture.getValue("BASE").jsonObject

    val originalLine: FrozenInvoiceCaptureLine get() {
        val original = base.getValue("original_po").jsonObject
        return line(original, "product", "expected_claim_basis_minor", 0)
    }

    val originalMinor: Long get() = originalLine.minor
    val supplierDate: String get() = text(common, "supplier_document_date")
    val purchaseTaxMinor: Long get() = text(common, "purchase_tax_minor").toLong()
    fun captureClaim(name: String): FrozenInvoiceCaptureClaim {
        val value = capture.getValue(name).jsonObject
        val lines = value.getValue("lines").jsonArray.map { element ->
            val terms = element.jsonObject
            val item = text(terms, "product")
            val scale = if (item == "C") text(base.getValue("products").jsonObject.getValue(item).jsonObject,
                "purchase_quantity_fraction_digits").toInt() else 0
            line(terms, "product", "expected_line_minor", scale)
        }
        return FrozenInvoiceCaptureClaim(name, text(value, "supplier_reference"), supplierDate,
            lines, text(value, "expected_invoice_minor").toLong())
    }

    fun referencedClaim(name: String): FrozenInvoiceCaptureClaim {
        val value = referenced.getValue(name).jsonObject
        return FrozenInvoiceCaptureClaim(name, text(value, "supplier_reference"),
            text(value, "supplier_document_date"), listOf(line(value, "item", "expected_minor", 0)),
            text(value, "expected_minor").toLong())
    }

    fun <T> body(value: T, serializer: KSerializer<T>): StoredBytes =
        StoredBytes(json.encodeToString(serializer, value).encodeToByteArray())

    fun moneyDecimal(minor: Long): String = (minor / 100).toString() + "." +
        (minor % 100).toString().padStart(2, '0')

    fun text(value: JsonObject, key: String): String = value.getValue(key).jsonPrimitive.content

    private fun line(value: JsonObject, itemField: String, minorField: String,
        scale: Int): FrozenInvoiceCaptureLine {
        val conversion = value.getValue("conversion").jsonObject
        return FrozenInvoiceCaptureLine(text(value, itemField), text(value, "supplier_item_reference"),
            text(value, "purchase_unit"), text(value, "base_unit"), text(conversion, "numerator"),
            text(conversion, "denominator"), text(value, "purchase_quantity"), scale,
            text(value, "unit_cost_egp"), text(value, "expected_base_quantity"), text(value, minorField).toLong())
    }

    private fun frozen(directory: Path): JsonObject {
        val manifest = read(directory.resolve("source-manifest.json"))
        check(text(manifest, "version") == "1.0")
        manifest.getValue("frozen_artifacts").jsonArray.forEach { entry ->
            val artifact = entry.jsonObject
            verify(directory.resolve(text(artifact, "path")), text(artifact, "sha256"))
        }
        return read(directory.resolve("fixtures.json")).also { check(text(it, "version") == "1.0") }
    }

    private fun read(path: Path): JsonObject = json.parseToJsonElement(Files.readString(path)).jsonObject

    private fun verify(path: Path, expected: String) {
        val observed = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        check(observed == expected) { "Frozen supplier invoice artifact digest changed: ${path.fileName}" }
    }
}
