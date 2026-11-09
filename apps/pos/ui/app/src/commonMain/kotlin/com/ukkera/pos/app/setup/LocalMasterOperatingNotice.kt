package com.ukkera.pos.app.setup

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.LocalAppLanguage
import com.ukkera.pos.app.ui.PosTheme
import com.ukkera.pos.app.ui.touchTarget

/** Shown for actual master publication, never client-only use or a saved startup hint. */
@Composable
public fun LocalMasterOperatingNotice(hosting: Boolean, language: AppLanguage) {
    if (!hosting) return
    var acknowledged by remember { mutableStateOf(false) }
    if (acknowledged) return
    fun text(english: String, arabic: String): String = if (language == AppLanguage.ARABIC) arabic else english
    CompositionLocalProvider(LocalAppLanguage provides language, LocalLayoutDirection provides language.direction) {
        PosTheme {
            AlertDialog(
                onDismissRequest = { acknowledged = true },
                title = { Text(text("This device is the local master", "هذا الجهاز هو الجهاز الرئيسي المحلي")) },
                text = { Text(text(
                    "Keep this device powered and the app open while the branch is operating. Employee logout does not stop the master. The operating system can still suspend or stop the service; this notice is not a guarantee of background availability.",
                    "أبقِ الجهاز متصلًا بالطاقة والتطبيق مفتوحًا أثناء عمل الفرع. خروج الموظف لا يوقف الجهاز الرئيسي. قد يعلّق نظام التشغيل الخدمة أو يوقفها؛ هذا التنبيه لا يضمن استمرارها في الخلفية."
                )) },
                confirmButton = {
                    TextButton(onClick = { acknowledged = true }, modifier = Modifier.touchTarget()) {
                        Text(text("Understood", "فهمت"))
                    }
                },
            )
        }
    }
}
