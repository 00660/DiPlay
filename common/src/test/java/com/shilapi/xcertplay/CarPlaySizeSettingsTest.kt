package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class CarPlaySizeSettingsTest {
    private lateinit var activity: DiPlayActivity
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun setup() {
        prefs.edit().clear().commit()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    @Test fun aLengthFromTheQuickMenuIsShownAsSavedAndSurvivesOpeningSettings() {
        AirPlayPersistence.saveWidthPhysicalMm(activity, 275)
        assertEquals("CarPlay size · Custom · 275 mm", sizeControl().text.toString())
        sizeControl().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(listOf("Large · 250 mm", "Medium · 300 mm", "Small · 350 mm", "Custom · 275 mm"), items(dialog))
        assertEquals(3, dialog.listView.checkedItemPosition)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(275, AirPlayPersistence.loadWidthPhysicalMm(activity))
        assertEquals("CarPlay size · Custom · 275 mm", sizeControl().text.toString())
    }

    @Test fun everyPresetShowsItsLengthAndPickingOneSavesIt() {
        assertEquals("CarPlay size · Medium · 300 mm", sizeControl().text.toString())
        sizeControl().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(listOf("Large · 250 mm", "Medium · 300 mm", "Small · 350 mm"), items(dialog))
        assertEquals(1, dialog.listView.checkedItemPosition)
        dialog.listView.performItemClick(null, 0, 0L)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(250, AirPlayPersistence.loadWidthPhysicalMm(activity))
    }

    @Test fun pickingAPresetReplacesTheCustomLength() {
        AirPlayPersistence.saveWidthPhysicalMm(activity, 275)
        val control = sizeControl()
        control.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 1L)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(300, AirPlayPersistence.loadWidthPhysicalMm(activity))
        assertEquals("CarPlay size · Medium · 300 mm", control.text.toString())
        assertFalse(items(ShadowAlertDialog.getLatestAlertDialog()).isEmpty())
    }

    @Test fun lengthsSavedWithTheEarlier25MmStepsLoadUnchanged() {
        for (mm in listOf(100, 125, 200, 225, 275, 325, 375, 400)) {
            prefs.edit().putInt("display_width_physical_mm", mm).commit()
            assertEquals(mm, AirPlayPersistence.loadWidthPhysicalMm(activity))
            assertTrue(sizeControl().text.toString().endsWith("$mm mm"))
            assertEquals(mm, prefs.getInt("display_width_physical_mm", -1))
        }
    }

    private fun items(dialog: AlertDialog) =
        (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() }

    private fun sizeControl(): Button {
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("carPlaySizeControl", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        return find(parent)!!
    }

    private fun find(view: View): Button? {
        if (view is Button && view.text.startsWith("CarPlay size")) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
        return null
    }
}
