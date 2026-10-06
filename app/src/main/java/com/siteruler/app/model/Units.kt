package com.siteruler.app.model

import kotlin.math.abs
import kotlin.math.roundToInt

enum class Units { IMPERIAL, METRIC }

object Format {
    private const val M_PER_IN = 0.0254
    private const val SQFT_PER_SQM = 10.7639104

    /** 12' 4 1/2" (nearest 1/2 inch) or 3.772 m. */
    fun length(meters: Double, units: Units): String = when (units) {
        Units.METRIC -> String.format("%.3f m", meters)
        Units.IMPERIAL -> {
            val sign = if (meters < 0) "-" else ""
            val halfInches = (abs(meters) / M_PER_IN * 2).roundToInt()
            val feet = halfInches / 24
            val rem = halfInches % 24
            val inches = rem / 2
            val half = if (rem % 2 == 1) " 1/2" else ""
            if (feet > 0) "$sign$feet' $inches$half\"" else "$sign$inches$half\""
        }
    }

    fun area(squareMeters: Double, units: Units): String = when (units) {
        Units.METRIC -> String.format("%.2f sq m", squareMeters)
        Units.IMPERIAL -> String.format("%.1f sq ft", squareMeters * SQFT_PER_SQM)
    }

    fun inches(meters: Double): Double = meters / M_PER_IN
}
