package com.ukkera.pos.app.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.LocalAppLanguage
import com.ukkera.pos.app.ui.Notice
import com.ukkera.pos.app.ui.PosTheme
import com.ukkera.pos.app.ui.SectionHeading
import com.ukkera.pos.app.ui.touchTarget
import com.ukkera.pos.security.LocalTrainingCompositionSlot

/**
 * Explicit original setup-mode selection. LIVE continues to the existing production setup
 * service. TRAINING creates or reopens one separately rooted, separately keyed, separately
 * identified synthetic training composition. This selection never changes an existing
 * composition's mode and never promotes training into live.
 */
@Composable
public fun LocalSetupModeSelectionScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onLive: () -> Unit,
    onTraining: (LocalTrainingCompositionSlot) -> Unit,
    onBack: () -> Unit,
) {
    CompositionLocalProvider(LocalAppLanguage provides language, LocalLayoutDirection provides language.direction) {
        PosTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(onClick = { onLanguageChange(language.toggle()) }, modifier = Modifier.touchTarget()) {
                    Text(if (language == AppLanguage.ENGLISH) "العربية" else "English")
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.touchTarget()) {
                    Text(text(language, "Back to device choices", "العودة إلى خيارات الجهاز"))
                }
                SectionHeading(
                    text(language, "Choose the setup composition", "اختر تكوين الإعداد"),
                    text(language,
                        "This choice creates or reopens one original composition. It is not a flag on an existing root.",
                        "هذا الاختيار ينشئ أو يعيد فتح تكوين أصلي واحد. وهو ليس علامة على جذر قائم."),
                )
                Button(onClick = onLive, modifier = Modifier.fillMaxWidth().touchTarget()) {
                    Text(text(language, "LIVE production setup", "إعداد الإنتاج المباشر"))
                }
                Notice(text(language,
                    "LIVE uses the existing production root, keys, SDK identity and setup service without migration or replacement.",
                    "يستخدم المباشر الجذر والمفاتيح وهوية SDK وخدمة الإعداد الإنتاجية القائمة دون ترحيل أو استبدال."))
                LocalTrainingCompositionSlot.entries.forEachIndexed { index, slot ->
                    val label = "SYNTHETIC TRAINING ${index + 1} - NOT LIVE"
                    OutlinedButton(onClick = { onTraining(slot) }, modifier = Modifier.fillMaxWidth().touchTarget()) {
                        Text(label)
                    }
                }
                Notice(text(language,
                    "Training has separate native storage, Android Keystore custody, official SDK identity, setup receipts, Room history, journals, numbering and outbox routes. It is synthetic, cannot publish into live and cannot be promoted in place.",
                    "للتدريب تخزين أصلي منفصل وعهادة مفاتيح Android Keystore وهوية SDK رسمية وإيصالات إعداد وسجل Room ويوميات وترقيم ومسارات صادرة منفصلة. وهو اصطناعي ولا يمكن نشره في المباشر أو ترقيته داخل المكان."), caution = true)
                Notice(text(language,
                    "Training compositions are not production evidence, fiscal records or a substitute for the genuine live fixture.",
                    "تكوينات التدريب ليست دليلًا إنتاجيًا أو سجلات ضريبية أو بديلًا عن تكوين مباشر حقيقي."))
            }
        }
    }
}

private fun text(language: AppLanguage, english: String, arabic: String): String =
    if (language == AppLanguage.ARABIC) arabic else english
