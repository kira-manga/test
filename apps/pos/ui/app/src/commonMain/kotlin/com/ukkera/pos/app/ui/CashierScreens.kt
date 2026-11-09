package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.EntryPresentation
import com.ukkera.pos.app.training.TrainingBasket
import com.ukkera.pos.app.training.TrainingCategory
import com.ukkera.pos.app.training.TrainingLine
import com.ukkera.pos.app.training.TrainingProduct
import com.ukkera.pos.app.training.TrainingWorkspace
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import kotlinx.coroutines.launch

@Composable
internal fun CashierScreen(training: TrainingWorkspace, state: EntryPresentation,
    change: ((EntryPresentation) -> EntryPresentation) -> Unit, review: () -> Unit) {
    val basket by training.basket.collectAsState()
    val searchFocus = remember { FocusRequester() }
    val catalogScroll = rememberLazyListState()
    val screenScope = rememberCoroutineScope()
    var consumedQuery: String? by remember { mutableStateOf(null) }
    val scan: () -> Unit = {
        if (state.query.isNotBlank() && consumedQuery != state.query) {
            consumedQuery = state.query
            val normalized = TrainingWorkspace.normalizeSearch(state.query)
            val product = TrainingWorkspace.products.firstOrNull { it.barcode == normalized }
            val added = product != null && training.add(product.id)
            change { it.copy(query = if (added) "" else it.query, notice = !added, quantityLimit = !added && product != null) }
        }
    }
    val queryChanged: (String) -> Unit = { input ->
        consumedQuery = null
        change { it.copy(query = input.take(80), notice = false, quantityLimit = false) }
    }
    val categoryChanged: (TrainingCategory?) -> Unit = { category -> change { it.copy(category = category) } }
    val add: (TrainingProduct) -> Unit = { product ->
        val added = training.add(product.id)
        change { it.copy(notice = !added, quantityLimit = !added) }
    }
    val products = training.search(state.query, state.category)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth.value / LocalDensity.current.fontScale >= 850
        val screenModifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.K && (event.isCtrlPressed || event.isMetaPressed)) {
                screenScope.launch {
                    // The compact field can be disposed by the lazy list. scrollToItem remeasures
                    // and composes it before focus is requested; no delay or detached target.
                    if (!split) catalogScroll.scrollToItem(1) // heading, then search
                    searchFocus.requestFocus()
                }
                true
            } else false
        }
        if (!split) {
            // Phone / enlarged text: search, filters and items scroll together. Never squeeze a
            // weighted grid to zero below fixed chrome; the total action stays reachable.
            Column(screenModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = catalogScroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "heading") { SectionHeading(copy(Copy.SELL_TITLE)) }
                    item(key = "search") { CatalogSearch(state.query, queryChanged, searchFocus, scan) }
                    item(key = "categories") { CategoryChoices(state.category, categoryChanged) }
                    if (state.notice) item(key = "notice") { CatalogNotice(state.quantityLimit) }
                    if (products.isEmpty()) item(key = "empty") { Text(copy(Copy.NO_PRODUCTS), color = Muted) }
                    items(products, key = { it.id.value }) { product -> ProductRow(product) { add(product) } }
                }
                CompactBasketSummary(basket, review)
            }
        } else {
            Column(screenModifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionHeading(copy(Copy.SELL_TITLE), copy(Copy.SELL_SUBTITLE))
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CatalogSearch(state.query, queryChanged, searchFocus, scan)
                        Text(copy(Copy.SEARCH_HELP), style = MaterialTheme.typography.bodySmall, color = Muted)
                        CategoryChoices(state.category, categoryChanged)
                        if (state.notice) CatalogNotice(state.quantityLimit)
                        if (products.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(copy(Copy.NO_PRODUCTS), color = Muted) }
                        } else {
                            LazyVerticalGrid(columns = GridCells.Adaptive((160 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp),
                                modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(products, key = { it.id.value }) { product -> ProductTile(product) { add(product) } }
                            }
                        }
                    }
                    BasketPanel(training, basket, Modifier.width(340.dp).fillMaxHeight(), review)
                }
            }
        }
    }
}

@Composable
private fun CatalogSearch(query: String, change: (String) -> Unit, searchFocus: FocusRequester, scan: () -> Unit) {
    var enterHeld by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = query, onValueChange = change, label = { Text(copy(Copy.SEARCH)) }, leadingIcon = { PosIcon(Glyph.SEARCH) },
        singleLine = true, shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { scan() }),
        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).onPreviewKeyEvent { event ->
            if (event.key != Key.Enter && event.key != Key.NumPadEnter) false
            else {
                if (event.type == KeyEventType.KeyUp) enterHeld = false
                else if (!enterHeld) { enterHeld = true; scan() }
                true
            }
        },
    )
}

@Composable
private fun CategoryChoices(selected: TrainingCategory?, change: (TrainingCategory?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = selected == null, onClick = { change(null) }, label = { Text(copy(Copy.ALL_PRODUCTS)) }, modifier = Modifier.touchTarget()) }
        items(TrainingCategory.entries) { category ->
            FilterChip(selected = selected == category, onClick = { change(category) },
                label = { Text(categoryTitle(category)) }, modifier = Modifier.touchTarget())
        }
    }
}

@Composable
private fun CatalogNotice(quantityLimit: Boolean) {
    Text(copy(if (quantityLimit) Copy.STOCK_LIMIT else Copy.UNKNOWN_BARCODE), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CompactBasketSummary(basket: TrainingBasket, review: () -> Unit) {
    val language = LocalAppLanguage.current
    val countLabel = basketItemCount(basket.count)
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(language.money(basket.total), style = MaterialTheme.typography.titleMedium)
            Button(onClick = review, enabled = basket.lines.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().touchTarget().semantics { contentDescription = countLabel }) {
                Text(copy(Copy.REVIEW_QUOTE))
            }
        }
    }
}

@Composable
private fun ProductRow(product: TrainingProduct, add: () -> Unit) {
    val language = LocalAppLanguage.current
    val addLabel = copy(Copy.ADD_ITEM, language.text(product.name))
    Surface(onClick = add, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline),
        modifier = Modifier.fillMaxWidth().visibleKeyboardFocus().semantics { contentDescription = addLabel }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(language.text(product.name), style = MaterialTheme.typography.titleMedium)
            Text(language.text(product.detail), style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.money(product.unitPrice), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                PosIcon(Glyph.PLUS, tint = MaterialTheme.colorScheme.primary)
            }
            Text(copy(Copy.AVAILABLE, language.digits(product.available.toString())), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
internal fun categoryTitle(category: TrainingCategory): String = copy(when (category) {
    TrainingCategory.GROCERY -> Copy.GROCERY
    TrainingCategory.BAKERY -> Copy.BAKERY
    TrainingCategory.CAFE -> Copy.CAFE
    TrainingCategory.KITCHEN -> Copy.KITCHEN
    TrainingCategory.CLOTHING -> Copy.CLOTHING
})

@Composable
private fun ProductTile(product: TrainingProduct, add: () -> Unit) {
    val language = LocalAppLanguage.current
    val name = language.text(product.name)
    val addLabel = copy(Copy.ADD_ITEM, name)
    Surface(onClick = add, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Outline),
        modifier = Modifier.fillMaxWidth().visibleKeyboardFocus().semantics { contentDescription = addLabel }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(color = when (product.category) {
                    TrainingCategory.GROCERY -> Mint
                    TrainingCategory.BAKERY -> Color(0xFFF5ECDC)
                    TrainingCategory.CAFE -> Color(0xFFEDE6DD)
                    TrainingCategory.KITCHEN -> Color(0xFFE8EBDD)
                    TrainingCategory.CLOTHING -> Color(0xFFE8E8F3)
                }, shape = RoundedCornerShape(12.dp)) {
                    PosIcon(if (product.category == TrainingCategory.CLOTHING) Glyph.BAG else Glyph.BOX, modifier = Modifier.padding(12.dp), tint = Forest)
                }
                PosIcon(Glyph.PLUS, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(language.text(product.detail), style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(language.money(product.unitPrice), style = MaterialTheme.typography.labelLarge)
            Text(copy(Copy.AVAILABLE, language.digits(product.available.toString())), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun BasketPanel(training: TrainingWorkspace, basket: TrainingBasket, modifier: Modifier, review: () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(copy(Copy.ORDER_BASKET), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(basketItemCount(basket.count), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = training::clear, enabled = basket.lines.isNotEmpty(), modifier = Modifier.touchTarget()) { Text(copy(Copy.CLEAR_BASKET)) }
            }
            HorizontalDivider(color = Outline)
            if (basket.lines.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    PosIcon(Glyph.BAG, modifier = Modifier.size(42.dp), tint = Muted)
                    Spacer(Modifier.height(16.dp))
                    Text(copy(Copy.EMPTY_BASKET), style = MaterialTheme.typography.titleMedium)
                    Text(copy(Copy.EMPTY_BASKET_HELP), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(basket.lines, key = { it.product.id.value }) { line -> BasketLine(line, { training.add(line.product.id) }, { training.removeOne(line.product.id) }) }
                }
            }
            BasketTotals(basket)
            Button(onClick = review, enabled = basket.lines.isNotEmpty(), modifier = Modifier.fillMaxWidth().touchTarget(), shape = RoundedCornerShape(12.dp)) { Text(copy(Copy.REVIEW_QUOTE)) }
            Text(copy(Copy.NO_TAX_POLICY), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun BasketLine(line: TrainingLine, increase: () -> Unit, decrease: () -> Unit) {
    val language = LocalAppLanguage.current
    val name = language.text(line.product.name)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(language.money(line.total), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = decrease, modifier = Modifier.touchTarget()) { PosIcon(Glyph.MINUS, copy(Copy.DECREASE_ITEM, name)) }
            Text(language.digits(line.quantity.toString()), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = increase, enabled = line.quantity < minOf(99, line.product.available), modifier = Modifier.touchTarget()) { PosIcon(Glyph.PLUS, copy(Copy.INCREASE_ITEM, name)) }
        }
        HorizontalDivider(color = Outline)
    }
}

@Composable
private fun BasketTotals(basket: TrainingBasket) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TotalRow(copy(Copy.SUBTOTAL), language.money(basket.total))
        TotalRow(copy(Copy.EXAMPLE_TAX), language.money(Money(Currency.EGP, 0)))
        HorizontalDivider(color = Outline)
        TotalRow(copy(Copy.TOTAL), language.money(basket.total), prominent = true)
    }
}

@Composable
internal fun TotalRow(label: String, value: String, prominent: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, color = if (prominent) Forest else Muted)
        Text(value, style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (prominent) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
internal fun QuoteScreen(training: TrainingWorkspace, back: () -> Unit) {
    val basket by training.basket.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 780.dp).fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { SectionHeading(copy(Copy.QUOTE_TITLE), copy(Copy.QUOTE_NOT_RECEIPT)) }
            item { Notice(copy(Copy.QUOTE_HELP), caution = true) }
            items(basket.lines, key = { it.product.id.value }) { line ->
                Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
                    Column(Modifier.padding(16.dp)) { BasketLine(line, { training.add(line.product.id) }, { training.removeOne(line.product.id) }) }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Outline)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        BasketTotals(basket)
                        Text(copy(Copy.NO_TAX_POLICY), style = MaterialTheme.typography.bodySmall, color = Muted)
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().touchTarget()) { Text(copy(Copy.LIVE_CHECKOUT_DISABLED)) }
                        Text(copy(Copy.NO_LIVE_PAYMENT), style = MaterialTheme.typography.bodyMedium, color = Muted)
                        OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth().touchTarget()) { Text(copy(Copy.CONTINUE_BASKET)) }
                    }
                }
            }
        }
    }
}
