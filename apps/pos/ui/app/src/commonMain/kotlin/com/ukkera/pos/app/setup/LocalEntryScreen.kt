package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.touchTarget

/** Connection presentation only, never a native-owner, employee or master admission. */
public enum class LocalSetupConnectionPhase { DETACHED, CONNECTING, ATTACHED, UNAVAILABLE }

/** Both real entry journeys remain available; selecting a route creates no company/account. */
@Composable
public fun LocalEntryScreen(language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit,
    onSetup: () -> Unit, onEmployee: () -> Unit) {
    EntryLayout(language, onLanguageChange) {
        SectionHeading(entryText(language, "Ukkera POS · Local", "عُكّرة · نقطة البيع المحلية"))
        Notice(entryText(language,
            "Choose the work for this device. Branch installation and employee sign-in use separate authority.",
            "اختر العمل على هذا الجهاز. يستخدم تثبيت الفرع وتسجيل دخول الموظف صلاحيات منفصلة."))
        Button(onClick = onSetup, modifier = Modifier.fillMaxWidth().touchTarget()) {
            Text(entryText(language, "Set up or inspect the original local branch", "إعداد الفرع المحلي أو فحص حالته الأصلية"))
        }
        Notice(entryText(language,
            "Setup opens the real local authority coordinator. No keys, company, branch or employee are created until you explicitly request the corresponding step.",
            "يفتح الإعداد منسق السلطة المحلية الفعلي. لن تُنشأ مفاتيح أو شركة أو فرع أو موظف إلا بعد طلبك الصريح للخطوة المقابلة."))
        OutlinedButton(onClick = onEmployee, modifier = Modifier.fillMaxWidth().touchTarget()) {
            Text(entryText(language, "Employee sign-in / existing branch", "دخول الموظف / فرع قائم"))
        }
        Notice(entryText(language,
            "An enrolled device and a connected local branch service are required for employee work. This build does not substitute a demonstration for an unavailable service.",
            "يتطلب عمل الموظف جهازًا مسجلًا وخدمة فرع محلية متصلة. لا يستبدل هذا الإصدار الخدمة غير المتاحة بعرض تجريبي."))
    }
}

/** An unavailable binding cannot become an empty/new installation or a restart-success claim. */
@Composable
public fun LocalSetupConnectionScreen(phase: LocalSetupConnectionPhase, language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    EntryLayout(language, onLanguageChange) {
        SectionHeading(entryText(language, "Local setup connection", "الاتصال بالإعداد المحلي"))
        if (phase == LocalSetupConnectionPhase.CONNECTING) {
            CircularProgressIndicator()
            Text(entryText(language, "Connecting to this device's setup service…", "جارٍ الاتصال بخدمة الإعداد على هذا الجهاز…"),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else {
            Notice(entryText(language,
                "The original setup controller is not available. No new owner, files or credentials have been substituted. A retiring or uncertain owner must finish its actual close before replacement is permitted.",
                "منسق الإعداد الأصلي غير متاح. لم يُستبدل بمالك أو ملفات أو بيانات اعتماد جديدة. يجب أن يُتم المالك الجاري إغلاقه أو غير المحسوم إغلاقه الفعلي قبل السماح باستبداله."), caution = true)
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().touchTarget()) {
                Text(entryText(language, "Reconnect to setup", "إعادة الاتصال بالإعداد"))
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().touchTarget()) {
            Text(entryText(language, "Back to device choices", "العودة إلى خيارات الجهاز"))
        }
    }
}

@Composable
private fun EntryLayout(language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit,
    content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppLanguage provides language, LocalLayoutDirection provides language.direction) {
        PosTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                OutlinedButton(onClick = { onLanguageChange(language.toggle()) }, modifier = Modifier.touchTarget()) {
                    Text(if (language == AppLanguage.ENGLISH) "العربية" else "English")
                }
                content()
            }
        }
    }
}

private fun entryText(language: AppLanguage, english: String, arabic: String): String =
    if (language == AppLanguage.ARABIC) arabic else english
