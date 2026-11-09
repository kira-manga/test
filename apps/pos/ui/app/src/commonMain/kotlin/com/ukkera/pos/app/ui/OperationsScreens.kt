package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.EntryPresentation
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.training.TrainingWorkspace
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
internal fun LiveUnavailableScreen(area: AppArea) {
    Page {
        SectionHeading(areaTitle(area))
        StatusPill(copy(Copy.INTEGRATION_REQUIRED), caution = true)
        Notice(copy(Copy.LIVE_FEATURE_NOTICE))
        Text(copy(Copy.NO_LIVE_PAYMENT), style = MaterialTheme.typography.bodyLarge)
    }
}

private val exampleOrders = listOf(
    DomainId("00000000-0000-4000-8000-000000000101"),
    DomainId("00000000-0000-4000-8000-000000000102"),
    DomainId("00000000-0000-4000-8000-000000000103"),
)

@Composable
internal fun OrdersScreen(open: (DomainId) -> Unit) {
    val language = LocalAppLanguage.current
    Page {
        SectionHeading(copy(Copy.ORDERS_TITLE), copy(Copy.ORDERS_HELP))
        StatusPill(copy(Copy.SAMPLE_OVERVIEW), caution = true)
        exampleOrders.forEachIndexed { index, id ->
            Surface(onClick = { open(id) }, modifier = Modifier.visibleKeyboardFocus(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PosIcon(Glyph.ORDER)
                        Text(copy(Copy.EXAMPLE_ORDER, language.digits((index + 1).toString().padStart(3, '0'))), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(copy(listOf(Copy.COUNTER_SALE, Copy.TABLE_ORDER, Copy.DELIVERY_ORDER)[index]), style = MaterialTheme.typography.bodyMedium, color = Muted)
                    StatusPill(copy(listOf(Copy.DRAFT_STATUS, Copy.WAITING_STATUS, Copy.OUTCOME_STATUS)[index]), caution = index != 0)
                }
            }
        }
        Notice(copy(Copy.RECOVER_BEFORE_RETRY), caution = true)
    }
}

@Composable
internal fun OrderDetailScreen(id: DomainId) {
    val index = exampleOrders.indexOf(id)
    if (index < 0) { LiveUnavailableScreen(AppArea.ORDERS); return }
    val language = LocalAppLanguage.current
    Page {
        SectionHeading(copy(Copy.EXAMPLE_ORDER, language.digits((index + 1).toString().padStart(3, '0'))), copy(Copy.ORDERS_HELP))
        StatusPill(copy(listOf(Copy.DRAFT_STATUS, Copy.WAITING_STATUS, Copy.OUTCOME_STATUS)[index]), caution = true)
        Notice(copy(Copy.QUOTE_NOT_RECEIPT))
        Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                TrainingWorkspace.products.take(2).forEach { product -> TotalRow(language.text(product.name), language.money(product.unitPrice)) }
                HorizontalDivider(color = Outline)
                TotalRow(copy(Copy.TOTAL), language.money(Money(Currency.EGP, 12050)), prominent = true)
            }
        }
        Notice(copy(Copy.RECOVER_BEFORE_RETRY), caution = true)
        Button(onClick = {}, enabled = false, modifier = Modifier.touchTarget()) { Text(copy(Copy.CHECK_OUTCOME)) }
    }
}

@Composable
internal fun DrawerScreen(state: EntryPresentation, change: ((EntryPresentation) -> EntryPresentation) -> Unit) {
    val language = LocalAppLanguage.current
    val count = parseTrainingCash(state.countedCash)
    Page {
        SectionHeading(copy(Copy.DRAWER_TITLE), copy(Copy.DRAWER_HELP))
        StatusPill(copy(Copy.INTERACTIVE_TRAINING), caution = true)
        Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                TotalRow(copy(Copy.OPENING_FLOAT), language.money(Money(Currency.EGP, 50000)))
                TotalRow(copy(Copy.CASH_RECEIVED), language.money(Money(Currency.EGP, 124000)))
                TotalRow(copy(Copy.CASH_PAID_OUT), language.money(Money(Currency.EGP, 15000)))
                HorizontalDivider(color = Outline)
                TotalRow(copy(Copy.EXPECTED_CASH), language.money(Money(Currency.EGP, 159000)), prominent = true)
                OutlinedTextField(value = state.countedCash, onValueChange = { input -> change { it.copy(countedCash = input.take(16)) } },
                    modifier = Modifier.fillMaxWidth(), label = { Text(copy(Copy.COUNTED_CASH)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    isError = state.countedCash.isNotBlank() && count == null,
                    supportingText = { Text(copy(Copy.COUNT_HELP)) })
                if (count != null) TotalRow(copy(Copy.VARIANCE), language.money(Money(Currency.EGP, count - 159000)), prominent = true)
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().touchTarget()) { Text(copy(Copy.SUBMIT_CLOSE)) }
                Text(copy(Copy.LIVE_FEATURE_NOTICE), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
internal fun StockScreen(training: TrainingWorkspace, state: EntryPresentation, change: ((EntryPresentation) -> EntryPresentation) -> Unit) {
    val language = LocalAppLanguage.current
    val products = training.search(state.query, null)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 1040.dp).fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { SectionHeading(copy(Copy.STOCK_TITLE), copy(Copy.STOCK_HELP)) }
            item { OutlinedTextField(value = state.query, onValueChange = { input -> change { it.copy(query = input.take(80)) } },
                modifier = Modifier.fillMaxWidth(), label = { Text(copy(Copy.SEARCH)) }, singleLine = true, leadingIcon = { PosIcon(Glyph.SEARCH) }) }
            if (products.isEmpty()) item { Text(copy(Copy.NO_PRODUCTS), color = Muted) }
            items(products, key = { it.id.value }) { product ->
                Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(language.text(product.name), style = MaterialTheme.typography.titleMedium)
                        Text(language.text(product.detail), color = Muted, style = MaterialTheme.typography.bodyMedium)
                        Text(copy(Copy.BARCODE) + ": \u2066${product.barcode}\u2069", style = MaterialTheme.typography.bodySmall, color = Muted)
                        TotalRow(copy(Copy.AVAILABLE, language.digits(product.available.toString())), language.money(product.unitPrice))
                    }
                }
            }
            item { Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().touchTarget()) { Text(copy(Copy.COUNT_STOCK)) } }
        }
    }
}

@Composable
internal fun ReportsScreen() {
    Page {
        SectionHeading(copy(Copy.REPORTS_TITLE), copy(Copy.REPORTS_HELP))
        listOf(Copy.SALES_REPORT to Copy.SALES_REPORT_BODY, Copy.CASH_REPORT to Copy.CASH_REPORT_BODY,
            Copy.STOCK_REPORT to Copy.STOCK_REPORT_BODY, Copy.COMPANY_REPORT to Copy.COMPANY_REPORT_BODY).forEach { (title, body) ->
            CapabilityCard(title, body, Glyph.CHART)
        }
        Button(onClick = {}, enabled = false, modifier = Modifier.touchTarget()) { Text(copy(Copy.EXPORT_REPORT)) }
    }
}

private val setupTitles = listOf(Copy.SETUP_0, Copy.SETUP_1, Copy.SETUP_2, Copy.SETUP_3, Copy.SETUP_4, Copy.SETUP_5, Copy.SETUP_6, Copy.SETUP_7)
private val setupBodies = listOf(Copy.SETUP_0_BODY, Copy.SETUP_1_BODY, Copy.SETUP_2_BODY, Copy.SETUP_3_BODY, Copy.SETUP_4_BODY, Copy.SETUP_5_BODY, Copy.SETUP_6_BODY, Copy.SETUP_7_BODY)

@Composable
internal fun SetupScreen(platform: PlatformCapabilities, open: (Int) -> Unit) {
    val language = LocalAppLanguage.current
    Page {
        SectionHeading(copy(Copy.SETUP_TITLE), copy(Copy.SETUP_HELP))
        Notice(copy(if (platform.family == PlatformFamily.IOS) Copy.IOS_HOST_NOTICE else Copy.PLATFORM_NOTICE), caution = true)
        setupTitles.forEachIndexed { index, title ->
            Surface(onClick = { open(index) }, modifier = Modifier.visibleKeyboardFocus(), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Mint, shape = RoundedCornerShape(10.dp)) {
                        Text(language.digits((index + 1).toString().padStart(2, '0')), Modifier.padding(12.dp), color = Forest, style = MaterialTheme.typography.labelLarge)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(copy(title), style = MaterialTheme.typography.titleMedium)
                        Text(copy(Copy.INTEGRATION_REQUIRED), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    PosIcon(Glyph.INFO)
                }
            }
        }
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().touchTarget()) { Text(copy(Copy.GO_LIVE)) }
    }
}

@Composable
internal fun SetupStepScreen(step: Int, training: Boolean, state: EntryPresentation, change: ((EntryPresentation) -> EntryPresentation) -> Unit) {
    Page {
        SectionHeading(copy(setupTitles[step]))
        StatusPill(copy(if (training) Copy.TRAINING else Copy.INTEGRATION_REQUIRED), caution = true)
        Text(copy(setupBodies[step]), style = MaterialTheme.typography.bodyLarge)
        if (step == 2) Notice(copy(Copy.IOS_HOST_NOTICE), caution = true)
        Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val label = copy(Copy.REHEARSAL_REVIEWED)
                Checkbox(checked = step in state.rehearsalChecks, enabled = training,
                    onCheckedChange = { checked -> change { it.copy(rehearsalChecks = if (checked) it.rehearsalChecks + step else it.rehearsalChecks - step) } },
                    modifier = Modifier.touchTarget().semantics { contentDescription = label })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Notice(copy(Copy.SETUP_HELP))
        Button(onClick = {}, enabled = false, modifier = Modifier.touchTarget()) { Text(copy(Copy.GO_LIVE)) }
    }
}

@Composable
internal fun CompanyScreen(area: AppArea, training: TrainingWorkspace?) {
    val language = LocalAppLanguage.current
    val body = when (area) {
        AppArea.CATALOG -> Copy.CATALOG_BODY
        AppArea.PEOPLE -> Copy.PEOPLE_BODY
        AppArea.PURCHASING -> Copy.PURCHASING_BODY
        AppArea.DEVICES -> Copy.DEVICES_BODY
        else -> Copy.COMPANY_SETTINGS_BODY
    }
    Page {
        SectionHeading(areaTitle(area), copy(Copy.COMPANY_TITLE))
        Notice(copy(if (training != null) Copy.COMPANY_PREVIEW_NOTICE else Copy.LIVE_FEATURE_NOTICE))
        Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                PosIcon(areaGlyph(area))
                Text(copy(body), style = MaterialTheme.typography.bodyLarge)
                StatusPill(copy(Copy.INTEGRATION_REQUIRED), caution = true)
                Button(onClick = {}, enabled = false, modifier = Modifier.touchTarget()) { Text(copy(if (area == AppArea.CATALOG) Copy.PUBLISH_CATALOG else Copy.EDIT_COMPANY)) }
            }
        }
        if (area == AppArea.CATALOG && training != null) {
            StatusPill(copy(Copy.SAMPLE_OVERVIEW), caution = true)
            TrainingWorkspace.products.take(4).forEach { product ->
                Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
                    Column(Modifier.padding(18.dp)) {
                        TotalRow(language.text(product.name), language.money(product.unitPrice))
                        Text(language.text(product.detail), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
        CapabilityCard(Copy.PENDING_ACTIVATION, Copy.PENDING_ACTIVATION_BODY, Glyph.INFO)
    }
}

@Composable
private fun CapabilityCard(title: Copy, body: Copy, glyph: Glyph) {
    Surface(shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PosIcon(glyph)
                Text(copy(title), style = MaterialTheme.typography.titleMedium)
            }
            Text(copy(body), style = MaterialTheme.typography.bodyMedium, color = Muted)
            StatusPill(copy(Copy.INTEGRATION_REQUIRED), caution = true)
        }
    }
}
