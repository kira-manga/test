package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.touchTarget
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.NoMonetaryCeiling
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.RequiredLimits
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money

/**
 * Optional editing shortcuts, not roles, stored policy templates or authorization.
 * Nothing is selected automatically. Every added grant enters the same complete
 * company-head review and real original installation as an individually entered grant.
 */
@Composable
internal fun LocalSetupPermissionSets(
    scope: BranchScope,
    actor: DomainId,
    grants: List<PermissionGrant>,
    enabled: Boolean,
    add: (List<PermissionGrant>) -> Unit,
) {
    var selected by remember(scope, actor) { mutableStateOf<SetupPermissionSet?>(null) }
    var amount by remember(scope, actor) { mutableStateOf("") }
    var noCeiling by remember(scope, actor) { mutableStateOf(false) }

    SectionHeading(t("Optional starter permission lists", "قوائم صلاحيات أولية اختيارية"))
    Notice(t("Choose only the lists this employee needs. They add named, branch-only grants; never company-wide access, other owners' orders, supervisor payment/refund resolutions or catalog signing. Existing grants are not replaced or widened. Review every grant before account creation.",
        "اختر القوائم التي يحتاجها الموظف فقط. تضيف صلاحيات مسماة لهذا الفرع فقط؛ لا وصولًا عامًا للشركة أو طلبات الآخرين أو تسويات مشرف للمدفوعات والاستردادات أو توقيعًا للكتالوج. لا تُستبدل الصلاحيات الحالية ولا تُوسّع. راجع كل صلاحية قبل إنشاء الحساب."))
    setupPermissionSets.forEach { choice ->
        OutlinedButton(onClick = { selected = choice; amount = ""; noCeiling = false }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().touchTarget()) {
            Text(t(choice.english, choice.arabic))
        }
    }
    val choice = selected ?: return
    val missing = choice.permissions.filter { permission -> grants.none { it.permission == permission } }
    val monetary = missing.any { it.action.requiredLimits != RequiredLimits.NONE }
    val maximum = amount.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()
    val limitReady = !monetary || (noCeiling && amount.isEmpty()) || (!noCeiling && maximum != null)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t(choice.english, choice.arabic))
        // Complete action/verb preview. The normal grant review below also shows the
        // exact subject, branch, ownership and monetary limit, not merely a role name.
        choice.permissions.forEach { permission ->
            val existing = grants.any { it.permission == permission }
            Text("${permission.action.name} · ${permission.verb.name}" + if (existing)
                t(" — already listed; unchanged", " — موجودة بالفعل؛ دون تغيير") else "")
        }
        if (monetary) {
            SetupInput(amount, { amount = it },
                t("Ceiling for EACH added monetary action, in EGP piastres", "حد كل إجراء نقدي مضاف بوحدة القرش المصري"),
                enabled && !noCeiling, 19)
            Consent(t("Explicitly no monetary ceiling for these added actions (EGP only)",
                "دون حد نقدي صراحةً لهذه الإجراءات المضافة (بالجنيه المصري فقط)"), noCeiling, enabled) {
                noCeiling = it
                if (it) amount = ""
            }
            Notice(t("No amount is assumed. Leave this blank to add nothing; zero is a zero ceiling, not unlimited. Different per-action limits remain available in the individual grant editor.",
                "لا يُفترض أي مبلغ. اتركه فارغًا لعدم الإضافة؛ الصفر حد صفري وليس غير محدود. تظل الحدود المختلفة لكل إجراء متاحة في محرر الصلاحية الفردية."))
        }
        OutlinedButton(onClick = {
            if (!enabled || selected !== choice || !limitReady || missing.isEmpty() || grants.size + missing.size > 1024) return@OutlinedButton
            val currentMaximum = amount.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()
            if (monetary && (if (noCeiling) amount.isNotEmpty() else currentMaximum == null)) return@OutlinedButton
            val monetaryLimits = when {
                !monetary -> PermissionLimits()
                noCeiling -> PermissionLimits(noMonetaryCeiling = NoMonetaryCeiling(Currency.EGP))
                else -> PermissionLimits(maximumAmount = Money(Currency.EGP, checkNotNull(currentMaximum)))
            }
            val additions = missing.map { permission ->
                // Starter lists never manufacture a percentage ceiling or an approval.
                check(permission.action.requiredLimits != RequiredLimits.AMOUNT_AND_PERCENTAGE)
                PermissionGrant(permission, scope, GrantEffect.ALLOW,
                    if (permission.action.requiredLimits == RequiredLimits.NONE) PermissionLimits() else monetaryLimits,
                    OwnerAccess.OWN_ONLY, namedSubjectId = actor)
            }
            add(additions)
            selected = null; amount = ""; noCeiling = false
        }, enabled = enabled && limitReady && missing.isNotEmpty() && grants.size + missing.size <= 1024,
            modifier = Modifier.fillMaxWidth().touchTarget()) {
            Text(t("Add ${missing.size} grants to the review", "إضافة ${missing.size} صلاحيات إلى المراجعة"))
        }
        OutlinedButton(onClick = { selected = null; amount = ""; noCeiling = false }, enabled = enabled,
            modifier = Modifier.touchTarget()) { Text(t("Cancel this list", "إلغاء هذه القائمة")) }
    }
}

private data class SetupPermissionSet(val english: String, val arabic: String, val permissions: List<Permission>)

private val setupPermissionSets = listOf(
    SetupPermissionSet("Cashier: own sales and drawer", "الكاشير: مبيعاته ودرجه فقط", listOf(
        SecurityAction.CATALOG_READ, SecurityAction.ORDER_CREATE, SecurityAction.ORDER_READ, SecurityAction.ORDER_EDIT,
        SecurityAction.CASH_READ, SecurityAction.CASH_OPEN, SecurityAction.CASH_MOVE, SecurityAction.CASH_TRANSFER,
        SecurityAction.CASH_RECEIVE, SecurityAction.CASH_CLOSE, SecurityAction.COLLECTION_BEGIN,
        SecurityAction.COLLECTION_ATTEST, SecurityAction.COLLECTION_FINALIZE, SecurityAction.COLLECTION_FINALIZE_ZERO,
        SecurityAction.OPERATION_RESULT_RECOVERY,
    ).map { Permission(it, PermissionVerb.PERFORM) }),
    SetupPermissionSet("Local setup: catalog activation, opening-stock approval and cost access, locations, drawers and tenders",
        "الإعداد المحلي: تفعيل الكتالوج واعتماد المخزون الافتتاحي وقراءة التكلفة والمواقع والأدراج ووسائل الدفع", listOf(
            Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM),
            Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM),
            // The real opening-basis detail includes value. These named read grants are
            // previewed explicitly, never inferred from approval or silently added at login.
            Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
            Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM),
            Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
            Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER),
            Permission(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER),
            // Cash configuration uses the existing sales command contract, whose
            // permission verb is PERFORM; catalog configuration uses ADMINISTER.
            Permission(SecurityAction.CASH_CONFIGURE, PermissionVerb.PERFORM),
            Permission(SecurityAction.TENDER_CONFIGURE, PermissionVerb.ADMINISTER),
            Permission(SecurityAction.OPENING_INVENTORY_APPROVE, PermissionVerb.ADMINISTER),
        )),
    SetupPermissionSet("Local administration: employees and device admission",
        "الإدارة المحلية: الموظفون وقبول الأجهزة", listOf(
            Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER),
            Permission(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER),
            // Explicit local-master STOP/RESUME authority is separate from account/device
            // administration and is never added automatically.
            Permission(SecurityAction.MASTER_LIFECYCLE, PermissionVerb.ADMINISTER),
        )),
)
