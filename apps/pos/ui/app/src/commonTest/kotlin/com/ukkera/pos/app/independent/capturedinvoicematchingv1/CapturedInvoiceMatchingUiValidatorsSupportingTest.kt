package com.ukkera.pos.app.independent.capturedinvoicematchingv1

import com.ukkera.pos.app.*
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Structural UI refusal gates for CCM v1.0.1 and binding v1.0.0; no source or Master authority. */
class CapturedInvoiceMatchingUiValidatorsSupportingTest {
    /** CCM-05/11 supporting: root invoice/PO selection cannot adopt a nested or revised coordinate. */
    @Test
    fun ccm05Ccm11InvoiceAndPoSelectionsRemainExactRoots() {
        assertTrue(matchingInvoiceAReference.isInvoiceUiSelection(LocalPurchasingSection.INVOICES))
        assertTrue(matchingOrderReference.isInvoiceUiSelection(LocalPurchasingSection.ORDERS))
        assertFalse(matchingOrderReference.isInvoiceUiSelection(LocalPurchasingSection.INVOICES))
        assertFalse(matchingInvoiceAReference.isInvoiceUiSelection(LocalPurchasingSection.ORDERS))
        for (reference in listOf(matchingLineReference(),
            matchingInvoiceAReference.copy(revision = Revision(1)),
            matchingInvoiceAReference.copy(parentId = matchingOrder)))
            assertFalse(reference.isInvoiceUiSelection(LocalPurchasingSection.INVOICES), reference.toString())
        for (section in LocalPurchasingSection.entries.filterNot {
            it in listOf(LocalPurchasingSection.INVOICES, LocalPurchasingSection.ORDERS)
        }) assertFalse(matchingInvoiceAReference.isInvoiceUiSelection(section), section.name)
    }

    /** CCM-05/08/11 supporting: the public scalar form admits only invoice-local choices, without discovery. */
    @Test
    fun ccm05Ccm08Ccm11FormRejectsForeignChoicesDiscoveryAndOtherActions() {
        val valid = matchingForm()
        val line = valid.fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == LocalField.INVOICE_LINE_ID }
        assertTrue(valid.isInvoiceUiForm(matchingAction, matchingInvoiceAReference))
        assertTrue(withLine(valid, line.copy(kind = LocalInputKind.MULTI_REFERENCE,
            initialValue = LocalScalarValue.References(listOf(matchingLineReference()))))
            .isInvoiceUiForm(matchingAction, matchingInvoiceAReference))
        val foreignReferences = listOf(matchingLineReference(matchingInvoiceBReference),
            matchingLineReference().copy(parentId = null),
            matchingLineReference().copy(revision = Revision(1)),
            matchingLineReference().copy(kind = LocalRecordKind.RECEIPT_LINE), matchingInvoiceAReference)
        for (reference in foreignReferences) {
            assertFalse(withLine(valid, line.copy(references = listOf(LocalReferenceChoice(reference, LocalText("Foreign", "")))))
                .isInvoiceUiForm(matchingAction, matchingInvoiceAReference), "choice $reference")
            assertFalse(withLine(valid, line.copy(initialValue = LocalScalarValue.Reference(reference)))
                .isInvoiceUiForm(matchingAction, matchingInvoiceAReference), "initial $reference")
            assertFalse(withLine(valid, line.copy(initialValue = LocalScalarValue.References(listOf(matchingLineReference(), reference))))
                .isInvoiceUiForm(matchingAction, matchingInvoiceAReference), "multiple initial $reference")
        }
        assertFalse(withLine(valid, line.copy(referenceSource = LocalReferenceSource(LocalRecordKind.SUPPLIER)))
            .isInvoiceUiForm(matchingAction, matchingInvoiceAReference))
        assertFalse(valid.copy(version = 2).isInvoiceUiForm(matchingAction, matchingInvoiceAReference))
        assertFalse(valid.isInvoiceUiForm(matchingAction, null))
        assertFalse(valid.isInvoiceUiForm(matchingAction, matchingOrderReference))
        assertFalse(valid.copy(fields = listOf(LocalFormField.Lines(LocalField.LINES, LocalText("Lines", ""), listOf(line))))
            .isInvoiceUiForm(matchingAction, matchingInvoiceAReference))
        for (action in listOf(LocalAction.SUPPLIER_INVOICE_CAPTURE, LocalAction.SUPPLIER_INVOICE_MATCH,
            LocalAction.SUPPLIER_INVOICE_RECORD, LocalAction.SUPPLIER_CREATE))
            assertFalse(valid.copy(action = action).isInvoiceUiForm(matchingAction, matchingInvoiceAReference), action.name)
    }

    /** CCM-05/10/11 supporting: editable and retained input cannot introduce another invoice's line. */
    @Test
    fun ccm05Ccm10Ccm11DraftAndRetainedInputRejectForeignLineAndLineGroups() {
        val valid = localFormDraft(matchingForm())
        val input = valid.inputContinuation()
        assertTrue(valid.isInvoiceUiDraft(matchingAction, matchingInvoiceAReference))
        assertTrue(input.isInvoiceUiInput(matchingAction, matchingInvoiceAReference))
        val foreign = matchingLineReference(matchingInvoiceBReference)
        for (selected in listOf(LocalScalarValue.Reference(foreign),
            LocalScalarValue.References(listOf(matchingLineReference(), foreign)))) {
            val values = valid.values + (LocalField.INVOICE_LINE_ID to LocalDraftValue(selected = selected))
            assertFalse(valid.copy(values = values).isInvoiceUiDraft(matchingAction, matchingInvoiceAReference))
            assertFalse(input.copy(values = values).isInvoiceUiInput(matchingAction, matchingInvoiceAReference))
        }
        val extraLine = LocalDraftLine(matchingLineA, emptyMap())
        assertFalse(valid.copy(lines = listOf(extraLine)).isInvoiceUiDraft(matchingAction, matchingInvoiceAReference))
        assertFalse(input.copy(lineField = LocalField.LINES).isInvoiceUiInput(matchingAction, matchingInvoiceAReference))
        assertFalse(input.copy(columns = input.scalars).isInvoiceUiInput(matchingAction, matchingInvoiceAReference))
        assertFalse(input.copy(sourceLineIds = setOf(matchingLineA)).isInvoiceUiInput(matchingAction, matchingInvoiceAReference))
        assertFalse(input.copy(lines = listOf(extraLine)).isInvoiceUiInput(matchingAction, matchingInvoiceAReference))
        assertFalse(input.copy(version = 2).isInvoiceUiInput(matchingAction, matchingInvoiceAReference))
        assertFalse(input.isInvoiceUiInput(matchingAction, null))
    }

    /** CCM-05/10/11 supporting: frozen UI input retains the selected invoice and scalar line shape. */
    @Test
    fun ccm05Ccm10Ccm11FrozenIntentRejectsForeignReferencesAndLineGroups() {
        val operation = OperationId("71000000-0000-4000-8000-000000000201")
        fun intent(reference: LocalRecordReference? = matchingInvoiceAReference,
            version: Int = 1, action: LocalAction = matchingAction,
            selected: LocalScalarValue = LocalScalarValue.Reference(matchingLineReference()),
            lineField: LocalField? = null, lines: List<LocalInputLine> = emptyList()) =
            LocalCommandIntent(operation, action, version, reference,
                listOf(LocalScalarInput(LocalField.INVOICE_LINE_ID, selected),
                    LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal("22"))), lineField, lines)
        assertTrue(intent().isInvoiceUiIntent(matchingAction))
        assertTrue(intent(selected = LocalScalarValue.References(listOf(matchingLineReference()))).isInvoiceUiIntent(matchingAction))
        for (reference in listOf(null, matchingOrderReference,
            matchingInvoiceAReference.copy(revision = Revision(1)),
            matchingInvoiceAReference.copy(parentId = matchingOrder)))
            assertFalse(intent(reference = reference).isInvoiceUiIntent(matchingAction), "$reference")
        val foreign = matchingLineReference(matchingInvoiceBReference)
        assertFalse(intent(selected = LocalScalarValue.Reference(foreign)).isInvoiceUiIntent(matchingAction))
        assertFalse(intent(selected = LocalScalarValue.References(listOf(matchingLineReference(), foreign))).isInvoiceUiIntent(matchingAction))
        assertFalse(intent(version = 2).isInvoiceUiIntent(matchingAction))
        assertFalse(intent(action = LocalAction.SUPPLIER_INVOICE_CAPTURE).isInvoiceUiIntent(matchingAction))
        assertFalse(intent(lineField = LocalField.LINES).isInvoiceUiIntent(matchingAction))
        assertFalse(intent(lineField = LocalField.LINES, lines = listOf(LocalInputLine(matchingLineA,
            listOf(LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal("22")))))).isInvoiceUiIntent(matchingAction))
    }

    private fun withLine(form: LocalActionForm, line: LocalFormField.Scalar): LocalActionForm =
        form.copy(fields = form.fields.map { if (it.field == LocalField.INVOICE_LINE_ID) line else it })
}
