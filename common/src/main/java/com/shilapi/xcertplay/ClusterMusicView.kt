package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.View
import com.shilapi.xcertplay.host.R

/** Local music card inside the existing projection; does not change any OEM cluster mode. */
internal class ClusterMusicView(context: Context) : View(context) {
    private val main = Handler(Looper.getMainLooper())
    private var stopState: (() -> Unit)? = null
    private var stopSettings: (() -> Unit)? = null
    private var snapshot = ClusterMusicState.Snapshot()
    private var visibleUntil = 0L
    private var lastTrackRevision: Long? = null
    private val expire = Runnable { updateVisibility() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init { visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopSettings = ClusterMusicSettings.observe(context) {
            visibleUntil = SystemClock.elapsedRealtime() + TEMPORARY_MILLIS
            updateVisibility()
        }
        stopState = ClusterMusicState.observe {
            snapshot = it
            if (lastTrackRevision != it.revision) {
                lastTrackRevision = it.revision
                visibleUntil = it.changedAt + TEMPORARY_MILLIS
            }
            updateVisibility()
        }
    }

    override fun onDetachedFromWindow() {
        stopState?.invoke(); stopState = null
        stopSettings?.invoke(); stopSettings = null
        main.removeCallbacksAndMessages(null)
        visibility = GONE
        snapshot = ClusterMusicState.Snapshot()
        lastTrackRevision = null
        super.onDetachedFromWindow()
    }

    private fun updateVisibility() {
        main.removeCallbacks(expire)
        val hasTrack = !snapshot.info.title.isNullOrBlank() || !snapshot.info.artist.isNullOrBlank()
        val permanent = ClusterMusicSettings.permanent(context)
        val remaining = visibleUntil - SystemClock.elapsedRealtime()
        visibility = if (ClusterMusicSettings.enabled(context) && hasTrack && (permanent || remaining > 0)) VISIBLE else GONE
        if (visibility == VISIBLE && !permanent) main.postDelayed(expire, remaining)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val scale = ClusterMusicSettings.size(context) / 100f
        val w = (width * .32f * scale).coerceAtMost(width * .85f)
        val h = (height * .22f * scale).coerceAtMost(height * .5f)
        val left = (width * ClusterMusicSettings.x(context) / 100f - w / 2).coerceIn(0f, width - w)
        val top = (height * ClusterMusicSettings.y(context) / 100f - h / 2).coerceIn(0f, height - h)
        val rect = RectF(left, top, left + w, top + h)
        val gap = h * .1f
        paint.color = Color.argb(235, 16, 23, 34)
        canvas.drawRoundRect(rect, h * .12f, h * .12f, paint)
        val artRect = RectF(left + gap, top + gap, left + h - gap, top + h - gap)
        val art = snapshot.artwork?.takeUnless { it.isRecycled }
        if (art != null) canvas.drawBitmap(art, null, artRect, paint)
        else {
            paint.color = Color.rgb(42, 55, 73)
            canvas.drawRoundRect(artRect, gap, gap, paint)
            textPaint.textSize = h * .35f
            canvas.drawText("♪", artRect.left + artRect.width() * .3f, top + h * .65f, textPaint)
        }
        val textLeft = left + h + gap
        val available = (rect.right - gap - textLeft).coerceAtLeast(1f)
        val saved = canvas.save()
        canvas.clipRect(textLeft, top, rect.right - gap, rect.bottom)
        textPaint.textSize = h * .21f
        textPaint.color = Color.WHITE
        canvas.drawText(TextUtils.ellipsize(snapshot.info.title.orEmpty(), textPaint, available, TextUtils.TruncateAt.END).toString(),
            textLeft, top + h * .4f, textPaint)
        textPaint.textSize = h * .16f
        textPaint.color = Color.rgb(190, 201, 217)
        val detail = snapshot.info.artist?.takeIf { it.isNotBlank() }
            ?: snapshot.info.album?.takeIf { it.isNotBlank() } ?: context.getString(R.string.settings_cluster_music_unknown)
        canvas.drawText(TextUtils.ellipsize(detail, textPaint, available, TextUtils.TruncateAt.END).toString(),
            textLeft, top + h * .7f, textPaint)
        canvas.restoreToCount(saved)
    }

    companion object { const val TEMPORARY_MILLIS = 8_000L }
}
