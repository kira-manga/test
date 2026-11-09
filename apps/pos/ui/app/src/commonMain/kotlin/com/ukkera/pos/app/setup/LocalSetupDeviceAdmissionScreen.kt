package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalDevicePairingPhase
import com.ukkera.pos.types.DomainId

/** Original DATA review and separate explicit decisions. The actual native Master owns effects. */
@Composable
internal fun LocalSetupDeviceAdmissionScreen(controller: LocalSetupController, view: LocalSetupView,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit, modifier: Modifier, onBack: (() -> Unit)?) {
    val selected = view.deviceAdmission
    val originals = view.deviceAdmissions?.originals.orEmpty()
    val blockedId = view.deviceAdmissions?.unresolvedActionId
    val enabled = view.work == null && view.localClientConnected
    val canReturn = blockedId == null && (selected == null || !selected.headAttempted && !selected.admissionAttempted ||
        selected.stage == LocalSetupDeviceAdmissionStage.APPROVED && !selected.admissionAttempted ||
        selected.stage in setOf(LocalSetupDeviceAdmissionStage.APPLIED, LocalSetupDeviceAdmissionStage.KNOWN_NO_EFFECT))
    CompositionLocalProvider(LocalAppLanguage provides language, LocalLayoutDirection provides language.direction) {
        PosTheme {
            LazyColumn(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    onBack?.let { back -> OutlinedButton(onClick = { controller.lockPresentation(); back() },
                        modifier = Modifier.touchTarget()) { Text(t("Device choices", "خيارات الجهاز")) } }
                    OutlinedButton(onClick = controller::lockPresentation, modifier = Modifier.touchTarget()) {
                        Text(t("Lock setup", "قفل الإعداد"))
                    }
                    OutlinedButton(onClick = { onLanguageChange(language.toggle()) }, modifier = Modifier.touchTarget()) {
                        Text(if (language == AppLanguage.ENGLISH) "العربية" else "English")
                    }
                    SectionHeading(t("Retained original device admission", "قبول الجهاز الأصلي المحفوظ"))
                    Notice(t("Select an actual retained native original, review its complete target and SDK definition with the named company head, then confirm admission separately. A displayed approval is DATA, not permission to dispatch.",
                        "اختر أصلًا فعليًا محفوظًا، وراجع تعريف هدفه وهويته في SDK كاملًا مع رئيس الشركة المسمّى، ثم أكّد القبول بصورة منفصلة. الاعتماد المعروض بيانات وليس تصريح إرسال."))
                    Notice(t("This uses the same original branch Master. Admission does not create an employee account or session, attach a remote peer, start LAN/RPC or establish full device readiness. Original native authority and expiry are never renewed by this screen.",
                        "يستخدم هذا الجهاز الرئيسي الأصلي نفسه للفرع. لا ينشئ القبول حساب موظف أو جلسة، ولا يصل نظيرًا بعيدًا، ولا يشغّل الشبكة المحلية أو RPC، ولا يثبت جاهزية الجهاز الكاملة. لا تجدّد هذه الشاشة السلطة الأصلية أو مدة صلاحيتها."))
                    view.native?.let { native ->
                        ReviewField(t("Original branch installation", "تثبيت الفرع الأصلي"), native.installationId.value)
                        ReviewField(t("Original cohost device", "الجهاز المضيف الأصلي"), native.deviceId.value)
                        ReviewField(t("Branch", "الفرع"), native.branchId.value)
                    }
                    view.work?.let {
                        CircularProgressIndicator()
                        Text(workText(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    view.problem?.let { Notice(problemText(it), caution = true,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                    SectionHeading(t("Actual retained native originals", "الأصول الفعلية المحفوظة"))
                    Notice(t("These are original pairing actions, not a current admitted-device directory or a transport grant. Application attempt markers preserve uncertainty; they do not prove a native outcome.",
                        "هذه إجراءات اقتران أصلية، وليست دليل الأجهزة المقبولة حاليًا أو تصريح نقل. تحفظ علامات محاولة التطبيق عدم اليقين؛ ولا تثبت نتيجة أصلية."))
                    if (originals.isEmpty()) Notice(t("No retained native original is available here. Fresh target review and full identity/challenge/response document transfer use the separate new-device pairing route. Known-unsigned continuation, remote authentication and LAN/RPC integration remain unfinished. No demo target, replacement original or imported approval will be created.",
                        "لا يتوفر هنا أصل فعلي محفوظ. تستخدم مراجعة الهدف الجديد ونقل مستندات الهوية والتحدي والاستجابة كاملةً مسار اقتران الجهاز الجديد المنفصل. ما زالت متابعة الأصل المعلوم عدم توقيعه والمصادقة عن بُعد وتكامل الشبكة المحلية وRPC غير مكتملة. لن يُنشأ هدف تجريبي أو أصل بديل أو اعتماد مستورد."), caution = true)
                    blockedId?.let { id ->
                        ReviewField(t("Original requiring read-only recovery", "الأصل الذي يتطلب استعادة للقراءة فقط"), id.value)
                        Notice(t("Resolve only this original. Another selection cannot replace uncertain work or missing history.",
                            "احسم هذا الأصل فقط. لا يستبدل اختيار آخر عملًا غير محسوم أو سجلًا مفقودًا."), caution = true)
                    }
                    OutlinedButton(onClick = controller::openDeviceAdmissions, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Read retained originals again", "قراءة الأصول المحفوظة مجددًا")) }
                }
                items(originals, key = { "original-${it.originalActionId.value}" }) { original ->
                    ReviewField(t("Original action", "الإجراء الأصلي"), original.originalActionId.value)
                    ReviewField(t("Original request", "الطلب الأصلي"), original.originalRequestId.value)
                    ReviewField(t("Exact target device", "جهاز الهدف الدقيق"), original.deviceId.value)
                    ReviewField(t("Native target key ID", "معرّف مفتاح الهدف الأصلي"), original.keyId.value)
                    ReviewField(t("Retained native phase", "المرحلة الأصلية المحفوظة"), deviceAdmissionNativePhaseText(original.phase), code = false)
                    OutlinedButton(onClick = { controller.inspectDeviceAdmission(original.originalActionId) },
                        enabled = enabled && (blockedId == null || blockedId == original.originalActionId),
                        modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Inspect only this original", "فحص هذا الأصل فقط")) }
                }
                if (selected != null) {
                    item {
                        SectionHeading(t("Selected complete original", "الأصل الكامل المحدد"))
                        ReviewField(t("Original action", "الإجراء الأصلي"), selected.originalActionId.value)
                        ReviewField(t("Original request", "الطلب الأصلي"), selected.originalRequestId.value)
                        ReviewField(t("Exact target device", "جهاز الهدف الدقيق"), selected.deviceId.value)
                        ReviewField(t("Native target key ID", "معرّف مفتاح الهدف الأصلي"), selected.keyId.value)
                        selected.nativePhase?.let { ReviewField(t("Retained native phase", "المرحلة الأصلية المحفوظة"), deviceAdmissionNativePhaseText(it), code = false) }
                        Notice(deviceAdmissionStageText(selected.stage), caution = selected.stage in setOf(
                            LocalSetupDeviceAdmissionStage.ORIGINAL_UNAVAILABLE, LocalSetupDeviceAdmissionStage.TARGET_NOT_READY,
                            LocalSetupDeviceAdmissionStage.APPROVAL_UNRESOLVED, LocalSetupDeviceAdmissionStage.ADMISSION_UNRESOLVED))
                        selected.outcome?.takeIf { it != LocalSetupDeviceAdmissionOutcome.APPLIED && it != LocalSetupDeviceAdmissionOutcome.KNOWN_NO_EFFECT }
                            ?.let { Notice(deviceAdmissionOutcomeText(it), caution = true) }
                        selected.eventId?.let { ReviewField(t("Recorded original admission event", "حدث القبول الأصلي المسجّل"), it.value) }
                        ReviewField(t("Application head-attempt marker", "علامة محاولة اعتماد الرئيس في التطبيق"),
                            if (selected.headAttempted) t("Retained / possibly started", "محفوظة / ربما بدأت") else t("Not retained by this workflow", "غير محفوظة بهذا المسار"), code = false)
                        ReviewField(t("Application admission-attempt marker", "علامة محاولة القبول في التطبيق"),
                            if (selected.admissionAttempted) t("Retained / possibly started", "محفوظة / ربما بدأت") else t("Not retained by this workflow", "غير محفوظة بهذا المسار"), code = false)
                        selected.companyName?.let { ReviewField(t("Company", "الشركة"), it, code = false) }
                        selected.headName?.let { ReviewField(t("Named company head", "رئيس الشركة المسمّى"), it, code = false) }
                        selected.headLogin?.let { ReviewField(t("Exact head login", "معرّف دخول الرئيس الدقيق"), it) }
                        selected.rootFingerprint?.let { ReviewField(t("Original company root fingerprint", "بصمة أصل الشركة الأصلي"), it) }
                        selected.challengeDigest?.let { ReviewField(t("Original target challenge digest", "بصمة تحدي الهدف الأصلي"), it) }
                        selected.authorityDigest?.let { ReviewField(t("Original approval digest (DATA only)", "بصمة الاعتماد الأصلي (بيانات فقط)"), it) }
                        selected.requested?.let { requested ->
                            SectionHeading(t("Complete requested definition", "التعريف المطلوب كاملًا"))
                            ReviewField(t("Exact pairing purpose", "غرض الاقتران الدقيق"), requested.purpose)
                            ScopeDetails(requested.request.scope)
                            ReviewField(t("Protected request action", "إجراء الطلب المحمي"), requested.request.action.name)
                            ReviewField(t("Protected request target", "هدف الطلب المحمي"), requested.request.targetId.value)
                            ReviewField(t("Complete definition digest", "بصمة التعريف الكامل"), requested.definitionDigest.value)
                            ReviewField(t("Exact protected admission request digest", "بصمة طلب القبول المحمي الدقيقة"), requested.request.contentDigest.value)
                            DeviceAdmissionData(requested.admission, t("Requested revision-zero admission", "القبول المطلوب بالمراجعة صفر"))
                            ReviewField(t("Native target descriptor digest", "بصمة وصف الهدف الأصلي"), requested.target.descriptorDigest.value)
                            ReviewField(t("Native P-256 public point", "النقطة العامة P-256 الأصلية"), deviceAdmissionPublicHex(requested.target.copyPublicPoint()))
                            val sdk = requested.sdkIdentity
                            if (sdk == null) Notice(t("This original has no SDK-bound V2 identity. Legacy evidence is unchanged and cannot authorize this admission workflow.",
                                "لا يملك هذا الأصل هوية V2 مرتبطة بـ SDK. تبقى الأدلة القديمة دون تغيير ولا تصرّح بمسار القبول هذا."), caution = true)
                            else {
                                SectionHeading(t("Original native / SDK association DATA", "بيانات ارتباط البيئة الأصلية وSDK"))
                                Notice(t("Native P-256 admission identity and SDK X25519 transport identity are distinct. Public values and QR syntax do not prove key custody, live transport or employee authority.",
                                    "هوية القبول الأصلية P-256 وهوية النقل في SDK بنوع X25519 منفصلتان. لا تثبت القيم العامة أو صيغة QR حيازة المفاتيح أو اتصالًا حيًا أو سلطة موظف."))
                                ReviewField(t("Original SDK acquisition ID", "معرّف الحصول الأصلي على SDK"), sdk.originalAcquisitionId.value)
                                ReviewField(t("Original SDK preparation ID", "معرّف تحضير SDK الأصلي"), sdk.originalPreparationId.value)
                                ReviewField(t("Complete native / SDK descriptor digest", "بصمة وصف البيئة الأصلية وSDK الكامل"), sdk.descriptorDigest.value)
                                ReviewField(t("Native installed application ID", "معرّف التطبيق المثبت الأصلي"), sdk.nativeApplication.applicationId)
                                ReviewField(t("Native application UID", "معرّف مستخدم التطبيق الأصلي"), sdk.nativeApplication.applicationUid.toString())
                                ReviewField(t("Native APK signer SHA-256", "بصمة SHA-256 لموقّع APK الأصلي"), sdk.nativeApplication.apkSignerSha256)
                                ReviewField(t("Original native Android ID commitment", "التزام معرّف Android الأصلي"), sdk.nativeApplication.androidIdCommitment)
                                ReviewField(t("SDK AppId", "معرّف التطبيق في SDK"), sdk.appId.value)
                                ReviewField(t("SDK PeerId", "معرّف النظير في SDK"), sdk.peerId.value)
                                ReviewField(t("SDK X25519 fingerprint", "بصمة X25519 في SDK"), sdk.fingerprint.value)
                                ReviewField(t("Original SDK pairing QR text (DATA)", "نص QR الاقتران الأصلي في SDK (بيانات)"), sdk.pairingQr)
                            }
                            SectionHeading(t("Complete retained admission census", "قائمة القبول المحفوظة كاملةً"))
                            ReviewField(t("Original census count", "عدد القائمة الأصلية"), requested.retainedAdmissions.size.toString())
                            Notice(t("Every admission below is part of this exact original definition. The Master rechecks the complete current protected census; cached approval cannot ignore changes.",
                                "كل قبول أدناه جزء من تعريف هذا الأصل بعينه. يعيد الجهاز الرئيسي فحص قائمة القبول المحمية الحالية كاملةً؛ ولا يمكن لاعتماد محفوظ تجاهل التغييرات."))
                        }
                    }
                    itemsIndexed(selected.requested?.retainedAdmissions.orEmpty(), key = { _, admission -> "census-${admission.deviceId.value}" }) { index, admission ->
                        DeviceAdmissionData(admission, t("Retained admission ", "القبول المحفوظ ") + (index + 1))
                    }
                    item {
                        when (selected.stage) {
                            LocalSetupDeviceAdmissionStage.HEAD_REVIEW -> if (selected.requested?.sdkIdentity != null && selected.headLogin != null)
                                DeviceAdmissionHeadApproval(selected, enabled, controller::approveDeviceAdmission)
                            LocalSetupDeviceAdmissionStage.APPROVED -> if (selected.requested?.sdkIdentity != null)
                                DeviceAdmissionConfirmation(selected, enabled, controller::admitApprovedDevice)
                            else -> Unit
                        }
                        OutlinedButton(onClick = { controller.lookupDeviceAdmission(selected.reviewId) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Read / look up only this original", "قراءة / الاستعلام عن هذا الأصل فقط")) }
                    }
                }
                item {
                    OutlinedButton(onClick = controller::closeDeviceAdmissions, enabled = enabled && canReturn,
                        modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Retain original history and return to setup", "حفظ التاريخ الأصلي والعودة إلى الإعداد")) }
                    if (!canReturn) Notice(t("Uncertain work stays on its original read-only recovery path. Locking or leaving this view does not cancel native work or discard its intent.",
                        "يبقى العمل غير المحسوم في مسار استعادة الأصل للقراءة فقط. لا يلغي قفل العرض أو مغادرته العمل الأصلي ولا يحذف نيته."), caution = true)
                }
            }
        }
    }
}

@Composable
private fun DeviceAdmissionData(admission: LocalDeviceAdmission, title: String) {
    SectionHeading(title)
    ScopeDetails(admission.scope)
    ReviewField(t("Device ID", "معرّف الجهاز"), admission.deviceId.value)
    ReviewField(t("Admission revision", "مراجعة القبول"), admission.revision.value.toString())
    ReviewField(t("Admission value in the original definition", "قيمة القبول في التعريف الأصلي"),
        if (admission.admitted) t("Admitted", "مقبول") else t("Not admitted", "غير مقبول"), code = false)
    ReviewField(t("Native public-key identifier", "معرّف المفتاح العام الأصلي"), admission.publicKeyIdentifier ?: t("Absent", "غير موجود"))
    ReviewField(t("Native public fingerprint", "البصمة العامة الأصلية"), admission.publicFingerprint ?: t("Absent", "غير موجود"))
    ReviewField(t("Complete capability descriptions (not permissions)", "أوصاف الإمكانات كاملةً (ليست صلاحيات)"),
        admission.capabilities.sorted().joinToString("\n").ifEmpty { t("None", "لا شيء") })
}

@Composable
private fun DeviceAdmissionHeadApproval(review: LocalSetupDeviceAdmissionView, enabled: Boolean,
    approve: (DomainId, CharArray) -> Unit) {
    var secret by remember(review.reviewId) { mutableStateOf("") }
    var consent by remember(review.reviewId) { mutableStateOf(false) }
    DisposableEffect(review.reviewId, enabled) { onDispose { secret = ""; consent = false } }
    SetupSecret(secret, { secret = it; consent = false }, t("Existing named company-head password", "كلمة مرور رئيس الشركة المسمّى الحالية"), enabled)
    Consent(t("I reviewed this exact original action/request, branch/root, target/native key, every retained admission and complete SDK association. Approve only this original; do not admit it yet.",
        "راجعت إجراء هذا الأصل وطلبه بعينهما والفرع والأصل والهدف والمفتاح الأصلي وكل قبول محفوظ وارتباط SDK الكامل. اعتمد هذا الأصل فقط؛ ولا تقبله بعد."), consent, enabled) { consent = it }
    Button(onClick = {
        val head = secret.toCharArray()
        try { secret = ""; consent = false; approve(review.reviewId, head) }
        finally { head.fill('\u0000') }
    }, enabled = enabled && consent && secret.length in 12..256, modifier = Modifier.fillMaxWidth().touchTarget()) {
        Text(t("Approve this exact original only", "اعتماد هذا الأصل بعينه فقط"))
    }
}

@Composable
private fun DeviceAdmissionConfirmation(review: LocalSetupDeviceAdmissionView, enabled: Boolean, admit: (DomainId) -> Unit) {
    var consent by remember(review.reviewId) { mutableStateOf(false) }
    DisposableEffect(review.reviewId, enabled) { onDispose { consent = false } }
    Consent(t("Ask the same original native Master to admit this unchanged SDK-bound target once. Approval is not a permit; preserve this original if the outcome becomes uncertain.",
        "اطلب من الجهاز الرئيسي الأصلي نفسه قبول هذا الهدف المرتبط بـ SDK دون تغيير مرة واحدة. الاعتماد ليس تصريحًا؛ احفظ هذا الأصل إن أصبحت النتيجة غير مؤكدة."), consent, enabled) { consent = it }
    Button(onClick = { consent = false; admit(review.reviewId) }, enabled = enabled && consent,
        modifier = Modifier.fillMaxWidth().touchTarget()) { Text(t("Admit this approved original device", "قبول هذا الجهاز الأصلي المعتمد")) }
}

@Composable
private fun deviceAdmissionStageText(stage: LocalSetupDeviceAdmissionStage): String = when (stage) {
    LocalSetupDeviceAdmissionStage.ORIGINAL_UNAVAILABLE -> t("The exact native original cannot currently be resolved. Preserve its identity; no approval, replacement or successful admission is established.", "لا يمكن حسم الأصل الفعلي الدقيق حاليًا. احفظ هويته؛ لم يثبت اعتماد أو بديل أو قبول ناجح.")
    LocalSetupDeviceAdmissionStage.TARGET_NOT_READY -> t("This original is not eligible for admission here. Complete SDK-bound V2 target possession is required; preparation or unsigned retirement is not an admission result.", "هذا الأصل غير مؤهل للقبول هنا. يلزم إثبات حيازة الهدف المرتبط بـ SDK في V2 كاملًا؛ والتحضير أو إنهاء أصل غير موقّع ليس نتيجة قبول.")
    LocalSetupDeviceAdmissionStage.HEAD_REVIEW -> t("The complete original is ready for explicit named-head review. This workflow has not submitted a head or admission attempt for it.", "الأصل الكامل جاهز لمراجعة صريحة من الرئيس المسمّى. لم يرسل هذا المسار محاولة اعتماد أو قبول له.")
    LocalSetupDeviceAdmissionStage.APPROVAL_UNRESOLVED -> t("Original head approval is unresolved. Read only this retained original; do not authenticate or sign it again, renew its expiry or submit admission.", "اعتماد الرئيس للأصل غير محسوم. اقرأ هذا الأصل المحفوظ فقط؛ ولا تتحقق من الرئيس أو توقّع من جديد أو تجدّد الصلاحية أو ترسل القبول.")
    LocalSetupDeviceAdmissionStage.APPROVED -> t("Exact original approval DATA is retained. Admission requires a separate explicit confirmation; the actual native producer alone checks its original single-use authority and expiry.", "بيانات الاعتماد الأصلي الدقيقة محفوظة. يتطلب القبول تأكيدًا صريحًا منفصلًا؛ ووحده المنتج الأصلي الفعلي يفحص سلطته الأصلية أحادية الاستخدام وصلاحيتها.")
    LocalSetupDeviceAdmissionStage.ADMISSION_UNRESOLVED -> t("The original admission outcome is unresolved. Use read-only original lookup; never submit admission again or replace this original.", "نتيجة القبول الأصلي غير محسومة. استخدم الاستعلام عن الأصل للقراءة فقط؛ ولا ترسل القبول من جديد أو تستبدل هذا الأصل.")
    LocalSetupDeviceAdmissionStage.APPLIED -> t("The actual original Master/native outcome records this device admission. This is original history, not employee sign-in, current business permission, a remote connection or LAN/RPC readiness.", "تسجّل نتيجة الجهاز الرئيسي الأصلي والبيئة الأصلية الفعلية قبول هذا الجهاز. هذا تاريخ أصلي وليس دخول موظف أو صلاحية أعمال حالية أو اتصالًا بعيدًا أو جاهزية للشبكة المحلية أو RPC.")
    LocalSetupDeviceAdmissionStage.KNOWN_NO_EFFECT -> t("Actual native/protected original lookup established settled no effect. Keep this original history; no new target, repeated dispatch or replacement approval follows automatically.", "أثبت الاستعلام الفعلي عن الأصل في البيئة والتخزين المحمي نتيجة مستقرة دون أثر. احفظ تاريخ هذا الأصل؛ ولا يتبعه تلقائيًا هدف جديد أو إرسال متكرر أو اعتماد بديل.")
}

@Composable
private fun deviceAdmissionOutcomeText(outcome: LocalSetupDeviceAdmissionOutcome): String = when (outcome) {
    LocalSetupDeviceAdmissionOutcome.APPLIED -> t("Original admission recorded.", "القبول الأصلي مسجّل.")
    LocalSetupDeviceAdmissionOutcome.KNOWN_NO_EFFECT -> t("Original settled no-effect result observed.", "لوحظت نتيجة الأصل المستقرة دون أثر.")
    LocalSetupDeviceAdmissionOutcome.DENIED -> t("The original operation or result read was refused. Refusal alone proves neither rollback nor a successful admission; keep original lookup only.", "رُفضت العملية الأصلية أو قراءة نتيجتها. لا يثبت الرفض وحده تراجعًا أو قبولًا ناجحًا؛ احتفظ بالاستعلام عن الأصل فقط.")
    LocalSetupDeviceAdmissionOutcome.UNAVAILABLE -> t("The original operation or result read is unavailable. No absence or successful admission is inferred.", "العملية الأصلية أو قراءة نتيجتها غير متاحة. لا يُستنتج غياب أثر أو قبول ناجح.")
    LocalSetupDeviceAdmissionOutcome.STORAGE_UNCERTAIN -> t("Original storage or outcome is uncertain. Keep the same original for read-only recovery.", "التخزين الأصلي أو النتيجة غير مؤكدة. احفظ الأصل نفسه للاستعادة للقراءة فقط.")
    LocalSetupDeviceAdmissionOutcome.BUSY -> t("The original operation or lookup is busy. This does not permit redispatch; inspect only the same original after owned work settles.", "العملية الأصلية أو الاستعلام مشغول. لا يصرّح ذلك بإعادة الإرسال؛ افحص الأصل نفسه فقط بعد استقرار العمل الجاري.")
}

@Composable
private fun deviceAdmissionNativePhaseText(phase: LocalDevicePairingPhase): String = when (phase) {
    LocalDevicePairingPhase.PREPARED -> t("Original target prepared (no target proof)", "الهدف الأصلي محضّر (دون إثبات الهدف)")
    LocalDevicePairingPhase.TARGET_PROVED -> t("Original target possession retained", "إثبات حيازة الهدف الأصلي محفوظ")
    LocalDevicePairingPhase.HEAD_CONSENT_INTENT -> t("Original head-consent intent retained", "نية موافقة الرئيس الأصلية محفوظة")
    LocalDevicePairingPhase.SIGNING_INTENT -> t("Original signing unresolved", "التوقيع الأصلي غير محسوم")
    LocalDevicePairingPhase.APPROVED -> t("Original head approval DATA retained", "بيانات اعتماد الرئيس الأصلية محفوظة")
    LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT -> t("Original unsigned-retirement signing unresolved", "توقيع إنهاء الأصل غير الموقّع غير محسوم")
    LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED -> t("Known-unsigned original retired (not admission)", "أُنهي أصل معلوم عدم توقيعه (ليس قبولًا)")
}

private fun deviceAdmissionPublicHex(bytes: ByteArray): String = try {
    val alphabet = "0123456789abcdef"
    buildString(bytes.size * 2) { bytes.forEach { byte ->
        val value = byte.toInt() and 255; append(alphabet[value ushr 4]); append(alphabet[value and 15])
    } }
} finally { bytes.fill(0) }
