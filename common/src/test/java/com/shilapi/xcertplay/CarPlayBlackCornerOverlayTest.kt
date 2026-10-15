package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayBlackCornerOverlayTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun draw(radiusDp: Int, bounds: RectF, width: Int = 200, height: Int = 100): Bitmap {
        val overlay = CarPlayBlackCornerOverlay(context).apply {
            this.radiusDp = radiusDp
            setContentBounds(bounds)
            layout(0, 0, width, height)
        }
        assertFalse(overlay.isClickable)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.MAGENTA) // Simulates the exposed vehicle wallpaper.
                overlay.draw(this)
            }
        }
    }

    @Test fun blackCornersCoverWallpaperButNotCarPlayContentOrLetterbox() {
        val image = draw(24, RectF(20f, 10f, 180f, 90f))
        try {
            assertEquals(Color.MAGENTA, image.getPixel(0, 0)) // outside the picture is unchanged
            assertEquals(Color.BLACK, image.getPixel(20, 10))
            assertEquals(Color.BLACK, image.getPixel(179, 10))
            assertEquals(Color.BLACK, image.getPixel(20, 89))
            assertEquals(Color.BLACK, image.getPixel(179, 89))
            assertEquals(Color.MAGENTA, image.getPixel(100, 50)) // picture center is not blacked out
            assertEquals(Color.MAGENTA, image.getPixel(100, 10)) // straight top edge is unchanged
        } finally {
            image.recycle()
        }
    }

    @Test fun disabledMaskNeverCoversPicture() {
        val image = draw(0, RectF(0f, 0f, 200f, 100f))
        try {
            for (x in listOf(0, 50, 199)) for (y in listOf(0, 50, 99)) {
                assertEquals(Color.MAGENTA, image.getPixel(x, y))
            }
        } finally {
            image.recycle()
        }
    }

    @Test fun radiusClampedForSmallPortraitOrSplitVideoAreas() {
        val image = draw(48, RectF(-40f, 0f, 40f, 20f), width = 80, height = 150)
        try {
            assertEquals(Color.MAGENTA, image.getPixel(70, 10))
            assertEquals(Color.MAGENTA, image.getPixel(0, 10))
            assertEquals(Color.BLACK, image.getPixel(39, 0))
        } finally {
            image.recycle()
        }
    }

    @Test fun persistenceIsDisabledByDefaultAndRejectsUnknownRadius() {
        assertEquals(0, AirPlayPersistence.loadBlackCornerRadiusDp(context))
        AirPlayPersistence.saveBlackCornerRadiusDp(context, 36)
        assertEquals(36, AirPlayPersistence.loadBlackCornerRadiusDp(context))
        context.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putInt("black_corner_radius_dp", 9999).apply()
        assertEquals(0, AirPlayPersistence.loadBlackCornerRadiusDp(context))
    }
}
