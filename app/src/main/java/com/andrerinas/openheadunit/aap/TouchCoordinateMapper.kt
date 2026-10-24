package com.andrerinas.openheadunit.aap

import kotlin.math.roundToInt

data class TouchCoordinate(
    val x: Int,
    val y: Int
)

object TouchCoordinateMapper {
    fun map(
        rawX: Float,
        rawY: Float,
        inputSurfaceWidth: Float,
        inputSurfaceHeight: Float,
        negotiatedWidth: Int,
        negotiatedHeight: Int,
        marginWidth: Float,
        marginHeight: Float,
        stretchToFill: Boolean,
        hudMirroring: Boolean
    ): TouchCoordinate {
        val surfaceW = inputSurfaceWidth.coerceAtLeast(1f)
        val surfaceH = inputSurfaceHeight.coerceAtLeast(1f)
        val px = if (hudMirroring) surfaceW - rawX else rawX

        val uiW = (negotiatedWidth - marginWidth).coerceAtLeast(1f)
        val uiH = (negotiatedHeight - marginHeight).coerceAtLeast(1f)

        val videoX: Float
        val videoY: Float

        if (stretchToFill) {
            videoX = (px / surfaceW) * uiW
            videoY = (rawY / surfaceH) * uiH
        } else {
            val uiRatio = (uiW / uiH).takeIf { it.isFinite() && it > 0f } ?: 1f
            val viewRatio = (surfaceW / surfaceH).takeIf { it.isFinite() && it > 0f } ?: 1f

            var displayedUiW = surfaceW
            var displayedUiH = surfaceH

            if (viewRatio > uiRatio) {
                displayedUiW = surfaceH * uiRatio
            } else {
                displayedUiH = surfaceW / uiRatio
            }

            val uiLeft = (surfaceW - displayedUiW) / 2f
            val uiTop = (surfaceH - displayedUiH) / 2f

            val localX = px - uiLeft
            val localY = rawY - uiTop

            videoX = if (displayedUiW > 0f) (localX / displayedUiW) * uiW else 0f
            videoY = if (displayedUiH > 0f) (localY / displayedUiH) * uiH else 0f
        }

        val clampedX = if (videoX.isFinite()) videoX.roundToInt().coerceIn(0, negotiatedWidth) else 0
        val clampedY = if (videoY.isFinite()) videoY.roundToInt().coerceIn(0, negotiatedHeight) else 0

        return TouchCoordinate(
            x = clampedX,
            y = clampedY
        )
    }
}
