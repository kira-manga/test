package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

internal val foodSelectionActions: Set<LocalAction> = setOf(LocalAction.FOOD_CREATE, LocalAction.FOOD_ADD)

internal val foodDeliveryInputFields: Set<LocalField> = setOf(LocalField.FOOD_DELIVERY_RECIPIENT,
    LocalField.FOOD_DELIVERY_CONTACT, LocalField.FOOD_DELIVERY_ADDRESS)

/** Conditional editor rules only; the Master validates the final request independently. */
internal fun LocalFormDraft.foodDraftFields(): List<LocalFormField.Scalar> {
    val fields = descriptor.fields.filterIsInstance<LocalFormField.Scalar>()
    if (descriptor.action != LocalAction.FOOD_CREATE_DRAFT || descriptor.version != 2) return fields
    val delivery = (values[LocalField.CHANNEL]?.selected as? LocalScalarValue.Choice)?.code == "DELIVERY"
    return fields.filter { delivery || it.field !in foodDeliveryInputFields }.map { field ->
        when {
            field.field in foodDeliveryInputFields -> field.copy(required = true)
            field.field == LocalField.SERVICE_REFERENCE -> field.copy(required = !delivery)
            else -> field
        }
    }
}

/** Explicit channel changes retire inapplicable customer data, not the staged original. */
internal fun LocalFormDraft.withFoodDeliveryChannelCleanup(): LocalFormDraft {
    if (descriptor.action != LocalAction.FOOD_CREATE_DRAFT || descriptor.version != 2 ||
        (values[LocalField.CHANNEL]?.selected as? LocalScalarValue.Choice)?.code == "DELIVERY") return this
    return copy(values = values.mapValues { (field, input) ->
        if (field in foodDeliveryInputFields) LocalDraftValue() else input
    })
}

internal fun LocalFormDraft.foodDeliveryInputIssue(): LocalInputIssue? {
    if (descriptor.action != LocalAction.FOOD_CREATE_DRAFT || descriptor.version != 2) return null
    val delivery = (values[LocalField.CHANNEL]?.selected as? LocalScalarValue.Choice)?.code == "DELIVERY"
    for (field in foodDeliveryInputFields) {
        val input = values[field] ?: LocalDraftValue()
        val invalid = if (delivery) input.selected != null ||
            !validFoodDeliveryText(input.text, if (field == LocalField.FOOD_DELIVERY_ADDRESS) 600 else 160)
        else input.text.isNotEmpty() || input.selected != null
        if (invalid) return LocalInputIssue(field)
    }
    return null
}

/** Refusal-only original selection coordinates. A fresh authorized menu must still bind them. */
internal data class LocalFoodMenuSelection(
    val outputSkuId: DomainId,
    val modifierIds: List<DomainId>,
    val portionQuantity: Quantity,
)

/** Retained menu drafts keep both their catalog generation and their exact create/add target. */
internal fun LocalFormDraft.matchesFoodMenu(query: LocalFoodUiQuery.Menu, catalogRevision: Revision?): Boolean {
    if (catalogRevision == null || values[LocalField.EXPECTED_CATALOG_REVISION]?.text?.toLongOrNull() != catalogRevision.value) return false
    return when (descriptor.action) {
        LocalAction.FOOD_CREATE -> query.orderId == null && query.expectedOrderStorageRevision == null
        LocalAction.FOOD_ADD -> query.orderId != null && query.expectedOrderStorageRevision != null &&
            values[LocalField.ORDER_ID]?.text == query.orderId.value &&
            values[LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION]?.text?.toLongOrNull() == query.expectedOrderStorageRevision.value
        else -> false
    }
}

/** IDs are allocated only on an explicit editor/selection action, never by composition or reads. */
internal fun foodActionDraft(source: LocalActionForm): LocalFormDraft {
    val created = mutableSetOf<LocalField>()
    val descriptor = source.copy(fields = source.fields.map { field ->
        if (field is LocalFormField.Scalar && field.readOnly && field.initialValue == null &&
            (field.field in setOf(LocalField.HANDOFF_ID, LocalField.CONFIRMATION_ID) ||
                source.action == LocalAction.FLOOR_CONFIGURE && field.field == LocalField.FLOOR_ID ||
                source.action == LocalAction.TABLE_CONFIGURE && field.field == LocalField.TABLE_ID ||
                source.action == LocalAction.FOOD_CREATE_DRAFT && field.field == LocalField.ORDER_ID)) {
            created += field.field
            field.copy(initialValue = LocalScalarValue.Identifier(DomainId.random()))
        } else field
    })
    val draft = localFormDraft(descriptor)
    return draft.copy(values = draft.values.mapValues { (field, input) ->
        if (field in created) input.copy(inputIdentityOrigin = LocalInputIdentityOrigin.CREATED) else input
    })
}

internal fun newFoodSelectionDraft(source: LocalActionForm, recipe: LocalFoodUiRecipe,
    choice: LocalFoodUiChoice, query: LocalFoodUiQuery.Menu? = null): LocalFormDraft {
    require(source.action in foodSelectionActions && source.fields.none { it is LocalFormField.Lines })
    val fields = source.fields.map { field ->
        if (source.action == LocalAction.FOOD_CREATE && field is LocalFormField.Scalar && field.field == LocalField.ORDER_ID)
            field.copy(initialValue = LocalScalarValue.Identifier(DomainId.random()), readOnly = true) else field
    } + foodSelectionGroup(listOf(foodSelectionLine(DomainId.random(), recipe, choice)))
    val draft = localFormDraft(source.copy(fields = fields))
    val values = draft.values.mapValues { (field, input) ->
        if (source.action == LocalAction.FOOD_CREATE && field == LocalField.ORDER_ID)
            input.copy(inputIdentityOrigin = LocalInputIdentityOrigin.CREATED) else input
    }
    return draft.copy(values = values, lines = draft.lines.map { it.foodSelectionInput(recipe, choice).copy(foodMenuSource = query) }, dirty = true)
}

internal fun appendFoodSelectionDraft(draft: LocalFormDraft, recipe: LocalFoodUiRecipe,
    choice: LocalFoodUiChoice, query: LocalFoodUiQuery.Menu? = null): LocalFormDraft {
    require(draft.descriptor.action in foodSelectionActions)
    val group = draft.descriptor.fields.filterIsInstance<LocalFormField.Lines>().single()
    require(draft.lines.size < group.maximumLines)
    return draft.copy(lines = draft.lines + localDraftLine(group.columns, foodSelectionLine(DomainId.random(), recipe, choice))
        .foodSelectionInput(recipe, choice).copy(foodMenuSource = query),
        confirmed = false, dirty = true)
}

/** Only the original explicit read selectors are eligible: no catalog scan or replacement target. */
internal fun LocalFormInputContinuation.foodMenuSourceQueries(query: LocalFoodUiQuery.Menu): List<LocalFoodUiQuery.Menu>? {
    if (action !in foodSelectionActions || lines.size !in 1..8 || lines.map { it.lineId }.distinct().size != lines.size ||
        foodRecipientSource != null || (action == LocalAction.FOOD_CREATE) != (query.orderId == null)) return null
    return lines.map { line ->
        val original = line.foodMenuSource ?: return null
        if (line.foodMenuSelection == null || original.orderId != query.orderId ||
            original.expectedOrderStorageRevision != query.expectedOrderStorageRevision) return null
        original
    }.distinct()
}

/** Each row comes from its own freshly accepted exact protected page. No old descriptor or new IDs. */
internal fun LocalFormInputContinuation.freshFoodSelectionForm(source: LocalActionForm, query: LocalFoodUiQuery.Menu,
    catalogRevision: Revision?, menus: Map<LocalFoodUiQuery.Menu, LocalFoodUiBody.Menu>): LocalActionForm? {
    val queries = foodMenuSourceQueries(query) ?: return null
    if (source.action != action || source.fields.any { it.field == LocalField.LINES } || source.fields.size >= 32 ||
        catalogRevision == null || source.fields.filterIsInstance<LocalFormField.Scalar>()
            .singleOrNull { it.field == LocalField.EXPECTED_CATALOG_REVISION }?.initialValue != LocalScalarValue.Integer(catalogRevision.value) ||
        menus.keys != queries.toSet() || menus.values.any { it.catalogRevision != catalogRevision }) return null
    // The fixed original page set cannot cycle. Page boundaries may overlap, but a
    // repeated recipe ID must have the same full fresh representation on every page.
    val recipes = menus.values.flatMap { it.recipes }.groupBy { it.recipeId }
    if (recipes.values.any { rows -> rows.distinct().size != 1 }) return null
    val freshLines = lines.map { line ->
        val recipeInput = line.values[LocalField.RECIPE_ID] ?: return null
        val choiceInput = line.values[LocalField.CHOICE_ID] ?: return null
        if (recipeInput.inputIdentityOrigin != LocalInputIdentityOrigin.SELECTED ||
            choiceInput.inputIdentityOrigin != LocalInputIdentityOrigin.SELECTED) return null
        val original = line.foodMenuSource ?: return null
        val menu = menus[original] ?: return null
        val recipe = menu.recipes.singleOrNull { it.recipeId.value == recipeInput.text && it.active } ?: return null
        val choice = recipe.choices.singleOrNull { it.choiceId.value == choiceInput.text } ?: return null
        val selection = line.foodMenuSelection ?: return null
        if (selection.outputSkuId != recipe.outputSkuId || selection.modifierIds != choice.modifierIds ||
            selection.portionQuantity != recipe.portionQuantity) return null
        foodSelectionLine(line.lineId, recipe, choice)
    }
    return source.copy(fields = source.fields + foodSelectionGroup(freshLines))
}

/** Unstaged menu input may use current catalog/recipe versions only after exact-source joining.
 * All other schema, order, unit and identity pins retain the ordinary refusal checks. */
internal fun LocalFormInputContinuation.rebindFreshFoodSelection(form: LocalActionForm): LocalFormDraft? {
    if (action !in foodSelectionActions || form.action != action || lines.any { it.foodMenuSelection == null }) return null
    val catalog = form.fields.filterIsInstance<LocalFormField.Scalar>()
        .singleOrNull { it.field == LocalField.EXPECTED_CATALOG_REVISION } ?: return null
    if (!catalog.readOnly || catalog.kind != LocalInputKind.INTEGER ||
        catalog.initialValue !is LocalScalarValue.Integer || LocalField.EXPECTED_CATALOG_REVISION !in values) return null
    val group = form.fields.filterIsInstance<LocalFormField.Lines>().singleOrNull() ?: return null
    val recipeColumn = group.columns.singleOrNull { it.field == LocalField.EXPECTED_FOOD_RECIPE_REVISION } ?: return null
    if (!recipeColumn.readOnly || recipeColumn.kind != LocalInputKind.INTEGER ||
        group.initialLines.map { it.lineId }.toSet() != lines.map { it.lineId }.toSet()) return null
    val refreshedLines = lines.map { line ->
        val source = group.initialLines.singleOrNull { it.lineId == line.lineId } ?: return null
        val recipe = source.values.singleOrNull { it.field == LocalField.EXPECTED_FOOD_RECIPE_REVISION }?.value
            as? LocalScalarValue.Integer ?: return null
        if (LocalField.EXPECTED_FOOD_RECIPE_REVISION !in line.values) return null
        line.copy(values = line.values + (LocalField.EXPECTED_FOOD_RECIPE_REVISION to localDraftValue(recipe)))
    }
    return copy(values = values + (LocalField.EXPECTED_CATALOG_REVISION to localDraftValue(catalog.initialValue)),
        lines = refreshedLines).rebind(form)
}

/** Shared by explicit selection and fresh restoration; never allocates the handoff identity. */
internal fun foodRecipientForm(source: LocalActionForm, recipient: LocalFoodUiRecipient): LocalActionForm? {
    val field = source.fields.filterIsInstance<LocalFormField.Scalar>().singleOrNull { it.field == LocalField.RECIPIENT_ID }
        ?: return null
    val selected = LocalScalarValue.Identifier(recipient.actorId)
    if (source.action != LocalAction.FOOD_OFFER_HANDOFF || field.kind != LocalInputKind.IDENTIFIER ||
        field.initialValue != null && field.initialValue != selected) return null
    return source.copy(title = LocalText("Offer responsibility to ${recipient.label}", "عرض تسليم المسؤولية إلى ${recipient.label}"),
        fields = source.fields.map { if (it == field) field.copy(initialValue = selected, readOnly = true) else it })
}

/** Missing original picker provenance or current eligibility leaves the input inactive. */
internal fun LocalFormInputContinuation.freshFoodRecipientForm(source: LocalActionForm, query: LocalFoodUiQuery.Recipients,
    body: LocalFoodUiBody.Recipients, actorId: DomainId): LocalActionForm? {
    if (action != LocalAction.FOOD_OFFER_HANDOFF || foodRecipientSource?.copy(after = null) != query.copy(after = null) || body.orderId != query.orderId ||
        body.orderStorageRevision != query.expectedOrderStorageRevision) return null
    val selected = values[LocalField.RECIPIENT_ID]?.takeIf { it.inputIdentityOrigin == LocalInputIdentityOrigin.SELECTED }
        ?: return null
    val recipient = body.recipients.singleOrNull { it.actorId.value == selected.text && it.actorId != actorId } ?: return null
    return foodRecipientForm(source, recipient)
}

/** The explicit menu choice contributes these input selectors, not captured names/output data. */
private fun LocalDraftLine.foodSelectionInput(recipe: LocalFoodUiRecipe, choice: LocalFoodUiChoice): LocalDraftLine = copy(values = values.mapValues { (field, input) ->
    if (field in setOf(LocalField.RECIPE_ID, LocalField.CHOICE_ID))
        input.copy(inputIdentityOrigin = LocalInputIdentityOrigin.SELECTED) else input
}, foodMenuSelection = LocalFoodMenuSelection(recipe.outputSkuId, choice.modifierIds.toList(), recipe.portionQuantity))

private fun foodSelectionLine(lineId: DomainId, recipe: LocalFoodUiRecipe, choice: LocalFoodUiChoice): LocalInputLine {
    require(recipe.active && choice in recipe.choices)
    return LocalInputLine(lineId, listOf(
        LocalScalarInput(LocalField.RECIPE_ID, LocalScalarValue.Identifier(recipe.recipeId)),
        LocalScalarInput(LocalField.EXPECTED_FOOD_RECIPE_REVISION, LocalScalarValue.Integer(recipe.recipeRevision.value)),
        LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(recipe.outputSkuId)),
        LocalScalarInput(LocalField.CHOICE_ID, LocalScalarValue.Identifier(choice.choiceId)),
        LocalScalarInput(LocalField.MODIFIER_IDS, LocalScalarValue.Text(choice.modifierIds.joinToString(",") { it.value })),
        LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(recipe.portionQuantity.unitId)),
        LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(recipe.portionQuantity.scale.toLong())),
        LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(foodFixedDecimal(recipe.portionQuantity.scaled, recipe.portionQuantity.scale))),
        LocalScalarInput(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(1)),
        LocalScalarInput(LocalField.NAME_ENGLISH, LocalScalarValue.Text(choice.names.english)),
        LocalScalarInput(LocalField.NAME_ARABIC, LocalScalarValue.Text(choice.names.arabic)),
        LocalScalarInput(LocalField.ITEM_NOTE, LocalScalarValue.Text("")),
        LocalScalarInput(LocalField.LINE_SEAT_NOTE, LocalScalarValue.Text("")),
    ))
}

private fun foodSelectionGroup(initialLines: List<LocalInputLine>): LocalFormField.Lines =
    LocalFormField.Lines(LocalField.LINES, LocalText("Commercial lines (maximum 8 configured servings total)", "السطور التجارية (بحد أقصى ٨ حصص مُعدّة إجمالًا)"),
        foodSelectionColumns(), minimumLines = 1, maximumLines = 8, initialLines = initialLines,
        allowBlankLines = false, allowDuplicateLines = true)

private fun foodSelectionColumns(): List<LocalFormField.Scalar> {
    fun column(field: LocalField, en: String, ar: String, kind: LocalInputKind, maximum: Int,
        required: Boolean = true, readOnly: Boolean = true) = LocalFormField.Scalar(field, LocalText(en, ar), kind,
        required = required, maximumCharacters = maximum, readOnly = readOnly)
    return listOf(
        column(LocalField.RECIPE_ID, "Recipe ID", "معرف الوصفة", LocalInputKind.IDENTIFIER, 36),
        column(LocalField.EXPECTED_FOOD_RECIPE_REVISION, "Signed recipe revision", "مراجعة الوصفة الموقعة", LocalInputKind.INTEGER, 19),
        column(LocalField.SKU_ID, "Configured output SKU", "الصنف الناتج المُعدّ", LocalInputKind.IDENTIFIER, 36),
        column(LocalField.CHOICE_ID, "Complete configured choice ID", "معرف الاختيار المُعدّ الكامل", LocalInputKind.IDENTIFIER, 36),
        column(LocalField.MODIFIER_IDS, "Exact configured modifiers", "الإضافات المُعدّة بدقة", LocalInputKind.TEXT, 591, required = false),
        column(LocalField.UNIT, "Exact configured unit ID", "معرف الوحدة المُعدّة بدقة", LocalInputKind.IDENTIFIER, 36),
        column(LocalField.QUANTITY_SCALE, "Quantity precision", "دقة الكمية", LocalInputKind.INTEGER, 1),
        column(LocalField.QUANTITY, "One configured serving quantity", "كمية الحصة الواحدة المُعدّة", LocalInputKind.DECIMAL, 32),
        column(LocalField.FOOD_SERVING_COUNT, "Number of configured servings (1–8)", "عدد الحصص المُعدّة (١–٨)", LocalInputKind.INTEGER, 1,
            readOnly = false),
        column(LocalField.NAME_ENGLISH, "Captured English choice name", "اسم الاختيار بالإنجليزية عند الاختيار", LocalInputKind.TEXT, 160, required = false),
        column(LocalField.NAME_ARABIC, "Captured Arabic choice name", "اسم الاختيار بالعربية عند الاختيار", LocalInputKind.TEXT, 160, required = false),
        column(LocalField.ITEM_NOTE, "Serving note (not a recipe change)", "ملاحظة الحصة (ليست تغييرًا للوصفة)", LocalInputKind.TEXT, 240,
            required = false, readOnly = false),
        column(LocalField.LINE_SEAT_NOTE, "Line seat note (separate from check note)", "ملاحظة مقعد السطر (منفصلة عن ملاحظة الطلب)", LocalInputKind.TEXT, 160,
            required = false, readOnly = false),
    )
}

/** Keep the original serving anchor and allocate additional IDs only on an explicit edit. */
internal fun LocalDraftLine.withFoodServingCount(value: LocalDraftValue): LocalDraftLine {
    val count = value.foodServingCount()
    val ids = if (foodPortionIds.isEmpty()) listOf(lineId) else foodPortionIds
    val retained = if (count == null || count <= ids.size) ids else
        ids + List(count - ids.size) { DomainId.random() }
    return copy(values = values + (LocalField.FOOD_SERVING_COUNT to value), foodPortionIds = retained)
}

/** Additional IDs are included in the immutable input digest, not regenerated while staging. */
internal fun LocalDraftLine.foodPortionIdentityInput(): LocalScalarInput? {
    val count = values[LocalField.FOOD_SERVING_COUNT]?.foodServingCount() ?: return null
    if (count == 1) return null
    require(count in 2..8 && foodPortionIds.size in count..8 && foodPortionIds.first() == lineId &&
        foodPortionIds.distinct().size == foodPortionIds.size)
    return LocalScalarInput(LocalField.FOOD_PORTION_IDS,
        LocalScalarValue.Text(foodPortionIds.take(count).joinToString(",") { it.value }))
}

internal fun LocalFormDraft.foodServingCount(): Int? {
    val counts = lines.map { it.values[LocalField.FOOD_SERVING_COUNT]?.foodServingCount() ?: return null }
    if (counts.any { it !in 1..8 }) return null
    return counts.sum()
}

/** Use the same Arabic/Persian numeric normalization as ordinary form parsing. */
internal fun LocalDraftValue.foodServingCount(): Int? = normalizeLocalNumber(text)
    .singleOrNull()?.takeIf { it in '1'..'8' }?.let { it - '0' }

internal fun foodFixedDecimal(scaled: Long, scale: Int): String {
    val signed = scaled.toString()
    val digits = signed.removePrefix("-").padStart(scale + 1, '0')
    return (if (signed.startsWith('-')) "-" else "") + if (scale == 0) digits else digits.dropLast(scale) + "." + digits.takeLast(scale)
}
