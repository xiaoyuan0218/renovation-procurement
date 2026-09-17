package com.xiaoyuan.renovation.mobile.util

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** 金额、数量、日期的统一格式化，与网页版 api.js 的 money()/qty() 保持一致。 */
object Fmt {

    /** 千分位 + 两位小数，带 ¥。 */
    fun money(value: Double?): String {
        if (value == null) return "—"
        return "¥" + plainMoney(value)
    }

    fun plainMoney(value: Double): String = String.format(Locale.CHINA, "%,.2f", value)

    /** 整数不带小数，非整数保留两位。 */
    fun qty(value: Double?): String {
        if (value == null) return "—"
        return if (abs(value - value.roundToLong()) < 1e-6) {
            value.roundToLong().toString()
        } else {
            String.format(Locale.CHINA, "%.2f", value)
        }
    }

    fun qtyUnit(value: Double?, unit: String): String = "${qty(value)}${unit.ifBlank { "个" }}"

    fun percent(value: Float): String = "${(value * 100).roundToLong()}%"

    fun percentOf(part: Double, whole: Double): String {
        if (whole <= 0) return "0%"
        return "${((part / whole) * 100).roundToLong()}%"
    }

    /** 大额金额压缩成"万"，用在空间紧张的图表标签上。 */
    fun moneyCompact(value: Double?): String {
        if (value == null) return "—"
        val abs = abs(value)
        return when {
            abs >= 10000 -> String.format(Locale.CHINA, "¥%.1f万", value / 10000)
            abs >= 1000 -> "¥" + String.format(Locale.CHINA, "%,.0f", value)
            else -> "¥" + String.format(Locale.CHINA, "%.0f", value)
        }
    }

    fun parseNumber(text: String): Double? = text.trim().toDoubleOrNull()

    fun parseNumberOrZero(text: String): Double = parseNumber(text) ?: 0.0

    fun today(): String = LocalDate.now().toString()

    fun prettyDate(iso: String): String {
        if (iso.isBlank()) return "未填日期"
        return iso
    }
}
