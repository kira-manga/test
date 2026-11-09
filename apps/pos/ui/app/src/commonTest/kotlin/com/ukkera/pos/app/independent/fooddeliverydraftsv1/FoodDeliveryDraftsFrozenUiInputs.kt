package com.ukkera.pos.app.independent.fooddeliverydraftsv1

import com.ukkera.pos.app.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/**
 * Exact synthetic inputs from qa/pos/freezes/food-delivery-drafts-v1/fixtures.json.
 * FREEZE.md establishes food-delivery-drafts-v1, frozen 2026-10-31T00:22:51.968449+00:00.
 * Missing/null wire objects V01/V02 have no scalar editor representation here.
 * Lone-surrogate raw tokens are retained; their exact attempted UTF-16 code unit
 * is supplied to UI validation. Command JSON decoding is a separate layer.
 */
internal data class FdpUiMetadataVariant(
    val caseId: String,
    val expected: String,
    val texts: Map<LocalField, String>,
    val boundedField: LocalField? = null,
    val utf16CodeUnits: Int? = null,
    val exactRawJsonStringToken: String? = null,
    val rawTokenField: LocalField? = null,
) {
    val accepts: Boolean get() = expected != "REJECT"
}

internal object FdpUiFrozenInputs {
    val company = DomainId("d3000000-0000-4000-8000-000000000002")
    val branch = DomainId("d3000000-0000-4000-8000-000000000003")
    val owner = DomainId("d3000000-0000-4000-8000-000000000010")
    val restaurantOrder = DomainId("d3000000-0000-4000-8000-000000000100")
    val cafeOrder = DomainId("d3000000-0000-4000-8000-000000000101")
    val takeawayOrder = DomainId("d3000000-0000-4000-8000-000000000102")
    val dineInOrder = DomainId("d3000000-0000-4000-8000-000000000103")
    val foreignOrder = DomainId("d3000000-0000-4000-8000-000000000105")
    val createOperation = OperationId("d3000000-0000-4000-8000-000000000300")
    val firstPortion = DomainId("d3000000-0000-4000-8000-000000000110")
    val secondPortion = DomainId("d3000000-0000-4000-8000-000000000111")
    val servingUnit = DomainId("d3000000-0000-4000-8000-000000000058")
    const val reference = "DEL-SYN-030"
    const val pickupReference = "PU-SYN-030"
    const val tableReference = "TABLE-SYN-030"
    val deliveryFields = listOf(LocalField.FOOD_DELIVERY_RECIPIENT,
        LocalField.FOOD_DELIVERY_CONTACT, LocalField.FOOD_DELIVERY_ADDRESS)
    val baseTexts = mapOf(
        LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
        LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
        LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District",
    )
    val arabicTexts = mapOf(
        LocalField.FOOD_DELIVERY_RECIPIENT to "عميل توصيل تجريبي ٣٠",
        LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
        LocalField.FOOD_DELIVERY_ADDRESS to "٣٠ شارع تجريبي، منطقة اختبار محلية",
    )
    val replacementTexts = mapOf(
        LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Replacement Recipient",
        LocalField.FOOD_DELIVERY_CONTACT to "replacement-30@example.invalid",
        LocalField.FOOD_DELIVERY_ADDRESS to "Replacement Synthetic Address",
    )

    val metadataVariants = listOf(
        FdpUiMetadataVariant("FDP-D03-V03", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V04", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V05", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid"
        )),
        FdpUiMetadataVariant("FDP-D03-V06", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V07", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "   ",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V08", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V09", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "   ",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V10", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to ""
        )),
        FdpUiMetadataVariant("FDP-D03-V11", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "   "
        )),
        FdpUiMetadataVariant("FDP-D03-V12", "VALIDATES", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "R".repeat(160),
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), boundedField = LocalField.FOOD_DELIVERY_RECIPIENT, utf16CodeUnits = 160),
        FdpUiMetadataVariant("FDP-D03-V13", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "R".repeat(161),
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), boundedField = LocalField.FOOD_DELIVERY_RECIPIENT, utf16CodeUnits = 161),
        FdpUiMetadataVariant("FDP-D03-V14", "VALIDATES", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "C".repeat(160),
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), boundedField = LocalField.FOOD_DELIVERY_CONTACT, utf16CodeUnits = 160),
        FdpUiMetadataVariant("FDP-D03-V15", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "C".repeat(161),
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), boundedField = LocalField.FOOD_DELIVERY_CONTACT, utf16CodeUnits = 161),
        FdpUiMetadataVariant("FDP-D03-V16", "VALIDATES", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "A".repeat(600)
        ), boundedField = LocalField.FOOD_DELIVERY_ADDRESS, utf16CodeUnits = 600),
        FdpUiMetadataVariant("FDP-D03-V17", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "A".repeat(601)
        ), boundedField = LocalField.FOOD_DELIVERY_ADDRESS, utf16CodeUnits = 601),
        FdpUiMetadataVariant("FDP-D03-V18", "VALIDATES", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "🚀".repeat(80),
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), boundedField = LocalField.FOOD_DELIVERY_RECIPIENT, utf16CodeUnits = 160),
        FdpUiMetadataVariant("FDP-D03-V19", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "🚀".repeat(81),
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), boundedField = LocalField.FOOD_DELIVERY_RECIPIENT, utf16CodeUnits = 162),
        FdpUiMetadataVariant("FDP-D03-V20", "VALIDATES", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "🚀".repeat(80),
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), boundedField = LocalField.FOOD_DELIVERY_CONTACT, utf16CodeUnits = 160),
        FdpUiMetadataVariant("FDP-D03-V21", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "🚀".repeat(81),
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), boundedField = LocalField.FOOD_DELIVERY_CONTACT, utf16CodeUnits = 162),
        FdpUiMetadataVariant("FDP-D03-V22", "VALIDATES", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "🚀".repeat(300)
        ), boundedField = LocalField.FOOD_DELIVERY_ADDRESS, utf16CodeUnits = 600),
        FdpUiMetadataVariant("FDP-D03-V23", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "🚀".repeat(301)
        ), boundedField = LocalField.FOOD_DELIVERY_ADDRESS, utf16CodeUnits = 602),
        FdpUiMetadataVariant("FDP-D03-V24", "VALIDATES_AND_PRESERVES_EXACT_VALUE", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "عميل توصيل تجريبي ٣٠",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "٣٠ شارع تجريبي، منطقة اختبار محلية"
        )),
        FdpUiMetadataVariant("FDP-D03-V25", "VALIDATES_AND_PRESERVES_EXACT_VALUE", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "  Synthetic Delivery Recipient 30  ",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V26", "VALIDATES_AND_PRESERVES_EXACT_VALUE", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Café Recipient",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V27", "VALIDATES_AND_PRESERVES_EXACT_VALUE", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Café Recipient",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V28", "VALIDATES_AND_PRESERVES_EXACT_VALUE", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "A\u0085B",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        )),
        FdpUiMetadataVariant("FDP-D03-V29", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "\uD800",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), exactRawJsonStringToken = "\"\\ud800\"", rawTokenField = LocalField.FOOD_DELIVERY_RECIPIENT),
        FdpUiMetadataVariant("FDP-D03-V30", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "\uDC00",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), exactRawJsonStringToken = "\"\\udc00\"", rawTokenField = LocalField.FOOD_DELIVERY_RECIPIENT),
        FdpUiMetadataVariant("FDP-D03-V31", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "\uD800",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), exactRawJsonStringToken = "\"\\ud800\"", rawTokenField = LocalField.FOOD_DELIVERY_CONTACT),
        FdpUiMetadataVariant("FDP-D03-V32", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "\uDC00",
            LocalField.FOOD_DELIVERY_ADDRESS to "30 Synthetic Lane, Local Qualification District"
        ), exactRawJsonStringToken = "\"\\udc00\"", rawTokenField = LocalField.FOOD_DELIVERY_CONTACT),
        FdpUiMetadataVariant("FDP-D03-V33", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "\uD800"
        ), exactRawJsonStringToken = "\"\\ud800\"", rawTokenField = LocalField.FOOD_DELIVERY_ADDRESS),
        FdpUiMetadataVariant("FDP-D03-V34", "REJECT", mapOf(
            LocalField.FOOD_DELIVERY_RECIPIENT to "Synthetic Delivery Recipient 30",
            LocalField.FOOD_DELIVERY_CONTACT to "delivery-30@example.invalid",
            LocalField.FOOD_DELIVERY_ADDRESS to "\uDC00"
        ), exactRawJsonStringToken = "\"\\udc00\"", rawTokenField = LocalField.FOOD_DELIVERY_ADDRESS),
    )

    /** FDP-D03-C-{FIELD_UPPERCASE}-{FOUR_HEX_CODEPOINT}, exact A + code point + B. */
    fun asciiControlVariants(): List<FdpUiMetadataVariant> = deliveryFields.flatMap { field ->
        ((0x0000..0x001F).toList() + 0x007F).map { point ->
            val fieldName = when (field) {
                LocalField.FOOD_DELIVERY_RECIPIENT -> "RECIPIENT"
                LocalField.FOOD_DELIVERY_CONTACT -> "CONTACT"
                LocalField.FOOD_DELIVERY_ADDRESS -> "ADDRESS"
                else -> error("No frozen delivery field")
            }
            FdpUiMetadataVariant("FDP-D03-C-$fieldName-${point.toString(16).uppercase().padStart(4, '0')}",
                "REJECT", baseTexts + (field to "A${point.toChar()}B"))
        }
    }
}

internal fun fdpUiText(value: String) = LocalText(value, value)

/** Scripted current form input; it does not qualify a protected gateway descriptor. */
internal fun fdpUiDraftForm(
    texts: Map<LocalField, String> = FdpUiFrozenInputs.baseTexts,
    channel: String = "DELIVERY",
    reference: String? = FdpUiFrozenInputs.reference,
    activity: String = "RESTAURANT",
    orderId: DomainId = FdpUiFrozenInputs.restaurantOrder,
): LocalActionForm {
    fun scalar(field: LocalField, kind: LocalInputKind, required: Boolean = true,
        readOnly: Boolean = false, maximum: Int = 256, initial: LocalScalarValue? = null,
        choices: List<String> = emptyList()) = LocalFormField.Scalar(field, fdpUiText(field.name), kind,
        required = required, readOnly = readOnly, maximumCharacters = maximum, initialValue = initial,
        choices = choices.map { LocalChoice(it, fdpUiText(it)) })
    return LocalActionForm(LocalAction.FOOD_CREATE_DRAFT, 2, fdpUiText("Review DELIVERY draft"), listOf(
        scalar(LocalField.ORDER_ID, LocalInputKind.IDENTIFIER, readOnly = true, maximum = 36,
            initial = LocalScalarValue.Identifier(orderId)),
        scalar(LocalField.ACTIVITY, LocalInputKind.CHOICE, initial = LocalScalarValue.Choice(activity),
            choices = listOf("RESTAURANT", "CAFE")),
        scalar(LocalField.CHANNEL, LocalInputKind.CHOICE, initial = LocalScalarValue.Choice(channel),
            choices = listOf("DELIVERY", "TAKEAWAY", "DINE_IN")),
        scalar(LocalField.SERVICE_REFERENCE, LocalInputKind.TEXT, maximum = 80,
            initial = reference?.let { LocalScalarValue.Text(it) }),
        scalar(LocalField.SEAT_NOTE, LocalInputKind.TEXT, required = false, maximum = 160,
            initial = LocalScalarValue.Text("")),
        scalar(LocalField.FOOD_DELIVERY_RECIPIENT, LocalInputKind.TEXT, required = false, maximum = 160,
            initial = texts[LocalField.FOOD_DELIVERY_RECIPIENT]?.let { LocalScalarValue.Text(it) }),
        scalar(LocalField.FOOD_DELIVERY_CONTACT, LocalInputKind.TEXT, required = false, maximum = 160,
            initial = texts[LocalField.FOOD_DELIVERY_CONTACT]?.let { LocalScalarValue.Text(it) }),
        scalar(LocalField.FOOD_DELIVERY_ADDRESS, LocalInputKind.TEXT, required = false, maximum = 600,
            initial = texts[LocalField.FOOD_DELIVERY_ADDRESS]?.let { LocalScalarValue.Text(it) }),
    ), confirmation = fdpUiText("Review immutable draft request"),
        reservedOperationId = FdpUiFrozenInputs.createOperation)
}

/** Missing scalar values remain absent instead of being replaced with invented text. */
internal fun fdpUiDraft(input: FdpUiMetadataVariant): LocalFormDraft {
    val draft = localFormDraft(fdpUiDraftForm(input.texts))
    return draft.copy(values = draft.values.filterKeys {
        it !in FdpUiFrozenInputs.deliveryFields || it in input.texts
    })
}

internal fun fdpUiSummary(texts: Map<LocalField, String>): List<LocalRecordValue> =
    FdpUiFrozenInputs.deliveryFields.map { field ->
        LocalRecordValue(fdpUiText(field.name), fdpUiText(texts.getValue(field)))
    }
