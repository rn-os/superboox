package com.superboox.quickaccess

import android.view.View

@Suppress("UNUSED_PARAMETER")
internal class HomeHardwareInk(private val view: View, color: Int, width: Float, onStroke: (FloatArray) -> Unit) {
    val drawing = false
    fun start() = Unit
    fun stop() = Unit
    fun refresh() = view.invalidate()
}
