package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.security.CompanyBusinessPublicationPhase
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** No native reviews, jobs, credentials or authorizations survive in this entry-owned holder. */
internal class LocalFoodConfigurationEntryDrafts {
    val lifetime = LocalConfigurationEntryLifetime()
    val retainedActionId = mutableStateOf<DomainId?>(null)
    val recipeEditor = mutableStateOf<FoodRecipeEditor?>(null)
    val stationEditor = mutableStateOf<FoodStationEditor?>(null)
    val routeEditor = mutableStateOf<FoodRouteEditor?>(null)
    val activationHeld = mutableStateOf(false)
    val notice = mutableStateOf<LocalText?>(null)
    val hasDraft: Boolean get() = recipeEditor.value != null || stationEditor.value?.hidden == false || routeEditor.value?.hidden == false

    fun retireAttachment() {
        lifetime.retire()
        // A submitted body's identity/content is immutable; only unstaged approval is cleared.
        stationEditor.value = stationEditor.value?.let { if (it.frozen) it else it.copy(confirmed = false) }
        routeEditor.value = routeEditor.value?.let { if (it.frozen) it else it.copy(confirmed = false) }
    }

    fun clear(denied: Boolean = false) {
        try { lifetime.retire(denied) }
        finally {
            recipeEditor.value = null; stationEditor.value = null; routeEditor.value = null
            retainedActionId.value = null; activationHeld.value = false; notice.value = null
        }
    }
}

/** Connected finite setup, active local entries only. This is not full food/local go-live completion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalFoodConfigurationCard(runtime: UkkeraRuntime, token: NavigationToken,
    drafts: LocalFoodConfigurationEntryDrafts, parentReady: Boolean,
    mayStartNew: Boolean, guardChanged: (draft: Boolean, working: Boolean) -> Unit,
    employeeAccessDenied: () -> Unit,
    activationStaged: (NavigationToken) -> Unit) {
    val session = runtime.localSessionFor(token) ?: return
    val runtimePresentation by runtime.presentation.collectAsState()
    val attachmentKey = runtimePresentation.localAttachment
    val presentationIdentity = drafts.lifetime.identity
    val gateway = remember(session, attachmentKey) { session.companyCatalog.foodConfiguration }
    val language = LocalAppLanguage.current
    fun label(english: String, arabic: String): String = language.text(LocalText(english, arabic))
    if (!gateway.configured) {
        Notice(label("Genuine local food configuration is unavailable; no unsigned recipe, invented ingredient or printer acknowledgement is accepted.",
            "إعداد الطعام المحلي الحقيقي غير متاح؛ لا تُقبل وصفة غير موقعة أو مكوّن مختلق أو تأكيد طباعة مصطنع."), caution = true)
        return
    }
    val scope = rememberCoroutineScope()
    val latestToken by rememberUpdatedState(token)
    val latestParentReady by rememberUpdatedState(parentReady)
    val latestMayStartNew by rememberUpdatedState(mayStartNew)
    val latestGuard by rememberUpdatedState(guardChanged)
    val latestStaged by rememberUpdatedState(activationStaged)
    val latestEmployeeAccessDenied by rememberUpdatedState(employeeAccessDenied)
    var alive by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(true) }
    var generation by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(0L) }
    var working by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(false) }
    var work by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<Job?>(null) }
    var retainedActionId by drafts.retainedActionId
    var pendingRefreshRequested by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(false) }
    // A failed initial read stays visible until explicit refresh. The sibling card's
    // busy/ready transitions must not create an automatic retry loop.
    var initialStatusRequested by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(drafts.lifetime.accessDenied) }
    var status by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<LocalFoodConfigurationStatus?>(null) }
    var retainedPage by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(0) }
    var recipeEditor by drafts.recipeEditor
    var stationEditor by drafts.stationEditor
    var routeEditor by drafts.routeEditor
    var picker by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<FoodSetupPicker?>(null) }
    var review by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<LocalFoodRecipeReview?>(null) }
    var completed by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<LocalFoodRecipeCompleted?>(null) }
    var activationHeld by drafts.activationHeld
    var problem by remember(session, token.entryId, attachmentKey, presentationIdentity) {
        mutableStateOf<LocalServiceProblem?>(LocalServiceProblem.DENIED.takeIf { drafts.lifetime.accessDenied })
    }
    var nativeProblem by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf<SelfOwnedInstallationFailureV1?>(null) }
    var notice by drafts.notice
    // Ephemeral only: no saveable state, journal, request, logs or employee-password reuse.
    var headLogin by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf("") }
    var headSecret by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf("") }
    var exactApproved by remember(session, token.entryId, attachmentKey, presentationIdentity) { mutableStateOf(false) }

    fun owns(): Boolean = alive && drafts.lifetime.isCurrent(presentationIdentity) &&
        runtime.ownsLocalAttachment(session, latestToken, attachmentKey)
    fun callbackCurrent(): Boolean = owns() && runtime.navigator.isCurrent(latestToken)
    fun editorReady(): Boolean = callbackCurrent() && !working && latestParentReady
    fun mayCreate(): Boolean = editorReady() && latestMayStartNew && recipeEditor == null && stationEditor == null &&
        routeEditor == null && review == null && picker == null && !activationHeld && status != null
    fun selectionAllowed(selection: FoodSetupSelection): Boolean = when (selection) {
        FoodSetupSelection.Output -> recipeEditor?.frozen == false
        is FoodSetupSelection.Ingredient -> recipeEditor?.let { !it.frozen && it.ingredients.any { component -> component.id == selection.componentId } } == true
        FoodSetupSelection.RouteRecipe -> routeEditor?.let { !it.frozen && it.expectedStorageRevision == null } == true
        FoodSetupSelection.Warehouse -> routeEditor?.frozen == false
        is FoodSetupSelection.RouteStation -> routeEditor?.let { !it.frozen && selection.routeKey in it.recipe?.routeKeys.orEmpty() } == true
        FoodSetupSelection.EditStation, FoodSetupSelection.EditRoute -> recipeEditor == null && stationEditor == null && routeEditor == null &&
            review == null && !activationHeld && latestMayStartNew && status != null
    }
    fun syncGuard() { if (owns()) latestGuard(recipeEditor != null || stationEditor?.hidden == false ||
        routeEditor?.hidden == false || picker != null || review != null, working) }
    fun unavailable(value: LocalServiceProblem, native: SelfOwnedInstallationFailureV1? = null) {
        if (value == LocalServiceProblem.DENIED) {
            headLogin = ""; headSecret = ""; exactApproved = false
            if (native != SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED) {
                latestEmployeeAccessDenied()
                return
            }
        }
        problem = value; nativeProblem = native
        if (value == LocalServiceProblem.SESSION_ENDED) runtime.localSessionEnded(session)
    }
    fun adopt(result: LocalFoodRecipeResult) {
        when (result) {
            is LocalFoodRecipeResult.Review -> {
                retainedActionId = result.review.action.originalActionId
                review = result.review; completed = null; recipeEditor = null; picker = null
                headLogin = result.review.action.headLogin; headSecret = ""; exactApproved = false
            }
            is LocalFoodRecipeResult.Completed -> {
                retainedActionId = result.publication.originalActionId
                completed = result.publication; review = null; recipeEditor = null; picker = null
                headLogin = ""; headSecret = ""; exactApproved = false
                notice = LocalText("Original head signing completed. Recipe activation, station/routing configuration and ingredient stock are separate branch operations.",
                    "اكتمل توقيع الرئيس الأصلي. تفعيل الوصفة وإعداد المحطات والمسارات ومخزون المكونات عمليات فرع منفصلة.")
            }
            is LocalFoodRecipeResult.Unavailable -> unavailable(result.problem, result.nativeReason)
        }
    }
    fun runAction(secret: CharArray? = null, body: suspend () -> Unit): Boolean {
        if (!callbackCurrent() || working || !latestParentReady) { secret?.fill('\u0000'); return false }
        working = true; problem = null; nativeProblem = null
        val ticket = ++generation
        syncGuard()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { if (owns() && generation == ticket) body(); currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: FoodSetupReadFailure) { if (owns() && generation == ticket) unavailable(failure.problem) }
            catch (_: IllegalArgumentException) { if (owns() && generation == ticket) unavailable(LocalServiceProblem.INVALID_INPUT) }
            catch (_: Exception) { if (owns() && generation == ticket) unavailable(LocalServiceProblem.UNAVAILABLE) }
            finally { if (owns() && generation == ticket) {
                working = false; syncGuard()
                // Register the read's navigation owner AFTER our critical guard unwinds.
                if (pendingRefreshRequested) { pendingRefreshRequested = false; latestStaged(runtime.navigator.token()) }
            } }
        }
        // Also clear a transferred password when cancellation wins before the body starts.
        job.invokeOnCompletion { secret?.fill('\u0000') }
        work = job
        job.start()
        return true
    }
    fun readStatus() {
        val started = runAction {
            val result = gateway.status()
            currentCoroutineContext().ensureActive()
            if (owns()) when (result) {
                is LocalFoodConfigurationStatusResult.Available -> {
                    drafts.lifetime.accessObserved(); status = result.status; retainedPage = 0
                }
                is LocalFoodConfigurationStatusResult.Unavailable -> { status = null; unavailable(result.problem, result.nativeReason) }
            }
        }
        if (started) initialStatusRequested = true
    }
    suspend fun selectedDetail(kind: LocalFoodConfigurationSourceKind, id: DomainId): LocalFoodConfigurationDetail {
        val result = gateway.source(kind, id)
        currentCoroutineContext().ensureActive()
        return when (result) {
            is LocalFoodConfigurationReadResult.Detail -> result.detail ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
            is LocalFoodConfigurationReadResult.Unavailable -> throw FoodSetupReadFailure(result.problem)
            is LocalFoodConfigurationReadResult.Page -> throw FoodSetupReadFailure(LocalServiceProblem.UNAVAILABLE)
        }
    }
    fun readPicker(selection: FoodSetupSelection, after: DomainId? = null) {
        if (!editorReady() || !selectionAllowed(selection)) return
        runAction {
            val result = gateway.sources(selection.kind, after)
            currentCoroutineContext().ensureActive()
            if (owns()) when (result) {
                is LocalFoodConfigurationReadResult.Page -> {
                    if (result.page.kind != selection.kind) unavailable(LocalServiceProblem.UNAVAILABLE)
                    else picker = FoodSetupPicker(selection, result.page)
                }
                is LocalFoodConfigurationReadResult.Unavailable -> unavailable(result.problem)
                is LocalFoodConfigurationReadResult.Detail -> unavailable(LocalServiceProblem.UNAVAILABLE)
            }
        }
    }
    fun choose(selection: FoodSetupSelection, row: LocalFoodConfigurationLabel) {
        if (!editorReady() || !selectionAllowed(selection) || picker?.selection != selection || picker?.page?.labels?.none { it == row } != false) return
        runAction {
            val detail = selectedDetail(selection.kind, row.id)
            if (detail.storageRevision() != row.storageRevision) throw FoodSetupReadFailure(LocalServiceProblem.RETRY_LATER)
            when (selection) {
                FoodSetupSelection.Output -> {
                    val source = (detail as? LocalFoodConfigurationDetail.Sku)?.source ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (!source.foodOutput()) throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (owns()) recipeEditor = recipeEditor?.copy(output = source)
                }
                is FoodSetupSelection.Ingredient -> {
                    val source = (detail as? LocalFoodConfigurationDetail.Sku)?.source ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (!source.rawIngredient()) throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (owns()) recipeEditor = recipeEditor?.let { editor -> editor.copy(ingredients = editor.ingredients.map {
                        if (it.id == selection.componentId) it.copy(sku = source) else it
                    }) }
                }
                FoodSetupSelection.RouteRecipe -> {
                    val source = (detail as? LocalFoodConfigurationDetail.Recipe)?.source ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (!source.active || source.routeKeys.size !in 1..8) throw FoodSetupReadFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                    if (owns()) routeEditor = routeEditor?.copy(recipe = source, targets = emptyMap(), confirmed = false)
                }
                FoodSetupSelection.Warehouse -> {
                    val source = (detail as? LocalFoodConfigurationDetail.Warehouse)?.source ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (!source.active) throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (owns()) routeEditor = routeEditor?.copy(warehouse = source, confirmed = false)
                }
                is FoodSetupSelection.RouteStation -> {
                    val source = (detail as? LocalFoodConfigurationDetail.Station)?.source ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (!source.active) throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (owns()) routeEditor = routeEditor?.let { it.copy(targets = it.targets + (selection.routeKey to source), confirmed = false) }
                }
                FoodSetupSelection.EditStation -> {
                    val source = (detail as? LocalFoodConfigurationDetail.Station)?.source ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (!source.active) throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (owns()) stationEditor = FoodStationEditor(stationId = source.id, expectedStorageRevision = source.storageRevision,
                        english = source.names.english, arabic = source.names.arabic, mode = source.mode)
                }
                FoodSetupSelection.EditRoute -> {
                    val source = (detail as? LocalFoodConfigurationDetail.Route)?.source ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    val recipe = (selectedDetail(LocalFoodConfigurationSourceKind.RECIPE, source.recipeId) as? LocalFoodConfigurationDetail.Recipe)?.source
                        ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    val warehouse = (selectedDetail(LocalFoodConfigurationSourceKind.WAREHOUSE, source.warehouseId) as? LocalFoodConfigurationDetail.Warehouse)?.source
                        ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                    if (!recipe.active || !warehouse.active || recipe.routeKeys.size !in 1..8) throw FoodSetupReadFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                    val targets = mutableMapOf<DomainId, LocalFoodStationSource>()
                    for ((routeKey, stationId) in source.targets) {
                        val station = (selectedDetail(LocalFoodConfigurationSourceKind.STATION, stationId) as? LocalFoodConfigurationDetail.Station)?.source
                            ?: throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                        if (!station.active) throw FoodSetupReadFailure(LocalServiceProblem.INVALID_INPUT)
                        if (routeKey in recipe.routeKeys) targets[routeKey] = station
                    }
                    if (owns()) routeEditor = FoodRouteEditor(routeId = source.id, expectedStorageRevision = source.storageRevision,
                        recipe = recipe, warehouse = warehouse, activity = source.activity, channel = source.channel, targets = targets.toMap())
                }
            }
            if (owns()) { picker = null; notice = null }
        }
    }
    fun staged(result: LocalStageResult, station: Boolean? = null) {
        when (result) {
            is LocalStageResult.Staged -> {
                if (station == true) stationEditor = null else if (station == false) routeEditor = null else activationHeld = false
                picker = null
                notice = LocalText("The ORIGINAL request is retained, not necessarily applied. Review/submit or read its original result in saved operations below. Only a durable APPLIED result confirms configuration.",
                    "حُفظ الطلب الأصلي، ولم يُطبق بالضرورة. راجعه وأرسله أو اقرأ نتيجته الأصلية في العمليات المحفوظة أدناه. تؤكد النتيجة الدائمة المُطبقة وحدها الإعداد.")
                syncGuard(); pendingRefreshRequested = true
            }
            is LocalStageResult.NotStaged -> {
                if (station == true) stationEditor = stationEditor?.copy(frozen = false, confirmed = false)
                else if (station == false) routeEditor = routeEditor?.copy(frozen = false, confirmed = false)
                else activationHeld = false
                unavailable(result.problem)
            }
            is LocalStageResult.Uncertain -> {
                notice = LocalText("Retention is uncertain. Keep original operation ${result.operationId.value}; retry exactly it or read saved operations. No replacement ID is allocated.",
                    "الحفظ غير مؤكد. احتفظ بالعملية الأصلية ${result.operationId.value}؛ أعد محاولتها بدقة أو اقرأ العمليات المحفوظة. لا يُخصص معرف بديل.")
                unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
                pendingRefreshRequested = true
            }
        }
    }

    fun retirePresentation() {
        alive = false; generation++
        val retiringWork = work; work = null
        headSecret = ""; headLogin = ""; exactApproved = false
        review = null; completed = null; status = null; picker = null; pendingRefreshRequested = false
        working = false
        retiringWork?.cancel()
    }
    DisposableEffect(session, token.entryId, attachmentKey, presentationIdentity) {
        alive = true
        val release = drafts.lifetime.attach(presentationIdentity, ::retirePresentation)
        syncGuard()
        onDispose {
            val wasCurrent = owns()
            release()
            retirePresentation()
            if (wasCurrent) latestGuard(drafts.hasDraft, false)
        }
    }
    LaunchedEffect(session, token.entryId, attachmentKey, presentationIdentity, parentReady) {
        if (parentReady && !initialStatusRequested && !working) readStatus()
    }
    val ready = parentReady && !working && owns()
    val canCreate = mayCreate()
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(label("Local restaurant / café configuration", "إعداد المطعم والمقهى المحلي"))
            Notice(label("Finite template: one complete recipe choice, no modifiers, 1–16 actual raw ingredients and one company-signed route key. Prepare/review and head signing are separate from employee branch activation. Stations and complete routing use the same protected branch journal. Not full food, payment, batch, printer or go-live completion.",
                "قالب محدود: اختيار وصفة كامل واحد، دون إضافات، مع 1–16 مكونًا خامًا حقيقيًا ومفتاح مسار واحد موقع من الشركة. الإعداد والمراجعة وتوقيع الرئيس منفصلة عن تفعيل موظف الفرع. تستخدم المحطات والتوجيه الكامل سجل الفرع المحمي نفسه. هذا ليس اكتمال الطعام أو الدفع أو الدفعات أو الطابعة أو بدء التشغيل."))
            if (working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(label("Waiting for the original native/read/journal operation. Lock and retained recovery remain available.",
                    "بانتظار عملية القراءة أو الحفظ الأصلي أو السجل الأصلية. يظل القفل والاسترداد المحفوظ متاحين."),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            problem?.let { Notice(localProblem(it), caution = true) }
            nativeProblem?.let { Text(label("Native result ${it.name}; continue only the exact original retained action.",
                "النتيجة الأصلية ${it.name}؛ تابع الإجراء الأصلي المحفوظ الدقيق فقط.")) }
            notice?.let { Notice(language.text(it), caution = true) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { readStatus() }, enabled = ready, modifier = Modifier.touchTarget()) {
                    Text(label("Read original recipe publications", "قراءة إصدارات الوصفات الأصلية")) }
                Button(onClick = { if (mayCreate()) { recipeEditor = FoodRecipeEditor(); completed = null; syncGuard() } },
                    enabled = canCreate, modifier = Modifier.touchTarget()) { Text(label("New recipe proposal", "اقتراح وصفة جديدة")) }
                OutlinedButton(onClick = { if (mayCreate()) { stationEditor = FoodStationEditor(); syncGuard() } },
                    enabled = canCreate, modifier = Modifier.touchTarget()) { Text(label("New kitchen station", "محطة مطبخ جديدة")) }
                OutlinedButton(onClick = { readPicker(FoodSetupSelection.EditStation) }, enabled = canCreate, modifier = Modifier.touchTarget()) {
                    Text(label("Read / rename existing station", "قراءة محطة قائمة أو إعادة تسميتها")) }
                OutlinedButton(onClick = { if (mayCreate()) { routeEditor = FoodRouteEditor(); syncGuard() } },
                    enabled = canCreate, modifier = Modifier.touchTarget()) { Text(label("New complete recipe route", "مسار وصفة كامل جديد")) }
                OutlinedButton(onClick = { readPicker(FoodSetupSelection.EditRoute) }, enabled = canCreate, modifier = Modifier.touchTarget()) {
                    Text(label("Read / update existing route", "قراءة مسار قائم أو تحديثه")) }
            }

            recipeEditor?.let { editor ->
                FoodRecipeTemplate(editor, ready && !editor.frozen, edit = { if (editorReady() && recipeEditor?.publicationId == editor.publicationId && recipeEditor?.frozen == false) recipeEditor = it },
                    output = { readPicker(FoodSetupSelection.Output) }, ingredient = { readPicker(FoodSetupSelection.Ingredient(it)) })
                IdentifierText(label("Stable native publication draft", "مسودة الإصدار الأصلي الثابتة"), editor.publicationId.value)
                Button(onClick = {
                    if (!editorReady()) return@Button
                    val exact = try { editor.toDraft() } catch (_: Exception) { unavailable(LocalServiceProblem.INVALID_INPUT); return@Button }
                    recipeEditor = editor.copy(frozen = true)
                    runAction { val result = gateway.prepare(exact); currentCoroutineContext().ensureActive(); if (owns()) adopt(result) }
                }, enabled = ready && editor.complete && mayStartNew, modifier = Modifier.touchTarget()) {
                    Text(label(if (editor.frozen) "Retry retaining EXACT original proposal — not sign" else "Retain exact proposal and review — not sign",
                        if (editor.frozen) "إعادة حفظ الاقتراح الأصلي الدقيق — دون توقيع" else "حفظ الاقتراح الدقيق ومراجعته — دون توقيع")) }
                if (editor.frozen) Notice(label("This original recipe draft is frozen after native retention was requested. Read its retained publication or retry identical content; no fresh revision or replacement proposal is guessed.",
                    "جُمِّدت مسودة الوصفة الأصلية بعد طلب حفظها. اقرأ إصدارها المحفوظ أو أعد محاولة المحتوى المطابق؛ لا تُختلق مراجعة جديدة أو مسودة بديلة."), caution = true)
                else OutlinedButton(onClick = { if (callbackCurrent() && ready) { recipeEditor = null; picker = null; syncGuard() } },
                    enabled = ready, modifier = Modifier.touchTarget()) { Text(label("Dismiss unsigned editor", "إغلاق المسودة غير الموقعة")) }
            }
            stationEditor?.let { editor ->
                if (editor.hidden) OutlinedButton(onClick = { if (callbackCurrent() && ready) { stationEditor = editor.copy(hidden = false); syncGuard() } },
                    enabled = ready, modifier = Modifier.touchTarget()) { Text(label("Restore SAME original station draft", "استعادة مسودة المحطة الأصلية نفسها")) }
                else {
                    FoodStationTemplate(editor, ready && !editor.frozen) { if (editorReady() && stationEditor?.operationId == editor.operationId && stationEditor?.frozen == false) stationEditor = it }
                    IdentifierText(label("Original station operation", "عملية المحطة الأصلية"), editor.operationId.value)
                    Button(onClick = {
                        if (!editorReady()) return@Button
                        val exact = try { editor.toDraft() } catch (_: Exception) { unavailable(LocalServiceProblem.INVALID_INPUT); return@Button }
                        stationEditor = editor.copy(frozen = true)
                        runAction { val result = gateway.stageStation(exact); currentCoroutineContext().ensureActive(); if (owns()) staged(result, true) }
                    }, enabled = ready && editor.complete, modifier = Modifier.touchTarget()) { Text(label(
                        if (editor.frozen) "Retry SAME station retention" else "Stage station configuration — not print",
                        if (editor.frozen) "إعادة حفظ المحطة نفسها" else "حفظ إعداد المحطة — دون طباعة")) }
                    OutlinedButton(onClick = { if (callbackCurrent() && ready) {
                        if (editor.frozen) stationEditor = editor.copy(hidden = true) else stationEditor = null
                        picker = null; syncGuard()
                    } }, enabled = ready, modifier = Modifier.touchTarget()) { Text(label(
                        if (editor.frozen) "Hide frozen editor; recover original in saved operations" else "Dismiss station editor",
                        if (editor.frozen) "إخفاء المسودة المجمدة واسترداد الأصل من العمليات المحفوظة" else "إغلاق مسودة المحطة")) }
                }
            }
            routeEditor?.let { editor ->
                if (editor.hidden) OutlinedButton(onClick = { if (callbackCurrent() && ready) { routeEditor = editor.copy(hidden = false); syncGuard() } },
                    enabled = ready, modifier = Modifier.touchTarget()) { Text(label("Restore SAME original route draft", "استعادة مسودة المسار الأصلية نفسها")) }
                else {
                    FoodRouteTemplate(editor, ready && !editor.frozen, edit = { if (editorReady() && routeEditor?.operationId == editor.operationId && routeEditor?.frozen == false) routeEditor = it },
                        recipe = { readPicker(FoodSetupSelection.RouteRecipe) }, warehouse = { readPicker(FoodSetupSelection.Warehouse) },
                        station = { readPicker(FoodSetupSelection.RouteStation(it)) })
                    IdentifierText(label("Original route operation", "عملية المسار الأصلية"), editor.operationId.value)
                    Button(onClick = {
                        if (!editorReady()) return@Button
                        val exact = try { editor.toDraft() } catch (_: Exception) { unavailable(LocalServiceProblem.INVALID_INPUT); return@Button }
                        routeEditor = editor.copy(frozen = true)
                        runAction { val result = gateway.stageRoute(exact); currentCoroutineContext().ensureActive(); if (owns()) staged(result, false) }
                    }, enabled = ready && editor.complete, modifier = Modifier.touchTarget()) { Text(label(
                        if (editor.frozen) "Retry SAME complete route retention" else "Stage COMPLETE routing — no send or stock issue",
                        if (editor.frozen) "إعادة حفظ المسار الكامل نفسه" else "حفظ التوجيه الكامل — دون إرسال أو صرف مخزون")) }
                    OutlinedButton(onClick = { if (callbackCurrent() && ready) {
                        if (editor.frozen) routeEditor = editor.copy(hidden = true) else routeEditor = null
                        picker = null; syncGuard()
                    } }, enabled = ready, modifier = Modifier.touchTarget()) { Text(label(
                        if (editor.frozen) "Hide frozen editor; recover original in saved operations" else "Dismiss route editor",
                        if (editor.frozen) "إخفاء المسودة المجمدة واسترداد الأصل من العمليات المحفوظة" else "إغلاق مسودة المسار")) }
                }
            }

            picker?.let { selected ->
                SectionHeading(label("Protected source selection · ${selected.selection.kind.name}", "اختيار مصدر محمي · ${selected.selection.kind.name}"))
                Notice(label("Labels are actual bounded branch reads. Selecting one reads its complete current definition; the gateway rechecks exact selected revisions before a NEW proposal. IDs alone never authorize a recipe or route.",
                    "الأوصاف قراءات فرع فعلية محدودة. يقرأ الاختيار تعريف المصدر الحالي الكامل؛ يتحقق المدخل من المراجعات المختارة الدقيقة قبل اقتراح جديد. المعرفات وحدها لا تجيز وصفة أو مسارًا."))
                if (selected.page.labels.isEmpty()) Text(label("No sources in this page. Configure and durably activate the required source first.",
                    "لا توجد مصادر بهذه الصفحة. اضبط المصدر المطلوب وفعّله بصورة دائمة أولًا."))
                selected.page.labels.forEach { row ->
                    OutlinedButton(onClick = { choose(selected.selection, row) }, enabled = ready && row.active, modifier = Modifier.fillMaxWidth().touchTarget()) {
                        Column { Text(language.text(row.names)); Text("${row.id.value} · r${row.revision.value} · storage ${row.storageRevision.value}",
                            style = MaterialTheme.typography.bodySmall) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { readPicker(selected.selection) }, enabled = ready, modifier = Modifier.touchTarget()) {
                        Text(label("Refresh first source page", "تحديث صفحة المصادر الأولى")) }
                    OutlinedButton(onClick = { readPicker(selected.selection, selected.page.nextAfter) }, enabled = ready && selected.page.nextAfter != null,
                        modifier = Modifier.touchTarget()) { Text(label("Read next source page", "قراءة صفحة المصادر التالية")) }
                    OutlinedButton(onClick = { if (callbackCurrent() && ready) { picker = null; syncGuard() } }, enabled = ready,
                        modifier = Modifier.touchTarget()) { Text(label("Close source selector", "إغلاق اختيار المصدر")) }
                }
            }

            if (review == null && completed == null) retainedActionId?.let { original ->
                IdentifierText(label("Original publication retained across session refresh", "الإصدار الأصلي المحفوظ بعد تجديد الجلسة"), original.value)
                OutlinedButton(onClick = {
                    runAction {
                        val result = gateway.review(original)
                        currentCoroutineContext().ensureActive()
                        if (owns()) adopt(result)
                    }
                }, enabled = ready, modifier = Modifier.touchTarget()) {
                    Text(label("Read SAME original publication before continuing", "قراءة الإصدار الأصلي نفسه قبل المتابعة"))
                }
            }

            review?.let { exact ->
                SectionHeading(label("Exact native company-head recipe review", "مراجعة الوصفة الأصلية الدقيقة لرئيس الشركة"))
                FoodRecipePreview(exact.preview)
                IdentifierText(label("Original native action", "الإجراء الأصلي"), exact.action.originalActionId.value)
                IdentifierText(label("Company / organization", "الشركة والمؤسسة"),
                    "${exact.action.proposal.company.companyId.value} / ${exact.action.proposal.company.organizationId.value}")
                IdentifierText(label("Endorsed signing key", "مفتاح التوقيع المجاز"), exact.action.signingKeyId.value)
                IdentifierText(label("Original root fingerprint", "بصمة جذر الثقة الأصلي"), exact.action.rootFingerprint)
                IdentifierText(label("Exact content SHA-256", "بصمة المحتوى الدقيق SHA-256"), exact.action.proposal.contentDigest.value)
                Text(label("Company ${exact.action.companyName}; separate head ${exact.action.headName} (${exact.action.headLogin}).",
                    "الشركة ${exact.action.companyName}؛ الرئيس المنفصل ${exact.action.headName} (${exact.action.headLogin})."))
                Notice(label("The original native head endorses ONLY this exact recipe and ephemeral signing key. Employee CATALOG_ACTIVATE / ADMINISTER does not replace head authentication or create a reusable signing approval. Signing does not activate, send, consume stock, charge or acknowledge paper output.",
                    "يجيز الرئيس الأصلي هذا المحتوى الدقيق ومفتاح التوقيع المؤقت فقط. لا تستبدل صلاحية الموظف CATALOG_ACTIVATE / ADMINISTER تحقق الرئيس ولا تنشئ موافقة توقيع قابلة لإعادة الاستخدام. لا يفعّل التوقيع أو يرسل أو يستهلك مخزونًا أو يحصل أموالًا أو يؤكد إخراجًا ورقيًا."), caution = true)
                FoodSetupField(label("Separate company-head login", "اسم دخول رئيس الشركة المنفصل"), headLogin, 128, ready) { if (editorReady()) headLogin = it }
                OutlinedTextField(headSecret, { if (editorReady() && it.length <= 256) headSecret = it }, singleLine = true,
                    label = { Text(label("Company-head password — not employee password", "كلمة مرور رئيس الشركة — ليست كلمة مرور الموظف")) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = ready, modifier = Modifier.fillMaxWidth())
                FoodSetupCheck(label("I approve the EXACT recipe, ingredients, quantities, choices, pricing SKU and route keys displayed.",
                    "أوافق على الوصفة والمكونات والكميات والاختيارات وصنف التسعير ومفاتيح المسارات الدقيقة المعروضة."), exactApproved, ready) { if (editorReady()) exactApproved = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val password = headSecret.toCharArray(); val login = headLogin; headSecret = ""; exactApproved = false
                        if (!runAction(secret = password) {
                            try { val result = gateway.approve(exact, login, password); currentCoroutineContext().ensureActive(); if (owns()) adopt(result) }
                            finally { password.fill('\u0000') }
                        }) password.fill('\u0000')
                    }, enabled = ready && exactApproved && headLogin.isNotBlank() && headSecret.length in 4..256,
                        modifier = Modifier.touchTarget()) { Text(label("Authenticate head and sign EXACT recipe", "التحقق من الرئيس وتوقيع الوصفة الدقيقة")) }
                    OutlinedButton(onClick = { headSecret = ""; exactApproved = false
                        runAction { val result = gateway.review(exact.action.originalActionId); currentCoroutineContext().ensureActive(); if (owns()) adopt(result) }
                    }, enabled = ready, modifier = Modifier.touchTarget()) { Text(label("Read original exact review", "قراءة المراجعة الأصلية الدقيقة")) }
                    OutlinedButton(onClick = { if (callbackCurrent() && ready) {
                        headSecret = ""; headLogin = ""; exactApproved = false; review = null; syncGuard()
                    } }, enabled = ready, modifier = Modifier.touchTarget()) { Text(label("Close review; preserve original native proposal", "إغلاق المراجعة مع حفظ الاقتراح الأصلي")) }
                }
            }
            completed?.let { signed ->
                SectionHeading(label("Head signed — separate branch activation", "وقّع الرئيس — تفعيل الفرع منفصل"))
                FoodRecipePreview(signed.preview)
                IdentifierText(label("Original signing / activation identity", "معرف التوقيع والتفعيل الأصلي"), signed.originalActionId.value)
                Button(onClick = {
                    if (!editorReady()) return@Button
                    activationHeld = true
                    runAction { val result = gateway.stageActivation(signed.originalActionId); currentCoroutineContext().ensureActive(); if (owns()) staged(result) }
                }, enabled = ready && recipeEditor == null && stationEditor == null && routeEditor == null && picker == null,
                    modifier = Modifier.touchTarget()) { Text(label("Stage SAME exact recipe activation — not sign or send",
                        "حفظ تفعيل الوصفة الدقيقة نفسها — دون توقيع أو إرسال")) }
                if (activationHeld) Notice(label("Original activation may be retained. Recover it in saved operations or retry only the identity above. New setup drafts are held; no replacement operation is created.",
                    "قد يكون التفعيل الأصلي محفوظًا. استرده من العمليات المحفوظة أو أعد محاولة المعرف أعلاه فقط. تُعلّق مسودات الإعداد الجديدة؛ لا تُنشأ عملية بديلة."), caution = true)
            }
            status?.let { retained ->
                Text(label("Native recipe publications ${retained.references.size}; master observation ${retained.observedAt.canonicalString()}.",
                    "إصدارات الوصفات الأصلية ${retained.references.size}؛ رصد الجهاز الرئيسي ${retained.observedAt.canonicalString()}."), style = MaterialTheme.typography.bodySmall)
                retained.references.drop(retainedPage * 8).take(8).forEach { reference ->
                    IdentifierText(label("Recipe publication · ${reference.phase.name}", "إصدار الوصفة · ${reference.phase.name}"), reference.publicationId.value)
                    if (reference.phase == CompanyBusinessPublicationPhase.SIGNING_INTENT) Notice(label(
                        "HELD signing intent: a key/signature may already have been used. No regeneration, re-signing, replacement or automatic repair is permitted.",
                        "نية توقيع معلقة: ربما استُخدم مفتاح أو توقيع بالفعل. لا تُسمح إعادة التوليد أو التوقيع أو الاستبدال أو الإصلاح التلقائي."), caution = true)
                    else OutlinedButton(onClick = {
                        if (activationHeld && completed?.originalActionId != reference.originalActionId) return@OutlinedButton
                        runAction { val result = gateway.review(reference.originalActionId); currentCoroutineContext().ensureActive(); if (owns()) adopt(result) }
                    }, enabled = ready && (!activationHeld || completed?.originalActionId == reference.originalActionId) &&
                        stationEditor == null && routeEditor == null && picker == null &&
                        (recipeEditor == null || recipeEditor?.frozen == true && recipeEditor?.publicationId == reference.publicationId),
                        modifier = Modifier.touchTarget()) { Text(label("Read EXACT original action / completed output", "قراءة الإجراء الأصلي الدقيق أو ناتجه المكتمل")) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (callbackCurrent() && ready) retainedPage-- }, enabled = ready && retainedPage > 0,
                        modifier = Modifier.touchTarget()) { Text(label("Previous retained page", "صفحة محفوظة سابقة")) }
                    OutlinedButton(onClick = { if (callbackCurrent() && ready) retainedPage++ }, enabled = ready && (retainedPage + 1) * 8 < retained.references.size,
                        modifier = Modifier.touchTarget()) { Text(label("Next retained page", "صفحة محفوظة تالية")) }
                }
            }
        }
    }
}

internal data class FoodIngredientEditor(val id: DomainId = DomainId.random(), val sku: LocalFoodSkuSource? = null, val quantity: String = "")
internal data class FoodRecipeEditor(val publicationId: DomainId = DomainId.random(), val recipeId: DomainId = DomainId.random(),
    val choiceId: DomainId = DomainId.random(), val routeKey: DomainId = DomainId.random(), val output: LocalFoodSkuSource? = null,
    val english: String = "", val arabic: String = "", val yield: String = "", val portion: String = "",
    val ingredients: List<FoodIngredientEditor> = listOf(FoodIngredientEditor()), val frozen: Boolean = false) {
    val complete: Boolean get() = output?.foodOutput() == true && (english.isNotBlank() || arabic.isNotBlank()) && yield.isNotBlank() &&
        portion.isNotBlank() && ingredients.size in 1..16 && ingredients.all { it.sku?.rawIngredient() == true && it.quantity.isNotBlank() }
    fun toDraft(): LocalFoodRecipeDraft {
        require(complete && english.length <= 160 && arabic.length <= 160)
        val source = requireNotNull(output)
        val y = Quantity.parse(source.baseUnitId, yield, source.quantityScale).requirePositive()
        val p = Quantity.parse(source.baseUnitId, portion, source.quantityScale).requirePositive()
        val components = ingredients.map { component ->
            val sku = requireNotNull(component.sku)
            val q = Quantity.parse(sku.baseUnitId, component.quantity, sku.quantityScale).requirePositive()
            require((q.toFraction() * p.toFraction() / y.toFraction()).toScaledLongExact(q.scale) > 0)
            LocalFoodRecipeIngredientDraft(component.id, sku, component.quantity)
        }
        return LocalFoodRecipeDraft(publicationId, recipeId, source, null, yield, portion,
            listOf(LocalFoodRecipeChoiceDraft(choiceId, LocalText(english, arabic), emptyList(), components, listOf(routeKey), source)))
    }
}
internal data class FoodStationEditor(val operationId: OperationId = OperationId.random(), val stationId: DomainId = DomainId.random(),
    val expectedStorageRevision: Revision? = null, val english: String = "", val arabic: String = "",
    val mode: LocalFoodConfigurationStationMode = LocalFoodConfigurationStationMode.DISPLAY,
    val confirmed: Boolean = false, val frozen: Boolean = false, val hidden: Boolean = false) {
    val complete: Boolean get() = (english.isNotBlank() || arabic.isNotBlank()) && confirmed
    fun toDraft(): LocalFoodStationDraft {
        require(complete && english.length <= 160 && arabic.length <= 160)
        return LocalFoodStationDraft(operationId, stationId, expectedStorageRevision, LocalText(english, arabic), mode)
    }
}
internal data class FoodRouteEditor(val operationId: OperationId = OperationId.random(), val routeId: DomainId = DomainId.random(),
    val expectedStorageRevision: Revision? = null, val recipe: LocalFoodRecipeSource? = null, val warehouse: LocalFoodWarehouseSource? = null,
    val activity: LocalFoodConfigurationActivity = LocalFoodConfigurationActivity.RESTAURANT,
    val channel: LocalFoodConfigurationChannel = LocalFoodConfigurationChannel.COUNTER,
    val targets: Map<DomainId, LocalFoodStationSource> = emptyMap(), val confirmed: Boolean = false,
    val frozen: Boolean = false, val hidden: Boolean = false) {
    val complete: Boolean get() = recipe?.let { selected -> confirmed && selected.active && selected.routeKeys.size in 1..8 && warehouse?.active == true &&
        targets.keys == selected.routeKeys.toSet() && targets.values.all { it.active } } == true
    fun toDraft(): LocalFoodRouteDraft {
        require(complete)
        val selected = requireNotNull(recipe)
        return LocalFoodRouteDraft(operationId, routeId, expectedStorageRevision, selected, activity, channel, requireNotNull(warehouse),
            selected.routeKeys.map { LocalFoodRouteTarget(it, requireNotNull(targets[it])) })
    }
}
private sealed interface FoodSetupSelection {
    val kind: LocalFoodConfigurationSourceKind
    data object Output : FoodSetupSelection { override val kind = LocalFoodConfigurationSourceKind.SKU }
    data class Ingredient(val componentId: DomainId) : FoodSetupSelection { override val kind = LocalFoodConfigurationSourceKind.SKU }
    data object RouteRecipe : FoodSetupSelection { override val kind = LocalFoodConfigurationSourceKind.RECIPE }
    data object Warehouse : FoodSetupSelection { override val kind = LocalFoodConfigurationSourceKind.WAREHOUSE }
    data class RouteStation(val routeKey: DomainId) : FoodSetupSelection { override val kind = LocalFoodConfigurationSourceKind.STATION }
    data object EditStation : FoodSetupSelection { override val kind = LocalFoodConfigurationSourceKind.STATION }
    data object EditRoute : FoodSetupSelection { override val kind = LocalFoodConfigurationSourceKind.ROUTE }
}
private data class FoodSetupPicker(val selection: FoodSetupSelection, val page: LocalFoodConfigurationSourcePage)
private class FoodSetupReadFailure(val problem: LocalServiceProblem) : Exception()
private fun LocalFoodSkuSource.foodOutput(): Boolean = active && canSell && fulfillment == LocalCompanySkuFulfillment.MADE_TO_ORDER && skuRevision.value > 0
private fun LocalFoodSkuSource.rawIngredient(): Boolean = active && canPurchase && fulfillment == LocalCompanySkuFulfillment.RETAIL &&
    tracking in setOf(LocalCompanyStockTracking.QUANTITY, LocalCompanyStockTracking.LOT)
private fun LocalFoodConfigurationDetail.storageRevision(): Revision = when (this) {
    is LocalFoodConfigurationDetail.Sku -> source.storageRevision
    is LocalFoodConfigurationDetail.Recipe -> source.storageRevision
    is LocalFoodConfigurationDetail.Station -> source.storageRevision
    is LocalFoodConfigurationDetail.Warehouse -> source.storageRevision
    is LocalFoodConfigurationDetail.Route -> source.storageRevision
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodRecipeTemplate(value: FoodRecipeEditor, enabled: Boolean, edit: (FoodRecipeEditor) -> Unit,
    output: () -> Unit, ingredient: (DomainId) -> Unit) {
    val language = LocalAppLanguage.current
    fun label(english: String, arabic: String): String = language.text(LocalText(english, arabic))
    SectionHeading(label("One complete recipe choice — no modifiers", "اختيار وصفة كامل واحد — دون إضافات"))
    OutlinedButton(onClick = output, enabled = enabled, modifier = Modifier.touchTarget()) {
        Text(label("Choose actually activated made-to-order output SKU", "اختيار صنف طعام حسب الطلب مُفعّل فعلًا")) }
    value.output?.let { FoodSetupSku(it) }
    FoodSetupField(label("Choice name in English", "اسم الاختيار بالإنجليزية"), value.english, 160, enabled) { edit(value.copy(english = it)) }
    FoodSetupField(label("Choice name in Arabic", "اسم الاختيار بالعربية"), value.arabic, 160, enabled) { edit(value.copy(arabic = it)) }
    FoodSetupField(label("Recipe yield in output base unit (exact)", "ناتج الوصفة بالوحدة الأساسية للمنتج — دقيق"), value.yield, 64, enabled, true) { edit(value.copy(yield = it)) }
    FoodSetupField(label("One portion in same output base unit (exact)", "حصة واحدة بالوحدة الأساسية نفسها — دقيقة"), value.portion, 64, enabled, true) { edit(value.copy(portion = it)) }
    IdentifierText(label("Company-proposed complete choice / route key", "اختيار الشركة الكامل ومفتاح المسار المقترح"), "${value.choiceId.value} / ${value.routeKey.value}")
    Text(label("Pricing uses the selected output's actual current SKU; no manually calculated price is signed here.",
        "يستخدم التسعير صنف المنتج الحالي الفعلي المختار؛ لا يُوقع هنا سعر محسوب يدويًا."))
    value.ingredients.forEach { component ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { ingredient(component.id) }, enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(label("Choose actual purchasable raw quantity/lot ingredient", "اختيار مكوّن خام فعلي قابل للشراء وتتبع الكمية أو الدفعة")) }
            component.sku?.let { FoodSetupSku(it) }
            FoodSetupField(label("Ingredient quantity per complete recipe yield (its exact base unit)", "كمية المكون لكل ناتج وصفة كامل — بوحدته الأساسية الدقيقة"),
                component.quantity, 64, enabled, true) { quantity -> edit(value.copy(ingredients = value.ingredients.map { if (it.id == component.id) it.copy(quantity = quantity) else it })) }
            OutlinedButton(onClick = { edit(value.copy(ingredients = value.ingredients.filterNot { it.id == component.id })) },
                enabled = enabled && value.ingredients.size > 1, modifier = Modifier.touchTarget()) { Text(label("Remove this unsigned ingredient line", "إزالة سطر المكون غير الموقع")) }
        }
    }
    OutlinedButton(onClick = { edit(value.copy(ingredients = value.ingredients + FoodIngredientEditor())) },
        enabled = enabled && value.ingredients.size < 16, modifier = Modifier.touchTarget()) { Text(label("Add ingredient (up to 16)", "إضافة مكون — حتى 16")) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodStationTemplate(value: FoodStationEditor, enabled: Boolean, edit: (FoodStationEditor) -> Unit) {
    val language = LocalAppLanguage.current
    fun label(english: String, arabic: String): String = language.text(LocalText(english, arabic))
    SectionHeading(label("Kitchen station configuration", "إعداد محطة المطبخ"))
    FoodSetupField(label("English name", "الاسم بالإنجليزية"), value.english, 160, enabled) { edit(value.copy(english = it, confirmed = false)) }
    FoodSetupField(label("Arabic name", "الاسم بالعربية"), value.arabic, 160, enabled) { edit(value.copy(arabic = it, confirmed = false)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalFoodConfigurationStationMode.entries.forEach { mode -> FilterChip(value.mode == mode,
            { edit(value.copy(mode = mode, confirmed = false)) }, enabled = enabled && value.expectedStorageRevision == null,
            label = { Text(mode.name) }, modifier = Modifier.touchTarget()) }
    }
    Text(label("Existing modes/retirement require draining and reconciliation; this template only creates stations or changes existing names. PAPER does not qualify a printer or assert physical printing.",
        "يتطلب تغيير الوضع أو إيقاف محطة قائمة تفريغ العمل والمعالجة؛ ينشئ هذا القالب المحطات أو يغير أسماء القائمة فقط. PAPER لا يعتمد طابعة أو يؤكد طباعة فعلية."))
    FoodSetupCheck(label("I confirm this station name/mode; branch KITCHEN_TASK / ADMINISTER is rechecked.",
        "أؤكد اسم المحطة ووضعها؛ تُراجع صلاحية الفرع KITCHEN_TASK / ADMINISTER."), value.confirmed, enabled) { edit(value.copy(confirmed = it)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodRouteTemplate(value: FoodRouteEditor, enabled: Boolean, edit: (FoodRouteEditor) -> Unit,
    recipe: () -> Unit, warehouse: () -> Unit, station: (DomainId) -> Unit) {
    val language = LocalAppLanguage.current
    fun label(english: String, arabic: String): String = language.text(LocalText(english, arabic))
    SectionHeading(label("Complete recipe → station / warehouse routing", "توجيه كامل من الوصفة إلى المحطات والمخزن"))
    OutlinedButton(onClick = recipe, enabled = enabled && value.expectedStorageRevision == null, modifier = Modifier.touchTarget()) {
        Text(label("Choose actually activated current recipe", "اختيار وصفة حالية مُفعّلة فعلًا")) }
    value.recipe?.let { selected ->
        Text(language.text(selected.names))
        IdentifierText(label("Recipe / domain revision / storage revision", "الوصفة ومراجعة النطاق ومراجعة التخزين"),
            "${selected.id.value} / ${selected.revision.value} / ${selected.storageRevision.value}")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalFoodConfigurationActivity.entries.forEach { activity -> FilterChip(value.activity == activity,
            { edit(value.copy(activity = activity, confirmed = false)) }, enabled = enabled && value.expectedStorageRevision == null,
            label = { Text(activity.name) }, modifier = Modifier.touchTarget()) }
        LocalFoodConfigurationChannel.entries.forEach { channel -> FilterChip(value.channel == channel,
            { edit(value.copy(channel = channel, confirmed = false)) }, enabled = enabled && value.expectedStorageRevision == null,
            label = { Text(channel.name) }, modifier = Modifier.touchTarget()) }
    }
    OutlinedButton(onClick = warehouse, enabled = enabled, modifier = Modifier.touchTarget()) {
        Text(label("Choose actual ingredient stock warehouse", "اختيار مخزن مكونات الطعام الفعلي")) }
    value.warehouse?.let { Text(language.text(it.names)); IdentifierText(label("Warehouse / storage revision", "المخزن ومراجعة التخزين"), "${it.id.value} / ${it.storageRevision.value}") }
    value.recipe?.routeKeys?.forEach { routeKey ->
        IdentifierText(label("Required route key", "مفتاح المسار المطلوب"), routeKey.value)
        value.targets[routeKey]?.let { Text(language.text(it.names)); IdentifierText(label("Station / storage revision", "المحطة ومراجعة التخزين"), "${it.id.value} / ${it.storageRevision.value}") }
        OutlinedButton(onClick = { station(routeKey) }, enabled = enabled, modifier = Modifier.touchTarget()) {
            Text(label("Choose actual station for this exact key", "اختيار محطة فعلية لهذا المفتاح الدقيق")) }
    }
    Notice(label("Every route key is required; at most 8, without silent truncation. Within each complete choice, separate route keys need separate stations. Existing recipe/activity/channel identity is immutable. Stock opening/receiving and hardware checks are separate.",
        "كل مفتاح مسار مطلوب؛ حتى 8 دون اختصار صامت. تحتاج المفاتيح المنفصلة داخل كل اختيار كامل إلى محطات منفصلة. لا تتغير هوية الوصفة والنشاط والقناة القائمة. الجرد الافتتاحي والاستلام وفحص المعدات منفصلة."))
    FoodSetupCheck(label("I confirm ALL selected recipe keys, stations, warehouse and channel; current WAREHOUSE_CONFIGURE / ADMINISTER is rechecked.",
        "أؤكد كل مفاتيح الوصفة والمحطات والمخزن والقناة المختارة؛ تُراجع صلاحية WAREHOUSE_CONFIGURE / ADMINISTER الحالية."),
        value.confirmed, enabled) { edit(value.copy(confirmed = it)) }
}

@Composable
private fun FoodRecipePreview(value: LocalFoodRecipePreview) {
    val language = LocalAppLanguage.current
    fun label(english: String, arabic: String): String = language.text(LocalText(english, arabic))
    IdentifierText(label("Recipe / genuinely signed recipe revision", "الوصفة ومراجعتها الموقعة فعلًا"), "${value.recipeId.value} / ${value.revision.value}")
    IdentifierText(label("Output SKU / genuinely signed SKU revision", "صنف المنتج ومراجعته الموقعة فعلًا"), "${value.outputSkuId.value} / ${value.outputSkuRevision.value}")
    Text(label("Yield ${language.foodQuantity(value.yieldQuantity)}; portion ${language.foodQuantity(value.portionQuantity)}; active ${value.active}.",
        "الناتج ${language.foodQuantity(value.yieldQuantity)}؛ الحصة ${language.foodQuantity(value.portionQuantity)}؛ نشط ${value.active}."))
    IdentifierText(label("Output base unit / precision", "الوحدة الأساسية للمنتج ودقتها"), "${value.portionQuantity.unitId.value} / ${value.portionQuantity.scale}")
    value.choices.forEach { choice ->
        Text(language.text(choice.names), style = MaterialTheme.typography.titleSmall)
        IdentifierText(label("Complete choice / pricing SKU", "الاختيار الكامل وصنف التسعير"), "${choice.id.value} / ${choice.pricingSkuId.value}")
        Text(label("Modifiers: ${if (choice.modifierIds.isEmpty()) "NONE" else choice.modifierIds.joinToString { it.value }}",
            "الإضافات: ${if (choice.modifierIds.isEmpty()) "لا توجد" else choice.modifierIds.joinToString { it.value }}"))
        choice.ingredients.forEach { component ->
            IdentifierText(label("Ingredient component / SKU / signed revision", "عنصر المكون وصنفه ومراجعته الموقعة"),
                "${component.componentId.value} / ${component.skuId.value} / ${component.skuRevision.value}")
            Text(label("Per yield ${language.foodQuantity(component.quantityPerYield)} · precision ${component.quantityPerYield.scale}",
                "لكل ناتج ${language.foodQuantity(component.quantityPerYield)} · الدقة ${component.quantityPerYield.scale}"))
            IdentifierText(label("Exact ingredient base unit", "الوحدة الأساسية الدقيقة للمكون"), component.quantityPerYield.unitId.value)
        }
        choice.routeKeys.forEach { IdentifierText(label("Exact company route key", "مفتاح مسار الشركة الدقيق"), it.value) }
    }
}

@Composable
private fun FoodSetupSku(value: LocalFoodSkuSource) {
    val language = LocalAppLanguage.current
    Text(language.text(value.names))
    IdentifierText(language.text(LocalText("Actual SKU / signed revision / storage revision", "الصنف الفعلي ومراجعته الموقعة ومراجعة تخزينه")),
        "${value.id.value} / ${value.skuRevision.value} / ${value.storageRevision.value}")
    IdentifierText(language.text(LocalText("Exact base unit / precision", "الوحدة الأساسية الدقيقة ودقتها")), "${value.baseUnitId.value} / ${value.quantityScale}")
    Text("${value.fulfillment?.name ?: "UNSUPPORTED"} · ${value.tracking?.name ?: "UNSUPPORTED"} · canSell=${value.canSell} · canPurchase=${value.canPurchase}")
}
@Composable
private fun FoodSetupField(title: String, value: String, maximum: Int, enabled: Boolean, decimal: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, { if (enabled && it.length <= maximum) change(it) }, label = { Text(title) }, enabled = enabled,
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth())
}
@Composable
private fun FoodSetupCheck(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().touchTarget().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f))
    }
}
