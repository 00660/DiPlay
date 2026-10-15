package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Optional local black corner fill for firmware that shows a wallpaper colour through CarPlay's
 * rounded corners (#635). This is NOT an iPhone-provided corner mask: its radius is user-selected.
 *
 * Draw above either TextureView or SurfaceView output, below touch handling and in-car menus.
 * A picture fitted into a letterbox uses [content] rather than the entire Activity window.
 */
internal class CarPlayBlackCornerOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val content = RectF()
    private val ring = Path()

    var radiusDp: Int = 0
        set(value) {
            field = value.coerceIn(0, MAX_RADIUS_DP)
            invalidate()
        }

    fun setContentBounds(bounds: RectF) {
        content.set(bounds)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radiusDp == 0 || content.isEmpty) return
        val radius = (radiusDp * resources.displayMetrics.density)
            .coerceAtMost(minOf(content.width(), content.height()) / 2f)
        if (radius <= 0f) return
        ring.reset()
        ring.fillType = Path.FillType.EVEN_ODD
        ring.addRect(content, Path.Direction.CW)
        ring.addRoundRect(content, radius, radius, Path.Direction.CW)
        canvas.drawPath(ring, paint)
    }

    companion object {
        const val MAX_RADIUS_DP = 48
        val RADII_DP = listOf(0, 12, 24, 36, 48)
    }
}
