package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.shilapi.xcertplay.network.CarHotspotConfigWrite.Result

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotConfigWriteTest {
    private val logs = mutableListOf<String>()
    private fun log(message: String) { logs += message }
    private val credentials = HotspotCredentials("DiPlay", "password1", ManualHotspotSecurity.WPA2)

    @Test fun onlyAndroidSevenPointOneAndNewerAreSupported() {
        listOf(23, 24).forEach { assertFalse(CarHotspotConfigWrite.supported(it)) }
        listOf(25, 26, 28, 29, 30, 31, 33, 35).forEach { assertTrue(CarHotspotConfigWrite.supported(it)) }
    }

    @Test fun unsupportedSdkIsReportedBeforeAnythingElse() {
        assertEquals(Result.UNSUPPORTED_SDK,
            CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(), credentials, sdk = 24, log = ::log))
        assertTrue(logs.single().contains("unsupported Android 24"))
    }

    @Test fun wpa3IsNeverDowngraded() {
        assertEquals(Result.SKIPPED, CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(),
            credentials.copy(security = ManualHotspotSecurity.WPA3), sdk = 25, log = ::log))
    }

    @Test fun credentialsWithLineBreaksAreSkipped() {
        assertEquals(Result.SKIPPED, CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(),
            credentials.copy(passphrase = "pass\nword"), sdk = 25, log = ::log))
        assertEquals(Result.SKIPPED, CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(),
            credentials.copy(ssid = "car\rhotspot"), sdk = 30, log = ::log))
    }

    @Test fun writeAppliesRawSsidAndWpa2Credentials() {
        val config = WifiConfiguration()
        CarHotspotConfigWrite.applyTo(config, credentials)
        assertEquals("DiPlay", config.SSID)
        assertEquals("password1", config.preSharedKey)
        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK))
    }

    @Test fun openHotspotsStayKeyless() {
        val config = WifiConfiguration()
        CarHotspotConfigWrite.applyTo(config, credentials.copy(security = ManualHotspotSecurity.OPEN))
        assertNull(config.preSharedKey)
        assertTrue(config.allowedKeyManagement.isEmpty)
        assertTrue(CarHotspotConfigWrite.matches(config, credentials.copy(security = ManualHotspotSecurity.OPEN)))
        assertFalse(CarHotspotConfigWrite.matches(config, credentials))
    }

    @Test fun fiveGhzIsWrittenThroughReflectionAndAutoKeepsTheDefault() {
        val auto = WifiConfiguration()
        CarHotspotConfigWrite.applyTo(auto, credentials)
        assertEquals(0, auto.javaClass.getField("apBand").get(auto))
        val five = WifiConfiguration()
        CarHotspotConfigWrite.applyTo(five, credentials.copy(band = ManualHotspotBand.GHZ_5))
        assertEquals(WifiConfiguration::class.java.getField("AP_BAND_5GHZ").getInt(null),
            five.javaClass.getField("apBand").get(five))
    }

    @Test fun appWriteVerifiesTheReadback() {
        var written: WifiConfiguration? = null
        assertEquals(Result.WRITTEN, CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { written = it; true },
            readBack = { written },
            log = ::log))
    }

    @Test fun aRejectedWriteFails() {
        assertEquals(Result.FAILED, CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { false },
            readBack = { null },
            log = ::log))
    }

    @Test fun aMissingReadbackFails() {
        assertEquals(Result.FAILED, CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { true },
            readBack = { null },
            log = ::log))
    }

    @Test fun aReadbackWithoutTheWrittenCredentialsFails() {
        val stale = WifiConfiguration().apply { SSID = "Other" }
        assertEquals(Result.FAILED, CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { true },
            readBack = { stale },
            log = ::log))
        assertTrue(logs.last().contains("readback does not match"))
    }

    @Test fun reflectionFailuresAreReportedAsFailed() {
        assertEquals(Result.FAILED, CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { throw NoSuchMethodException("setWifiApConfiguration") },
            write = { true },
            readBack = { null },
            log = ::log))
        assertTrue(logs.last().contains("app write failed"))
    }

    @Test fun shellWriteAcceptsTheHelperOkLine() {
        var command: String? = null
        assertEquals(Result.WRITTEN, CarHotspotConfigWrite.writeAdb(
            { sent, _ -> command = sent; "noise\nDIPLAY_HOTSPOT_APCONFIG_V1|ok\n" },
            "/data/app/diplay.apk", credentials, 4_000, ::log))
        command!!.let {
            assertTrue(it.contains("app_process /system/bin com.shilapi.xcertplay.network.CarHotspotStartMain"))
            assertTrue(it.contains(" apconfig "))
            assertTrue(it.contains(" 'DiPlay' 'password1' AUTO"))
        }
    }

    @Test fun shellWriteRejectsFailuresAndSilence() {
        assertEquals(Result.FAILED, CarHotspotConfigWrite.writeAdb(
            { _, _ -> "DIPLAY_HOTSPOT_APCONFIG_V1|error rejected" }, "", credentials, 4_000, ::log))
        assertEquals(Result.FAILED, CarHotspotConfigWrite.writeAdb(
            { _, _ -> null }, "", credentials, 4_000, ::log))
        assertTrue(logs.last().contains("no reply"))
    }

    @Test fun parsesTheApconfigProtocolLine() {
        assertEquals("ok", CarHotspotStartMain.parseApConfig("x\nDIPLAY_HOTSPOT_APCONFIG_V1|ok\nmore"))
        assertEquals("error rejected", CarHotspotStartMain.parseApConfig("DIPLAY_HOTSPOT_APCONFIG_V1|error rejected"))
        assertNull(CarHotspotStartMain.parseApConfig("nothing"))
        assertNull(CarHotspotStartMain.parseApConfig(null))
    }

    @Test fun ssidQuotesAreShellEscaped() {
        val command = CarHotspotStartMain.apConfigCommand("/a/b.apk",
            HotspotCredentials("it's", "pass'word", ManualHotspotSecurity.WPA2))
        assertTrue(command.contains(" 'it'\"'\"'s' 'pass'\"'\"'word' AUTO"))
    }
}
