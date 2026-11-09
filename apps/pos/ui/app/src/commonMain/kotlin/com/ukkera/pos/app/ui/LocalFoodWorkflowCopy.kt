package com.ukkera.pos.app.ui

import androidx.compose.runtime.Composable
import com.ukkera.pos.app.*
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity

internal enum class FoodCopy {
    TITLE, MY_CHECKS, MENU, STATIONS, REFRESH_FIRST, NEXT, OWNED_ORDER, INCOMING_ORDER, STATION_LOOKUP,
    OPEN_CHECK, REVIEW_HANDOFF, OPEN_STATION, OPEN_TASK, TASKS, ADD_SERVINGS, SELECT_SERVING, MENU_HELP,
    LOOKUP_HELP, INVALID_ID, EMPTY_PAGE, CURRENT_UNPAID, CURRENT_UNPAID_UNKNOWN, CURRENT_UNPAID_WARNING, QUOTE, QUOTE_WARNING, CHARGE_REQUIRED, PAYMENT_HELD, PREPARATION_WARNING,
    STATION_WARNING, HANDOFF_WARNING, TABLE_REFERENCE, SEAT_NOTE, ITEM_NOTE, ORDER_ID, PORTION_ID, TASK_ID,
    STATION_ID, RECIPE_ID, CHOICE_ID, UNIT_ID, OWNER_ID, RECIPIENT_ID, HANDOFF_ID, ORDER_STORAGE, PORTION_DOMAIN,
    CONFIGURATION_STORAGE, RECIPE_REVISION, PORTION_COUNT, PREPARATION, FULFILLMENT, TASK_STATE,
    DETAILS, HIDE_DETAILS, EXPANDED, COLLAPSED, ACTIVE, INACTIVE, DISPLAY, PAPER, STARTED, CANCEL_REQUESTED,
    RESULT_CODE, ISSUE_ID, TRAINING_UNAVAILABLE, NO_MODIFIERS, MODIFIERS, SELECTED_COUNT,
    INBOX, CHOOSE_RECIPIENT, SELECT_RECIPIENT, RECIPIENT_HELP, NO_RECIPIENTS, INBOX_HELP, INBOX_PROGRESS,
    INBOX_PAGE_EMPTY, INBOX_SCANNING_EMPTY, INBOX_CURSOR_EXPIRED, CONTINUE_INCOMING, OFFERED_AT,
    COLLECTION_STATUS, COLLECTED, REMAINING, REFUND_DUE, CLAIM_COUNT, COLLECTION_ID, COLLECTION_REVISION,
    MY_CLAIMS, FINANCIAL_LOOKUP, OPEN_COLLECTION, OPEN_RECEIPT, OPEN_REFUND, OPEN_CLAIM,
}

@Composable
internal fun foodCopy(key: FoodCopy): String = LocalAppLanguage.current.text(foodCopyText(key))

internal fun foodCopyText(key: FoodCopy): LocalText = when (key) {
    FoodCopy.TITLE -> LocalText("Food service", "خدمة المطعم والمقهى")
    FoodCopy.MY_CHECKS -> LocalText("My checks", "طلباتي")
    FoodCopy.MENU -> LocalText("Configured menu", "القائمة المُعدّة")
    FoodCopy.STATIONS -> LocalText("Configured stations", "المحطات المُعدّة")
    FoodCopy.REFRESH_FIRST -> LocalText("Refresh from first page", "تحديث من الصفحة الأولى")
    FoodCopy.NEXT -> LocalText("Read next page", "قراءة الصفحة التالية")
    FoodCopy.OWNED_ORDER -> LocalText("Known food order ID", "معرف طلب طعام معلوم")
    FoodCopy.INCOMING_ORDER -> LocalText("Known incoming food order ID", "معرف طلب طعام وارد معلوم")
    FoodCopy.STATION_LOOKUP -> LocalText("Known station ID", "معرف محطة معلوم")
    FoodCopy.OPEN_CHECK -> LocalText("Read owned check", "قراءة طلب أملكه")
    FoodCopy.REVIEW_HANDOFF -> LocalText("Review incoming responsibility", "مراجعة المسؤولية الواردة")
    FoodCopy.OPEN_STATION -> LocalText("Read station label", "قراءة وصف المحطة")
    FoodCopy.OPEN_TASK -> LocalText("Read this task and its actions", "قراءة هذه المهمة وإجراءاتها")
    FoodCopy.TASKS -> LocalText("Read this station's tasks", "قراءة مهام هذه المحطة")
    FoodCopy.ADD_SERVINGS -> LocalText("Add configured servings", "إضافة حصص مُعدّة")
    FoodCopy.SELECT_SERVING -> LocalText("Add one serving", "إضافة حصة واحدة")
    FoodCopy.MENU_HELP -> LocalText("Select a complete choice, then review its commercial line and edit the whole serving count. Duplicate creates another commercial line with the same count and new preparation identities. Each serving retains its configured quantity and modifiers. Menu pages do not reprice saved selections.",
        "اختر اختيارًا كاملًا ثم راجع سطره التجاري وعدّل عدد الحصص الصحيح. التكرار ينشئ سطرًا تجاريًا آخر بالعدد نفسه ومعرفات تحضير جديدة. تحتفظ كل حصة بكميتها وإضافاتها المُعدّة. صفحات القائمة لا تعيد تسعير الاختيارات المحفوظة.")
    FoodCopy.LOOKUP_HELP -> LocalText("Optional exact-ID lookup. Use Incoming offers to discover your offers, and choose a recipient from an owned check. A lookup never grants access or manages table occupancy.",
        "بحث اختياري بمعرف دقيق. استخدم العروض الواردة لاكتشاف عروضك، واختر مستلمًا من طلب تملكه. البحث لا يمنح صلاحية ولا يدير إشغال الطاولات.")
    FoodCopy.INVALID_ID -> LocalText("Enter the exact valid identifier; no lookup was sent.", "أدخل المعرف الصحيح بدقة؛ لم تُرسل قراءة.")
    FoodCopy.EMPTY_PAGE -> LocalText("No entries in this successfully read page. This does not certify that all responsibilities are resolved.",
        "لا توجد عناصر في هذه الصفحة المقروءة بنجاح. هذا لا يثبت حسم جميع المسؤوليات.")
    FoodCopy.CURRENT_UNPAID -> LocalText("Current unpaid amount", "المبلغ الحالي غير المدفوع")
    FoodCopy.CURRENT_UNPAID_UNKNOWN -> LocalText("Unavailable — not zero", "غير متاح — لا يعني صفرًا")
    FoodCopy.CURRENT_UNPAID_WARNING -> LocalText("Whole-check amount from this Master read, after ordinary unpaid pre-send cancellations. Informational only; it does not authorize sending, stock movement or payment.",
        "مبلغ الطلب كله من هذه القراءة للجهاز الرئيسي، بعد إلغاء الحصص غير المدفوعة قبل الإرسال. للمعلومات فقط؛ لا يسمح بالإرسال أو حركة المخزون أو الدفع.")
    FoodCopy.QUOTE -> LocalText("Original quoted total", "الإجمالي الأصلي المسعّر")
    FoodCopy.QUOTE_WARNING -> LocalText("Historical whole-order quote, not collected money, a current balance or a sum of this portion page.",
        "تسعير تاريخي للطلب كله، وليس نقدًا محصلًا أو رصيدًا حاليًا أو مجموع حصص هذه الصفحة.")
    FoodCopy.CHARGE_REQUIRED -> LocalText("The source flags a cancellation-related charge decision. This preparation view does not verify its financial resolution. Cancellation does not erase a charge or pay a refund.",
        "يشير المصدر إلى قرار رسوم مرتبط بالإلغاء. شاشة التحضير هذه لا تثبت حسمه المالي. الإلغاء لا يمحو الرسوم ولا ينفذ رد مبلغ.")
    FoodCopy.PAYMENT_HELD -> LocalText("Physical food payment/refund recording is unavailable here. Do not collect or refund money in this flow. Collection, original receipt, refund liability and claim views are separate. Paid pickup requires actual finalized stored coverage; cancellation is not a payout.",
        "تسجيل الدفع أو الرد الفعلي للطعام غير متاح هنا. لا تحصّل أو ترد مالًا عبر هذا المسار. شاشات التحصيل والإيصال الأصلي والتزام الرد والمطالبة منفصلة. استلام الطلب المدفوع يتطلب تغطية محفوظة مثبتة فعلية؛ الإلغاء ليس صرفًا.")
    FoodCopy.PREPARATION_WARNING -> LocalText("Preparation, handover and money are separate. A cancellation request is not kitchen confirmation. Started ingredients remain consumed when work is classified as waste.",
        "التحضير والتسليم والنقد حالات منفصلة. طلب الإلغاء ليس تأكيدًا من المطبخ. تظل المكونات التي بدأ استخدامها مستهلكة عند تصنيف العمل كهالك.")
    FoodCopy.STATION_WARNING -> LocalText("Durable delivery intent only — not proof of printing, viewing or physical preparation. A delivery failure does not authorize another send or another stock issue.",
        "نية إرسال مسجلة فقط — ليست إثباتًا للطباعة أو المشاهدة أو التحضير الفعلي. تعطل الإرسال لا يسمح بتكرار الإرسال أو صرف المخزون.")
    FoodCopy.HANDOFF_WARNING -> LocalText("Review the entire food responsibility and any settled collection. The old owner remains responsible until the named recipient explicitly accepts. Earlier payment actors, receipts, stock and cash custody do not change.",
        "راجع مسؤولية الطلب كاملة وأي تحصيل مستقر. تظل المسؤولية لدى المالك السابق حتى يقبل المستلم المقصود صراحة. لا تتغير هوية من استلم المدفوعات السابقة أو الإيصالات أو المخزون أو العهدة النقدية.")
    FoodCopy.TABLE_REFERENCE -> LocalText("Table/reference label — not managed occupancy", "وصف الطاولة أو مرجع الطلب — ليس إشغالًا مُدارًا")
    FoodCopy.SEAT_NOTE -> LocalText("Seat/service note", "ملاحظة المقعد أو الخدمة")
    FoodCopy.ITEM_NOTE -> LocalText("Serving note", "ملاحظة الحصة")
    FoodCopy.ORDER_ID -> LocalText("Food order ID", "معرف طلب الطعام")
    FoodCopy.PORTION_ID -> LocalText("Serving ID", "معرف الحصة")
    FoodCopy.TASK_ID -> LocalText("Task ID", "معرف المهمة")
    FoodCopy.STATION_ID -> LocalText("Station ID", "معرف المحطة")
    FoodCopy.RECIPE_ID -> LocalText("Recipe ID", "معرف الوصفة")
    FoodCopy.CHOICE_ID -> LocalText("Complete choice ID", "معرف الاختيار الكامل")
    FoodCopy.UNIT_ID -> LocalText("Exact unit ID (unit label not available)", "معرف الوحدة بدقة (وصف الوحدة غير متاح)")
    FoodCopy.OWNER_ID -> LocalText("Current responsibility owner ID", "معرف مالك المسؤولية الحالي")
    FoodCopy.RECIPIENT_ID -> LocalText("Intended recipient ID", "معرف المستلم المقصود")
    FoodCopy.HANDOFF_ID -> LocalText("Handoff offer ID", "معرف عرض تسليم المسؤولية")
    FoodCopy.ORDER_STORAGE -> LocalText("Order storage revision", "مراجعة تخزين الطلب")
    FoodCopy.PORTION_DOMAIN -> LocalText("Preparation domain revision", "مراجعة مجال التحضير")
    FoodCopy.CONFIGURATION_STORAGE -> LocalText("Configuration storage revision", "مراجعة تخزين الإعداد")
    FoodCopy.RECIPE_REVISION -> LocalText("Signed recipe revision", "مراجعة الوصفة الموقعة")
    FoodCopy.PORTION_COUNT -> LocalText("Whole-order serving count", "عدد حصص الطلب كله")
    FoodCopy.PREPARATION -> LocalText("All required stations", "جميع المحطات المطلوبة")
    FoodCopy.FULFILLMENT -> LocalText("Handover (not payment)", "التسليم (ليس الدفع)")
    FoodCopy.TASK_STATE -> LocalText("This station's task", "مهمة هذه المحطة")
    FoodCopy.DETAILS -> LocalText("Inspect exact source details", "فحص تفاصيل المصدر الدقيقة")
    FoodCopy.HIDE_DETAILS -> LocalText("Hide source details", "إخفاء تفاصيل المصدر")
    FoodCopy.EXPANDED -> LocalText("Expanded", "موسّع")
    FoodCopy.COLLAPSED -> LocalText("Collapsed", "مطوي")
    FoodCopy.ACTIVE -> LocalText("Active configuration", "إعداد نشط")
    FoodCopy.INACTIVE -> LocalText("Inactive configuration", "إعداد غير نشط")
    FoodCopy.DISPLAY -> LocalText("Display delivery", "إرسال إلى الشاشة")
    FoodCopy.PAPER -> LocalText("Paper delivery", "إرسال ورقي")
    FoodCopy.STARTED -> LocalText("Started work is recorded", "بدء العمل مسجل")
    FoodCopy.CANCEL_REQUESTED -> LocalText("Cancellation was requested", "طُلب الإلغاء")
    FoodCopy.RESULT_CODE -> LocalText("Master result code", "رمز نتيجة الجهاز الرئيسي")
    FoodCopy.ISSUE_ID -> LocalText("Original stock issue ID", "معرف صرف المخزون الأصلي")
    FoodCopy.TRAINING_UNAVAILABLE -> LocalText("Food service is available only through the actual local workflow gateway. This training/company entry supplies no substitute food orders or results.",
        "خدمة الطعام متاحة فقط عبر بوابة سير العمل المحلية الفعلية. لا يوفر مدخل التدريب أو الشركة طلبات طعام أو نتائج بديلة.")
    FoodCopy.NO_MODIFIERS -> LocalText("No configured modifiers", "لا توجد إضافات مُعدّة")
    FoodCopy.MODIFIERS -> LocalText("Exact configured modifiers", "الإضافات المُعدّة بدقة")
    FoodCopy.SELECTED_COUNT -> LocalText("Selected servings", "الحصص المحددة")
    FoodCopy.INBOX -> LocalText("Incoming offers", "العروض الواردة")
    FoodCopy.CHOOSE_RECIPIENT -> LocalText("Choose an eligible recipient", "اختيار مستلم مؤهل")
    FoodCopy.SELECT_RECIPIENT -> LocalText("Review an offer to this person", "مراجعة عرض لهذا المستلم")
    FoodCopy.RECIPIENT_HELP -> LocalText("These current local accounts can receive this check under their protected permissions. Select a person, then review and save the offer separately. Eligibility and the exact order revision are rechecked by the master; this list is not a grant.",
        "يمكن لهذه الحسابات المحلية الحالية استلام هذا الطلب وفق صلاحياتها المحمية. اختر شخصًا ثم راجع العرض واحفظه منفصلًا. يعيد الجهاز الرئيسي فحص الأهلية ومراجعة الطلب الدقيقة؛ هذه القائمة ليست منحًا للصلاحية.")
    FoodCopy.NO_RECIPIENTS -> LocalText("No eligible recipients in this current page. No responsibility was moved.",
        "لا يوجد مستلمون مؤهلون في هذه الصفحة الحالية. لم تُنقل أي مسؤولية.")
    FoodCopy.INBOX_HELP -> LocalText("Only offers addressed to you and available under your receiving permissions appear here. Open an offer to review every serving, settled collection and remaining obligation before acceptance. An unresolved payment blocks ordinary acceptance.",
        "تظهر هنا العروض الموجهة إليك والمتاحة وفق صلاحيات الاستلام لديك فقط. افتح العرض لمراجعة كل حصة والتحصيل المستقر والالتزامات المتبقية قبل القبول. يمنع الدفع غير المحسوم القبول العادي.")
    FoodCopy.INBOX_PROGRESS -> LocalText("Discovery is not finished. Continue to read more current offers and retained legacy offer records. Each page is a separate current observation; no earlier page is a cached grant.",
        "لم ينتهِ اكتشاف العروض. تابع لقراءة المزيد من العروض الحالية وسجلات العروض السابقة المحفوظة. كل صفحة قراءة حالية مستقلة؛ الصفحات السابقة ليست صلاحية مخزنة.")
    FoodCopy.INBOX_SCANNING_EMPTY -> LocalText("No matching offers in the portion scanned so far. Continue discovery; this is not an empty-inbox conclusion.",
        "لا توجد عروض مطابقة في الجزء المفحوص حتى الآن. تابع الاكتشاف؛ هذا ليس استنتاجًا بأن صندوق الوارد فارغ.")
    FoodCopy.INBOX_PAGE_EMPTY -> LocalText("No additional eligible offers in this page. Refresh from the first page to observe new or changed offers.",
        "لا توجد عروض مؤهلة إضافية في هذه الصفحة. حدّث من الصفحة الأولى لقراءة العروض الجديدة أو المتغيرة.")
    FoodCopy.INBOX_CURSOR_EXPIRED -> LocalText("The saved discovery position no longer matches the current inbox. Refresh from the first page. No empty result or acceptance was substituted.",
        "موضع الاكتشاف المحفوظ لم يعد يطابق صندوق الوارد الحالي. حدّث من الصفحة الأولى. لم تُستبدل القراءة بنتيجة فارغة أو قبول.")
    FoodCopy.CONTINUE_INCOMING -> LocalText("Continue offer discovery", "متابعة اكتشاف العروض")
    FoodCopy.OFFERED_AT -> LocalText("Offer recorded at", "وقت تسجيل العرض")
    FoodCopy.COLLECTION_STATUS -> LocalText("Collection state", "حالة التحصيل")
    FoodCopy.COLLECTED -> LocalText("Already collected", "المبلغ المحصّل سابقًا")
    FoodCopy.REMAINING -> LocalText("Remaining to collect", "المتبقي للتحصيل")
    FoodCopy.REFUND_DUE -> LocalText("Refund still due", "المبلغ المستحق رده")
    FoodCopy.CLAIM_COUNT -> LocalText("Recorded quality claims", "مطالبات الجودة المسجلة")
    FoodCopy.COLLECTION_ID -> LocalText("Collection ID", "معرف التحصيل")
    FoodCopy.COLLECTION_REVISION -> LocalText("Reviewed collection revision", "مراجعة التحصيل التي تمت مراجعتها")
    FoodCopy.MY_CLAIMS -> LocalText("My quality/fault claims", "مطالباتي بالجودة أو الخطأ")
    FoodCopy.FINANCIAL_LOOKUP -> LocalText("Exact financial record ID — choose its type below", "معرف السجل المالي الدقيق — اختر نوعه أدناه")
    FoodCopy.OPEN_COLLECTION -> LocalText("Read food collection", "قراءة تحصيل الطعام")
    FoodCopy.OPEN_RECEIPT -> LocalText("Read original food paid receipt", "قراءة إيصال الطعام المدفوع الأصلي")
    FoodCopy.OPEN_REFUND -> LocalText("Read food refund obligation", "قراءة التزام رد مبلغ الطعام")
    FoodCopy.OPEN_CLAIM -> LocalText("Read quality/fault claim", "قراءة مطالبة الجودة أو الخطأ")
}

/** No error code or client capability hint is interpreted as a kitchen-only diagnosis. */
internal fun foodKitchenNoticeText(body: LocalFoodUiBody.Check): LocalText = when (body.kitchen?.status) {
    LocalFoodUiKitchenStatus.NO_UNSENT_WORK -> LocalText(
        "This current check has no unsent servings. That does not prove kitchen availability or settle preparation, payment or refund obligations. Existing issued work and original-operation recovery keep their separate rules.",
        "لا توجد حصص غير مرسلة في هذا الطلب الحالي. هذا لا يثبت إتاحة المطبخ ولا يحسم التزامات التحضير أو الدفع أو الرد. يبقى العمل المصروف واستعادة العملية الأصلية خاضعين لقواعدهما المنفصلة.")
    LocalFoodUiKitchenStatus.UNAVAILABLE -> if (body.check.collectionId == null && body.financial == null &&
        body.portions.all { it.preparation == LocalFoodPreparation.NOT_SENT } &&
        body.kitchen.unsentPortionCount == body.check.portionCount) LocalText(
        "Unpaid draft — kitchen sending is unavailable at the Master. Authorized items can still be saved and added. Saving does not reserve or issue ingredients, create kitchen work, or collect money. Sending and starting collection are blocked until kitchen support is available.",
        "مسودة غير مدفوعة — الإرسال إلى المطبخ غير متاح لدى الجهاز الرئيسي. يمكن حفظ الأصناف وإضافتها وفق الصلاحيات. الحفظ لا يحجز أو يصرف مكونات ولا ينشئ عملًا للمطبخ ولا يحصّل مالًا. الإرسال وبدء التحصيل محظوران حتى تتاح خدمة المطبخ.")
    else LocalText(
        "Kitchen sending is unavailable at the Master. Unsent work cannot be sent or used to start collection. Previously issued work, recorded payments, refunds and original-operation recovery remain subject to their own current authorization and rules; this notice does not undo them.",
        "الإرسال إلى المطبخ غير متاح لدى الجهاز الرئيسي. لا يمكن إرسال العمل غير المرسل أو بدء تحصيله. يبقى العمل المصروف والمدفوعات والردود المسجلة واستعادة العملية الأصلية خاضعًا لصلاحياته وقواعده الحالية؛ هذا التنبيه لا يتراجع عنها.")
    LocalFoodUiKitchenStatus.UNKNOWN, null -> LocalText(
        "Current kitchen sending availability is unknown. This read is not proof that sending or payment is supported. The Master checks every operation; saved items and original payment outcomes remain separate.",
        "إتاحة الإرسال الحالية إلى المطبخ غير معروفة. هذه القراءة ليست إثباتًا لدعم الإرسال أو الدفع. يتحقق الجهاز الرئيسي من كل عملية؛ الأصناف المحفوظة ونتائج الدفع الأصلية حالات منفصلة.")
}

/** Financial-only presentation never suggests Draft/Add or a native cash capability. */
internal fun foodCashFinancialKitchenNoticeText(body: LocalFoodUiBody.Check): LocalText = when (body.kitchen?.status) {
    LocalFoodUiKitchenStatus.NO_UNSENT_WORK -> LocalText(
        "This check has no unsent servings. Begin still requires its exact original quote, ownership, cash session and current Master authority. Previously issued ingredients must not be reserved or issued again; payment and finalization remain separate.",
        "لا توجد حصص غير مرسلة في هذا الطلب. ما زال بدء التحصيل يتطلب عرض سعره الأصلي الدقيق وملكيته وجلسته النقدية وصلاحية حالية من الجهاز الرئيسي. لا تُحجز المكونات المصروفة سابقًا ولا تُصرف مرة أخرى؛ ويبقى الدفع والإتمام منفصلين.")
    LocalFoodUiKitchenStatus.UNAVAILABLE -> LocalText(
        "Current kitchen support is unavailable for unsent work. Such work cannot start collection here. Already-issued work and genuine retained payment originals keep their independent current authorization and accounting rules; this notice does not undo them.",
        "دعم المطبخ الحالي غير متاح للعمل غير المرسل. لا يمكن بدء تحصيل هذا العمل هنا. يحتفظ العمل المصروف ووقائع الدفع الأصلية الفعلية المحفوظة بصلاحياتهما الحالية وقواعدهما المحاسبية المستقلة؛ ولا يتراجع عنهما هذا التنبيه.")
    LocalFoodUiKitchenStatus.UNKNOWN, null -> LocalText(
        "This read does not prove kitchen or cash-capture capability. The Master checks actual kitchen requirements again for Begin. Applying a retained payment original and finalizing exact coverage do not create another physical instruction or issue ingredients again.",
        "لا تثبت هذه القراءة قدرة المطبخ أو التحصيل النقدي الفعلي. يعيد الجهاز الرئيسي فحص متطلبات المطبخ الفعلية عند بدء التحصيل. لا ينشئ تطبيق واقعة دفع أصلية محفوظة أو إتمام التغطية الدقيقة تعليمات فعلية أخرى ولا يصرف المكونات مجددًا.")
}

internal fun foodUnavailableText(reason: LocalFoodUnavailable): LocalText = when (reason) {
    LocalFoodUnavailable.TARGET_UNAVAILABLE -> LocalText("This exact food target or source revision is unavailable to this actor. Refresh and review; no empty or partial success was substituted.",
        "هدف الطعام أو مراجعة المصدر المحددة غير متاحة لهذا المستخدم. حدّث وراجع؛ لم تُستبدل بنجاح فارغ أو جزئي.")
    LocalFoodUnavailable.CAPACITY_REQUIRED -> LocalText("The actual food read cannot fit the current bounded capacity. Its result is unavailable, not an empty ledger or permission to repeat an operation.",
        "قراءة الطعام الفعلية تتجاوز السعة المحدودة الحالية. النتيجة غير متاحة، وليست سجلًا فارغًا أو إذنًا بتكرار العملية.")
    LocalFoodUnavailable.NOT_PRECOLLECTION -> LocalText("This check is not transferable through the precollection handoff flow. Financial responsibility and cash custody were not transferred.",
        "لا يمكن نقل هذا الطلب عبر مسار التسليم قبل التحصيل. لم تُنقل المسؤولية المالية أو العهدة النقدية.")
    LocalFoodUnavailable.PAYMENT_UNRESOLVED -> LocalText("A payment or refund still has an unresolved physical outcome. Resolve that original operation before ordinary handoff; no responsibility or cash was transferred.",
        "ما زالت النتيجة الفعلية لدفع أو رد مبلغ غير محسومة. عالج العملية الأصلية قبل التسليم العادي؛ لم تُنقل مسؤولية أو أموال.")
}

internal fun foodCollectionStatusText(status: String): LocalText = when (status) {
    "LOCKED_UNPAID" -> LocalText("Locked, not yet paid", "مثبّت، لم يُدفع بعد")
    "PART_PAID" -> LocalText("Partly paid", "مدفوع جزئيًا")
    "OUTCOME_UNKNOWN" -> LocalText("Payment outcome unresolved", "نتيجة الدفع غير محسومة")
    "COVERED" -> LocalText("Fully collected, not finalized", "محصّل بالكامل، لم يُقفل البيع")
    "FINALIZED_PAID" -> LocalText("Paid sale retained", "البيع المدفوع محفوظ")
    "CANCELLED_REFUND_OBLIGATION" -> LocalText("Cancelled, refund obligation retained", "ملغى، التزام رد المبلغ محفوظ")
    else -> error("food.collection.status")
}
internal fun foodPreparationText(value: LocalFoodPreparation): LocalText = when (value) {
    LocalFoodPreparation.NOT_SENT -> LocalText("Not sent to kitchen", "لم يُرسل إلى المطبخ")
    LocalFoodPreparation.QUEUED -> LocalText("Queued — not started", "في الانتظار — لم يبدأ")
    LocalFoodPreparation.PREPARING -> LocalText("Preparation in progress", "التحضير جارٍ")
    LocalFoodPreparation.READY -> LocalText("Ready at all required stations", "جاهز في جميع المحطات المطلوبة")
    LocalFoodPreparation.CANCEL_PENDING -> LocalText("Cancellation awaiting kitchen resolution", "الإلغاء في انتظار معالجة المطبخ")
    LocalFoodPreparation.CANCELLED_BEFORE_START -> LocalText("Cancelled before start", "أُلغي قبل البدء")
    LocalFoodPreparation.WASTE_AFTER_START -> LocalText("Waste recorded — ingredients remain consumed", "سُجل الهالك — تظل المكونات مستهلكة")
}
internal fun foodFulfillmentText(value: LocalFoodFulfillment): LocalText = when (value) {
    LocalFoodFulfillment.NOT_HANDED_OVER -> LocalText("Not handed over", "لم يُسلّم")
    LocalFoodFulfillment.SERVED -> LocalText("Served — not proof of payment", "تم التقديم — ليس إثباتًا للدفع")
    LocalFoodFulfillment.PICKED_UP -> LocalText("Picked up — payment details not shown here", "تم الاستلام — تفاصيل الدفع غير معروضة هنا")
}
internal fun foodTaskText(value: LocalFoodTaskState): LocalText = when (value) {
    LocalFoodTaskState.QUEUED -> LocalText("Queued here", "في انتظار هذه المحطة")
    LocalFoodTaskState.STARTED -> LocalText("Started here", "بدأ هنا")
    LocalFoodTaskState.READY -> LocalText("Ready at this station", "جاهز في هذه المحطة")
    LocalFoodTaskState.CANCEL_CONFIRMED -> LocalText("This station confirmed no start", "أكدت هذه المحطة عدم البدء")
    LocalFoodTaskState.CANCELLED -> LocalText("Station task cancelled", "أُلغيت مهمة المحطة")
}
internal fun foodChannelText(channel: String): LocalText = when (channel) {
    "DINE_IN" -> LocalText("Dine-in", "داخل المكان")
    "COUNTER" -> LocalText("Counter", "الكاونتر")
    "TAKEAWAY" -> LocalText("Takeaway", "سفري")
    "DELIVERY" -> LocalText("Delivery", "توصيل")
    else -> LocalText(channel, channel)
}

internal fun foodDeliveryDraftNoticeText(): LocalText = LocalText(
    "Delivery draft only. Item quotations do not resolve delivery fees, their tax or the final amount due. Sending, collection and dispatch remain blocked until real kitchen and delivery support is available. Existing recorded payments and original-operation recovery keep their separate rules.",
    "مسودة توصيل فقط. عروض أسعار الأصناف لا تحسم رسوم التوصيل أو ضريبتها أو المبلغ النهائي المستحق. يبقى الإرسال والتحصيل والتوصيل محظورًا حتى تتاح خدمات المطبخ والتوصيل الفعلية. تبقى المدفوعات المسجلة واستعادة العملية الأصلية خاضعة لقواعدها المنفصلة.")
internal fun AppLanguage.foodQuantity(quantity: Quantity): String = digits(foodFixedDecimal(quantity.scaled, quantity.scale))
internal fun AppLanguage.foodMoney(money: Money): String = "\u2066${digits(foodFixedDecimal(money.minor, money.currency.fractionDigits))}\u2069 ${money.currency.name}"
