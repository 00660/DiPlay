package com.shilapi.xcertplay

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.util.Locale

/** Suggested user action, never a diagnosis inferred from an arbitrary exception message. */
internal object ConnectionFailureGuidance {
    @StringRes
    fun message(failure: CarPlayStatus.Failed, wireless: Boolean): Int {
        if (failure.wifiResetRequired) return R.string.connection_help_reset_wifi
        when (failure.startupFailure) {
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> return R.string.connection_help_bluetooth_unusable
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> return R.string.connection_help_hotspot
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> return R.string.connection_help_iphone_wifi
            null -> Unit
        }

        val error = failure.message.lowercase(Locale.ROOT)
        if ("permission" in error || "denied" in error) return R.string.connection_help_permissions
        if (!wireless) {
            return if (listOf("usb", "ncm", "configuration", "iphone", "cable").any { it in error }) {
                R.string.connection_help_usb
            } else {
                R.string.connection_help_general
            }
        }
        if ("bluetooth" in error || "rfcomm" in error || "paired" in error) {
            return R.string.connection_help_bluetooth
        }
        if ("hotspot" in error || "access point" in error || "creategroup" in error) {
            return R.string.connection_help_hotspot
        }
        if ("wifi" in error || "wi-fi" in error || "tcp" in error || "airplay" in error) {
            return R.string.connection_help_iphone_wifi
        }
        return R.string.connection_help_general
    }
}
