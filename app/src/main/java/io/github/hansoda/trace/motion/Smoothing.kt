package io.github.hansoda.trace.motion

import kotlin.math.ceil
import kotlin.math.exp

/** Smooth 0→1 ease with zero velocity and acceleration at both ends. */
internal fun smootherstep(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * x * (x * (x * 6 - 15) + 10)
}

/** Gaussian blur of a per-frame signal, [sigma] in frames. The signal holds its first and last values beyond its ends. */
internal fun gaussian(values: DoubleArray, sigma: Double): DoubleArray {
    if (sigma < 0.5 || values.size < 2) return values.copyOf()
    val radius = ceil(3 * sigma).toInt()
    val kernel = DoubleArray(2 * radius + 1) {
        val d = (it - radius).toDouble()
        exp(-0.5 * d * d / (sigma * sigma))
    }
    val total = kernel.sum()
    for (k in kernel.indices) kernel[k] /= total
    val last = values.size - 1
    return DoubleArray(values.size) { i ->
        var sum = 0.0
        for (k in kernel.indices) sum += kernel[k] * values[(i + k - radius).coerceIn(0, last)]
        sum
    }
}
