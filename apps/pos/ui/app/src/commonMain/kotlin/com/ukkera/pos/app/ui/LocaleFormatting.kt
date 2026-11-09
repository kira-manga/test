package com.ukkera.pos.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.LayoutDirection
import com.ukkera.pos.app.training.BilingualText
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.types.Money

enum class AppLanguage(val code: String, val direction: LayoutDirection) {
    ENGLISH("en", LayoutDirection.Ltr), ARABIC("ar", LayoutDirection.Rtl);

    fun toggle(): AppLanguage = if (this == ENGLISH) ARABIC else ENGLISH
    fun text(value: BilingualText): String = if (this == ARABIC) value.ar else value.en
    fun text(value: LocalText): String = if (this == ARABIC) value.arabic else value.english
    fun digits(value: String): String = if (this == ENGLISH) value else value.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")

    /** Exact, non-floating-point formatting, including Long.MIN_VALUE. LTR isolation for money. */
    fun money(value: Money): String {
        val signed = value.minor.toString()
        val magnitude = signed.removePrefix("-").padStart(3, '0')
        val amount = (if (value.minor < 0) "-" else "") + magnitude.dropLast(2) +
            (if (this == ARABIC) "٫" else ".") + magnitude.takeLast(2)
        return "\u2066${digits(amount)}\u2069 ${if (this == ARABIC) "ج.م" else "EGP"}"
    }
}

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ENGLISH }

internal enum class ArabicCardinal { ZERO, ONE, TWO, FEW, MANY, OTHER }

/** Arabic integer cardinal rules; tied to app language, never the device's unrelated locale. */
internal fun arabicCardinal(count: Int): ArabicCardinal {
    require(count >= 0)
    return when {
        count == 0 -> ArabicCardinal.ZERO
        count == 1 -> ArabicCardinal.ONE
        count == 2 -> ArabicCardinal.TWO
        count % 100 in 3..10 -> ArabicCardinal.FEW
        count % 100 in 11..99 -> ArabicCardinal.MANY
        else -> ArabicCardinal.OTHER
    }
}

/** A scalar input parser, not a float conversion. Counts are bounded for the training drawer only. */
fun parseTrainingCash(input: String): Long? {
    val normalized = com.ukkera.pos.app.training.TrainingWorkspace.normalizeSearch(input).replace('٫', '.')
    if (!Regex("(?:0|[1-9][0-9]{0,6})(?:\\.[0-9]{1,2})?").matches(normalized)) return null
    val parts = normalized.split('.')
    return parts[0].toLong() * 100 + (parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0)
}
