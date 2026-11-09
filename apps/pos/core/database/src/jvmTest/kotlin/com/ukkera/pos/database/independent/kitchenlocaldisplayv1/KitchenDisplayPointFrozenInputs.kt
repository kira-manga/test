package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact inputs from the independent kitchen-display-point-integrity 1.0.0 freeze. */
internal class KitchenDisplayPointFrozenInputs private constructor(private val document: JsonObject) {
    internal enum class StorageClass { TEXT, INTEGER, BLOB }
    internal data class Cell(val storageClass: StorageClass, val rawBytes: StoredBytes)
    internal data class Row(val cells: List<Cell>)
    internal data class Body(val bytes: StoredBytes, val byteLength: Int, val digest: String)
    internal data class Selector(val scope: String, val operationId: String, val ordinal: Int) {
        internal fun branchScope(): BranchScope {
            val ids = scope.split('/').map(::DomainId)
            return BranchScope(ids[0], ids[1], ids[2])
        }
    }
    internal enum class Outcome { SUCCESS_BYTES, EXPLICIT_INTEGRITY_FAILURE }
    internal sealed interface Action {
        data class Read(
            val selector: Selector,
            val expectedBody: Body,
            val outcome: Outcome,
            val returnedBody: Body?,
        ) : Action
        data class Insert(val row: Row, val expectedRows: List<Row>) : Action
    }
    internal data class Case(val id: String, val initialRows: List<Row>, val actions: List<Action>)

    private val bodyDefinitions: JsonObject = document.getValue("bodies").jsonObject
    private val selectorDefinitions: JsonObject = document.getValue("selectors").jsonObject
    private val baseRow: JsonObject = document.getValue("base_row").jsonObject
    private val rowVariants: JsonObject = document.getValue("row_variants").jsonObject

    internal fun case(id: String): Case {
        val value = document.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        val initial = value.getValue("initial_rows").jsonArray.map { row(it.jsonPrimitive.content) }
        val actions = value["actions"]?.jsonArray?.map { entry ->
            val action = entry.jsonObject
            when (action.getValue("action").jsonPrimitive.content) {
                "read" -> read(action)
                "fixture_insert" -> Action.Insert(
                    row(action.getValue("row_ref").jsonPrimitive.content),
                    action.getValue("expected_rows").jsonArray.map { row(it.jsonPrimitive.content) },
                )
                else -> error("Unknown frozen point action")
            }
        } ?: List(value.getValue("repeat_reads").jsonPrimitive.int) { read(value) }
        return Case(id, initial, actions)
    }

    private fun read(value: JsonObject): Action.Read = Action.Read(
        selector(value.getValue("selector_ref").jsonPrimitive.content),
        body(value.getValue("expected_body_ref").jsonPrimitive.content),
        Outcome.valueOf(value.getValue("expected_outcome").jsonPrimitive.content),
        value["expected_returned_body_ref"]?.jsonPrimitive?.content?.let(::body),
    )

    private fun selector(reference: String): Selector {
        val value = selectorDefinitions.getValue(reference).jsonObject
        return Selector(value.getValue("scope").jsonPrimitive.content,
            value.getValue("operation_id").jsonPrimitive.content, value.getValue("ordinal").jsonPrimitive.int)
    }

    private fun body(reference: String): Body {
        val value = bodyDefinitions.getValue(reference).jsonObject
        val unit = HexFormat.of().parseHex(value.getValue("hex_unit").jsonPrimitive.content)
        val repeat = value.getValue("repeat_count").jsonPrimitive.int
        val bytes = ByteArray(unit.size * repeat) { unit[it % unit.size] }
        val length = value.getValue("byte_length").jsonPrimitive.int
        val digest = value.getValue("sha256").jsonPrimitive.content
        check(bytes.size == length && independentDigest(bytes) == digest) { "Frozen point body encoding mismatch" }
        return Body(StoredBytes(bytes), length, digest)
    }

    private fun row(reference: String): Row {
        val replacements = rowVariants.getValue(reference).jsonObject.getValue("replace").jsonObject
        return Row(fieldNames.map { field -> cell((replacements[field] ?: baseRow.getValue(field)).jsonObject) })
    }

    private fun cell(value: JsonObject): Cell {
        val storageClass = StorageClass.valueOf(value.getValue("sqlite_type").jsonPrimitive.content)
        val bytes = when (storageClass) {
            StorageClass.BLOB -> value["bytes_ref"]?.jsonPrimitive?.content?.let { body(it).bytes.copyBytes() }
                ?: value.getValue("ascii_value").jsonPrimitive.content.encodeToByteArray()
            StorageClass.TEXT, StorageClass.INTEGER -> value.getValue("value").jsonPrimitive.content.encodeToByteArray()
        }
        return Cell(storageClass, StoredBytes(bytes))
    }

    internal companion object {
        private const val FIXTURE_PATH = "qa/pos/freezes/kitchen-display-point-integrity-v1/fixtures.json"
        private const val FIXTURE_HASH = "6e59cc5e1183ed59405537cedb7b3e757b2fc2db7255ebcf5d6dffeee726c8e4"
        private val fieldNames = listOf("scope", "operation_id", "ordinal", "kind", "body", "body_hash")

        internal fun load(): KitchenDisplayPointFrozenInputs {
            val path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve(FIXTURE_PATH) }.firstOrNull { Files.isRegularFile(it) }
                ?: error("Frozen kitchen DISPLAY point fixtures unavailable")
            val bytes = Files.readAllBytes(path)
            check(independentDigest(bytes) == FIXTURE_HASH) { "Frozen kitchen DISPLAY point fixtures changed" }
            val document = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            check(document.getValue("case_variant_count").jsonPrimitive.int == 20)
            return KitchenDisplayPointFrozenInputs(document)
        }

        internal fun independentDigest(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
