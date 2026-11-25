package dev.fxforge.shizuku

internal object LogcatLimits {
    const val DEFAULT_LINES = 200
    const val MAX_LINES = 500
    const val MAX_OUTPUT_CHARS = 64 * 1024

    fun lines(requested: Int): Int = requested.coerceIn(1, MAX_LINES)
}
