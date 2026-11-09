package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity

/** Content in the existing recovery/form LazyColumn; no gateway, mutation or second lifecycle. */
internal fun LazyListScope.localFoodContent(state: LocalWorkspaceState, controller: LocalWorkflowController, token: NavigationToken) {
    item("food-navigation") { FoodNavigation(state, controller, token) }
    if (controller.foodCashFinancialOnly) item("food-financial-gate") {
        Notice(LocalAppLanguage.current.text(LocalText(
            "Financial accounting only: apply a genuine already retained payment original, not a new cash capture. Attempt reads are data, not physical instructions. Draft/Add, Send, pickup, claims, refunds and UNKNOWN resolution are unavailable here. Missing original evidence remains unavailable, not a paid or empty result.",
            "محاسبة مالية فقط: طبّق واقعة دفع أصلية فعلية محفوظة بالفعل، وليس تحصيلًا نقديًا جديدًا. قراءات المحاولة بيانات وليست تعليمات لإجراء فعلي. لا تتوفر هنا المسودة أو الإضافة أو الإرسال أو الاستلام أو المطالبات أو رد الأموال أو حسم UNKNOWN. يبقى الدليل الأصلي المفقود غير متاح، ولا يتحول إلى نتيجة مدفوعة أو فارغة.")), caution = true)
    }
    if (state.workflow == LocalWorkflow.FOOD && !controller.foodChecksOnly && !controller.foodCashFinancialOnly && !controller.foodTableAssignmentsOnly)
        item("food-financial-gate") { Notice(foodCopy(FoodCopy.PAYMENT_HELD), caution = true) }
    if (controller.foodTableAssignmentsOnly && state.foodQuery is LocalFoodUiQuery.Check &&
        state.readProblem in setOf(LocalServiceProblem.UNAVAILABLE, LocalServiceProblem.FOOD_TARGET_UNAVAILABLE))
        item("food-table-check-unavailable") { Notice(LocalAppLanguage.current.text(LocalText(
            "This check's detail is unavailable through this connection. Delivery check details require a compatible workflow. A failed read does not establish an absent table assignment.",
            "تفاصيل هذا الطلب غير متاحة عبر هذا الاتصال. تتطلب تفاصيل طلب التوصيل مسارًا متوافقًا. فشل القراءة لا يثبت عدم وجود تعيين للطاولة.")), caution = true) }
    val page = state.page?.food ?: return
    val forms = state.page?.forms.orEmpty()
    item("food-read-cut") {
        val language = LocalAppLanguage.current
        Text(language.text(LocalText(
            "Source cut ${page.branchRevision.value} · ${page.observedAt.canonicalString()}. This body is one actual read; other pages and retained selections may have different cuts.",
            "مراجعة المصدر ${page.branchRevision.value} · ${page.observedAt.canonicalString()}. هذا المحتوى قراءة فعلية واحدة؛ قد تختلف مراجعات الصفحات الأخرى والاختيارات المحتفظ بها.")),
            style = MaterialTheme.typography.bodySmall)
    }
    val browse = !state.working && state.form == null && !state.formFrozen
    when (val body = page.body) {
        is LocalFoodUiBody.Floors, is LocalFoodUiBody.Tables, is LocalFoodUiBody.Table ->
            localFoodFloorContent(body, controller, token, browse, page.query.tableAssignmentTarget())
        is LocalFoodUiBody.TableAssignment -> item("food-table-assignment") {
            FoodPanel {
                val language = LocalAppLanguage.current
                FoodIdentifier(language.text(LocalText("Check ID", "معرف الطلب")), body.target.orderId.value)
                if (body.tableId == null) Notice(language.text(LocalText(
                    "This check has no managed table assignment. Its service reference is only a label.",
                    "هذا الطلب غير معيّن لطاولة مُدارة. مرجع الخدمة هو وصف فقط.")))
                else {
                    FoodIdentifier(language.text(LocalText("Assigned floor ID", "معرف القاعة المعيّنة")), checkNotNull(body.floorId).value)
                    FoodIdentifier(language.text(LocalText("Assigned table ID", "معرف الطاولة المعيّنة")), body.tableId.value)
                }
                Notice(language.text(LocalText(
                    "Assignment changes only the typed table binding, not the check owner, service labels, stock, kitchen work or payments. Destination availability is checked again by the Master.",
                    "التعيين يغير ربط الطاولة فقط، وليس مالك الطلب أو أوصاف الخدمة أو المخزون أو عمل المطبخ أو المدفوعات. يعيد الجهاز الرئيسي التحقق من الوجهة.")), caution = true)
                if (!body.eligible) Notice(language.text(LocalText(
                    "Closed work or a pending ownership handoff cannot be moved.",
                    "لا يمكن نقل طلب أُغلق عمله أو ينتظر تسليم الملكية.")), caution = true)
                else if (!body.assignmentAllowed) Notice(language.text(LocalText(
                    "Table assignment is not currently available with this session's permissions.",
                    "تعيين الطاولة غير متاح حاليًا بصلاحيات هذه الجلسة.")), caution = true)
                else if (body.assignmentAllowed) OutlinedButton(onClick = {
                    controller.selectFood(LocalFoodUiQuery.FloorDirectory(assignment = body.target), token)
                }, enabled = browse, modifier = Modifier.touchTarget()) {
                    Text(language.text(LocalText("Choose destination table", "اختيار الطاولة الوجهة")))
                }
                OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.Check(body.target.orderId), token) },
                    enabled = browse, modifier = Modifier.touchTarget()) {
                    Text(language.text(LocalText("Back to check", "العودة إلى الطلب")))
                }
            }
        }
        is LocalFoodUiBody.Financial -> {
            if (body.groups.isEmpty()) item("food-empty") { Notice(foodCopy(FoodCopy.EMPTY_PAGE)) }
            items(body.groups, key = { "food-financial-${it.key}" }) { group ->
                val language = LocalAppLanguage.current
                FoodPanel {
                    Text(language.text(group.title), style = MaterialTheme.typography.titleLarge)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            group.values.forEach { value ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(language.text(value.label), style = MaterialTheme.typography.labelLarge)
                                    Text(language.text(value.value), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
            val links = body.links.filter { it.query.canAppearIn(state.workflow) &&
                (!controller.foodCashFinancialOnly || it.query.isFoodCashFinancialUiQuery()) }
            if (links.isNotEmpty()) item("food-financial-links") {
                FoodPanel {
                    val language = LocalAppLanguage.current
                    links.forEach { link ->
                        OutlinedButton(onClick = { controller.selectFood(link.query, token) }, enabled = browse,
                            modifier = Modifier.touchTarget()) { Text(language.text(link.title)) }
                    }
                }
            }
        }
        is LocalFoodUiBody.Checks -> {
            if (body.checks.isEmpty()) item("food-empty") { Notice(foodCopy(FoodCopy.EMPTY_PAGE)) }
            items(body.checks, key = { "food-check-${it.orderId.value}" }) { check ->
                FoodPanel {
                    FoodCheckHeader(check)
                    OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.Check(check.orderId), token) },
                        enabled = browse, modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_CHECK)) }
                }
            }
        }
        is LocalFoodUiBody.Check -> {
            item("food-check-header") {
                FoodPanel {
                    FoodCheckHeader(body.check)
                    body.delivery?.let { observation ->
                        FoodDeliveryDetails(observation.details)
                        Notice(LocalAppLanguage.current.text(foodDeliveryDraftNoticeText()), caution = true,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (body.check.portionCount == 0) {
                        val language = LocalAppLanguage.current
                        Notice(language.text(if (controller.foodTableAssignmentsOnly || controller.foodCashFinancialOnly) LocalText(
                            "This check has no items.", "لا توجد أصناف في هذا الطلب.") else LocalText(
                            "Empty unpaid check: no items or captured quotation. Add menu items separately.",
                            "طلب فارغ غير مدفوع: لا أصناف ولا عرض سعر مسجل. أضف أصناف القائمة بصورة منفصلة.")))
                    } else FoodQuote(body.originalQuotedTotal, body.chargeResolutionRequired)
                    if (!controller.foodTableAssignmentsOnly) FoodCurrentUnpaid(body.currentUnpaidPayable)
                    body.financial?.let { FoodFinancialResponsibility(it) }
                    if (!controller.foodTableAssignmentsOnly) {
                        Notice(LocalAppLanguage.current.text(if (controller.foodCashFinancialOnly) foodCashFinancialKitchenNoticeText(body)
                            else foodKitchenNoticeText(body)), caution = true,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Notice(foodCopy(FoodCopy.PREPARATION_WARNING))
                    }
                    if (!controller.foodChecksOnly && !controller.foodCashFinancialOnly && state.workflow == LocalWorkflow.FOOD && body.tableAssignmentAvailable && body.check.service.channel == "DINE_IN" &&
                        !body.check.workClosed && body.check.handoffId == null) OutlinedButton(onClick = {
                        controller.selectFood(LocalFoodUiQuery.TableAssignment(body.check.orderId,
                            body.check.orderStorageRevision), token)
                    }, enabled = browse, modifier = Modifier.touchTarget()) {
                        Text(LocalAppLanguage.current.text(LocalText("Review or change assigned table", "مراجعة الطاولة المعيّنة أو تغييرها")))
                    }
                    body.check.collectionId?.takeUnless { controller.foodChecksOnly || controller.foodTableAssignmentsOnly }?.let { collectionId ->
                        OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.Collection(collectionId), token) },
                            enabled = browse, modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_COLLECTION)) }
                    }
                    if (!controller.foodTableAssignmentsOnly && !controller.foodCashFinancialOnly && state.workflow == LocalWorkflow.FOOD && !body.check.workClosed && body.check.collectionId == null && body.financial == null &&
                        body.check.handoffId == null && body.check.portionCount < 32) OutlinedButton(
                        onClick = { controller.selectFood(LocalFoodUiQuery.Menu(orderId = body.check.orderId,
                            expectedOrderStorageRevision = body.check.orderStorageRevision), token) },
                        enabled = browse, modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.ADD_SERVINGS)) }
                    if (!controller.foodChecksOnly && !controller.foodCashFinancialOnly && !controller.foodTableAssignmentsOnly && body.check.handoffId == null && forms.any { it.action == LocalAction.FOOD_OFFER_HANDOFF }) OutlinedButton(
                        onClick = { controller.openForm(LocalAction.FOOD_OFFER_HANDOFF, token) },
                        enabled = state.canStart, modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.CHOOSE_RECIPIENT)) }
                }
            }
            val completeLines = if ((page.query as? LocalFoodUiQuery.Check)?.afterPortionId == null && page.next == null)
                body.completeCommercialLines() else null
            if (completeLines != null) items(completeLines, key = { "food-commercial-${it.lineId.value}" }) { line ->
                FoodPanel {
                    val language = LocalAppLanguage.current
                    Text(language.text(LocalText("Commercial line", "السطر التجاري")), style = MaterialTheme.typography.labelLarge)
                    Text(line.description, style = MaterialTheme.typography.titleMedium)
                    FoodQuantity(line.quantity)
                    if (line.itemNote.isNotEmpty()) Text("${foodCopy(FoodCopy.ITEM_NOTE)}: ${line.itemNote}")
                    if (line.seatNote.isNotEmpty()) Text("${language.text(LocalText("Line seat note", "ملاحظة مقعد السطر"))}: ${line.seatNote}")
                    Text(language.text(LocalText("${line.portionCount} independently prepared servings", "${language.digits(line.portionCount.toString())} حصص مستقلة التحضير")))
                    FoodDisclosure { FoodIdentifier(language.text(LocalText("Original line ID", "معرف السطر الأصلي")), line.lineId.value) }
                }
            } else if (body.check.portionCount > 0) item("food-incomplete-commercial-quantity") {
                Notice(LocalAppLanguage.current.text(LocalText(
                    "This page is not a complete commercial-line quantity. Browse preparation members separately; no whole-line quantity is inferred from a partial page.",
                    "هذه الصفحة لا تعرض كمية السطر التجاري كاملة. استعرض حصص التحضير بصورة منفصلة؛ لا تُستنتج كمية السطر كاملة من صفحة جزئية.")), caution = true)
            }
            if (body.portions.isEmpty()) item("food-empty") { Notice(foodCopy(FoodCopy.EMPTY_PAGE)) }
            items(body.portions, key = { "food-portion-${it.portionId.value}" }) { portion -> FoodPanel {
                Text(LocalAppLanguage.current.text(LocalText("Preparation member", "حصة التحضير")), style = MaterialTheme.typography.labelLarge)
                FoodPortion(portion)
            } }
        }
        is LocalFoodUiBody.Menu -> {
            item("food-menu-help") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading(foodCopy(FoodCopy.MENU), foodCopy(FoodCopy.MENU_HELP))
                    state.form?.takeIf { it.descriptor.action in foodSelectionActions }?.let {
                        Text("${foodCopy(FoodCopy.SELECTED_COUNT)}: ${it.foodServingCount()?.let { count -> LocalAppLanguage.current.digits(count.toString()) } ?: "—"} / ${LocalAppLanguage.current.digits("8")}",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
            if (body.recipes.isEmpty()) item("food-empty") { Notice(foodCopy(FoodCopy.EMPTY_PAGE)) }
            items(body.recipes, key = { "food-recipe-${it.recipeId.value}" }) { recipe ->
                val language = LocalAppLanguage.current
                FoodPanel {
                    Text(foodCopy(if (recipe.active) FoodCopy.ACTIVE else FoodCopy.INACTIVE), style = MaterialTheme.typography.labelLarge)
                    FoodQuantity(recipe.portionQuantity)
                    recipe.choices.forEach { choice -> key(choice.choiceId) {
                        val name = language.text(choice.names).ifBlank { if (language == AppLanguage.ARABIC) choice.names.english else choice.names.arabic }
                        val canSelect = !state.working && !state.recoveryRequired && !state.formFrozen && recipe.active &&
                            forms.any { it.action in foodSelectionActions } &&
                            (state.form == null || state.form.descriptor.action in foodSelectionActions &&
                                state.form.lines.size < 8 && state.form.foodServingCount()?.let { it < 8 } == true)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            FoodDisclosure {
                                FoodIdentifier(foodCopy(FoodCopy.CHOICE_ID), choice.choiceId.value)
                                if (choice.modifierIds.isEmpty()) Text(foodCopy(FoodCopy.NO_MODIFIERS))
                                choice.modifierIds.forEach { FoodIdentifier(foodCopy(FoodCopy.MODIFIERS), it.value) }
                            }
                            val selectLabel = foodCopy(FoodCopy.SELECT_SERVING)
                            Button(onClick = { controller.chooseFood(recipe.recipeId, choice.choiceId, token) }, enabled = canSelect,
                                modifier = Modifier.touchTarget().semantics { contentDescription = "$selectLabel · $name" }) {
                                Text(selectLabel)
                            }
                        }
                    } }
                    FoodDisclosure {
                        FoodIdentifier(foodCopy(FoodCopy.RECIPE_ID), recipe.recipeId.value)
                        Text("${foodCopy(FoodCopy.RECIPE_REVISION)}: ${language.digits(recipe.recipeRevision.value.toString())}")
                        FoodIdentifier("SKU", recipe.outputSkuId.value)
                    }
                }
            }
        }
        is LocalFoodUiBody.Stations -> {
            if (body.stations.isEmpty()) item("food-empty") { Notice(foodCopy(FoodCopy.EMPTY_PAGE)) }
            items(body.stations, key = { "food-station-${it.stationId.value}" }) { station ->
                val language = LocalAppLanguage.current
                FoodPanel {
                    Text(language.text(station.names).ifBlank { if (language == AppLanguage.ARABIC) station.names.english else station.names.arabic },
                        style = MaterialTheme.typography.titleLarge)
                    Text(foodCopy(if (station.active) FoodCopy.ACTIVE else FoodCopy.INACTIVE))
                    Text(foodCopy(if (station.deliveryMode == LocalFoodDeliveryMode.PAPER) FoodCopy.PAPER else FoodCopy.DISPLAY))
                    FoodIdentifier(foodCopy(FoodCopy.STATION_ID), station.stationId.value)
                    Text("${foodCopy(FoodCopy.CONFIGURATION_STORAGE)}: ${language.digits(station.configurationStorageRevision.value.toString())}")
                    Notice(foodCopy(FoodCopy.STATION_WARNING), caution = true)
                    val direct = page.query is LocalFoodUiQuery.StationLabel
                    OutlinedButton(onClick = { controller.selectFood(if (direct) LocalFoodUiQuery.StationTasks(station.stationId)
                        else LocalFoodUiQuery.StationLabel(station.stationId), token) }, enabled = browse,
                        modifier = Modifier.touchTarget()) { Text(foodCopy(if (direct) FoodCopy.TASKS else FoodCopy.OPEN_STATION)) }
                }
            }
        }
        is LocalFoodUiBody.Tasks -> {
            item("food-station-intent") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val stationId = when (val query = page.query) {
                        is LocalFoodUiQuery.StationTasks -> query.stationId
                        is LocalFoodUiQuery.Task -> query.stationId
                        else -> null
                    }
                    stationId?.let { FoodIdentifier(foodCopy(FoodCopy.STATION_ID), it.value) }
                    Notice(foodCopy(FoodCopy.STATION_WARNING), caution = true)
                }
            }
            if (body.tasks.isEmpty()) item("food-empty") { Notice(foodCopy(FoodCopy.EMPTY_PAGE)) }
            items(body.tasks, key = { "food-task-${it.taskId.value}" }) { task ->
                val language = LocalAppLanguage.current
                FoodPanel {
                    Text(task.serviceReference, style = MaterialTheme.typography.titleLarge)
                    Text(task.description, style = MaterialTheme.typography.titleMedium)
                    FoodQuantity(task.quantity)
                    Text("${foodCopy(FoodCopy.TASK_STATE)}: ${language.text(foodTaskText(task.taskState))}")
                    Text("${foodCopy(FoodCopy.PREPARATION)}: ${language.text(foodPreparationText(task.preparation))}")
                    if (state.workflow == LocalWorkflow.KITCHEN && task.preparation == LocalFoodPreparation.CANCEL_PENDING)
                        Notice(language.text(LocalText(
                            "Cancellation is pending. Any stock return needs verified unstarted work; started ingredients remain consumed.",
                            "الإلغاء معلق. تتطلب إعادة المخزون إثبات عدم بدء التحضير؛ وتظل المكونات التي بدأ استخدامها مستهلكة.")), caution = true)
                    Text(foodCopy(if (task.deliveryMode == LocalFoodDeliveryMode.PAPER) FoodCopy.PAPER else FoodCopy.DISPLAY))
                    if (task.itemNote.isNotEmpty()) Text("${foodCopy(FoodCopy.ITEM_NOTE)}: ${task.itemNote}")
                    if (task.seatNote.isNotEmpty()) Text("${language.text(LocalText("Line seat note", "ملاحظة مقعد السطر"))}: ${task.seatNote}")
                    FoodDisclosure {
                        FoodIdentifier(foodCopy(FoodCopy.STATION_ID), task.stationId.value)
                        FoodIdentifier(foodCopy(FoodCopy.TASK_ID), task.taskId.value)
                        FoodIdentifier(foodCopy(FoodCopy.ORDER_ID), task.orderId.value)
                        FoodIdentifier(foodCopy(FoodCopy.PORTION_ID), task.portionId.value)
                        Text("${foodCopy(FoodCopy.ORDER_STORAGE)}: ${language.digits(task.orderStorageRevision.value.toString())}")
                        Text("${foodCopy(FoodCopy.PORTION_DOMAIN)}: ${language.digits(task.preparationDomainRevision.value.toString())}")
                    }
                    if (page.query !is LocalFoodUiQuery.Task) OutlinedButton(
                        onClick = { controller.selectFood(LocalFoodUiQuery.Task(task.stationId, task.taskId), token) }, enabled = browse,
                        modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_TASK)) }
                    if (state.workflow == LocalWorkflow.KITCHEN && page.query is LocalFoodUiQuery.StationTasks)
                        task.kitchenBoardAction()?.let { action ->
                            val start = action == LocalAction.FOOD_STATION_START
                            val label = language.text(if (start) LocalText("Start preparation", "بدء التحضير")
                                else LocalText("Mark ready", "تسجيل الجاهزية"))
                            val accessibleLabel = language.text(if (start)
                                LocalText("Start preparation for ${task.serviceReference}", "بدء التحضير للطلب ${task.serviceReference}")
                                else LocalText("Mark ${task.serviceReference} ready", "تسجيل جاهزية الطلب ${task.serviceReference}"))
                            OutlinedButton(onClick = { controller.chooseKitchenTask(task, action, token) },
                                enabled = state.canStart,
                                modifier = Modifier.touchTarget().semantics { contentDescription = accessibleLabel }) {
                                Text(label)
                            }
                        }
                }
            }
        }
        is LocalFoodUiBody.Recipients -> {
            item("food-recipients-help") {
                FoodPanel {
                    SectionHeading(foodCopy(FoodCopy.CHOOSE_RECIPIENT), foodCopy(FoodCopy.RECIPIENT_HELP))
                    FoodIdentifier(foodCopy(FoodCopy.ORDER_ID), body.orderId.value)
                    body.financial?.let { FoodFinancialResponsibility(it) }
                    Notice(foodCopy(FoodCopy.HANDOFF_WARNING), caution = true)
                }
            }
            if (body.recipients.isEmpty()) item("food-recipients-empty") { Notice(foodCopy(FoodCopy.NO_RECIPIENTS)) }
            items(body.recipients, key = { "food-recipient-${it.actorId.value}" }) { recipient ->
                FoodPanel {
                    Text(recipient.label, style = MaterialTheme.typography.titleLarge)
                    FoodDisclosure { FoodIdentifier(foodCopy(FoodCopy.RECIPIENT_ID), recipient.actorId.value) }
                    val choose = foodCopy(FoodCopy.SELECT_RECIPIENT)
                    Button(onClick = { controller.chooseFoodRecipient(recipient.actorId, token) },
                        enabled = state.canStart && forms.any { it.action == LocalAction.FOOD_OFFER_HANDOFF },
                        modifier = Modifier.touchTarget().semantics { contentDescription = "$choose · ${recipient.label}" }) { Text(choose) }
                }
            }
        }
        is LocalFoodUiBody.Incoming -> {
            item("food-incoming-help") {
                FoodPanel {
                    SectionHeading(foodCopy(FoodCopy.INBOX))
                    Notice(foodCopy(if (body.cursorExpired) FoodCopy.INBOX_CURSOR_EXPIRED else FoodCopy.INBOX_HELP), caution = body.cursorExpired)
                    if (!body.cursorExpired && page.next != null) Notice(foodCopy(FoodCopy.INBOX_PROGRESS))
                    if (!body.cursorExpired && body.offers.isEmpty()) Notice(foodCopy(
                        if (page.next == null) FoodCopy.INBOX_PAGE_EMPTY else FoodCopy.INBOX_SCANNING_EMPTY))
                }
            }
            items(body.offers, key = { "food-incoming-${it.check.handoffId?.value}" }) { offer ->
                FoodPanel {
                    FoodCheckHeader(offer.check)
                    offer.financial?.let { FoodFinancialResponsibility(it) }
                    Text("${foodCopy(FoodCopy.OFFERED_AT)}: ${offer.offeredAt.canonicalString()}", style = MaterialTheme.typography.bodySmall)
                    Notice(foodCopy(FoodCopy.HANDOFF_WARNING), caution = true)
                    OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.IncomingKnownOrder(offer.check.orderId,
                        offer.check.handoffId, offer.check.orderStorageRevision), token) }, enabled = browse,
                        modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.REVIEW_HANDOFF)) }
                }
            }
        }
        is LocalFoodUiBody.Handoff -> {
            item("food-handoff") {
                FoodPanel {
                    SectionHeading(foodCopy(FoodCopy.REVIEW_HANDOFF))
                    Notice(foodCopy(FoodCopy.HANDOFF_WARNING), caution = true)
                    FoodCheckHeader(body.check)
                    FoodQuote(body.originalQuotedTotal, body.chargeResolutionRequired)
                    body.financial?.let { FoodFinancialResponsibility(it) }
                }
            }
            items(body.portions, key = { "food-handoff-portion-${it.portionId.value}" }) { portion -> FoodPanel { FoodPortion(portion) } }
        }
    }
    if (page.next != null) item("food-next") {
        OutlinedButton(onClick = { controller.refresh(token, nextPage = true) },
            enabled = !state.working && !state.formFrozen && (state.form == null || state.form.descriptor.action in foodSelectionActions && !state.recoveryRequired),
            modifier = Modifier.touchTarget()) { Text(foodCopy(if (page.body is LocalFoodUiBody.Incoming)
                FoodCopy.CONTINUE_INCOMING else FoodCopy.NEXT)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodNavigation(state: LocalWorkspaceState, controller: LocalWorkflowController, token: NavigationToken) {
    if (controller.foodCashFinancialOnly) {
        FoodCashFinancialNavigation(state, controller, token)
        return
    }
    if (state.workflow == LocalWorkflow.KITCHEN) {
        KitchenNavigation(state, controller, token)
        return
    }
    val browse = !state.working && state.form == null && !state.formFrozen
    val fulfillment = state.workflow == LocalWorkflow.FULFILLMENT
    val checksOnly = controller.foodChecksOnly
    val tablesOnly = controller.foodTableAssignmentsOnly
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (fulfillment) Notice(LocalAppLanguage.current.text(LocalText(
            "Record dine-in serving and paid counter/takeaway pickup. Read an owned check or its collection first. Retail release, courier delivery, COD/change/remittance, failed delivery, abandoned pickup and unpaid incident resolution are unavailable here.",
            "سجّل تقديم الطعام داخل المكان واستلام الطلب المدفوع من المنضدة أو للسفري. اقرأ طلبًا تملكه أو تحصيله أولًا. التسليم بالتجزئة وتوصيل المندوب والدفع عند الاستلام والفكة وتسليم المتحصلات النقدية وفشل التوصيل والطلبات التي لم تُستلم وحسم حالات التسليم غير المدفوع غير متاحة هنا.")))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.MyChecks(), token) }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.MY_CHECKS)) }
            if (!fulfillment) {
                if (!checksOnly && !tablesOnly) OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.Incoming(), token) }, enabled = browse,
                    modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.INBOX)) }
                if (!tablesOnly) OutlinedButton(onClick = { controller.openFoodMenu(token) }, enabled = browse,
                    modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.MENU)) }
                if (!checksOnly) {
                    if (!tablesOnly) OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.ConfiguredStations(), token) }, enabled = browse,
                        modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.STATIONS)) }
                    OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.FloorDirectory(), token) }, enabled = browse,
                        modifier = Modifier.touchTarget()) { Text(LocalAppLanguage.current.text(LocalText("Floors and tables", "القاعات والطاولات"))) }
                    if (!tablesOnly) OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.OwnClaims(), token) }, enabled = browse,
                        modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.MY_CLAIMS)) }
                }
            }
            OutlinedButton(onClick = { controller.refresh(token) },
                enabled = browse || !state.working && !state.formFrozen && !state.recoveryRequired && state.form?.descriptor?.action in foodSelectionActions,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.REFRESH_FIRST)) }
        }
        if (state.form == null) FoodDisclosure {
            Notice(foodCopy(FoodCopy.LOOKUP_HELP))
            var order by remember(controller, state.workflow, state.foodQuery) { mutableStateOf("") }
            var station by remember(controller, state.workflow, state.foodQuery) { mutableStateOf("") }
            var financial by remember(controller, state.workflow, state.foodQuery) { mutableStateOf("") }
            var invalid by remember(controller, state.workflow, state.foodQuery) { mutableStateOf(false) }
            fun lookup(value: String, selector: (DomainId) -> LocalFoodUiQuery) {
                val id = try { DomainId(value) } catch (_: IllegalArgumentException) { null }
                invalid = id == null
                if (id != null) controller.selectFood(selector(id), token)
            }
            OutlinedTextField(order, { if (it.length <= 36) { order = it; invalid = false } }, enabled = browse,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                singleLine = true, label = { Text(foodCopy(FoodCopy.OWNED_ORDER)) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { lookup(order) { LocalFoodUiQuery.Check(it) } }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_CHECK)) }
            if (!fulfillment && !checksOnly && !tablesOnly) {
                OutlinedButton(onClick = { lookup(order) { LocalFoodUiQuery.IncomingKnownOrder(it) } }, enabled = browse,
                    modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.REVIEW_HANDOFF)) }
                OutlinedTextField(station, { if (it.length <= 36) { station = it; invalid = false } }, enabled = browse,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    singleLine = true, label = { Text(foodCopy(FoodCopy.STATION_LOOKUP)) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { lookup(station) { LocalFoodUiQuery.StationLabel(it) } }, enabled = browse,
                    modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_STATION)) }
            }
            if (!checksOnly && !tablesOnly) {
                OutlinedTextField(financial, { if (it.length <= 36) { financial = it; invalid = false } }, enabled = browse,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    singleLine = true, label = { Text(foodCopy(FoodCopy.FINANCIAL_LOOKUP)) }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { lookup(financial) { LocalFoodUiQuery.Collection(it) } }, enabled = browse,
                        modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_COLLECTION)) }
                    if (!fulfillment) {
                        OutlinedButton(onClick = { lookup(financial) { LocalFoodUiQuery.Receipt(it) } }, enabled = browse,
                            modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_RECEIPT)) }
                        OutlinedButton(onClick = { lookup(financial) { LocalFoodUiQuery.Refund(it) } }, enabled = browse,
                            modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_REFUND)) }
                        OutlinedButton(onClick = { lookup(financial) { LocalFoodUiQuery.Claim(it) } }, enabled = browse,
                            modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_CLAIM)) }
                    }
                }
            }
            if (invalid) Notice(foodCopy(FoodCopy.INVALID_ID), caution = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodCashFinancialNavigation(state: LocalWorkspaceState, controller: LocalWorkflowController, token: NavigationToken) {
    val language = LocalAppLanguage.current
    val browse = !state.working && state.form == null && state.formInput == null && !state.formFrozen
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.MyChecks(), token) }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.MY_CHECKS)) }
            OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.OwnPendingPayments(), token) }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(language.text(LocalText("Own pending payment originals", "وقائع الدفع الأصلية المعلقة الخاصة بي"))) }
            OutlinedButton(onClick = { controller.refresh(token) }, enabled = browse && !state.attachmentReviewRequired,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.REFRESH_FIRST)) }
        }
        if (state.form == null && state.formInput == null && !state.formFrozen) FoodDisclosure {
            // Unsaved selectors are ephemeral. A new query or attachment review drops them;
            // no selector is a staged operation, native source or instruction capability.
            var recording by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf("") }
            var order by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf("") }
            var collection by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf("") }
            var sale by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf("") }
            var attemptOrder by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf("") }
            var attemptCollection by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf("") }
            var attemptSession by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf("") }
            var attemptId by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf("") }
            var attemptIntent by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf("") }
            var invalid by remember(controller, state.foodQuery, state.attachmentReviewRequired) { mutableStateOf(false) }
            fun id(value: String): DomainId? = try { DomainId(value) } catch (_: IllegalArgumentException) { null }
            fun lookup(value: String, selector: (DomainId) -> LocalFoodUiQuery) {
                val selected = id(value)
                invalid = selected == null
                if (selected != null) controller.selectFood(selector(selected), token)
            }
            Notice(language.text(LocalText(
                "Read a genuine payment original by its recording operation ID. The Master checks CASH_READ and original ownership. Applying its retained outcome is a separate reviewed and saved operation; this lookup cannot capture money.",
                "اقرأ واقعة دفع أصلية فعلية بمعرف عملية تسجيلها. يتحقق الجهاز الرئيسي من CASH_READ والملكية الأصلية. تطبيق نتيجتها المحفوظة عملية منفصلة تُراجع وتُحفظ؛ ولا يمكن لهذه القراءة تحصيل مال.")))
            FoodFinancialIdentifierInput(recording, browse, LocalText("Payment recording operation ID", "معرف عملية تسجيل الدفع")) {
                recording = it; invalid = false
            }
            OutlinedButton(onClick = { lookup(recording) { LocalFoodUiQuery.PaymentOriginal(OperationId(it.value)) } }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(language.text(LocalText("Read retained payment original", "قراءة واقعة الدفع الأصلية المحفوظة"))) }

            SectionHeading(language.text(LocalText("Owned check and collection", "الطلب والتحصيل المملوكان")))
            FoodFinancialIdentifierInput(order, browse, LocalText("Owned check ID", "معرف الطلب المملوك")) { order = it; invalid = false }
            OutlinedButton(onClick = { lookup(order) { LocalFoodUiQuery.Check(it) } }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_CHECK)) }
            FoodFinancialIdentifierInput(collection, browse, LocalText("Owned collection ID", "معرف التحصيل المملوك")) { collection = it; invalid = false }
            OutlinedButton(onClick = { lookup(collection) { LocalFoodUiQuery.Collection(it) } }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_COLLECTION)) }
            Notice(language.text(LocalText(
                "Paid receipt lookup requires the separate scoped RETURN_RECEIPT_LOOKUP permission. ORDER_READ alone does not grant receipt or payment-original access.",
                "تتطلب قراءة الإيصال المدفوع صلاحية RETURN_RECEIPT_LOOKUP المحددة المستقلة. لا يمنح ORDER_READ وحده الوصول إلى الإيصال أو واقعة الدفع الأصلية.")))
            FoodFinancialIdentifierInput(sale, browse, LocalText("Paid sale ID for receipt", "معرف البيع المدفوع لقراءة الإيصال")) { sale = it; invalid = false }
            OutlinedButton(onClick = { lookup(sale) { LocalFoodUiQuery.Receipt(it) } }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_RECEIPT)) }

            SectionHeading(language.text(LocalText("Exact attempt recording lookup", "قراءة تسجيل المحاولة المحددة")))
            Notice(language.text(LocalText(
                "Enter all five original coordinates: order, collection, cash session, attempt and intent operation. No ID is guessed from a collection or generated here. A successful read is retained DATA only, not permission or a physical Start instruction.",
                "أدخل الإحداثيات الأصلية الخمسة كاملة: الطلب والتحصيل والجلسة النقدية والمحاولة وعملية الطلب الأصلي. لا يُخمّن أي معرف من التحصيل ولا يُنشأ هنا. القراءة الناجحة بيانات محفوظة فقط وليست إذنًا أو تعليمات لبدء إجراء فعلي.")))
            FoodFinancialIdentifierInput(attemptOrder, browse, LocalText("Original attempt order ID", "معرف طلب المحاولة الأصلي")) { attemptOrder = it; invalid = false }
            FoodFinancialIdentifierInput(attemptCollection, browse, LocalText("Original attempt collection ID", "معرف تحصيل المحاولة الأصلي")) { attemptCollection = it; invalid = false }
            FoodFinancialIdentifierInput(attemptSession, browse, LocalText("Original cash session ID", "معرف الجلسة النقدية الأصلي")) { attemptSession = it; invalid = false }
            FoodFinancialIdentifierInput(attemptId, browse, LocalText("Original attempt ID", "معرف المحاولة الأصلي")) { attemptId = it; invalid = false }
            FoodFinancialIdentifierInput(attemptIntent, browse, LocalText("Original intent operation ID", "معرف عملية الطلب الأصلي")) { attemptIntent = it; invalid = false }
            OutlinedButton(onClick = {
                val orderId = id(attemptOrder)
                val collectionId = id(attemptCollection)
                val sessionId = id(attemptSession)
                val originalAttemptId = id(attemptId)
                val intentId = id(attemptIntent)
                invalid = orderId == null || collectionId == null || sessionId == null || originalAttemptId == null || intentId == null
                if (orderId != null && collectionId != null && sessionId != null && originalAttemptId != null && intentId != null)
                    controller.selectFood(LocalFoodUiQuery.PaymentAttempt(orderId, collectionId, sessionId, originalAttemptId,
                        OperationId(intentId.value)), token)
            }, enabled = browse, modifier = Modifier.touchTarget()) { Text(language.text(LocalText("Read exact attempt recording", "قراءة تسجيل المحاولة المحددة"))) }
            if (invalid) Notice(foodCopy(FoodCopy.INVALID_ID), caution = true)
        }
    }
}

@Composable
private fun FoodFinancialIdentifierInput(value: String, enabled: Boolean, label: LocalText, change: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 36) change(it) }, enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
        singleLine = true, label = { Text(LocalAppLanguage.current.text(label)) }, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KitchenNavigation(state: LocalWorkspaceState, controller: LocalWorkflowController, token: NavigationToken) {
    val browse = !state.working && state.form == null && state.formInput == null && !state.formFrozen
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Notice(LocalAppLanguage.current.text(LocalText(
            "Choose a station to read its tasks and record preparation start or readiness.",
            "اختر محطة لقراءة مهامها وتسجيل بدء التحضير أو الجاهزية.")))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.selectFood(LocalFoodUiQuery.ConfiguredStations(), token) }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.STATIONS)) }
            (state.foodQuery as? LocalFoodUiQuery.Task)?.let { task ->
                OutlinedButton(onClick = { controller.returnKitchenBoard(token) }, enabled = browse,
                    modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.TASKS)) }
            }
            OutlinedButton(onClick = { controller.refresh(token) }, enabled = browse,
                modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.REFRESH_FIRST)) }
        }
        if (state.form == null) FoodDisclosure {
            var station by remember(controller, state.workflow, state.foodQuery) { mutableStateOf("") }
            var invalid by remember(controller, state.workflow, state.foodQuery) { mutableStateOf(false) }
            OutlinedTextField(station, { if (it.length <= 36) { station = it; invalid = false } }, enabled = browse,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                singleLine = true, label = { Text(foodCopy(FoodCopy.STATION_LOOKUP)) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                val id = try { DomainId(station) } catch (_: IllegalArgumentException) { null }
                invalid = id == null
                if (id != null) controller.selectFood(LocalFoodUiQuery.StationLabel(id), token)
            }, enabled = browse, modifier = Modifier.touchTarget()) { Text(foodCopy(FoodCopy.OPEN_STATION)) }
            if (invalid) Notice(foodCopy(FoodCopy.INVALID_ID), caution = true)
        }
    }
}

@Composable
private fun FoodPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun FoodDeliveryDetails(details: LocalFoodUiDeliveryDetails) {
    val language = LocalAppLanguage.current
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(language.text(LocalText("Delivery recipient", "مستلم التوصيل")), style = MaterialTheme.typography.labelLarge)
            Text(details.recipient, style = MaterialTheme.typography.bodyLarge)
            Text(language.text(LocalText("Contact", "بيانات التواصل")), style = MaterialTheme.typography.labelLarge)
            Text(details.contact, style = MaterialTheme.typography.bodyLarge)
            Text(language.text(LocalText("Delivery address", "عنوان التوصيل")), style = MaterialTheme.typography.labelLarge)
            Text(details.address, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun FoodCheckHeader(check: LocalFoodUiCheck) {
    val language = LocalAppLanguage.current
    Text(check.service.reference, style = MaterialTheme.typography.titleLarge)
    Text(foodCopy(FoodCopy.TABLE_REFERENCE), style = MaterialTheme.typography.bodySmall)
    Text(language.text(foodChannelText(check.service.channel)))
    if (check.service.seatNote.isNotEmpty()) Text("${foodCopy(FoodCopy.SEAT_NOTE)}: ${check.service.seatNote}")
    Text("${foodCopy(FoodCopy.PORTION_COUNT)}: ${language.digits(check.portionCount.toString())}")
    FoodIdentifier(foodCopy(FoodCopy.ORDER_ID), check.orderId.value)
    FoodIdentifier(foodCopy(FoodCopy.OWNER_ID), check.ownerId.value)
    check.handoffId?.let { FoodIdentifier(foodCopy(FoodCopy.HANDOFF_ID), it.value) }
    check.recipientId?.let { FoodIdentifier(foodCopy(FoodCopy.RECIPIENT_ID), it.value) }
    check.collectionId?.let { FoodIdentifier(foodCopy(FoodCopy.COLLECTION_ID), it.value) }
    FoodDisclosure { Text("${foodCopy(FoodCopy.ORDER_STORAGE)}: ${language.digits(check.orderStorageRevision.value.toString())}") }
}

@Composable
private fun FoodCurrentUnpaid(amount: Money?) {
    val value = amount?.let { LocalAppLanguage.current.foodMoney(it) } ?: foodCopy(FoodCopy.CURRENT_UNPAID_UNKNOWN)
    Text("${foodCopy(FoodCopy.CURRENT_UNPAID)}: $value", style = MaterialTheme.typography.titleMedium)
    if (amount != null) Text(foodCopy(FoodCopy.CURRENT_UNPAID_WARNING), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FoodQuote(total: Money, chargeDecision: Boolean) {
    Text("${foodCopy(FoodCopy.QUOTE)}: ${LocalAppLanguage.current.foodMoney(total)}", style = MaterialTheme.typography.titleMedium)
    Text(foodCopy(FoodCopy.QUOTE_WARNING), style = MaterialTheme.typography.bodySmall)
    if (chargeDecision) Notice(foodCopy(FoodCopy.CHARGE_REQUIRED), caution = true)
}

@Composable
private fun FoodFinancialResponsibility(value: LocalFoodUiFinancial) {
    val language = LocalAppLanguage.current
    Text("${foodCopy(FoodCopy.COLLECTION_STATUS)}: ${language.text(foodCollectionStatusText(value.status))}",
        style = MaterialTheme.typography.titleMedium)
    Text("${foodCopy(FoodCopy.COLLECTED)}: ${language.foodMoney(value.collected)}")
    Text("${foodCopy(FoodCopy.REMAINING)}: ${language.foodMoney(value.remaining)}")
    value.refundDue?.let { Text("${foodCopy(FoodCopy.REFUND_DUE)}: ${language.foodMoney(it)}") }
    if (value.pendingClaimCount > 0) Text("${foodCopy(FoodCopy.CLAIM_COUNT)}: ${language.digits(value.pendingClaimCount.toString())}")
    if (value.paymentUnresolved) Notice(language.text(foodUnavailableText(LocalFoodUnavailable.PAYMENT_UNRESOLVED)), caution = true)
    FoodDisclosure {
        FoodIdentifier(foodCopy(FoodCopy.COLLECTION_ID), value.collectionId.value)
        Text("${foodCopy(FoodCopy.COLLECTION_REVISION)}: ${language.digits(value.collectionDomainRevision.value.toString())}")
    }
}

@Composable
private fun FoodPortion(portion: LocalFoodUiPortion) {
    val language = LocalAppLanguage.current
    Text(portion.description, style = MaterialTheme.typography.titleMedium)
    FoodQuantity(portion.quantity)
    Text("${foodCopy(FoodCopy.PREPARATION)}: ${language.text(foodPreparationText(portion.preparation))}")
    Text("${foodCopy(FoodCopy.FULFILLMENT)}: ${language.text(foodFulfillmentText(portion.fulfillment))}")
    if (portion.started) Text(foodCopy(FoodCopy.STARTED))
    if (portion.cancellationRequested) Text(foodCopy(FoodCopy.CANCEL_REQUESTED))
    if (portion.itemNote.isNotEmpty()) Text("${foodCopy(FoodCopy.ITEM_NOTE)}: ${portion.itemNote}")
    if (portion.seatNote.isNotEmpty()) Text("${language.text(LocalText("Line seat note", "ملاحظة مقعد السطر"))}: ${portion.seatNote}")
    FoodDisclosure {
        FoodIdentifier(language.text(LocalText("Original commercial line ID", "معرف السطر التجاري الأصلي")), portion.commercialLineId.value)
        FoodIdentifier(foodCopy(FoodCopy.PORTION_ID), portion.portionId.value)
        Text("${foodCopy(FoodCopy.PORTION_DOMAIN)}: ${language.digits(portion.preparationDomainRevision.value.toString())}")
    }
}

@Composable
private fun FoodQuantity(quantity: Quantity) {
    Text(LocalAppLanguage.current.foodQuantity(quantity), style = MaterialTheme.typography.titleMedium)
    FoodIdentifier(foodCopy(FoodCopy.UNIT_ID), quantity.unitId.value)
}

@Composable
internal fun FoodIdentifier(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace,
            textDirection = TextDirection.Ltr)) }
    }
}

@Composable
private fun FoodDisclosure(content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val stateLabel = foodCopy(if (expanded) FoodCopy.EXPANDED else FoodCopy.COLLAPSED)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.touchTarget()
            .semantics { stateDescription = stateLabel }) { Text(foodCopy(if (expanded) FoodCopy.HIDE_DETAILS else FoodCopy.DETAILS)) }
        if (expanded) content()
    }
}

/** Read-only captured metadata is inspectable, not manually editable/replaced with a live name. */
@Composable
internal fun FoodDraftDetails(fields: List<LocalFormField.Scalar>, values: Map<LocalField, LocalDraftValue>, servingId: DomainId? = null) {
    val language = LocalAppLanguage.current
    FoodDisclosure {
        servingId?.let { FoodIdentifier(foodCopy(FoodCopy.PORTION_ID), it.value) }
        fields.filter { it.readOnly }.forEach { field ->
            val draft = values[field.field] ?: LocalDraftValue()
            if (field.kind == LocalInputKind.IDENTIFIER || field.field == LocalField.MODIFIER_IDS)
                FoodIdentifier(language.text(field.label), draft.text.ifEmpty { "—" })
            else Text("${language.text(field.label)}: ${draft.text.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun FoodDraftSelectionHeader(line: LocalDraftLine) {
    val language = LocalAppLanguage.current
    val english = line.values[LocalField.NAME_ENGLISH]?.text.orEmpty()
    val arabic = line.values[LocalField.NAME_ARABIC]?.text.orEmpty()
    Text(language.text(LocalText(english, arabic)).ifBlank { if (language == AppLanguage.ARABIC) english else arabic },
        style = MaterialTheme.typography.titleMedium)
    Text(language.text(LocalText("Configured quantity per serving: ${line.values[LocalField.QUANTITY]?.text.orEmpty()}",
        "الكمية المُعدّة لكل حصة: ${language.digits(line.values[LocalField.QUANTITY]?.text.orEmpty())}")))
    Text(language.text(LocalText("Configured serving count: ${line.values[LocalField.FOOD_SERVING_COUNT]?.text.orEmpty()}",
        "عدد الحصص المُعدّة: ${language.digits(line.values[LocalField.FOOD_SERVING_COUNT]?.text.orEmpty())}")))
    FoodIdentifier(foodCopy(FoodCopy.UNIT_ID), line.values[LocalField.UNIT]?.text.orEmpty())
}

@Composable
internal fun FoodRecordedDetails(details: LocalFoodOperationDetails) {
    val language = LocalAppLanguage.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FoodIdentifier(foodCopy(FoodCopy.RESULT_CODE), details.code)
        details.orderId?.let { FoodIdentifier(foodCopy(FoodCopy.ORDER_ID), it.value) }
        details.orderStorageRevision?.let { Text("${foodCopy(FoodCopy.ORDER_STORAGE)}: ${language.digits(it.value.toString())}") }
        details.portions.forEach { portion ->
            FoodIdentifier(foodCopy(FoodCopy.PORTION_ID), portion.portionId.value)
            Text(language.text(foodPreparationText(portion.preparation)))
            Text("${foodCopy(FoodCopy.PORTION_DOMAIN)}: ${language.digits(portion.preparationDomainRevision.value.toString())}")
            portion.originalIssueId?.let { FoodIdentifier(foodCopy(FoodCopy.ISSUE_ID), it.value) }
        }
    }
}

@Composable
internal fun FoodUnavailableTraining() {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeading(foodCopy(FoodCopy.TITLE))
        Notice(foodCopy(FoodCopy.TRAINING_UNAVAILABLE), caution = true)
    }
}
