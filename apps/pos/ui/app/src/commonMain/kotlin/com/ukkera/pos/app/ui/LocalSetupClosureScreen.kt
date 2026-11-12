package com.ukkera.pos.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalSetupCatalogRow
import com.ukkera.pos.app.LocalSetupCheckpointView
import com.ukkera.pos.app.LocalSetupClosureAvailability
import com.ukkera.pos.app.LocalSetupClosureController
import com.ukkera.pos.app.LocalSetupClosureState
import com.ukkera.pos.app.LocalSetupConfigurationInput
import com.ukkera.pos.app.LocalSetupMutationResult
import com.ukkera.pos.app.LocalSetupOpeningFloatRow
import com.ukkera.pos.app.LocalSetupOpeningStockRow
import com.ukkera.pos.app.LocalSetupOperationKind
import com.ukkera.pos.app.LocalSetupPrerequisiteKind
import com.ukkera.pos.app.LocalSetupResultView
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/**
 * Explicit installed setup closure. Read values come only from protected production reads;
 * entered configuration/manifests are operator input, never pre-filled samples or readiness.
 */
@Composable
internal fun LocalSetupClosureScreen(runtime: UkkeraRuntime, token: NavigationToken, model: EntryViewModel) {
    val presentation by runtime.presentation.collectAsState()
    val controller = model.setupClosure(runtime, token, presentation.localAttachment)
    if (controller == null) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(t("Installed setup closure", "إغلاق الإعداد المثبت"))
            Notice(t("The current authenticated local session or its attachment is unavailable. No setup result was read.",
                "جلسة الموظف المحلية الموثقة أو ارتباطها غير متاح. لم تُقرأ أي نتيجة إعداد."), caution = true)
        }
        return
    }
    val state by controller.state.collectAsState()
    val active = runtime.navigator.isActiveEntry(token)
    LaunchedEffect(controller, presentation.localAttachment, active) {
        if (active) controller.activate(token)
    }
    DisposableEffect(controller, active) {
        if (!active) controller.deactivate()
        onDispose { if (!runtime.navigator.isActiveEntry(token)) controller.deactivate() }
    }
    if (!active) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 1100.dp).fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Header(state, controller, token) }
            if (state.availability != LocalSetupClosureAvailability.AVAILABLE) item {
                Notice(availabilityText(state.availability), caution = true)
            }
            item { ResultSection(state, controller, token) }
            item { PendingSection(state, controller, token) }
            item { CheckpointHeader(state, controller, token) }
            if (state.checkpointsLoaded) {
                val checkpoints = state.checkpoints
                if (state.checkpointProblem != null) item {
                    val problem = localProblem(state.checkpointProblem!!)
                    Notice(t("Detailed checkpoints were not disclosed: $problem. Saved-result read authority alone does not disclose fiscal or opening details.",
                        "لم تُكشف نقاط التحقق التفصيلية: $problem. صلاحية قراءة النتيجة المحفوظة وحدها لا تكشف التفاصيل المالية أو الافتتاحية."), caution = true)
                }
                if (checkpoints != null) {
                    item { ConfigurationSection(checkpoints, state, controller, token) }
                    item { CatalogImportSection(checkpoints, state, controller, token) }
                    item { OpeningSection(checkpoints, state, controller, token) }
                } else if (state.checkpointProblem == null) item {
                    Notice(t("No configuration, catalog-import or opening checkpoint is retained under this exact original yet.",
                        "لا توجد نقطة تحقق تكوين أو استيراد كتالوج أو افتتاح محفوظة تحت هذا الأصل الدقيق بعد."))
                }
            }
            item { state.lastRecorded?.let { RecordedNotice(it) } }
            item {
                state.lastMessage?.let { Notice(LocalAppLanguage.current.text(it)) }
                state.lastProblem?.let { Notice(localProblem(it), caution = true) }
            }
        }
    }
}

@Composable
private fun Header(state: LocalSetupClosureState, controller: LocalSetupClosureController, token: NavigationToken) {
    SectionHeading(t("Installed setup acknowledgement, checkpoints and go-live",
        "تأكيد الإعداد المثبت ونقاط التحقق وبدء العمل"),
        t("Separate protected reads and actions for the actual saved installation. ACK is voluntary and is not required for go-live. Neither action installs, signs in, starts RUN, repairs or qualifies a native API.",
            "قراءات وإجراءات محمية منفصلة للتثبيت المحفوظ الفعلي. التأكيد اختياري وليس شرطًا لبدء العمل. لا ينفذ أي منهما تثبيتًا أو تسجيل دخول أو بدء تشغيل أو إصلاحًا أو تأهيلًا لواجهة أصلية."))
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill(t("Saved-result read: SETUP_RESULT_READ", "قراءة النتيجة المحفوظة: SETUP_RESULT_READ"))
        StatusPill(t("Checkpoints: SETUP_CHECKPOINTS_READ", "نقاط التحقق: SETUP_CHECKPOINTS_READ"), caution = true)
        OutlinedButton(onClick = { controller.refresh(token) }, enabled = !state.busy, modifier = Modifier.touchTarget()) {
            Text(t("Reload protected reads", "إعادة القراءة المحمية"))
        }
    }
}

@Composable
private fun ResultSection(state: LocalSetupClosureState, controller: LocalSetupClosureController, token: NavigationToken) {
    SectionHeading(t("Saved setup result", "نتيجة الإعداد المحفوظة"))
    if (state.resultProblem != null) {
        Notice(t("No saved setup result was disclosed: ${localProblem(state.resultProblem)}. This is not proof that the installation is absent.",
            "لم تُكشف نتيجة الإعداد المحفوظة: ${localProblem(state.resultProblem)}. هذا ليس دليلًا على غياب التثبيت."), caution = true)
    }
    val result = state.result
    if (result != null) {
        OriginalCard(result)
        PrerequisiteList(result)
    } else {
        Notice(t("Saved facts are not disclosed. Each action uses its own current grant; the master checks the exact saved original and current prerequisites.",
            "الحقائق المحفوظة غير مكشوفة. يستخدم كل إجراء صلاحيته الحالية المستقلة؛ ويتحقق الجهاز الرئيسي من الأصل المحفوظ الدقيق والشروط الحالية."))
    }
    val ack = result?.acknowledgement
    var ackConfirmed by remember(result?.original?.installationId, ack?.operationId) { mutableStateOf(false) }
    SectionHeading(t("Voluntary delivery acknowledgement", "تأكيد التسليم الاختياري"))
    if (ack == null) {
        Notice(t("This records product acknowledgement of the saved setup result only. Native delivery-envelope lifetime remains undecided. This acknowledgement is not required for responsible go-live.",
            "يسجل هذا تأكيد المنتج لنتيجة الإعداد المحفوظة فقط. يبقى عمر مظروف التسليم الأصلي غير محسوم. هذا التأكيد ليس شرطًا لبدء العمل المسؤول."))
        CheckBoxRow(
            checked = ackConfirmed,
            label = t("I voluntarily confirm delivery of this exact saved setup result.",
                "أؤكد طوعًا تسليم نتيجة الإعداد المحفوظة الدقيقة هذه."),
            enabled = state.canMutate,
        ) { ackConfirmed = it }
        Button(
            onClick = { controller.acknowledge(token) },
            enabled = state.canMutate && ackConfirmed && state.acknowledgeOperation == null,
            modifier = Modifier.touchTarget(),
        ) { Text(t("Acknowledge saved result delivery", "تأكيد تسليم النتيجة المحفوظة")) }
        state.acknowledgeOperation?.let {
            Notice(t("An unresolved acknowledgement original is retained: ${it.value}. Look it up or resume it below; a new operation ID is refused.",
                "أصل تأكيد غير محلول محفوظ: ${it.value}. ابحث عنه أو استأنفه أدناه؛ يُرفض معرف عملية جديد."), caution = true)
        }
    } else {
        Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(t("Delivery acknowledged", "تم تأكيد التسليم"))
                ReviewField(t("Actor", "الموظف"), ack.actorId.value)
                ReviewField(t("Acknowledged at (UTC)", "وقت التأكيد (UTC)"), ack.acknowledgedAt.toString())
                ReviewField(t("Operation", "العملية"), ack.operationId.value)
                ReviewField(t("Policy revision", "مراجعة السياسة"), ack.policyRevision.value.toString())
            }
        }
    }
    val goLive = result?.goLive
    var goLiveConfirmed by remember(result?.original?.installationId, goLive?.operationId) { mutableStateOf(false) }
    SectionHeading(t("Responsible business go-live", "بدء العمل التجاري المسؤول"))
    if (goLive == null) {
        if (result != null && !state.allPrerequisitesSettled) {
            Notice(t("The last read contains unsettled prerequisites. The master rechecks every prerequisite from its exact production source when you request go-live. ACK and employee sign-in/RUN are independent of this decision.",
                "تحتوي القراءة الأخيرة على شروط غير مسوّاة. يعيد الجهاز الرئيسي التحقق من كل شرط من مصدره الإنتاجي الدقيق عند طلب بدء العمل. التأكيد وتسجيل الموظف وبدء التشغيل مستقلون عن هذا القرار."), caution = true)
        }
        CheckBoxRow(
            checked = goLiveConfirmed,
            label = t("I am the responsible current local actor for this branch go-live. This is not an approval substitute or a health/native claim.",
                "أنا الموظف المحلي الحالي المسؤول عن بدء عمل هذا الفرع. هذا ليس بديلًا عن الموافقة ولا ادعاءً للصحة أو الأصالة."),
            enabled = state.canMutate,
        ) { goLiveConfirmed = it }
        Button(
            onClick = { controller.goLive(token) },
            enabled = state.canMutate && goLiveConfirmed && state.goLiveOperation == null,
            modifier = Modifier.touchTarget(),
        ) { Text(t("Record responsible go-live", "تسجيل بدء العمل المسؤول")) }
        state.goLiveOperation?.let {
            Notice(t("An unresolved go-live original is retained: ${it.value}. Look it up or resume it below; a new operation ID is refused.",
                "أصل بدء عمل غير محلول محفوظ: ${it.value}. ابحث عنه أو استأنفه أدناه؛ يُرفض معرف عملية جديد."), caution = true)
        }
    } else {
        Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(t("Business go-live recorded", "تم تسجيل بدء العمل التجاري"))
                ReviewField(t("Responsible actor", "الموظف المسؤول"), goLive.actorId.value)
                ReviewField(t("Occurred at (UTC)", "وقت الحدوث (UTC)"), goLive.occurredAt.toString())
                ReviewField(t("Operation", "العملية"), goLive.operationId.value)
                ReviewField(t("Policy revision", "مراجعة السياسة"), goLive.policyRevision.value.toString())
                Notice(t("This is a local responsible-business record. It does not register centrally, prove health, qualify a native API or post financial/stock effects.",
                    "هذا سجل محلي لبدء العمل المسؤول. لا يسجل مركزيًا ولا يثبت الصحة ولا يؤهل واجهة أصلية ولا يرحل آثارًا مالية أو مخزونية."))
            }
        }
    }
}

@Composable
private fun OriginalCard(result: LocalSetupResultView) {
    val original = result.original
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(t("Protected saved original", "الأصل المحفوظ المحمي"))
                StatusPill(if (original.nativeSettlement) t("Native settlement observed", "لوحظت التسوية الأصلية")
                else t("Native settlement unavailable", "التسوية الأصلية غير متاحة"), caution = !original.nativeSettlement)
            }
            ReviewField(t("Installation", "التثبيت"), original.installationId.value)
            ReviewField(t("Original request", "الطلب الأصلي"), original.originalRequestId.value)
            ReviewField(t("Issued attempt", "المحاولة الصادرة"), original.issuedAttemptId.value)
            ReviewField(t("Attempt sequence", "تسلسل المحاولة"), original.attemptSequence.toString())
            ReviewField(t("Definition digest", "بصمة التعريف"), original.definitionDigest)
            ReviewField(t("Genesis digest", "بصمة النشأة"), original.genesisDigest)
            ReviewField(t("Receipt digest", "بصمة الإيصال"), original.receiptDigest)
            ReviewField(t("Installed at (UTC)", "وقت التثبيت (UTC)"), original.installedAt.toString())
            ReviewField(t("Current authority epoch", "عهد الصلاحية الحالي"), original.currentAuthorityEpoch.value.toString())
        }
    }
}

@Composable
private fun PrerequisiteList(result: LocalSetupResultView) {
    SectionHeading(t("Go-live prerequisites", "شروط بدء العمل"))
    result.prerequisites.forEach { prerequisite ->
        Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(prerequisiteName(prerequisite.kind), caution = !prerequisite.settled)
                    Text(if (prerequisite.settled) t("Settled", "تمت التسوية") else t("Held", "معلّق"),
                        style = MaterialTheme.typography.labelLarge)
                }
                Text(prerequisite.binding, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun PendingSection(state: LocalSetupClosureState, controller: LocalSetupClosureController, token: NavigationToken) {
    SectionHeading(t("Retained setup originals", "أصول الإعداد المحفوظة"))
    if (state.pendingProblem != null) {
        Notice(localProblem(state.pendingProblem), caution = true)
        return
    }
    if (state.pending.isEmpty()) {
        Notice(t("No unresolved or unarchived setup original is retained in this client journal.",
            "لا يوجد أصل إعداد غير محلول أو غير مؤرشف محفوظ في سجل العميل هذا."))
        return
    }
    state.pending.forEach { entry ->
        Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(operationName(entry.kind), style = MaterialTheme.typography.titleMedium)
                ReviewField(t("Original operation", "العملية الأصلية"), entry.operationId.value)
                ReviewField(t("Phase", "المرحلة"), entry.phase)
                if (entry.recorded) ReviewField(t("Retained result", "النتيجة المحفوظة"), entry.recordedCode.orEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.lookup(token, entry.operationId) },
                        enabled = !state.busy, modifier = Modifier.touchTarget()) {
                        Text(t("Look up same original", "بحث عن الأصل نفسه"))
                    }
                    if (!entry.recorded) OutlinedButton(onClick = { controller.resume(token, entry.operationId) },
                        enabled = !state.busy, modifier = Modifier.touchTarget()) {
                        Text(t("Resume same original", "استئناف الأصل نفسه"))
                    }
                    if (entry.recorded) OutlinedButton(onClick = { controller.acknowledgeRecorded(token, entry.operationId) },
                        enabled = !state.busy, modifier = Modifier.touchTarget()) {
                        Text(t("Archive reviewed result", "أرشفة النتيجة المراجعة"))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckpointHeader(state: LocalSetupClosureState, controller: LocalSetupClosureController, token: NavigationToken) {
    SectionHeading(t("Detailed checkpoints", "نقاط التحقق التفصيلية"),
        t("Requires the separate SETUP_CHECKPOINTS_READ grant. Saved-result read authority alone discloses no fiscal or opening values.",
            "تتطلب صلاحية SETUP_CHECKPOINTS_READ المنفصلة. صلاحية قراءة النتيجة المحفوظة وحدها لا تكشف قيمًا مالية أو افتتاحية."))
    OutlinedButton(onClick = { controller.loadCheckpoints(token) }, enabled = !state.busy, modifier = Modifier.touchTarget()) {
        Text(t("Reload checkpoints and saved rows", "إعادة تحميل نقاط التحقق والصفوف المحفوظة"))
    }
    Text(t("Reload replaces the manifest reference drafts with saved rows. Unrecorded references can be added again.",
        "تستبدل إعادة التحميل مسودات مراجع البيان بالصفوف المحفوظة. يمكن إضافة المراجع غير المسجلة مرة أخرى."),
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ConfigurationSection(checkpoints: LocalSetupCheckpointView,
    state: LocalSetupClosureState, controller: LocalSetupClosureController, token: NavigationToken) {
    SectionHeading(t("Required configuration", "التكوين المطلوب"))
    val configuration = checkpoints.configuration
    if (configuration != null) {
        Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(t("Configuration checkpoint recorded", "نقطة تحقق التكوين مسجلة"))
                ReviewField(t("Revision", "المراجعة"), configuration.revision.value.toString())
                ReviewField(t("Currency", "العملة"), configuration.currency)
                ReviewField(t("Time zone", "المنطقة الزمنية"), configuration.timeZone)
                ReviewField(t("Business-day cutoff (minutes)", "قطع يوم العمل (دقائق)"), configuration.businessDayCutoffMinute.toString())
                ReviewField(t("Legal name", "الاسم القانوني"), configuration.legalName)
                ReviewField(t("Tax registration", "التسجيل الضريبي"),
                    configuration.taxRegistrationId ?: t("Explicitly absent", "غائب صراحةً"))
                ReviewField(t("Applied activity", "النشاط المطبق"), configuration.activities.joinToString(", "))
                ReviewField(t("Recorded by", "سجلها"), configuration.actorId.value)
                ReviewField(t("Recorded at (UTC)", "وقت التسجيل (UTC)"), configuration.recordedAt.toString())
                ReviewField(t("Operation", "العملية"), configuration.operationId.value)
            }
        }
        return
    }
    if (state.configurationOperation != null) {
        Notice(t("A configuration original is unresolved: ${state.configurationOperation.value}. Look it up or resume it under retained setup originals; do not start another configuration.",
            "أصل تكوين غير محلول: ${state.configurationOperation.value}. ابحث عنه أو استأنفه ضمن أصول الإعداد المحفوظة؛ لا تبدأ تكوينًا آخر."), caution = true)
        return
    }
    var currencyChosen by remember { mutableStateOf(false) }
    var timeZone by remember { mutableStateOf("") }
    var cutoff by remember { mutableStateOf("") }
    var legalName by remember { mutableStateOf("") }
    var taxId by remember { mutableStateOf("") }
    var taxAbsent by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var localProblem by remember { mutableStateOf<LocalText?>(null) }
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(t("No configuration checkpoint retained", "لا توجد نقطة تحقق تكوين محفوظة"), caution = true)
            Text(t("Enter the actual currency, time zone, business-day cutoff, legal name and exact tax identity. Activity values come only from the master's actual applied branch activity; they cannot be typed here.",
                "أدخل العملة والمنطقة الزمنية وقطع يوم العمل والاسم القانوني والهوية الضريبية الدقيقة الفعلية. تأتي قيم النشاط فقط من نشاط الفرع الفعلي المطبق لدى الجهاز الرئيسي؛ ولا يمكن كتابتها هنا."),
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Currency", "العملة"), style = MaterialTheme.typography.labelLarge)
                FilterChip(selected = currencyChosen, onClick = { currencyChosen = true },
                    label = { Text("EGP") }, modifier = Modifier.touchTarget())
            }
            SetupTextField(t("Time zone (e.g. Africa/Cairo)", "المنطقة الزمنية (مثال Africa/Cairo)"), timeZone, { timeZone = it })
            SetupTextField(t("Business-day cutoff minute (0–1439)", "دقيقة قطع يوم العمل (0–1439)"), cutoff, { cutoff = it })
            SetupTextField(t("Legal name", "الاسم القانوني"), legalName, { legalName = it })
            SetupTextField(t("Tax registration id", "معرف التسجيل الضريبي"), taxId, { taxId = it },
                enabled = !taxAbsent)
            CheckBoxRow(checked = taxAbsent,
                label = t("This exact installation explicitly has no tax registration.",
                    "هذا التثبيت الدقيق بلا تسجيل ضريبي، صراحةً."),
                enabled = true) { checked -> taxAbsent = checked; if (checked) taxId = "" }
            CheckBoxRow(checked = confirmed,
                label = t("I confirm this is the actual fiscal configuration for this installation.",
                    "أؤكد أن هذا هو التكوين المالي الفعلي لهذا التثبيت."),
                enabled = state.canMutate) { confirmed = it }
            localProblem?.let { Notice(LocalAppLanguage.current.text(it), caution = true) }
            Button(
                onClick = {
                    val expected = checkpoints.configuration?.revision ?: Revision(0)
                    val minutes = cutoff.trim().toIntOrNull()
                    val input = try {
                        if (!currencyChosen) throw IllegalArgumentException()
                        require(minutes != null)
                        require(taxAbsent.xor(taxId.isNotBlank()))
                        LocalSetupConfigurationInput(
                            expectedRevision = expected,
                            currencyCode = "EGP",
                            timeZone = timeZone.trim(),
                            businessDayCutoffMinute = minutes,
                            legalName = legalName.trim(),
                            taxRegistrationId = taxId.trim().takeIf { it.isNotEmpty() },
                            taxRegistrationAbsent = taxAbsent,
                        )
                    } catch (_: IllegalArgumentException) {
                        localProblem = LocalText(
                            "Enter an explicit EGP choice, time zone, whole cutoff minute in 0–1439, legal name and either the exact tax id or its explicit absence.",
                            "اختر EGP صراحةً وأدخل المنطقة الزمنية ودقيقة القطع كعدد صحيح بين 0 و1439 والاسم القانوني وإما معرفًا ضريبيًا دقيقًا أو غيابه الصريح.")
                        return@Button
                    }
                    localProblem = null
                    controller.configure(token, input)
                },
                enabled = state.canMutate && confirmed,
                modifier = Modifier.touchTarget(),
            ) { Text(t("Record configuration checkpoint", "تسجيل نقطة تحقق التكوين")) }
        }
    }
}

@Composable
private fun CatalogImportSection(checkpoints: LocalSetupCheckpointView,
    state: LocalSetupClosureState, controller: LocalSetupClosureController, token: NavigationToken) {
    SectionHeading(t("Catalog import settlement", "تسوية استيراد الكتالوج"))
    val completed = checkpoints.catalogImport
    if (completed != null) {
        Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(if (completed.explicitNoImport) t("Explicit no-import recorded", "سُجل اختيار عدم الاستيراد صراحةً")
                else t("Declared catalog manifest settled", "تمت تسوية بيان الكتالوج المعلن"),
                    caution = completed.explicitNoImport)
                ReviewField(t("Rows", "الصفوف"), completed.rowCount.toString())
                ReviewField(t("Manifest digest", "بصمة البيان"), completed.manifestDigest ?: t("None (explicit no-import)", "لا يوجد (عدم استيراد صريح)"))
                ReviewField(t("Input manifest digest", "بصمة بيان الإدخال"), completed.inputManifestDigest ?: t("None", "لا يوجد"))
                ReviewField(t("Operation", "العملية"), completed.operationId.value)
                ReviewField(t("Recorded at (UTC)", "وقت التسجيل (UTC)"), completed.recordedAt.toString())
            }
        }
        return
    }
    if (state.catalogCompleteOperation != null) {
        Notice(t("A catalog-import completion original is unresolved: ${state.catalogCompleteOperation.value}. Look it up or resume it under retained setup originals.",
            "أصل إتمام استيراد الكتالوج غير محلول: ${state.catalogCompleteOperation.value}. ابحث عنه أو استأنفه ضمن أصول الإعداد المحفوظة."), caution = true)
        return
    }
    var sourceId by remember { mutableStateOf("") }
    var skuId by remember { mutableStateOf("") }
    var catalogOperation by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var localProblem by remember { mutableStateOf<LocalText?>(null) }
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(t("No catalog-import checkpoint retained", "لا توجد نقطة تحقق استيراد كتالوج محفوظة"), caution = true)
            Text(t("Each row names an exact retained catalog source, SKU and source operation. The master verifies that actual effect; this screen never creates a sample SKU or publication.",
                "كل صف يحدد مصدر كتالوج وSKU وعملية مصدر محفوظة بدقة. يتحقق الجهاز الرئيسي من الأثر الفعلي؛ ولا تنشئ هذه الشاشة SKU أو إصدارًا تجريبيًا."),
                style = MaterialTheme.typography.bodyMedium)
            state.catalogRows.forEach { row ->
                CatalogRowCard(row, enabled = state.canMutate, remove = { controller.removeCatalogRow(token, row.sourceId) })
            }
            val preview = controller.catalogPreview()
            ReviewField(t("Explicit manifest count", "عدد البيان الصريح"), preview.rowCount.toString())
            ReviewField(t("Explicit manifest digest", "بصمة البيان الصريحة"), preview.digest ?: t("Computed after at least one row", "تُحتسب بعد صف واحد على الأقل"))
            SetupTextField(t("Source identity (UUID)", "هوية المصدر (UUID)"), sourceId, { sourceId = it })
            SetupTextField(t("SKU identity (UUID)", "هوية SKU (UUID)"), skuId, { skuId = it })
            SetupTextField(t("Retained catalog source operation (UUID)", "عملية مصدر الكتالوج المحفوظة (UUID)"), catalogOperation, { catalogOperation = it })
            localProblem?.let { Notice(LocalAppLanguage.current.text(it), caution = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val row = parseCatalogRow(sourceId, skuId, catalogOperation)
                        if (row == null) {
                            localProblem = LocalText("Enter exact source, SKU and retained catalog operation identifiers.",
                                "أدخل معرفات المصدر وSKU وعملية الكتالوج المحفوظة الدقيقة.")
                        } else {
                            localProblem = null
                            controller.addCatalogReference(token, row)
                        }
                    },
                    enabled = state.canMutate,
                    modifier = Modifier.touchTarget(),
                ) { Text(t("Add already-retained reference only", "إضافة مرجع محفوظ فقط دون تنفيذ")) }
                Button(
                    onClick = {
                        val row = parseCatalogRow(sourceId, skuId, catalogOperation)
                        if (row == null) {
                            localProblem = LocalText("Enter exact source, SKU and retained catalog operation identifiers.",
                                "أدخل معرفات المصدر وSKU وعملية الكتالوج المحفوظة الدقيقة.")
                        } else {
                            localProblem = null
                            controller.recordCatalogRow(token, row)
                        }
                    },
                    enabled = state.canMutate && state.catalogRowOperation == null,
                    modifier = Modifier.touchTarget(),
                ) { Text(t("Record new catalog row", "تسجيل صف كتالوج جديد")) }
            }
            Text(t("Declared manifest and explicit no-import are different completion choices:",
                "البيان المعلن واختيار عدم الاستيراد الصريح قراران مختلفان للإتمام:"),
                style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = choice == "DECLARED", onClick = { choice = "DECLARED" },
                    label = { Text(t("Declared manifest (${state.catalogRows.size} rows)", "بيان معلن (${state.catalogRows.size} صفوف)")) },
                    modifier = Modifier.touchTarget())
                FilterChip(selected = choice == "ZERO", onClick = { choice = "ZERO" },
                    label = { Text(t("No catalog import — explicit zero", "لا استيراد كتالوج — صفر صريح")) },
                    modifier = Modifier.touchTarget())
            }
            if (choice == "ZERO") Notice(t("This records that no catalog import occurred. It is not an empty declared manifest and cannot be combined with rows.",
                "يسجل هذا عدم حدوث استيراد كتالوج. ليس بيانًا معلنًا فارغًا ولا يمكن دمجه مع صفوف."), caution = true)
            if (choice == "DECLARED" && state.catalogRows.isEmpty()) Notice(t("A declared manifest needs at least one exact retained row.",
                "يحتاج البيان المعلن إلى صف محفوظ دقيق واحد على الأقل."), caution = true)
            CheckBoxRow(checked = confirmed,
                label = t("I confirm the exact count/digest and this explicit completion choice.",
                    "أؤكد العدد والبصمة الدقيقين وقرار الإتمام الصريح هذا."),
                enabled = state.canMutate) { confirmed = it }
            Button(
                onClick = {
                    val zero = choice == "ZERO"
                    if (choice == null || (!zero && state.catalogRows.isEmpty())) {
                        localProblem = LocalText("Choose the declared manifest or explicit no-import.", "اختر البيان المعلن أو عدم الاستيراد الصريح.")
                        return@Button
                    }
                    localProblem = null
                    controller.completeCatalogImport(token, explicitNoImport = zero)
                },
                enabled = state.canMutate && confirmed && choice != null &&
                    (choice == "ZERO" || state.catalogRows.isNotEmpty()),
                modifier = Modifier.touchTarget(),
            ) { Text(t("Complete catalog import checkpoint", "إتمام نقطة تحقق استيراد الكتالوج")) }
        }
    }
}

@Composable
private fun OpeningSection(checkpoints: LocalSetupCheckpointView,
    state: LocalSetupClosureState, controller: LocalSetupClosureController, token: NavigationToken) {
    SectionHeading(t("Opening settlement", "تسوية الافتتاح"))
    val completed = checkpoints.opening
    if (completed != null) {
        Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(t("Opening checkpoint recorded", "نقطة تحقق الافتتاح مسجلة"))
                ReviewField(t("Stock choice", "قرار المخزون"),
                    if (completed.stockExplicitZero) t("Explicit zero", "صفر صريح") else t("Declared", "معلن"))
                ReviewField(t("Stock rows", "صفوف المخزون"), completed.stockRowCount.toString())
                ReviewField(t("Stock value", "قيمة المخزون"), money(completed.stockValueTotalMinor, completed.currency))
                ReviewField(t("Float choice", "قرار العهدة"),
                    if (completed.floatExplicitZero) t("Explicit zero", "صفر صريح") else t("Declared", "معلن"))
                ReviewField(t("Float rows", "صفوف العهدة"), completed.floatRowCount.toString())
                ReviewField(t("Float total", "إجمالي العهدة"), money(completed.floatTotalMinor, completed.currency))
                ReviewField(t("Stock manifest digest", "بصمة بيان المخزون"), completed.stockManifestDigest ?: t("None (explicit zero)", "لا يوجد (صفر صريح)"))
                ReviewField(t("Float manifest digest", "بصمة بيان العهدة"), completed.floatManifestDigest ?: t("None (explicit zero)", "لا يوجد (صفر صريح)"))
                ReviewField(t("Operation", "العملية"), completed.operationId.value)
                ReviewField(t("Recorded at (UTC)", "وقت التسجيل (UTC)"), completed.recordedAt.toString())
            }
        }
        return
    }
    if (state.openingCompleteOperation != null) {
        Notice(t("An opening completion original is unresolved: ${state.openingCompleteOperation.value}. Look it up or resume it under retained setup originals.",
            "أصل إتمام الافتتاح غير محلول: ${state.openingCompleteOperation.value}. ابحث عنه أو استأنفه ضمن أصول الإعداد المحفوظة."), caution = true)
        return
    }
    var stockSource by remember { mutableStateOf("") }
    var stockReceipt by remember { mutableStateOf("") }
    var stockOperation by remember { mutableStateOf("") }
    var floatSource by remember { mutableStateOf("") }
    var floatSession by remember { mutableStateOf("") }
    var floatOperation by remember { mutableStateOf("") }
    var stockChoice by remember { mutableStateOf<String?>(null) }
    var floatChoice by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var localProblem by remember { mutableStateOf<LocalText?>(null) }
    val preview = controller.openingPreview()
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(t("No opening checkpoint retained", "لا توجد نقطة تحقق افتتاح محفوظة"), caution = true)
            Text(t("Opening stock and float cite exact retained stock-opening and cash-opening operation/effect references. Zero is recorded explicitly and is distinct from a declared manifest.",
                "يستشهد المخزون والعهدة الافتتاحيان بمراجع عمليات/آثار افتتاح مخزون وفتح نقدية محفوظة بدقة. يُسجل الصفر صراحةً ويختلف عن البيان المعلن."),
                style = MaterialTheme.typography.bodyMedium)
            Text(t("Opening stock", "المخزون الافتتاحي"), style = MaterialTheme.typography.titleMedium)
            state.openingStockRows.forEach { row ->
                ReferenceRow(row.sourceId, t("Receipt: ${row.receiptId.value}", "الاستلام: ${row.receiptId.value}"),
                    t("Operation: ${row.openingOperationId.value}", "العملية: ${row.openingOperationId.value}"),
                    enabled = state.canMutate, remove = { controller.removeOpeningStockRow(token, row.sourceId) })
            }
            ReviewField(t("Stock count / digest", "عدد المخزون / البصمة"),
                "${preview.stock.rowCount} / ${preview.stock.digest ?: t("none", "لا يوجد")}")
            SetupTextField(t("Stock source identity (UUID)", "هوية مصدر المخزون (UUID)"), stockSource, { stockSource = it })
            SetupTextField(t("Retained stock receipt (UUID)", "استلام المخزون المحفوظ (UUID)"), stockReceipt, { stockReceipt = it })
            SetupTextField(t("Retained stock-opening operation (UUID)", "عملية افتتاح المخزون المحفوظة (UUID)"), stockOperation, { stockOperation = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val row = parseStockRow(stockSource, stockReceipt, stockOperation)
                        if (row == null) localProblem = LocalText("Enter exact stock source, receipt and opening operation identifiers.",
                            "أدخل معرفات مصدر المخزون والاستلام وعملية الافتتاح الدقيقة.")
                        else { localProblem = null; controller.addOpeningStockReference(token, row) }
                    },
                    enabled = state.canMutate, modifier = Modifier.touchTarget(),
                ) { Text(t("Add retained stock reference only", "إضافة مرجع مخزون محفوظ فقط")) }
                Button(
                    onClick = {
                        val row = parseStockRow(stockSource, stockReceipt, stockOperation)
                        if (row == null) localProblem = LocalText("Enter exact stock source, receipt and opening operation identifiers.",
                            "أدخل معرفات مصدر المخزون والاستلام وعملية الافتتاح الدقيقة.")
                        else { localProblem = null; controller.recordOpeningStockRow(token, row) }
                    },
                    enabled = state.canMutate && state.openingStockOperation == null,
                    modifier = Modifier.touchTarget(),
                ) { Text(t("Record new stock row", "تسجيل صف مخزون جديد")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = stockChoice == "DECLARED", onClick = { stockChoice = "DECLARED" },
                    label = { Text(t("Declared stock (${state.openingStockRows.size})", "مخزون معلن (${state.openingStockRows.size})")) },
                    modifier = Modifier.touchTarget())
                FilterChip(selected = stockChoice == "ZERO", onClick = { stockChoice = "ZERO" },
                    label = { Text(t("No opening stock — explicit zero", "لا مخزون افتتاحي — صفر صريح")) },
                    modifier = Modifier.touchTarget())
            }
            if (stockChoice == "ZERO") Notice(t("Explicit zero opening stock: no stock row may be included.",
                "صفر صريح للمخزون الافتتاحي: لا يجوز تضمين أي صف مخزون."), caution = true)

            Text(t("Opening float", "العهدة الافتتاحية"), style = MaterialTheme.typography.titleMedium)
            state.openingFloatRows.forEach { row ->
                ReferenceRow(row.sourceId, t("Session: ${row.sessionId.value}", "الجلسة: ${row.sessionId.value}"),
                    t("Operation: ${row.openingOperationId.value}", "العملية: ${row.openingOperationId.value}"),
                    enabled = state.canMutate, remove = { controller.removeOpeningFloatRow(token, row.sourceId) })
            }
            ReviewField(t("Float count / digest", "عدد العهدة / البصمة"),
                "${preview.float.rowCount} / ${preview.float.digest ?: t("none", "لا يوجد")}")
            SetupTextField(t("Float source identity (UUID)", "هوية مصدر العهدة (UUID)"), floatSource, { floatSource = it })
            SetupTextField(t("Retained cash session (UUID)", "جلسة النقدية المحفوظة (UUID)"), floatSession, { floatSession = it })
            SetupTextField(t("Retained cash-opening operation (UUID)", "عملية فتح النقدية المحفوظة (UUID)"), floatOperation, { floatOperation = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val row = parseFloatRow(floatSource, floatSession, floatOperation)
                        if (row == null) localProblem = LocalText("Enter exact float source, cash session and opening operation identifiers.",
                            "أدخل معرفات مصدر العهدة وجلسة النقدية وعملية الافتتاح الدقيقة.")
                        else { localProblem = null; controller.addOpeningFloatReference(token, row) }
                    },
                    enabled = state.canMutate, modifier = Modifier.touchTarget(),
                ) { Text(t("Add retained float reference only", "إضافة مرجع عهدة محفوظ فقط")) }
                Button(
                    onClick = {
                        val row = parseFloatRow(floatSource, floatSession, floatOperation)
                        if (row == null) localProblem = LocalText("Enter exact float source, cash session and opening operation identifiers.",
                            "أدخل معرفات مصدر العهدة وجلسة النقدية وعملية الافتتاح الدقيقة.")
                        else { localProblem = null; controller.recordOpeningFloatRow(token, row) }
                    },
                    enabled = state.canMutate && state.openingFloatOperation == null,
                    modifier = Modifier.touchTarget(),
                ) { Text(t("Record new float row", "تسجيل صف عهدة جديد")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = floatChoice == "DECLARED", onClick = { floatChoice = "DECLARED" },
                    label = { Text(t("Declared float (${state.openingFloatRows.size})", "عهدة معلنة (${state.openingFloatRows.size})")) },
                    modifier = Modifier.touchTarget())
                FilterChip(selected = floatChoice == "ZERO", onClick = { floatChoice = "ZERO" },
                    label = { Text(t("No opening float — explicit zero", "لا عهدة افتتاحية — صفر صريح")) },
                    modifier = Modifier.touchTarget())
            }
            if (floatChoice == "ZERO") Notice(t("Explicit zero opening float: no float row may be included.",
                "صفر صريح للعهدة الافتتاحية: لا يجوز تضمين أي صف عهدة."), caution = true)

            localProblem?.let { Notice(LocalAppLanguage.current.text(it), caution = true) }
            if (stockChoice == null || floatChoice == null) Notice(t("Choose both stock and float decisions explicitly.",
                "اختر قرار المخزون والعهدة صراحةً."), caution = true)
            CheckBoxRow(checked = confirmed,
                label = t("I confirm the exact stock/float counts, digests and explicit zero/declared choices.",
                    "أؤكد أعداد المخزون والعهدة والبصمات الدقيقة وقرارات الصفر/الإعلان الصريحة."),
                enabled = state.canMutate) { confirmed = it }
            Button(
                onClick = {
                    if (stockChoice == null || floatChoice == null) {
                        localProblem = LocalText("Choose both stock and float decisions explicitly.",
                            "اختر قرار المخزون والعهدة صراحةً.")
                        return@Button
                    }
                    localProblem = null
                    controller.completeOpening(token,
                        stockExplicitZero = stockChoice == "ZERO",
                        floatExplicitZero = floatChoice == "ZERO")
                },
                enabled = state.canMutate && confirmed && stockChoice != null && floatChoice != null &&
                    (stockChoice == "ZERO" || state.openingStockRows.isNotEmpty()) &&
                    (floatChoice == "ZERO" || state.openingFloatRows.isNotEmpty()),
                modifier = Modifier.touchTarget(),
            ) { Text(t("Complete opening checkpoint", "إتمام نقطة تحقق الافتتاح")) }
        }
    }
}

@Composable
private fun CatalogRowCard(row: LocalSetupCatalogRow, enabled: Boolean, remove: () -> Unit) {
    ReferenceRow(row.sourceId,
        t("SKU: ${row.skuId.value}", "SKU: ${row.skuId.value}"),
        t("Operation: ${row.catalogOperationId.value}", "العملية: ${row.catalogOperationId.value}"),
        enabled = enabled, remove = remove)
}

@Composable
private fun ReferenceRow(sourceId: DomainId, first: String, second: String, enabled: Boolean, remove: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Outline)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t("Source: ${sourceId.value}", "المصدر: ${sourceId.value}"), style = MaterialTheme.typography.bodyMedium)
                Text(first, style = MaterialTheme.typography.bodySmall, color = Muted)
                Text(second, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            OutlinedButton(onClick = remove, enabled = enabled, modifier = Modifier.touchTarget()) {
                Text(t("Remove", "إزالة"))
            }
        }
    }
}

@Composable
private fun RecordedNotice(result: LocalSetupMutationResult.Recorded) {
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Outline)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusPill(t("Terminal original result retained", "النتيجة الأصلية النهائية محفوظة"),
                caution = !result.committed)
            ReviewField(t("Operation", "العملية"), result.operationId.value)
            ReviewField(t("Code", "الرمز"), result.code)
            if (result.rowId != null) ReviewField(t("Row", "الصف"), result.rowId.value)
            if (result.rowCount > 0) ReviewField(t("Rows", "الصفوف"), result.rowCount.toString())
            if (result.manifestDigest != null) ReviewField(t("Manifest digest", "بصمة البيان"), result.manifestDigest)
        }
    }
}

@Composable
private fun CheckBoxRow(checked: Boolean, label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onChange,
            modifier = Modifier.touchTarget().semantics { contentDescription = label })
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SetupTextField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        enabled = enabled, modifier = Modifier.fillMaxWidth())
}

private fun parseCatalogRow(source: String, sku: String, operation: String): LocalSetupCatalogRow? = try {
    LocalSetupCatalogRow(DomainId(source.trim()), DomainId(sku.trim()), OperationId(operation.trim()))
} catch (_: IllegalArgumentException) { null }

private fun parseStockRow(source: String, receipt: String, operation: String): LocalSetupOpeningStockRow? = try {
    LocalSetupOpeningStockRow(DomainId(source.trim()), DomainId(receipt.trim()), OperationId(operation.trim()))
} catch (_: IllegalArgumentException) { null }

private fun parseFloatRow(source: String, session: String, operation: String): LocalSetupOpeningFloatRow? = try {
    LocalSetupOpeningFloatRow(DomainId(source.trim()), DomainId(session.trim()), OperationId(operation.trim()))
} catch (_: IllegalArgumentException) { null }

private fun money(minor: Long, currency: String): String {
    val sign = if (minor < 0) "-" else ""
    val absolute = kotlin.math.abs(minor)
    return "$sign${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')} $currency"
}

@Composable
private fun availabilityText(value: LocalSetupClosureAvailability): String = when (value) {
    LocalSetupClosureAvailability.AVAILABLE -> ""
    LocalSetupClosureAvailability.NOT_CONFIGURED -> t(
        "The installed setup closure provider is not configured on this local connection. No read, ACK, checkpoint or go-live was attempted.",
        "مزود إغلاق الإعداد المثبت غير مهيأ على هذا الاتصال المحلي. لم تُجر أي قراءة أو تأكيد أو نقطة تحقق أو بدء عمل.")
    LocalSetupClosureAvailability.NOT_IMPLEMENTED -> t(
        "This connection does not install the full production setup-result/checkpoint registry. Read, ACK, checkpoint and go-live actions are refused visibly; no substitute readiness is shown.",
        "هذا الاتصال لا يثبت سجل نتيجة الإعداد/نقاط التحقق الإنتاجي الكامل. تُرفض قراءة النتيجة والتأكيد ونقاط التحقق وبدء العمل بشكل ظاهر؛ ولا تُعرض جاهزية بديلة.")
}

@Composable
private fun prerequisiteName(kind: LocalSetupPrerequisiteKind): String = when (kind) {
    LocalSetupPrerequisiteKind.NATIVE_INSTALLATION_SETTLEMENT -> t(
        "Native installation settlement", "التسوية الأصلية للتثبيت")
    LocalSetupPrerequisiteKind.REQUIRED_CONFIGURATION -> t(
        "Required configuration", "التكوين المطلوب")
    LocalSetupPrerequisiteKind.CATALOG_IMPORT_SETTLEMENT -> t(
        "Catalog import settlement", "تسوية استيراد الكتالوج")
    LocalSetupPrerequisiteKind.OPENING_SETTLEMENT -> t(
        "Opening settlement", "تسوية الافتتاح")
}

@Composable
private fun operationName(kind: LocalSetupOperationKind): String = when (kind) {
    LocalSetupOperationKind.ACKNOWLEDGE -> t("Delivery acknowledgement", "تأكيد التسليم")
    LocalSetupOperationKind.GO_LIVE -> t("Responsible go-live", "بدء العمل المسؤول")
    LocalSetupOperationKind.CONFIGURATION -> t("Configuration checkpoint", "نقطة تحقق التكوين")
    LocalSetupOperationKind.CATALOG_IMPORT_ROW -> t("Catalog import row", "صف استيراد الكتالوج")
    LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE -> t("Catalog import completion", "إتمام استيراد الكتالوج")
    LocalSetupOperationKind.OPENING_STOCK_ROW -> t("Opening stock row", "صف المخزون الافتتاحي")
    LocalSetupOperationKind.OPENING_FLOAT_ROW -> t("Opening float row", "صف العهدة الافتتاحية")
    LocalSetupOperationKind.OPENING_COMPLETE -> t("Opening completion", "إتمام الافتتاح")
    LocalSetupOperationKind.UNKNOWN -> t("Setup original", "أصل الإعداد")
}

@Composable
private fun t(english: String, arabic: String): String = LocalAppLanguage.current.text(LocalText(english, arabic))
