package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlin.text.CharacterCodingException

/** Explicit producer provenance, not a permission or a classification inferred from field names. */
internal enum class LocalInputIdentityOrigin { CREATED, SELECTED }

/** Ephemeral editor material. It is never a navigation key, saved state, journal or authority. */
internal data class LocalDraftValue(val text: String = "", val selected: LocalScalarValue? = null,
    /** Display only; never included in a staged reference or treated as source authority. */
    val referenceLabel: LocalText? = null,
    val inputIdentityOrigin: LocalInputIdentityOrigin? = null)
internal data class LocalDraftLine(
    val lineId: DomainId,
    val values: Map<LocalField, LocalDraftValue>,
    /** Exact public menu selector captured by an explicit serving choice, never source data or authority. */
    val foodMenuSource: LocalFoodUiQuery.Menu? = null,
    /** Selected business identities/quantity only, not cached names, prices or permission. */
    val foodMenuSelection: LocalFoodMenuSelection? = null,
    /** Proposed preparation identities allocated by explicit quantity edits; never authority. */
    val foodPortionIds: List<DomainId> = emptyList(),
)
internal data class LocalFormDraft(
    val descriptor: LocalActionForm,
    val values: Map<LocalField, LocalDraftValue>,
    val lines: List<LocalDraftLine>,
    val confirmed: Boolean = false,
    val dirty: Boolean = false,
    /** Exact public picker selector captured by an explicit recipient choice. */
    val foodRecipientSource: LocalFoodUiQuery.Recipients? = null,
)

/** Editable input, proven input/line identities, exact source selectors and minimal refusal pins only.
 * No read-only display values, labels, choices, source rows or confirmation survive. */
internal data class LocalFormInputContinuation(
    val action: LocalAction,
    val version: Int,
    val reservedOperationId: OperationId?,
    val scalars: Map<LocalField, LocalFormInputShape>,
    val lineField: LocalField?,
    val columns: Map<LocalField, LocalFormInputShape>,
    val minimumLines: Int?,
    val maximumLines: Int?,
    val allowBlankLines: Boolean?,
    val allowDuplicateLines: Boolean?,
    val openingQuery: LocalOpeningAllocationQuery?,
    val sourceLineIds: Set<DomainId>,
    val values: Map<LocalField, LocalDraftValue>,
    val lines: List<LocalDraftLine>,
    val dirty: Boolean,
    val foodRecipientSource: LocalFoodUiQuery.Recipients? = null,
)

/** Schema comparison is a refusal gate, never a source of form metadata or permission. */
internal data class LocalFormInputShape(
    val kind: LocalInputKind,
    val required: Boolean,
    val maximumCharacters: Int,
    val allowNegative: Boolean,
    val readOnly: Boolean,
)

private fun LocalFormField.Scalar.inputShape(): LocalFormInputShape = LocalFormInputShape(
    kind, required, maximumCharacters, allowNegative, readOnly)

/** Explicit source-version/unit/body coordinates only. Expected amounts, names and other
 * read-only content are not retained by a field-name convention. */
private val localInputPinFields: Set<LocalField> = setOf(
    LocalField.EXPECTED_REVISION, LocalField.EXPECTED_WORK_REVISION, LocalField.EXPECTED_DRAWER_REVISION,
    LocalField.EXPECTED_CUSTODIAN_REVISION, LocalField.EXPECTED_SAFE_REVISION, LocalField.EXPECTED_INVENTORY_REVISION,
    LocalField.EXPECTED_SOURCE_REVISION, LocalField.EXPECTED_CATALOG_REVISION, LocalField.EXPECTED_BASIS_REVISION,
    LocalField.EXPECTED_RECEIPT_REVISION, LocalField.EXPECTED_COUNT_REVISION, LocalField.EXPECTED_ITEM_STORAGE_REVISION,
    LocalField.EXPECTED_SURPLUS_AUTHORITY_REVISION, LocalField.EXPECTED_COMPANY_PRICE_REVISION,
    LocalField.EXPECTED_PRICE_POLICY_REVISION, LocalField.EXPECTED_OVERRIDE_STORAGE_REVISION,
    LocalField.EXPECTED_STORAGE_REVISION, LocalField.EXPECTED_SESSION_REVISION, LocalField.EXPECTED_ORDER_REVISION,
    LocalField.EXPECTED_COLLECTION_REVISION, LocalField.EXPECTED_ARRIVAL_REVISION,
    LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION, LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION,
    LocalField.EXPECTED_CATALOG_STORAGE_REVISION, LocalField.EXPECTED_EVIDENCE_POLICY_REVISION,
    LocalField.EXPECTED_ATTACHMENT_STORAGE_REVISION, LocalField.EXPECTED_SERIAL_REGISTRATION_REVISION,
    LocalField.EXPECTED_RESERVATION_REVISION, LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION,
    LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION, LocalField.EXPECTED_FOOD_RECIPE_REVISION,
    LocalField.EXPECTED_PROGRESS_REVISION, LocalField.TAX_REVISION, LocalField.EXPECTED_CASE_REVISION,
    LocalField.EXPECTED_ENTITLEMENT_REVISION, LocalField.EXPECTED_CLAIM_REVISION, LocalField.EXPECTED_TRANSFER_REVISION,
    LocalField.EXPECTED_FOOD_RECIPE_STORAGE_REVISION, LocalField.EXPECTED_PAYABLE_REVISION,
    LocalField.EXPECTED_CREDIT_REVISION, LocalField.EXPECTED_SETTLEMENT_REVISION,
    LocalField.EXPECTED_ADVANCE_REVISION, LocalField.EXPECTED_ADVANCE_ALLOCATION_REVISION,
    LocalField.UNIT, LocalField.QUANTITY_SCALE, LocalField.CURRENCY,
    LocalField.RETAIL_BODY_DIGEST, LocalField.FOOD_CONFIGURATION_BODY_DIGEST,
    LocalField.ARRIVAL_SELECTION_DIGEST, LocalField.EXPECTED_REQUESTER_POLICY_REVISION,
    LocalField.EXPECTED_PRODUCT_REVISION,
    LocalField.EXPECTED_FLOOR_STORAGE_REVISION, LocalField.EXPECTED_TABLE_STORAGE_REVISION,
    LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION,
)

private fun LocalFormField.Scalar.keepsInputPin(): Boolean = readOnly && field in localInputPinFields

private fun LocalFormField.Scalar.keepsIdentity(input: LocalDraftValue?): Boolean =
    readOnly && kind == LocalInputKind.IDENTIFIER && input?.inputIdentityOrigin != null

private fun LocalFormField.Scalar.keepsInput(input: LocalDraftValue?): Boolean =
    !readOnly || keepsInputPin() || keepsIdentity(input)

private fun retainedInputValues(fields: List<LocalFormField.Scalar>, values: Map<LocalField, LocalDraftValue>): Map<LocalField, LocalDraftValue> {
    val retained = fields.filter { it.keepsInput(values[it.field]) }.map { it.field }.toSet()
    return values.filterKeys { it in retained }.mapValues { it.value.copy(referenceLabel = null) }
}

internal fun LocalFormDraft.inputContinuation(): LocalFormInputContinuation {
    val fields = descriptor.fields.filterIsInstance<LocalFormField.Scalar>()
    val group = descriptor.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull()
    return LocalFormInputContinuation(descriptor.action, descriptor.version, descriptor.reservedOperationId,
        fields.associate { it.field to it.inputShape() },
        group?.field, group?.columns.orEmpty().associate { it.field to it.inputShape() },
        group?.minimumLines, group?.maximumLines, group?.allowBlankLines, group?.allowDuplicateLines,
        descriptor.openingAllocations?.query,
        group?.initialLines.orEmpty().map { it.lineId }.filter { id -> lines.any { it.lineId == id } }.toSet(),
        retainedInputValues(fields, values),
        lines.map { it.copy(values = retainedInputValues(group?.columns.orEmpty(), it.values)) }, dirty,
        foodRecipientSource)
}

/** Called only after the controller accepts a fresh protected read on its exact attachment. */
internal fun LocalFormInputContinuation.rebind(form: LocalActionForm): LocalFormDraft? {
    val fields = form.fields.filterIsInstance<LocalFormField.Scalar>()
    val group = form.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull()
    if (form.action != action || form.version != version || form.reservedOperationId != reservedOperationId ||
        fields.associate { it.field to it.inputShape() } != scalars || group?.field != lineField ||
        group?.columns.orEmpty().associate { it.field to it.inputShape() } != columns ||
        group?.minimumLines != minimumLines || group?.maximumLines != maximumLines ||
        group?.allowBlankLines != allowBlankLines || group?.allowDuplicateLines != allowDuplicateLines ||
        form.openingAllocations?.query != openingQuery) return null
    val rebound = rebindInputValues(fields, values) ?: return null
    val reboundLines = lines.map { line ->
        val source = group?.initialLines?.singleOrNull { it.lineId == line.lineId }
        // A source row must be reread under its exact identity. Never restore old read-only
        // columns or guess a replacement row/default when a source slice cannot be rejoined.
        if (source == null && group != null && group.columns.any { it.readOnly } &&
            (line.lineId in sourceLineIds || !group.allowBlankLines)) return null
        line.copy(values = rebindInputValues(group?.columns.orEmpty(), line.values, source) ?: return null)
    }
    return LocalFormDraft(form, rebound, reboundLines, confirmed = false, dirty = dirty,
        foodRecipientSource = foodRecipientSource)
        .takeIf { it.withinInputBound() }
}

private fun rebindInputValues(fields: List<LocalFormField.Scalar>, values: Map<LocalField, LocalDraftValue>,
    source: LocalInputLine? = null): Map<LocalField, LocalDraftValue>? {
    if (values.keys != fields.filter { it.keepsInput(values[it.field]) }.map { it.field }.toSet()) return null
    val sourceValues = source?.values.orEmpty().associate { it.field to it.value }
    val rebound = mutableMapOf<LocalField, LocalDraftValue>()
    for (field in fields) {
        val initial = sourceValues[field.field] ?: field.initialValue
        // Old revision/body/unit values are equality-only refusal pins, never rebound data.
        if (field.keepsInputPin()) {
            val same = when (val parsed = parseLocalInput(field, values.getValue(field.field))) {
                is LocalParsedValue.Value -> parsed.value == initial
                LocalParsedValue.Omitted -> initial == null
                LocalParsedValue.Invalid -> false
            }
            if (!same) return null
        }
        // An absent read-only flag is not a freshly observed false business condition.
        if (field.readOnly && initial == null && field.kind == LocalInputKind.CHECKBOX) return null
        val input = if (field.keepsIdentity(values[field.field])) {
            val original = values.getValue(field.field)
            val identity = (parseLocalInput(field, original) as? LocalParsedValue.Value)?.value as? LocalScalarValue.Identifier
                ?: return null
            if (initial != null && initial != identity) return null
            // Created IDs are original proposal input, never read-derived metadata. A selected
            // identity still needs a fresh exact source binding; an empty field cannot supply it.
            if (initial == null && original.inputIdentityOrigin == LocalInputIdentityOrigin.SELECTED) return null
            if (initial == null) original else localDraftValue(initial).copy(inputIdentityOrigin = original.inputIdentityOrigin)
        } else if (field.readOnly) localDraftValue(initial) else values.getValue(field.field)
        if (field.readOnly && parseLocalInput(field, input) == LocalParsedValue.Invalid) return null
        when (val selected = input.selected) {
            is LocalScalarValue.Choice -> if (field.choices.none { it.code == selected.code }) return null
            is LocalScalarValue.Choices -> if (selected.codes.any { code -> field.choices.none { it.code == code } }) return null
            is LocalScalarValue.Reference -> {
                val choices = field.references.filter { it.reference.kind == selected.value.kind && it.reference.id == selected.value.id }
                if (choices.isNotEmpty() && choices.none { it.reference == selected.value } ||
                    !(field.referenceSource?.accepts(selected.value) ?: field.references.any { it.reference == selected.value })) return null
            }
            is LocalScalarValue.References -> if (selected.values.any { reference -> field.references.none { it.reference == reference } }) return null
            else -> Unit // Incomplete editable text remains input; read-only data comes only from this fresh form.
        }
        rebound[field.field] = input.copy(referenceLabel = (input.selected as? LocalScalarValue.Reference)?.let { selected ->
            field.references.singleOrNull { it.reference == selected.value }?.label
        })
    }
    return rebound
}

internal data class LocalInputIssue(val field: LocalField? = null, val lineId: DomainId? = null, val tooLarge: Boolean = false)

internal sealed interface LocalParsedValue {
    data class Value(val value: LocalScalarValue) : LocalParsedValue
    data object Omitted : LocalParsedValue
    data object Invalid : LocalParsedValue
}

internal fun localDraftValue(value: LocalScalarValue?): LocalDraftValue = when (value) {
    is LocalScalarValue.Text -> LocalDraftValue(value.value)
    is LocalScalarValue.Identifier -> LocalDraftValue(value.value.value)
    is LocalScalarValue.Integer -> LocalDraftValue(value.value.toString())
    is LocalScalarValue.Decimal -> LocalDraftValue(value.canonical)
    is LocalScalarValue.Date -> LocalDraftValue(value.iso)
    else -> LocalDraftValue(selected = value)
}

internal fun localDraftLine(columns: List<LocalFormField.Scalar>, initial: LocalInputLine? = null): LocalDraftLine =
    LocalDraftLine(initial?.lineId ?: DomainId.random(), columns.associate { column ->
        column.field to localDraftValue(initial?.values?.firstOrNull { it.field == column.field }?.value ?: column.initialValue)
    })

internal fun localFormDraft(form: LocalActionForm): LocalFormDraft {
    val lineField = form.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull()
    val initialLines = lineField?.initialLines.orEmpty().map { localDraftLine(lineField!!.columns, it) }
    return LocalFormDraft(form, form.fields.filterIsInstance<LocalFormField.Scalar>().associate {
        it.field to localDraftValue(it.initialValue)
    }, if (lineField == null) emptyList() else initialLines + List((lineField.minimumLines - initialLines.size).coerceAtLeast(0)) {
        localDraftLine(lineField.columns)
    })
}

/** Exact Arabic/Persian digit input; no grouping, exponent, float conversion or rounding. */
internal fun normalizeLocalNumber(input: String): String = buildString(input.length) {
    input.forEach { append(when (it) {
        in '٠'..'٩' -> '0' + (it - '٠')
        in '۰'..'۹' -> '0' + (it - '۰')
        '٫' -> '.'
        else -> it
    }) }
}

internal fun parseLocalInput(field: LocalFormField.Scalar, input: LocalDraftValue): LocalParsedValue {
    fun value(value: LocalScalarValue) = LocalParsedValue.Value(value)
    fun omitted() = if (field.required) LocalParsedValue.Invalid else LocalParsedValue.Omitted
    return try {
        when (field.kind) {
            LocalInputKind.CHOICE -> (input.selected as? LocalScalarValue.Choice)?.let { chosen ->
                if (field.choices.any { it.code == chosen.code }) value(chosen) else LocalParsedValue.Invalid
            } ?: omitted()
            LocalInputKind.REFERENCE -> (input.selected as? LocalScalarValue.Reference)?.let { chosen ->
                if (field.referenceSource?.accepts(chosen.value) ?: field.references.any { it.reference == chosen.value })
                    value(chosen) else LocalParsedValue.Invalid
            } ?: omitted()
            LocalInputKind.MULTI_CHOICE -> {
                val chosen = input.selected as? LocalScalarValue.Choices
                if (chosen == null || chosen.codes.isEmpty()) omitted()
                else if (chosen.codes.all { code -> field.choices.any { it.code == code } }) value(chosen) else LocalParsedValue.Invalid
            }
            LocalInputKind.MULTI_REFERENCE -> {
                val chosen = input.selected as? LocalScalarValue.References
                if (chosen == null || chosen.values.isEmpty()) omitted()
                else if (chosen.values.all { reference -> field.references.any { it.reference == reference } }) value(chosen) else LocalParsedValue.Invalid
            }
            LocalInputKind.CHECKBOX -> {
                val checked = (input.selected as? LocalScalarValue.Flag)?.value ?: false
                if (field.required && !checked) LocalParsedValue.Invalid else value(LocalScalarValue.Flag(checked))
            }
            else -> {
                val raw = input.text
                if (raw.length > field.maximumCharacters) return LocalParsedValue.Invalid
                if (raw.isEmpty()) return omitted()
                when (field.kind) {
                    LocalInputKind.TEXT, LocalInputKind.MULTILINE -> {
                        if (field.required && raw.isBlank()) LocalParsedValue.Invalid else value(LocalScalarValue.Text(raw))
                    }
                    LocalInputKind.IDENTIFIER -> value(LocalScalarValue.Identifier(DomainId(raw)))
                    LocalInputKind.INTEGER -> {
                        val normalized = normalizeLocalNumber(raw)
                        if (!Regex("-?(0|[1-9][0-9]*)").matches(normalized) || !field.allowNegative && normalized.startsWith('-'))
                            LocalParsedValue.Invalid
                        else normalized.toLongOrNull()?.let { value(LocalScalarValue.Integer(it)) } ?: LocalParsedValue.Invalid
                    }
                    LocalInputKind.DECIMAL -> {
                        val normalized = normalizeLocalNumber(raw)
                        if (!field.allowNegative && normalized.startsWith('-')) LocalParsedValue.Invalid
                        else value(LocalScalarValue.Decimal(normalized))
                    }
                    LocalInputKind.DATE -> {
                        val normalized = normalizeLocalNumber(raw)
                        if (validLocalDate(normalized)) value(LocalScalarValue.Date(normalized)) else LocalParsedValue.Invalid
                    }
                    else -> LocalParsedValue.Invalid
                }
            }
        }
    } catch (_: IllegalArgumentException) { LocalParsedValue.Invalid }
}

private fun validLocalDate(input: String): Boolean {
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(input)) return false
    val year = input.take(4).toInt()
    val month = input.substring(5, 7).toInt()
    val day = input.takeLast(2).toInt()
    if (year == 0 || month !in 1..12) return false
    val maximum = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..maximum
}

/** Bound editor retention too, not merely the finally submitted immutable intent. */
internal fun LocalFormDraft.withinInputBound(): Boolean = try {
    fun LocalDraftValue.bytes(): Int = text.encodeToByteArray(throwOnInvalidSequence = true).size +
        (referenceLabel?.let { it.english.encodeToByteArray().size + it.arabic.encodeToByteArray().size } ?: 0) + when (val value = selected) {
        is LocalScalarValue.Choices -> value.codes.sumOf { it.length }
        is LocalScalarValue.References -> value.values.size * 144
        is LocalScalarValue.Reference -> 144
        null -> 0
        else -> 128
    }
    values.values.sumOf { it.bytes() } + lines.sumOf { line ->
        36 + line.foodPortionIds.size * 36 + line.values.values.sumOf { it.bytes() }
    } <= MAX_LOCAL_INPUT_BYTES
} catch (_: IllegalArgumentException) { false } catch (_: CharacterCodingException) { false }
