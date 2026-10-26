package com.xiaomiscalesync.ui

import com.xiaomiscalesync.data.WeightUnit
import java.util.Locale

/** Formats the archive's 1/100 kg integer for the selected display unit. */
fun WeightUnit.formatCentiKg(weightCentiKg: Int): String =
    String.format(Locale.US, "%.2f", displayValue(weightCentiKg))

/** Converts the archive value to the selected unit without changing stored data. */
fun WeightUnit.displayValue(weightCentiKg: Int): Double {
    val kilograms = weightCentiKg / 100.0
    return when (this) {
        WeightUnit.KG -> kilograms
        WeightUnit.LB -> kilograms * 2.2046226218
    }
}

/** Formats a signed centi-kilogram delta in the selected display unit. */
fun WeightUnit.formatDeltaCentiKg(deltaCentiKg: Int): String {
    val sign = when {
        deltaCentiKg > 0 -> "+"
        deltaCentiKg < 0 -> "−"
        else -> "±"
    }
    return "$sign${formatCentiKg(kotlin.math.abs(deltaCentiKg))} $symbol"
}
