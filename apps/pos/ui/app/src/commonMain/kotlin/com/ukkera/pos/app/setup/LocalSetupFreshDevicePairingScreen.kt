package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.LocalAppLanguage
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.app.ui.PosTheme
import com.ukkera.pos.app.ui.ReviewField
import com.ukkera.pos.app.ui.ScopeDetails
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.touchTarget
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPhase
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingTargetPhase
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.types.DomainId

/** Full public transfer review and separate explicit ceremonies, without automatic admission. */
@Composable
public fun LocalSetupFreshDevicePairingScreen(controller: LocalSetupController, view: LocalSetupView,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit, modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null) {
    val selected = view.freshDevicePairing
    val retiredChallenge = selected?.retirement?.originalChallenge
    val showRetiredChallenge = retiredChallenge != null && retiredChallenge.challengeDigest != selected?.challenge?.challengeDigest
    val lanClientOwnsTarget = view.lan.role == LocalSetupLanRole.CLIENT && view.lan.status != LocalSetupLanStatus.NOT_ATTACHED
    val enabled = !view.locked && view.work == null && view.problem != LocalSetupProblem.CLOSE_UNCONFIRMED && !lanClientOwnsTarget
    CompositionLocalProvider(LocalAppLanguage provides language, LocalLayoutDirection provides language.direction) {
        PosTheme {
            LazyColumn(modifier.fillMaxSize().widthIn(max = 960.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        onBack?.let { back -> OutlinedButton(onClick = { controller.lockPresentation(); back() },
                            modifier = Modifier.touchTarget()) { Text(t("Device choices", "خيارات الجهاز")) } }
                        OutlinedButton(onClick = controller::lockPresentation, modifier = Modifier.touchTarget()) {
                            Text(t("Lock setup", "قفل الإعداد"))
                        }
                        OutlinedButton(onClick = { onLanguageChange(language.toggle()) }, modifier = Modifier.touchTarget()) {
                            Text(if (language == AppLanguage.ENGLISH) "العربية" else "English")
                        }
                    }
                    SectionHeading(t("Pair a new physical device", "اقتران جهاز فعلي جديد"))
                    Notice(t("Prepare the new target, transfer its identity to the original branch Master, return the complete challenge to the target, then return its original possession response to that same Master.",
                        "حضّر الجهاز الهدف الجديد، وانقل هويته إلى الجهاز الرئيسي الأصلي للفرع، ثم أعد التحدي الكامل إلى الهدف، وأعد استجابة حيازة المفتاح الأصلية إلى الجهاز الرئيسي نفسه."))
                    Notice(t("Pairing proof, head approval and admission are separate. This transfer does not create an employee account or session, connect a remote peer, or start LAN/RPC.",
                        "إثبات الاقتران واعتماد الرئيس والقبول خطوات منفصلة. لا ينشئ النقل حساب موظف أو جلسة، ولا يصل نظيرًا بعيدًا، ولا يشغّل الشبكة المحلية أو RPC."))
                    view.work?.let {
                        CircularProgressIndicator()
                        Text(workText(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    view.problem?.let { Notice(problemText(it), caution = true,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                    view.nativeProblem?.let { Notice(problemText(it), caution = true) }
                    LocalSetupLanSection(controller, view)
                    if (lanClientOwnsTarget) Notice(t(
                        "The retained LAN Client owns this target's transfer attempt. Earlier target pairing actions are disabled. Use the LAN attachment's explicit refresh or close controls.",
                        "يملك عميل الشبكة المحلية المحفوظ محاولة نقل هذا الهدف. تُعطَّل إجراءات اقتران الهدف السابقة. استخدم عناصر تحديث اتصال الشبكة المحلية أو إغلاقه الصريحة."), caution = true)
                    if (selected == null && !lanClientOwnsTarget) {
                        Notice(t("The original pairing context is unavailable. Read the original Master again; missing source evidence cannot create a replacement request.",
                            "سياق الاقتران الأصلي غير متاح. اقرأ الجهاز الرئيسي الأصلي مجددًا؛ لا تنشئ الأدلة المفقودة طلبًا بديلًا."), caution = true)
                        OutlinedButton(onClick = controller::openFreshDevicePairing, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Read original pairing context", "قراءة سياق الاقتران الأصلي")) }
                    } else if (selected != null) {
                        ReviewField(t("Device role", "دور الجهاز"), if (selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET)
                            t("New physical target", "الجهاز الهدف الفعلي الجديد") else t("Original operational branch Master", "الجهاز الرئيسي الأصلي التشغيلي للفرع"), code = false)
                        Notice(freshPairingStageText(selected.stage), caution = selected.stage in FRESH_PAIRING_UNRESOLVED_STAGES)
                        selected.originalIntentId?.let { ReviewField(t("Retained local target intent", "نية الهدف المحلية المحفوظة"), it.value) }
                        selected.originalRequestId?.let { ReviewField(t("Exact original Master request", "طلب الجهاز الرئيسي الأصلي الدقيق"), it.value) }
                        selected.nativePhase?.let { ReviewField(t("Native original Master phase", "مرحلة الجهاز الرئيسي الأصلية"), freshMasterNativePhaseText(it), code = false) }
                        if (selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET &&
                            (selected.responseCycle > 0 || selected.retirement != null || selected.continuationAttempted))
                            ReviewField(t("Retained response cycle (0 is initial)", "دورة الاستجابة المحفوظة (صفر هي الأولى)"), selected.responseCycle.toString())
                        if (selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET && selected.stage == LocalSetupFreshDevicePairingStage.TARGET_INPUT)
                            FreshTargetInputs(enabled, controller::reviewFreshDeviceTarget)
                        selected.targetInput?.let { input ->
                            SectionHeading(t("Exact local target request", "طلب الهدف المحلي الدقيق"))
                            ScopeDetails(input.scope)
                            ReviewField(t("Requested capability descriptions", "أوصاف الإمكانات المطلوبة"), capabilitiesText(input.capabilities))
                            ReviewField(t("SDK AppId", "معرّف التطبيق في SDK"), input.appId)
                            if (!input.matchesBranchRpcNamespace) Notice(t(
                                "This retained AppId is not the branch RPC namespace. Its original data is preserved, but a new SDK acquisition is unavailable.",
                                "معرّف التطبيق المحفوظ ليس معرّف RPC الخاص بالفرع. تُحفظ بياناته الأصلية، لكن الحصول على هوية SDK جديدة غير متاح."), caution = true)
                            ReviewField(t("Private local acquisition name", "اسم الحصول المحلي الخاص"), input.deviceName, code = false)
                            Notice(t("The name is retained only on this target. It is absent from the identity, challenge and response documents and is not an authenticated Master name.",
                                "يُحفظ الاسم على هذا الهدف فقط. لا يوجد في مستندات الهوية والتحدي والاستجابة، وليس اسمًا موثّقًا للجهاز الرئيسي."))
                        }
                        if (selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET && selected.preparationAttempted && selected.targetInput == null)
                            Notice(t("The original private acquisition name/input metadata is unavailable. It will not be guessed or replaced; native retained DATA can still be recovered.",
                                "الاسم الخاص الأصلي وبيانات مدخلات الحصول غير متاحة. لن تُخمّن أو تُستبدل؛ ويمكن استعادة البيانات الأصلية المحفوظة."), caution = true)
                        selected.targetReference?.let { original ->
                            SectionHeading(t("Actual retained target reference", "مرجع الهدف الفعلي المحفوظ"))
                            ReviewField(t("Native preparation", "التحضير الأصلي"), original.originalPreparationId.value)
                            ReviewField(t("Target device", "الجهاز الهدف"), original.deviceId.value)
                            ReviewField(t("Native key ID", "معرّف المفتاح الأصلي"), original.keyId.value)
                            ReviewField(t("Native target phase", "مرحلة الهدف الأصلية"), freshTargetNativePhaseText(original.phase), code = false)
                            original.originalResponseId?.let { ReviewField(t("Original private response reference", "مرجع الاستجابة الخاص الأصلي"), it.value) }
                            original.originalPairingActionId?.let { ReviewField(t("Original pairing action", "إجراء الاقتران الأصلي"), it.value) }
                            original.challengeDigest?.let { ReviewField(t("Original challenge digest", "بصمة التحدي الأصلي"), it.value) }
                            original.originalSdkAcquisitionId?.let { ReviewField(t("Original SDK acquisition", "الحصول الأصلي على SDK"), it.value) }
                            original.sdkIdentityDigest?.let { ReviewField(t("Original identity digest", "بصمة الهوية الأصلية"), it.value) }
                        }
                        selected.identity?.let {
                            FreshIdentityDetails(it)
                            if (it.appId != LocalMasterSdkProfileV1.appId(it.target.admission.scope)) Notice(t(
                                "This historical SDK identity is not compatible with the branch RPC namespace. Original documents remain readable; new pairing effects are unavailable.",
                                "هوية SDK التاريخية هذه غير متوافقة مع معرّف RPC الخاص بالفرع. تبقى المستندات الأصلية قابلة للقراءة؛ ولا تتاح آثار اقتران جديدة."), caution = true)
                        }
                        selected.challenge?.let { FreshChallengeDetails(it) }
                        selected.masterTransfer?.let { transfer ->
                            SectionHeading(t("Original Master approval / root transfer", "نقل اعتماد الجهاز الرئيسي / الأصل الأصلي"))
                            ReviewField(t("Complete transfer digest", "بصمة النقل الكامل"), transfer.transferDigest.value)
                            Notice(t("This imported document is review DATA only. Verify its signatures and exact retained challenge/response using this target's native owner before treating the Master as selected.",
                                "المستند المستورد بيانات للمراجعة فقط. تحقّق من توقيعاته ومن تحدّيه واستجابته المحفوظين بدقة باستخدام المالك الأصلي لهذا الهدف قبل اعتبار الجهاز الرئيسي مختارًا."), caution = true)
                        }
                        selected.verifiedMaster?.let { master ->
                            SectionHeading(t("Native-verified original Master selection", "اختيار الجهاز الرئيسي الأصلي المتحقق منه أصليًا"))
                            ReviewField(t("Original Master name", "اسم الجهاز الرئيسي الأصلي"), master.exactDeviceName, code = false)
                            ReviewField(t("Original Master fingerprint", "بصمة الجهاز الرئيسي الأصلي"), master.fingerprint.value)
                            Notice(t("The target's native readback verified the original root and approved endorsement for its retained challenge/response. This selects an identity only: device admission, employee authentication, permissions and RPC startup are still separate.",
                                "تحققت القراءة الأصلية للهدف من الأصل وتوقيع الاعتماد الأصلي لتحدّيه واستجابته المحفوظين. يختار هذا الهوية فقط؛ وما زال قبول الجهاز وتسجيل دخول الموظف والصلاحيات وبدء RPC خطوات منفصلة."))
                        }
                    }
                }
                items(selected?.challenge?.requested?.retainedAdmissions.orEmpty(), key = { "census-${it.deviceId.value}" }) { admission ->
                    FreshAdmissionDetails(admission, t("Retained admission in the complete original census", "القبول المحفوظ في قائمة الأصل الكاملة"))
                }
                if (selected != null) {
                    selected.retirement?.let { retirement ->
                        item {
                            FreshRetirementDetails(retirement)
                            if (showRetiredChallenge) FreshChallengeDetails(checkNotNull(retiredChallenge))
                            else Notice(t("The complete challenge and retained census above are this certificate's exact old challenge. Retirement does not rewrite that request or its original deadline.",
                                "التحدي الكامل وقائمة القبول المحفوظة أعلاه هما التحدي القديم الدقيق لهذا المستند. لا يعيد الإنهاء كتابة ذلك الطلب أو موعده الأصلي."))
                        }
                        if (showRetiredChallenge) items(checkNotNull(retiredChallenge).requested.retainedAdmissions,
                            key = { "retired-census-${it.deviceId.value}" }) { admission ->
                            FreshAdmissionDetails(admission, t("Retained admission in R's complete old challenge", "القبول المحفوظ في التحدي القديم الكامل للمستند R"))
                        }
                    }
                    item {
                        selected.possession?.let { possession ->
                            SectionHeading(t("Exact original possession response", "استجابة حيازة المفتاح الأصلية الدقيقة"))
                            ReviewField(t("Original action", "الإجراء الأصلي"), possession.originalActionId.value)
                            ReviewField(t("Original challenge digest", "بصمة التحدي الأصلي"), possession.challengeDigest.value)
                            ReviewField(t("Target device", "الجهاز الهدف"), possession.deviceId.value)
                            ReviewField(t("Native key ID", "معرّف المفتاح الأصلي"), possession.keyId.value)
                            ReviewField(t("Native descriptor digest", "بصمة الوصف الأصلي"), possession.descriptorDigest.value)
                            possession.sdkIdentityDigest?.let { ReviewField(t("SDK identity digest", "بصمة هوية SDK"), it.value) }
                            ReviewField(t("Original public possession signature", "توقيع حيازة المفتاح العام الأصلي"), freshPublicHex(possession.copySignature()))
                            Notice(t("A transferred signature is public DATA. Only the original Master can confirm this exact target proof under its current native authority.",
                                "التوقيع المنقول بيانات عامة. وحده الجهاز الرئيسي الأصلي يؤكد إثبات هذا الهدف بعينه تحت سلطته الأصلية الحالية."))
                        }
                        selected.document?.let { document ->
                            val name = freshDocumentName(document.kind)
                            val result = when (document.outcome) {
                                LocalSetupPairingDocumentOutcome.IMPORTED -> t("Complete document imported for review", "استُورد المستند الكامل للمراجعة")
                                LocalSetupPairingDocumentOutcome.EXPORTED -> t("Exact retained document exported", "صُدّر المستند المحفوظ الدقيق")
                                LocalSetupPairingDocumentOutcome.CANCELLED -> t("Document transfer cancelled", "أُلغي نقل المستند")
                                LocalSetupPairingDocumentOutcome.UNAVAILABLE -> t("Document transfer unavailable", "نقل المستند غير متاح")
                            }
                            Notice("$name: $result" + (document.byteCount?.let { " ($it ${t("bytes", "بايت")})" } ?: ""))
                        }
                        FreshPairingActions(controller, selected, enabled)
                        Notice(t("Transfer the complete binary document. Identity: up to 16,384 bytes; challenge: up to 2,113,536; response: up to 2,048; retirement: up to 2,187,264. Cancellation or partial transfer is not success. A large valid challenge may still exceed native retained signing capacity; refusal preserves this original without a replacement.",
                            "انقل المستند الثنائي كاملًا. الهوية: حتى 16,384 بايت؛ التحدي: حتى 2,113,536؛ الاستجابة: حتى 2,048؛ الإنهاء: حتى 2,187,264. الإلغاء أو النقل الجزئي ليس نجاحًا. قد يتجاوز تحدٍّ كبير صحيح سعة الحفظ الأصلية اللازمة للتوقيع؛ ويحفظ الرفض هذا الأصل دون بديل."))
                        OutlinedButton(onClick = { controller.recoverFreshDevicePairing(selected.reviewId) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Read / recover only this original", "قراءة / استعادة هذا الأصل فقط")) }
                        OutlinedButton(onClick = controller::closeFreshDevicePairing, enabled = enabled &&
                            (selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET || freshMasterMayLeaveOriginal(selected)),
                            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Retain original history and return", "حفظ التاريخ الأصلي والعودة")) }
                        Notice(t("Lost or uncertain replies remain original-only recovery. Locking, leaving or restarting cannot create another key, SDK identity, request, signature or expiry.",
                            "تبقى الاستجابات المفقودة أو غير المؤكدة في مسار استعادة الأصل فقط. لا ينشئ القفل أو المغادرة أو إعادة التشغيل مفتاحًا أو هوية SDK أو طلبًا أو توقيعًا أو مدة صلاحية أخرى."))
                    }
                    if (selected.role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER) {
                        items(selected.masterOriginals, key = { "request-${it.originalRequestId.value}" }) { original ->
                            ReviewField(t("Retained original Master request", "طلب الجهاز الرئيسي الأصلي المحفوظ"), original.originalRequestId.value)
                            original.originalActionId?.let { ReviewField(t("Retained original action", "الإجراء الأصلي المحفوظ"), it.value) }
                            ReviewField(t("Exact requested target device", "جهاز الهدف المطلوب بعينه"), original.deviceId.value)
                            ReviewField(t("Exact native key", "المفتاح الأصلي الدقيق"), original.keyId.value)
                            OutlinedButton(onClick = { controller.inspectFreshDevicePairing(original.originalRequestId) }, enabled = enabled &&
                                (selected.blockedRequestId == null || selected.blockedRequestId == original.originalRequestId),
                                modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Review this exact saved request", "مراجعة هذا الطلب المحفوظ بعينه")) }
                        }
                        item {
                            selected.blockedRequestId?.let { ReviewField(t("Original requiring recovery before new work", "الأصل الذي يتطلب استعادة قبل عمل جديد"), it.value) }
                            if (freshMasterUnsignedRetired(selected)) Notice(t("The actual original Master confirmed terminal retirement. Separately review and prepare a new request for the same identity I. Its new challenge must carry this exact retirement link and its own native deadline; the old request and deadline stay unchanged.",
                                "أكد الجهاز الرئيسي الأصلي الفعلي الإنهاء النهائي. راجع وحضّر بصورة منفصلة طلبًا جديدًا للهوية I نفسها. يجب أن يحمل تحديه الجديد رابط الإنهاء الدقيق هذا وموعده الأصلي الخاص؛ ويبقى الطلب القديم وموعده دون تغيير."))
                            OutlinedButton(onClick = controller::beginFreshDevicePairing, enabled = enabled && selected.blockedRequestId == null &&
                                freshMasterMayLeaveOriginal(selected), modifier = Modifier.fillMaxWidth().touchTarget()) {
                                Text(if (freshMasterUnsignedRetired(selected)) t("Review a separate successor request for the same I", "مراجعة طلب لاحق منفصل للهوية I نفسها")
                                    else t("Review a separately requested new device", "مراجعة جهاز جديد بطلب منفصل"))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FreshTargetInputs(enabled: Boolean, review: (LocalSetupFreshDeviceTargetInput) -> Unit) {
    var organization by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var capabilities by remember { mutableStateOf("") }
    val scope = remember(organization, company, branch) {
        runCatching {
            BranchScope(DomainId(organization), DomainId(company), DomainId(branch)).also {
                require(listOf(it.organizationId, it.companyId, it.branchId).distinct().size == 3)
            }
        }.getOrNull()
    }
    val appId = scope?.let { LocalMasterSdkProfileV1.appId(it).value }
    val input = remember(scope, appId, name, capabilities) {
        runCatching {
            val codes = if (capabilities.isEmpty()) emptyList() else capabilities.split('\n')
            require(codes.none { it.isEmpty() } && codes.distinct().size == codes.size)
            LocalSetupFreshDeviceTargetInput(requireNotNull(scope), codes.toSet(), requireNotNull(appId), name)
        }.getOrNull()
    }
    SectionHeading(t("Review a distinct new target", "مراجعة هدف جديد مستقل"))
    Notice(t("Enter the branch's exact organization/company/branch UUIDs. The operational Master must match them independently. Review creates no key or SDK identity.",
        "أدخل معرّفات UUID الدقيقة للمؤسسة والشركة والفرع. يجب أن يطابقها الجهاز الرئيسي التشغيلي بصورة مستقلة. لا تنشئ المراجعة مفتاحًا أو هوية SDK."))
    FreshInput(organization, { organization = it }, t("Organization UUID", "UUID المؤسسة"), enabled)
    FreshInput(company, { company = it }, t("Company UUID", "UUID الشركة"), enabled)
    FreshInput(branch, { branch = it }, t("Branch UUID", "UUID الفرع"), enabled)
    if (appId != null) ReviewField(t("Branch SDK AppId (derived)", "معرّف تطبيق الفرع في SDK (مشتق)"), appId)
    else Notice(t("Enter three distinct canonical branch UUIDs to derive the shared SDK AppId.",
        "أدخل ثلاثة معرّفات UUID أصلية ومختلفة للفرع لاشتقاق معرّف تطبيق SDK المشترك."))
    FreshInput(name, { name = it }, t("Private local device name", "اسم الجهاز المحلي الخاص"), enabled)
    OutlinedTextField(capabilities, { capabilities = it }, enabled = enabled,
        label = { Text(t("Capability codes: one exact code per line; leave empty for none", "رموز الإمكانات: رمز دقيق في كل سطر؛ اتركه فارغًا لعدم طلب أي رمز")) },
        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
    Notice(t("Use up to 64 distinct case-sensitive ASCII capability codes, 1–64 characters each: letters, digits, dot, underscore or hyphen. The private name must be nonblank and at most 255 UTF-8 bytes. Entered values are preserved verbatim; all branch peers use the derived AppId.",
        "استخدم حتى 64 رمز إمكانات ASCII مختلفًا وحساسًا لحالة الحروف، بطول 1–64 محرفًا: حروف وأرقام ونقطة وشرطة سفلية أو عادية. يجب ألا يكون الاسم الخاص فارغًا أو يتجاوز 255 بايت UTF-8. تُحفظ القيم المدخلة كما هي؛ ويستخدم جميع نظراء الفرع معرّف التطبيق المشتق."))
    Button(onClick = { input?.let(review) }, enabled = enabled && input != null,
        modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Review these exact target inputs", "مراجعة مدخلات الهدف هذه بعينها")) }
}

@Composable
private fun FreshInput(value: String, change: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled,
        singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FreshPairingActions(controller: LocalSetupController, selected: LocalSetupFreshDevicePairingView, enabled: Boolean) {
    val reviewId = selected.reviewId
    val namespaceCompatible = selected.identity?.let {
        it.appId == LocalMasterSdkProfileV1.appId(it.target.admission.scope)
    } == true
    when (selected.stage) {
        LocalSetupFreshDevicePairingStage.TARGET_INPUT_REVIEW -> FreshExplicitAction(reviewId, enabled,
            t("I reviewed the exact branch, capabilities, AppId and private name. This is a new physical target, separate from the original Master.",
                "راجعت الفرع والإمكانات ومعرّف التطبيق والاسم الخاص بدقة. هذا هدف فعلي جديد منفصل عن الجهاز الرئيسي الأصلي."),
            t("Retain intent and prepare this new target", "حفظ النية وتحضير هذا الهدف الجديد")) { controller.prepareFreshDeviceTarget(reviewId) }
        LocalSetupFreshDevicePairingStage.TARGET_KEY_READY -> if (selected.targetInput != null) FreshExplicitAction(reviewId,
            enabled && selected.targetInput.matchesBranchRpcNamespace,
            t("Acquire only this target's actual original SDK identity with the reviewed AppId and private name. Do not connect or start transport.",
                "احصل فقط على هوية SDK الفعلية الأصلية لهذا الهدف بمعرّف التطبيق والاسم الخاص اللذين روجعا. لا تصل الجهاز ولا تبدأ النقل."),
            t("Retain intent and acquire original SDK identity", "حفظ النية والحصول على هوية SDK الأصلية")) { controller.acquireFreshDeviceSdkIdentity(reviewId) }
        LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_IMPORT, LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_REVIEW -> {
            OutlinedButton(onClick = { controller.importFreshDeviceIdentity(reviewId) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Import the target identity document (I)", "استيراد مستند هوية الهدف (I)")) }
            if (selected.stage == LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_REVIEW) FreshExplicitAction(reviewId, enabled && namespaceCompatible,
                t("I reviewed this complete native/SDK identity and requested admission for this original branch Master. Retain this exact request before preparing its challenge.",
                    "راجعت هوية البيئة الأصلية وSDK كاملةً والقبول المطلوب لهذا الجهاز الرئيسي الأصلي للفرع. احفظ هذا الطلب بعينه قبل تحضير تحديه."),
                t("Retain original request and prepare challenge", "حفظ الطلب الأصلي وتحضير التحدي")) { controller.prepareFreshDeviceChallenge(reviewId) }
        }
        LocalSetupFreshDevicePairingStage.TARGET_CHALLENGE_REVIEW -> FreshExplicitAction(reviewId, enabled && namespaceCompatible,
            t("I reviewed the complete original Master context, request, census, native target and SDK identity. This unsigned challenge does not authenticate the Master. Produce only this original possession response.",
                "راجعت سياق الجهاز الرئيسي الأصلي والطلب وقائمة القبول والهدف الأصلي وهوية SDK كاملًا. لا يوثّق هذا التحدي غير الموقّع الجهاز الرئيسي. أنتج استجابة حيازة المفتاح الأصلية هذه فقط."),
            t("Retain exact signing intent and respond once", "حفظ نية التوقيع الدقيقة والاستجابة مرة واحدة")) { controller.respondFreshDeviceChallenge(reviewId) }
        LocalSetupFreshDevicePairingStage.MASTER_POSSESSION_REVIEW -> FreshExplicitAction(reviewId, enabled && namespaceCompatible,
            t("I reviewed the exact response against this original challenge, request and target. Ask this same actual Master to verify the original proof, without approving or admitting it.",
                "راجعت الاستجابة الدقيقة مقابل التحدي والطلب والهدف الأصليين. اطلب من الجهاز الرئيسي الفعلي نفسه التحقق من الإثبات الأصلي دون اعتماده أو قبوله."),
            t("Verify this original target proof once", "التحقق من إثبات هذا الهدف الأصلي مرة واحدة")) { controller.proveFreshDeviceTarget(reviewId) }
        LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_REVIEW -> if (freshMasterMayReviewUnsignedRetirement(selected))
            FreshExplicitAction(reviewId, enabled,
                t("I reviewed this complete original challenge, matching identity and native phase. Retire only this exact known-unsigned pairing, with no root endorsement, uncertain signing, credential custody or submitted admission. Retain the exact retirement intent before the native action.",
                    "راجعت التحدي الأصلي الكامل والهوية المطابقة والمرحلة الأصلية. أنهِ فقط هذا الاقتران الدقيق المعروف أنه دون توقيع، دون اعتماد أصل أو توقيع غير مؤكد أو حيازة بيانات دخول أو قبول مرسل. احفظ نية الإنهاء الدقيقة قبل الإجراء الأصلي."),
                t("Retain exact intent and retire this unsigned original", "حفظ النية الدقيقة وإنهاء هذا الأصل دون توقيع")) { controller.retireUnsignedDevicePairing(reviewId) }
        LocalSetupFreshDevicePairingStage.TARGET_RETIREMENT_REVIEW -> if (selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET &&
            selected.identity != null && selected.retirement != null && !selected.continuationAttempted)
            FreshExplicitAction(reviewId, enabled && namespaceCompatible,
                t("I reviewed R as DATA and its complete old challenge against this same target's retained identity. Ask the native target to verify the original root signature and exact retirement lineage, then allocate one successor response while keeping the same key, device, preparation and SDK identity. This grants no approval, admission or session.",
                    "راجعت R كبيانات وتحديه القديم كاملًا مقابل الهوية المحفوظة للهدف نفسه. اطلب من الهدف الأصلي التحقق من توقيع الأصل ومسار الإنهاء الدقيق، ثم تخصيص استجابة لاحقة واحدة مع إبقاء المفتاح والجهاز والتحضير وهوية SDK نفسها. لا يمنح ذلك اعتمادًا أو قبولًا أو جلسة."),
                t("Retain exact intent and allocate one successor response", "حفظ النية الدقيقة وتخصيص استجابة لاحقة واحدة")) { controller.continueUnsignedDevicePairing(reviewId) }
        LocalSetupFreshDevicePairingStage.TARGET_PROVED, LocalSetupFreshDevicePairingStage.RETAINED_ADMISSION_HANDOFF ->
            OutlinedButton(onClick = { controller.handoffFreshDeviceAdmission(reviewId) }, enabled = enabled && !selected.retirementAttempted && selected.retirement == null,
                modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Open separate head / admission review", "فتح مراجعة الرئيس / القبول المنفصلة")) }
        LocalSetupFreshDevicePairingStage.TARGET_MASTER_REVIEW -> FreshExplicitAction(reviewId, enabled,
            t("I reviewed this exact original Master, challenge and approval/root document. Ask this target's native owner to verify and retain it without signing again, admitting a device or starting an employee session.",
                "راجعت هذا الجهاز الرئيسي الأصلي والتحدي ومستند الاعتماد / الأصل بعينهم. اطلب من المالك الأصلي لهذا الهدف التحقق والحفظ دون إعادة التوقيع أو قبول جهاز أو بدء جلسة موظف."),
            t("Verify and retain this original Master", "التحقق من هذا الجهاز الرئيسي الأصلي وحفظه")) { controller.verifyApprovedPairingMaster(reviewId) }
        else -> Unit
    }
    if (freshMasterMayReviewUnsignedRetirement(selected) && selected.stage != LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_REVIEW) {
        OutlinedButton(onClick = { controller.reviewUnsignedDevicePairingRetirement(reviewId) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Review retirement of this known-unsigned original", "مراجعة إنهاء هذا الأصل المعروف أنه دون توقيع")) }
    }
    if (freshMasterUnsignedRetired(selected)) {
        OutlinedButton(onClick = { controller.exportFreshDeviceDocument(reviewId, LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Re-read and export exact native retirement (R)", "إعادة قراءة الإنهاء الأصلي الدقيق وتصديره (R)")) }
    }
    if (selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET && selected.identity != null) {
        OutlinedButton(onClick = { controller.exportFreshDeviceDocument(reviewId, LocalSetupPairingDocumentKind.SDK_IDENTITY) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Re-read and export original identity (I)", "إعادة قراءة الهوية الأصلية وتصديرها (I)")) }
        OutlinedButton(onClick = { controller.importFreshDeviceChallenge(reviewId) }, enabled = enabled && !selected.continuationAttempted &&
            selected.stage !in setOf(LocalSetupFreshDevicePairingStage.TARGET_RETIREMENT_REVIEW, LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED),
            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Import / review the complete challenge (C)", "استيراد / مراجعة التحدي الكامل (C)")) }
        OutlinedButton(onClick = { controller.importUnsignedDevicePairingRetirement(reviewId) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().touchTarget()) {
            Text(if (selected.continuationAttempted || selected.stage == LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED)
                t("Re-import exact retirement (R) for original recovery", "إعادة استيراد الإنهاء الدقيق (R) لاستعادة الأصل")
                else t("Import / review the original retirement (R)", "استيراد / مراجعة الإنهاء الأصلي (R)"))
        }
        if (selected.possession != null) OutlinedButton(onClick = {
            controller.exportFreshDeviceDocument(reviewId, LocalSetupPairingDocumentKind.POSSESSION)
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Re-read and export original response (P)", "إعادة قراءة الاستجابة الأصلية وتصديرها (P)")) }
        if (selected.possession != null) {
            OutlinedButton(onClick = { controller.importApprovedPairingMaster(reviewId) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Import / review original approval and root", "استيراد / مراجعة الاعتماد والأصل الأصليين")) }
            OutlinedButton(onClick = { controller.recoverApprovedPairingMaster(reviewId) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Read only the native-verified original Master", "قراءة الجهاز الرئيسي الأصلي المتحقق منه أصليًا فقط")) }
        }
    }
    if (selected.role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER && selected.challenge != null && !selected.retirementAttempted &&
        selected.retirement == null && selected.stage !in setOf(LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_UNRESOLVED,
            LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED)) {
        OutlinedButton(onClick = { controller.exportFreshDeviceDocument(reviewId, LocalSetupPairingDocumentKind.CHALLENGE) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Re-read and export original challenge (C)", "إعادة قراءة التحدي الأصلي وتصديره (C)")) }
        OutlinedButton(onClick = { controller.importFreshDevicePossession(reviewId) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Import / review the exact response (P)", "استيراد / مراجعة الاستجابة الدقيقة (P)")) }
        if (selected.nativePhase == LocalDevicePairingPhase.APPROVED && selected.challenge.requested.masterSdkOriginal != null)
            OutlinedButton(onClick = { controller.exportFreshDeviceDocument(reviewId, LocalSetupPairingDocumentKind.APPROVED_MASTER) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Re-read and export original approval and root", "إعادة قراءة الاعتماد والأصل الأصليين وتصديرهما")) }
    }
}

@Composable
private fun FreshExplicitAction(reviewId: DomainId, enabled: Boolean, consentText: String, buttonText: String, action: () -> Unit) {
    var consent by remember(reviewId, enabled, consentText) { mutableStateOf(false) }
    Consent(consentText, consent, enabled) { consent = it }
    Button(onClick = { consent = false; action() }, enabled = enabled && consent,
        modifier = Modifier.fillMaxWidth().touchTarget()) { Text(buttonText) }
}

private fun freshMasterUnsignedRetired(selected: LocalSetupFreshDevicePairingView): Boolean =
    selected.role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER && selected.stage == LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED &&
        selected.nativePhase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED && selected.retirement != null

private fun freshMasterMayLeaveOriginal(selected: LocalSetupFreshDevicePairingView): Boolean = freshMasterUnsignedRetired(selected) ||
    (!selected.retirementAttempted && selected.retirement == null && (!selected.preparationAttempted ||
        selected.stage in setOf(LocalSetupFreshDevicePairingStage.TARGET_PROVED, LocalSetupFreshDevicePairingStage.RETAINED_ADMISSION_HANDOFF)))

private fun freshMasterMayReviewUnsignedRetirement(selected: LocalSetupFreshDevicePairingView): Boolean =
    selected.role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER && selected.identity != null && selected.challenge != null &&
        !selected.retirementAttempted && selected.retirement == null &&
        selected.nativePhase in setOf(LocalDevicePairingPhase.PREPARED, LocalDevicePairingPhase.TARGET_PROVED) &&
        selected.stage in setOf(LocalSetupFreshDevicePairingStage.MASTER_CHALLENGE_READY, LocalSetupFreshDevicePairingStage.MASTER_POSSESSION_REVIEW,
            LocalSetupFreshDevicePairingStage.TARGET_PROVED, LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_REVIEW)

@Composable
private fun FreshRetirementDetails(retirement: LocalDevicePairingUnsignedRetirement) {
    SectionHeading(t("Original unsigned-retirement certificate (R)", "مستند الإنهاء الأصلي دون توقيع (R)"))
    ReviewField(t("Exact retired original action", "الإجراء الأصلي المنهي بعينه"), retirement.originalActionId.value)
    ReviewField(t("Exact retired original request", "الطلب الأصلي المنهي بعينه"), retirement.requested.request.requestId.value)
    ReviewField(t("Exact old challenge digest", "بصمة التحدي القديم الدقيقة"), retirement.originalChallenge.challengeDigest.value)
    ReviewField(t("Retirement lineage digest", "بصمة مسار الإنهاء"), retirement.dispositionDigest.value)
    Notice(t("R is public DATA. Importing or reviewing it does not confirm current Master authority, admit a device, approve a head, grant a session or start RPC. The same native target must independently verify the original root signature, old challenge/response and exact retirement lineage before a separate explicit successor allocation.",
        "R بيانات عامة. لا يؤكد استيراده أو مراجعته سلطة الجهاز الرئيسي الحالية، ولا يقبل جهازًا أو يعتمد رئيسًا أو يمنح جلسة أو يبدأ RPC. يجب أن يتحقق الهدف الأصلي نفسه بصورة مستقلة من توقيع الأصل والتحدي والاستجابة القديمين ومسار الإنهاء الدقيق قبل تخصيص لاحق صريح منفصل."))
    Notice(t("Keep the same target key, device, native preparation, SDK acquisition and identity, with every earlier response and certificate. At most 256 native actions/response allocations are allowed, including the initial allocation; no history is reset or replaced.",
        "احتفظ بمفتاح الهدف وجهازه وتحضيره الأصلي وحصوله على SDK وهويته نفسها، مع كل استجابة ومستند سابقين. الحد الأقصى 256 إجراءً أصليًا أو تخصيص استجابة، شاملًا التخصيص الأول؛ ولا يُصفّر السجل أو يُستبدل."))
}

@Composable
private fun FreshIdentityDetails(identity: LocalDevicePairingSdkIdentityV2) {
    SectionHeading(t("Complete original identity document (I)", "مستند الهوية الأصلية الكامل (I)"))
    ReviewField(t("Original SDK acquisition", "الحصول الأصلي على SDK"), identity.originalAcquisitionId.value)
    ReviewField(t("Original native preparation", "التحضير الأصلي للبيئة الأصلية"), identity.originalPreparationId.value)
    ReviewField(t("Complete identity digest", "بصمة الهوية الكاملة"), identity.descriptorDigest.value)
    FreshAdmissionDetails(identity.target.admission, t("Exact requested revision-zero target", "الهدف المطلوب بعينه بالمراجعة صفر"))
    ReviewField(t("Native key ID", "معرّف المفتاح الأصلي"), identity.target.keyId.value)
    ReviewField(t("Native target descriptor digest", "بصمة وصف الهدف الأصلي"), identity.target.descriptorDigest.value)
    ReviewField(t("Native P-256 public point", "النقطة العامة P-256 الأصلية"), freshPublicHex(identity.target.copyPublicPoint()))
    val installed = identity.nativeApplication
    ReviewField(t("Installed application ID", "معرّف التطبيق المثبت"), installed.applicationId)
    ReviewField(t("Installed application UID", "معرّف مستخدم التطبيق المثبت"), installed.applicationUid.toString())
    ReviewField(t("Installed APK signer SHA-256", "بصمة SHA-256 لموقّع APK المثبت"), installed.apkSignerSha256)
    ReviewField(t("Original Android ID commitment", "التزام معرّف Android الأصلي"), installed.androidIdCommitment)
    ReviewField(t("SDK AppId", "معرّف التطبيق في SDK"), identity.appId.value)
    ReviewField(t("SDK PeerId", "معرّف النظير في SDK"), identity.peerId.value)
    ReviewField(t("SDK X25519 fingerprint", "بصمة X25519 في SDK"), identity.fingerprint.value)
    ReviewField(t("Canonical SDK pairing QR text", "نص QR الاقتران المعتمد في SDK"), identity.pairingQr)
    Notice(t("Native P-256 and SDK X25519 are distinct identities. These public values do not grant admission, a live transport session or employee authority. The private device name is not included in I.",
        "P-256 الأصلي وX25519 في SDK هويتان مختلفتان. لا تمنح هذه القيم العامة قبولًا أو جلسة نقل حية أو سلطة موظف. اسم الجهاز الخاص غير موجود في I."))
}

@Composable
private fun FreshAdmissionDetails(admission: LocalDeviceAdmission, title: String) {
    SectionHeading(title)
    ScopeDetails(admission.scope)
    ReviewField(t("Device", "الجهاز"), admission.deviceId.value)
    ReviewField(t("Admission revision", "مراجعة القبول"), admission.revision.value.toString())
    ReviewField(t("Admission value in this original DATA", "قيمة القبول في بيانات هذا الأصل"),
        if (admission.admitted) t("Admitted", "مقبول") else t("Not admitted", "غير مقبول"), code = false)
    ReviewField(t("Native public-key identifier", "معرّف المفتاح العام الأصلي"), admission.publicKeyIdentifier ?: t("Absent", "غير موجود"))
    ReviewField(t("Native public fingerprint", "البصمة العامة الأصلية"), admission.publicFingerprint ?: t("Absent", "غير موجود"))
    ReviewField(t("Complete capability descriptions", "أوصاف الإمكانات الكاملة"), capabilitiesText(admission.capabilities))
}

@Composable
private fun FreshChallengeDetails(challenge: LocalDevicePairingChallenge) {
    SectionHeading(t("Complete original challenge (C)", "التحدي الأصلي الكامل (C)"))
    Notice(t("This is unsigned Master DATA. Review its exact original context with the intended branch. Its elapsed times belong to that Master; this target never decides expiry using its own clock.",
        "هذه بيانات غير موقّعة للجهاز الرئيسي. راجع سياق الأصل الدقيق مع الفرع المقصود. تخص أوقاتها المنقضية ذلك الجهاز الرئيسي؛ ولا يقرر الهدف انتهاء الصلاحية بساعته."))
    ReviewField(t("Original action", "الإجراء الأصلي"), challenge.originalActionId.value)
    ReviewField(t("Original request", "الطلب الأصلي"), challenge.requested.request.requestId.value)
    ReviewField(t("Exact purpose", "الغرض الدقيق"), challenge.purpose)
    ReviewField(t("Protected request action", "إجراء الطلب المحمي"), challenge.requested.request.action.name)
    ReviewField(t("Protected request target", "هدف الطلب المحمي"), challenge.requested.request.targetId.value)
    ReviewField(t("Complete challenge digest", "بصمة التحدي الكامل"), challenge.challengeDigest.value)
    ReviewField(t("Complete requested definition digest", "بصمة التعريف المطلوب الكامل"), challenge.requested.definitionDigest.value)
    ReviewField(t("Exact protected request digest", "بصمة الطلب المحمي الدقيق"), challenge.requested.request.contentDigest.value)
    ReviewField(t("Company", "الشركة"), challenge.companyName, code = false)
    ReviewField(t("Named company head", "رئيس الشركة المسمّى"), challenge.headName, code = false)
    ReviewField(t("Exact head login", "معرّف دخول الرئيس الدقيق"), challenge.headLogin)
    challenge.requested.masterSdkOriginal?.let { master ->
        SectionHeading(t("Selected original Master SDK identity", "هوية SDK للجهاز الرئيسي الأصلي المختار"))
        ReviewField(t("Original Master name", "اسم الجهاز الرئيسي الأصلي"), master.exactDeviceName, code = false)
        ReviewField(t("Master SDK AppId", "معرّف تطبيق SDK للجهاز الرئيسي"), master.appId.value)
        ReviewField(t("Master SDK peer ID", "معرّف نظير SDK للجهاز الرئيسي"), master.peerId.value)
        ReviewField(t("Master SDK fingerprint", "بصمة SDK للجهاز الرئيسي"), master.fingerprint.value)
        ReviewField(t("Master SDK pairing QR", "رمز اقتران SDK للجهاز الرئيسي"), master.pairingQr)
        ReviewField(t("Original Master SDK acquisition", "الحصول الأصلي على SDK للجهاز الرئيسي"), master.originalAcquisitionId.value)
        ReviewField(t("Complete Master original digest", "بصمة الأصل الكامل للجهاز الرئيسي"), master.descriptorDigest.value)
        val installed = master.nativeApplication
        ReviewField(t("Master installed application ID", "معرّف التطبيق المثبت للجهاز الرئيسي"), installed.applicationId)
        ReviewField(t("Master installed application UID", "معرّف مستخدم التطبيق المثبت للجهاز الرئيسي"), installed.applicationUid.toString())
        ReviewField(t("Master APK signer SHA-256", "بصمة SHA-256 لموقّع APK للجهاز الرئيسي"), installed.apkSignerSha256)
        ReviewField(t("Master Android ID commitment", "التزام معرّف Android للجهاز الرئيسي"), installed.androidIdCommitment)
        ReviewField(t("Master original P1 delegation digest", "بصمة تفويض P1 الأصلي للجهاز الرئيسي"), master.allocation.p1DelegationDigest.value)
        ReviewField(t("Master original P1 preparation digest", "بصمة تحضير P1 الأصلي للجهاز الرئيسي"), master.allocation.p1PreparationDigest.value)
        ReviewField(t("Master original native public point", "النقطة العامة الأصلية للجهاز الرئيسي"), freshPublicHex(master.allocation.copyTargetPublicKey()))
        Notice(t("These unsigned details record the Master you are selecting. Respond only after checking the intended Master. Selection and possession alone do not authenticate it; the matching original root and approved endorsement must be verified separately.",
            "تسجل هذه التفاصيل غير الموقّعة الجهاز الرئيسي الذي تختاره. لا تستجب إلا بعد التأكد من الجهاز الرئيسي المقصود. لا يوثّق الاختيار وحيازة المفتاح وحدهما هويته؛ ويجب التحقق بصورة منفصلة من الأصل المطابق وتوقيع الاعتماد الأصلي."), caution = true)
    } ?: Notice(t("This historical challenge does not include an original Master SDK identity. Its exact data remains readable, but it cannot establish a selected RPC Master.",
        "لا يتضمن هذا التحدي التاريخي هوية SDK أصلية للجهاز الرئيسي. تبقى بياناته الدقيقة قابلة للقراءة، لكنه لا يثبت جهازًا رئيسيًا مختارًا لـ RPC."), caution = true)
    val origin = challenge.reviewContext; val ids = origin.coordinates
    SectionHeading(t("Original Master allocation and installation", "تخصيص الجهاز الرئيسي وتثبيته الأصليان"))
    ScopeDetails(ids.scope)
    ReviewField(t("Allocation", "التخصيص"), ids.allocationId.value)
    ReviewField(t("Installation", "التثبيت"), ids.installationId.value)
    ReviewField(t("Assignment", "الإسناد"), ids.assignmentId.value)
    ReviewField(t("Original cohost device", "الجهاز المضيف الأصلي"), ids.deviceId.value)
    ReviewField(t("Original storage instance", "نسخة التخزين الأصلية"), ids.storageInstanceId.value)
    ReviewField(t("Original storage key ID", "معرّف مفتاح التخزين الأصلي"), ids.storageKeyId.value)
    ReviewField(t("Original pepper key ID", "معرّف مفتاح Pepper الأصلي"), ids.pepperKeyId.value)
    ReviewField(t("Original target signing key ID", "معرّف مفتاح توقيع الهدف الأصلي"), ids.targetSigningKeyId.value)
    ReviewField(t("Original first actor", "المستخدم الأول الأصلي"), ids.firstActorId.value)
    ReviewField(t("Original allocation digest", "بصمة التخصيص الأصلي"), origin.allocationDigest.value)
    ReviewField(t("Original native binding digest", "بصمة الارتباط الأصلي"), origin.bindingDigest.value)
    ReviewField(t("Original installation request", "طلب التثبيت الأصلي"), origin.installationRequestId.value)
    ReviewField(t("Original installation definition digest", "بصمة تعريف التثبيت الأصلي"), origin.installationDefinitionDigest.value)
    ReviewField(t("Original installation receipt digest", "بصمة إيصال التثبيت الأصلي"), origin.installationReceiptDigest.value)
    ReviewField(t("Original authority epoch", "عهد السلطة الأصلي"), origin.authorityEpoch.value.toString())
    SectionHeading(t("Original company root and head authority", "أصل الشركة وسلطة الرئيس الأصليان"))
    ReviewField(t("Root ID", "معرّف الأصل"), origin.rootId.value)
    ReviewField(t("Root key ID", "معرّف مفتاح الأصل"), origin.rootKeyId.value)
    ReviewField(t("Root document digest", "بصمة مستند الأصل"), origin.rootDocumentDigest.value)
    ReviewField(t("Root fingerprint", "بصمة الأصل"), origin.rootFingerprint)
    ReviewField(t("Original founding action", "إجراء التأسيس الأصلي"), origin.foundingActionId.value)
    ReviewField(t("Original head authority", "سلطة الرئيس الأصلية"), origin.headAuthorityId.value)
    ReviewField(t("Original head enrollment", "تسجيل الرئيس الأصلي"), origin.headEnrollmentId.value)
    ReviewField(t("Prepared head account revision", "مراجعة حساب الرئيس المحضّرة"), origin.accountRevision.toString())
    ReviewField(t("Prepared head credential revision", "مراجعة بيانات دخول الرئيس المحضّرة"), origin.credentialRevision.toString())
    ReviewField(t("Prepared head retained revision", "مراجعة الرئيس المحفوظة المحضّرة"), origin.retainedRevision.toString())
    SectionHeading(t("Original history and timing DATA", "بيانات السجل والتوقيت الأصلية"))
    val head = origin.preparationParent
    ReviewField(t("Preparation parent history sequence", "تسلسل سجل أصل التحضير"), head.historySequence.toString())
    ReviewField(t("Preparation parent history digest", "بصمة سجل أصل التحضير"), head.historyDigest.value)
    ReviewField(t("Installation genesis digest", "بصمة أصل سجل التثبيت"), head.installationGenesisDigest.value)
    ReviewField(t("Parent command epoch", "عهد أمر الأصل"), head.commandEpoch.toString())
    ReviewField(t("Parent command sequence", "تسلسل أمر الأصل"), head.commandSequence.toString())
    ReviewField(t("Parent command digest", "بصمة أمر الأصل"), head.commandDigest.value)
    ReviewField(t("Parent role commitment", "التزام دور الأصل"), head.roleCommitment.value)
    ReviewField(t("Parent recovery commitment", "التزام استعادة الأصل"), head.recoveryCommitment.value)
    ReviewField(t("Preparation anchor digest", "بصمة مرتكز التحضير"), origin.preparationAnchorDigest.value)
    ReviewField(t("Master OS boot count", "عدد إقلاعات نظام الجهاز الرئيسي"), origin.osBootCount.toString())
    ReviewField(t("Master observation issuer", "جهة رصد الجهاز الرئيسي"), origin.observationIssuerContextId.value)
    ReviewField(t("Master observation boot ID", "معرّف إقلاع رصد الجهاز الرئيسي"), origin.observationBootId.value)
    ReviewField(t("Master original observed elapsed milliseconds", "المللي ثانية المنقضية المرصودة أصلًا في الجهاز الرئيسي"), origin.observationElapsedMillis.toString())
    ReviewField(t("Master original deadline elapsed milliseconds", "الموعد الأصلي بالمللي ثانية المنقضية في الجهاز الرئيسي"), origin.deadlineElapsedMillis.toString())
    ReviewField(t("Original public nonce", "القيمة العشوائية العامة الأصلية"), freshPublicHex(origin.copyNonceBytes()))
    ReviewField(t("Original nonce digest", "بصمة القيمة العشوائية الأصلية"), origin.nonceDigest.value)
    ReviewField(t("Previous unsigned-retirement link", "رابط الإنهاء السابق دون توقيع"), origin.previousUnsignedRetirementDigest?.value ?: t("Absent", "غير موجود"))
    FreshAdmissionDetails(challenge.requested.admission, t("Complete requested target admission", "قبول الهدف المطلوب كاملًا"))
    ReviewField(t("Full retained admission census count", "عدد قائمة القبول المحفوظة الكاملة"), challenge.requested.retainedAdmissions.size.toString())
    Notice(t("Every retained admission below belongs to this exact original challenge. The same actual Master independently checks current authority, the complete census and the unchanged original deadline.",
        "كل قبول محفوظ أدناه جزء من هذا التحدي الأصلي بعينه. يتحقق الجهاز الرئيسي الفعلي نفسه بصورة مستقلة من السلطة الحالية وقائمة القبول الكاملة والموعد الأصلي دون تغيير."))
}

@Composable
private fun capabilitiesText(codes: Set<String>): String = codes.sorted().joinToString("\n").ifEmpty { t("None", "لا شيء") }

private fun freshPublicHex(bytes: ByteArray): String = try {
    val alphabet = "0123456789abcdef"
    buildString(bytes.size * 2) { bytes.forEach { val n = it.toInt() and 255; append(alphabet[n ushr 4]); append(alphabet[n and 15]) } }
} finally { bytes.fill(0) }

@Composable
private fun freshDocumentName(kind: LocalSetupPairingDocumentKind): String = when (kind) {
    LocalSetupPairingDocumentKind.SDK_IDENTITY -> t("Identity I", "الهوية I")
    LocalSetupPairingDocumentKind.CHALLENGE -> t("Challenge C", "التحدي C")
    LocalSetupPairingDocumentKind.POSSESSION -> t("Response P", "الاستجابة P")
    LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT -> t("Retirement R", "الإنهاء R")
    LocalSetupPairingDocumentKind.APPROVED_MASTER -> t("Original approval / root", "الاعتماد / الأصل الأصليان")
}

@Composable
private fun freshPairingStageText(stage: LocalSetupFreshDevicePairingStage): String = when (stage) {
    LocalSetupFreshDevicePairingStage.TARGET_INPUT -> t("Enter exact descriptive inputs, then review them before native preparation.", "أدخل المدخلات الوصفية الدقيقة، ثم راجعها قبل التحضير الأصلي.")
    LocalSetupFreshDevicePairingStage.TARGET_INPUT_REVIEW -> t("The target request is reviewed DATA. Explicit preparation is still required.", "طلب الهدف بيانات تمت مراجعتها. ما زال التحضير الصريح مطلوبًا.")
    LocalSetupFreshDevicePairingStage.TARGET_PREPARATION_UNRESOLVED -> t("Native target preparation was entered or remains uncertain. Recover that same original; do not prepare another.", "دخل التحضير الأصلي للهدف أو بقي غير مؤكد. استعد الأصل نفسه؛ ولا تحضّر آخر.")
    LocalSetupFreshDevicePairingStage.TARGET_KEY_READY -> t("The original native target is retained. Its actual SDK identity needs a separate explicit acquisition.", "الهدف الأصلي محفوظ. تتطلب هوية SDK الفعلية الحصول عليها بإجراء صريح منفصل.")
    LocalSetupFreshDevicePairingStage.SDK_ACQUISITION_UNRESOLVED -> t("Original SDK acquisition is unresolved. Recovery cannot reconstruct a replacement identity or re-enter acquisition.", "الحصول الأصلي على SDK غير محسوم. لا تنشئ الاستعادة هوية بديلة ولا تعِد إجراء الحصول.")
    LocalSetupFreshDevicePairingStage.SDK_IDENTITY_READY -> t("The exact original identity is available for document export. It does not prove Master admission.", "الهوية الأصلية الدقيقة متاحة لتصدير المستند. لا تثبت قبول الجهاز الرئيسي.")
    LocalSetupFreshDevicePairingStage.TARGET_CHALLENGE_REVIEW -> t("Review the entire original challenge and matching target identity before explicitly authorizing one possession response.", "راجع التحدي الأصلي كاملًا وهوية الهدف المطابقة قبل التصريح صراحةً باستجابة حيازة مفتاح واحدة.")
    LocalSetupFreshDevicePairingStage.TARGET_RESPONSE_UNRESOLVED -> t("The original signing intent/attempt remains unresolved. Only original possession recovery is allowed.", "نية التوقيع الأصلية أو محاولته غير محسومة. تُسمح باستعادة استجابة حيازة المفتاح الأصلية فقط.")
    LocalSetupFreshDevicePairingStage.TARGET_POSSESSION_READY -> t("The original target response is retained DATA for export. The original Master still must verify it.", "استجابة الهدف الأصلية بيانات محفوظة للتصدير. ما زال على الجهاز الرئيسي الأصلي التحقق منها.")
    LocalSetupFreshDevicePairingStage.TARGET_RETIREMENT_REVIEW -> t("The imported retirement R is DATA. Review its complete old challenge and this same target identity before explicitly requesting one native successor response allocation.", "مستند الإنهاء R المستورد بيانات. راجع تحديه القديم كاملًا وهوية الهدف نفسه قبل طلب تخصيص استجابة لاحقة أصلية واحدة صراحةً.")
    LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED -> t("The exact original successor allocation is unresolved. Re-import only its exact R if needed for recovery, then recover that same retained allocation. No second allocation or response attempt is allowed.", "تخصيص الاستجابة اللاحقة الأصلي بعينه غير محسوم. أعد استيراد R الدقيق الخاص به فقط عند الحاجة للاستعادة، ثم استعد التخصيص المحفوظ نفسه. لا يُسمح بتخصيص ثانٍ أو محاولة استجابة أخرى.")
    LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_READY -> t("The same native target completed and retained the successor response allocation. Import a separately prepared new challenge with the exact predecessor retirement link before reviewing a new response. The old key, SDK identity, challenge and history remain retained.", "أكمل الهدف الأصلي نفسه تخصيص الاستجابة اللاحقة وحفظه. استورد تحديًا جديدًا محضّرًا بصورة منفصلة يحمل رابط الإنهاء السابق الدقيق قبل مراجعة استجابة جديدة. يبقى المفتاح القديم وهوية SDK والتحدي والسجل محفوظة.")
    LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_IMPORT -> t("Import the original target identity for review. Importing starts no native preparation.", "استورد هوية الهدف الأصلية للمراجعة. لا يبدأ الاستيراد تحضيرًا أصليًا.")
    LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_REVIEW -> t("Review the complete imported target identity. A separate retained original request is required before Master preparation.", "راجع هوية الهدف المستوردة كاملةً. يلزم طلب أصلي محفوظ منفصل قبل تحضير الجهاز الرئيسي.")
    LocalSetupFreshDevicePairingStage.MASTER_PREPARATION_UNRESOLVED -> t("The exact original Master request remains unresolved. Resolve it by retained native request/action lookup; do not replace or renew it.", "طلب الجهاز الرئيسي الأصلي بعينه غير محسوم. احسمه بالاستعلام الأصلي عن الطلب والإجراء المحفوظين؛ ولا تستبدله أو تجدده.")
    LocalSetupFreshDevicePairingStage.MASTER_CHALLENGE_READY -> t("The exact retained original challenge is available for export. Transfer it completely to its actual target.", "التحدي الأصلي المحفوظ بعينه متاح للتصدير. انقله كاملًا إلى هدفه الفعلي.")
    LocalSetupFreshDevicePairingStage.MASTER_POSSESSION_REVIEW -> t("Review the exact imported response, then explicitly ask the same original Master to verify it once.", "راجع الاستجابة المستوردة الدقيقة، ثم اطلب صراحةً من الجهاز الرئيسي الأصلي نفسه التحقق منها مرة واحدة.")
    LocalSetupFreshDevicePairingStage.MASTER_PROOF_UNRESOLVED -> t("Original Master proof confirmation is unresolved. Read only this native original; no second proof attempt is allowed.", "تأكيد الإثبات في الجهاز الرئيسي الأصلي غير محسوم. اقرأ هذا الأصل فقط؛ ولا تُسمح بمحاولة إثبات ثانية.")
    LocalSetupFreshDevicePairingStage.TARGET_PROVED -> t("The actual original Master confirmed TARGET_PROVED for this exact retained original. Open head/admission review separately; no approval or admission has been submitted here.", "أكد الجهاز الرئيسي الأصلي الفعلي TARGET_PROVED لهذا الأصل المحفوظ بعينه. افتح مراجعة الرئيس والقبول بصورة منفصلة؛ ولم يُرسل هنا اعتماد أو قبول.")
    LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_REVIEW -> t("Review this complete original challenge, matching target identity, native unsigned phase and intended retirement. Review alone changes nothing; retiring this exact original requires a separate explicit action.", "راجع التحدي الأصلي الكامل وهوية الهدف المطابقة والمرحلة الأصلية دون توقيع والإنهاء المقصود. لا تغيّر المراجعة وحدها شيئًا؛ ويتطلب إنهاء هذا الأصل بعينه إجراءً صريحًا منفصلًا.")
    LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_UNRESOLVED -> t("The exact original retirement intent/attempt is retained and unresolved. Recover only its native original certificate; do not retire again, renew the deadline or infer retirement from expiry or admission no-effect.", "نية الإنهاء الأصلية الدقيقة أو محاولتها محفوظة وغير محسومة. استعد مستند أصلها الأصلي فقط؛ ولا تعِد الإنهاء أو تجدد الموعد أو تستنتج الإنهاء من انقضاء الصلاحية أو عدم أثر القبول.")
    LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED -> t("The actual original Master confirmed terminal known-unsigned retirement. Re-read/export its exact native R, then separately review a new request for the same identity. Retirement grants no approval or admission and does not revoke an admitted device.", "أكد الجهاز الرئيسي الأصلي الفعلي الإنهاء النهائي المعروف أنه دون توقيع. أعد قراءة R الأصلي الدقيق وتصديره، ثم راجع بصورة منفصلة طلبًا جديدًا للهوية نفسها. لا يمنح الإنهاء اعتمادًا أو قبولًا ولا يلغي قبول جهاز مقبول.")
    LocalSetupFreshDevicePairingStage.RETAINED_ADMISSION_HANDOFF -> t("An actual approved original was re-read. Continue only to its separate retained admission/outcome review.", "أُعيدت قراءة أصل معتمد فعلي. تابع فقط إلى مراجعة قبوله المحفوظ ونتيجته المنفصلة.")
    LocalSetupFreshDevicePairingStage.ORIGINAL_UNAVAILABLE -> t("The exact original is unavailable or its closure is unconfirmed. Preserve the original metadata and use existing-only recovery.", "الأصل بعينه غير متاح أو إغلاقه غير مؤكد. احفظ بيانات الأصل واستخدم استعادة الموجود فقط.")
    LocalSetupFreshDevicePairingStage.TARGET_MASTER_REVIEW -> t("Review the original approval/root transfer before explicit native verification.", "راجع نقل الاعتماد / الأصل الأصلي قبل التحقق الأصلي الصريح.")
    LocalSetupFreshDevicePairingStage.TARGET_MASTER_UNRESOLVED -> t("Original Master import remains unresolved. Read only the exact native original; do not replace its evidence or sign again.", "استيراد الجهاز الرئيسي الأصلي غير محسوم. اقرأ الأصل الأصلي بعينه فقط؛ ولا تستبدل أدلته أو تعِد التوقيع.")
    LocalSetupFreshDevicePairingStage.TARGET_MASTER_VERIFIED -> t("The native owner verified the selected original Master. This is not admission, employee authentication or RPC startup.", "تحقق المالك الأصلي من الجهاز الرئيسي الأصلي المختار. لا يُعد ذلك قبولًا أو تسجيل دخول موظف أو بدء RPC.")
}

@Composable
private fun freshMasterNativePhaseText(phase: LocalDevicePairingPhase): String = when (phase) {
    LocalDevicePairingPhase.PREPARED -> t("Original prepared, unsigned", "الأصل محضّر دون توقيع")
    LocalDevicePairingPhase.TARGET_PROVED -> t("Original target proof confirmed, unsigned", "إثبات الهدف الأصلي مؤكد دون توقيع")
    LocalDevicePairingPhase.HEAD_CONSENT_INTENT -> t("Original head consent intent", "نية موافقة الرئيس الأصلية")
    LocalDevicePairingPhase.SIGNING_INTENT -> t("Original root endorsement signing intent", "نية توقيع اعتماد الأصل")
    LocalDevicePairingPhase.APPROVED -> t("Original head approval retained", "اعتماد الرئيس الأصلي محفوظ")
    LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT -> t("Original unsigned-retirement signing intent", "نية توقيع الإنهاء الأصلي دون توقيع")
    LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED -> t("Original known-unsigned retirement retained", "الإنهاء الأصلي المعروف أنه دون توقيع محفوظ")
}

@Composable
private fun freshTargetNativePhaseText(phase: LocalDevicePairingTargetPhase): String = when (phase) {
    LocalDevicePairingTargetPhase.KEY_GENERATION_INTENT -> t("Original key-generation intent", "نية إنشاء المفتاح الأصلي")
    LocalDevicePairingTargetPhase.KEY_METADATA_SIGNING_INTENT -> t("Original key metadata signing intent", "نية توقيع بيانات المفتاح الأصلي")
    LocalDevicePairingTargetPhase.KEY_READY -> t("Original key retained", "المفتاح الأصلي محفوظ")
    LocalDevicePairingTargetPhase.RESPONSE_SIGNING_INTENT -> t("Original response signing intent", "نية توقيع الاستجابة الأصلية")
    LocalDevicePairingTargetPhase.RESPONSE_COMPLETED -> t("Original response retained", "الاستجابة الأصلية محفوظة")
    LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_SIGNING_INTENT -> t("Original response-allocation signing intent", "نية توقيع تخصيص الاستجابة الأصلية")
    LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY -> t("Original response allocation retained", "تخصيص الاستجابة الأصلية محفوظ")
    LocalDevicePairingTargetPhase.SDK_ACQUISITION_INTENT -> t("Original SDK acquisition intent", "نية الحصول الأصلي على SDK")
    LocalDevicePairingTargetPhase.SDK_METADATA_SIGNING_INTENT -> t("Original SDK metadata signing intent", "نية توقيع بيانات SDK الأصلية")
    LocalDevicePairingTargetPhase.SDK_READY -> t("Original SDK identity retained", "هوية SDK الأصلية محفوظة")
}

private val FRESH_PAIRING_UNRESOLVED_STAGES = setOf(LocalSetupFreshDevicePairingStage.TARGET_PREPARATION_UNRESOLVED,
    LocalSetupFreshDevicePairingStage.SDK_ACQUISITION_UNRESOLVED, LocalSetupFreshDevicePairingStage.TARGET_RESPONSE_UNRESOLVED,
    LocalSetupFreshDevicePairingStage.MASTER_PREPARATION_UNRESOLVED, LocalSetupFreshDevicePairingStage.MASTER_PROOF_UNRESOLVED,
    LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_UNRESOLVED, LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED,
    LocalSetupFreshDevicePairingStage.ORIGINAL_UNAVAILABLE, LocalSetupFreshDevicePairingStage.TARGET_MASTER_UNRESOLVED)
