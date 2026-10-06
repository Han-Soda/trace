package io.github.hansoda.trace.motion

/** Smooth 0→1 ease with zero velocity and acceleration at both ends. */
internal fun smootherstep(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * x * (x * (x * 6 - 15) + 10)
}
