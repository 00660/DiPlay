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

    @Test fun onlyAndroidSevenPointOneAndElevenPlusAreSupported() {
        listOf(23, 24, 26, 27, 28, 29).forEach { assertFalse(CarHotspotConfigWrite.supported(it)) }
        listOf(25, 30, 31, 33, 35).forEach { assertTrue(CarHotspotConfigWrite.supported(it)) }
    }

    @Test fun unsupportedSdkIsReportedBeforeAnythingElse() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.UNSUPPORTED_SDK),
            CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(), credentials, sdk = 29, log = ::log))
        assertTrue(logs.single().contains("unsupported Android 29"))
    }

    @Test fun wpa3IsNeverDowngraded() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.SKIPPED), CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(),
            credentials.copy(security = ManualHotspotSecurity.WPA3), sdk = 25, log = ::log))
    }

    @Test fun credentialsWithLineBreaksAreSkipped() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.SKIPPED), CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(),
            credentials.copy(passphrase = "pass\nword"), sdk = 25, log = ::log))
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.SKIPPED), CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(),
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
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "DiPlay"),
            CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { written = it; true },
            readBack = { written },
            log = ::log))
    }

    @Test fun appWriteKeepsTheCarSsidWhenOneIsReadable() {
        val existing = WifiConfiguration().apply { SSID = "Car-Original" }
        var written: WifiConfiguration? = null
        var reads = 0
        val outcome = CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { written = it; true },
            readBack = { reads++; if (reads == 1) existing else written },
            log = ::log)
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "Car-Original"), outcome)
        assertEquals("Car-Original", written?.SSID)
        assertEquals("password1", written?.preSharedKey)
    }

    @Test fun appWriteUsesTheGeneratedSsidWithoutAReadableConfiguration() {
        var written: WifiConfiguration? = null
        val outcome = CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { written = it; true },
            readBack = { written },
            log = ::log)
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "DiPlay"), outcome)
        assertEquals("DiPlay", written?.SSID)
    }

    @Test fun aRejectedWriteFails() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { false },
            readBack = { null },
            log = ::log))
    }

    @Test fun aMissingReadbackFails() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { true },
            readBack = { null },
            log = ::log))
    }

    @Test fun aReadbackWithoutTheWrittenCredentialsFails() {
        val stale = WifiConfiguration().apply { SSID = "Other" }
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { true },
            readBack = { stale },
            log = ::log))
        assertTrue(logs.last().contains("readback does not match"))
    }

    @Test fun reflectionFailuresAreReportedAsFailed() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { throw NoSuchMethodException("setWifiApConfiguration") },
            write = { true },
            readBack = { null },
            log = ::log))
        assertTrue(logs.last().contains("app write failed"))
    }

    @Test fun shellWriteAcceptsTheHelperOkLine() {
        var command: String? = null
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "Car-Original"), CarHotspotConfigWrite.writeAdb(
            { sent, _ -> command = sent; "noise\nDIPLAY_HOTSPOT_APCONFIG_V1|ok|Q2FyLU9yaWdpbmFs\n" },
            "/data/app/diplay.apk", credentials, 4_000, ::log))
        command!!.let {
            assertTrue(it.contains("app_process /system/bin com.shilapi.xcertplay.network.CarHotspotStartMain"))
            assertTrue(it.contains(" apconfig "))
            assertTrue(it.contains(" 'DiPlay' 'password1' AUTO"))
        }
    }

    @Test fun shellWriteRejectsFailuresAndSilence() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeAdb(
            { _, _ -> "DIPLAY_HOTSPOT_APCONFIG_V1|error rejected" }, "", credentials, 4_000, ::log))
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeAdb(
            { _, _ -> null }, "", credentials, 4_000, ::log))
        assertTrue(logs.last().contains("no reply"))
    }

    @Test fun shellWriteAcceptsAnOkLineWithoutASsid() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, null), CarHotspotConfigWrite.writeAdb(
            { _, _ -> "DIPLAY_HOTSPOT_APCONFIG_V1|ok" }, "", credentials, 4_000, ::log))
    }

    @Test fun parsesTheApconfigProtocolLine() {
        assertEquals("ok|Car-Original",
            CarHotspotStartMain.parseApConfig("x\nDIPLAY_HOTSPOT_APCONFIG_V1|ok|Car-Original\nmore"))
        assertEquals("error rejected", CarHotspotStartMain.parseApConfig("DIPLAY_HOTSPOT_APCONFIG_V1|error rejected"))
        assertNull(CarHotspotStartMain.parseApConfig("nothing"))
        assertNull(CarHotspotStartMain.parseApConfig(null))
    }

    @Test fun readsTheSavedSsidFromTheHelperReply() {
        assertEquals("Car-Original", CarHotspotStartMain.apConfigSsid("ok|Q2FyLU9yaWdpbmFs"))
        assertNull(CarHotspotStartMain.apConfigSsid("ok"))
        assertNull(CarHotspotStartMain.apConfigSsid("ok|"))
        assertNull(CarHotspotStartMain.apConfigSsid("ok|Car-Original"))
        assertNull(CarHotspotStartMain.apConfigSsid("error rejected"))
        assertNull(CarHotspotStartMain.apConfigSsid(null))
        assertEquals("pass|word", CarHotspotStartMain.apConfigPassphrase("ok|Q2Fy|cGFzc3x3b3Jk|1"))
        assertEquals("", CarHotspotStartMain.apConfigPassphrase("ok|Q2Fy"))
        assertEquals("", CarHotspotStartMain.apConfigPassphrase("ok|Q2Fy||1"))
        assertEquals(1, CarHotspotStartMain.apConfigSecurity("ok|Q2Fy|cGFzc3x3b3Jk|1"))
        assertNull(CarHotspotStartMain.apConfigSecurity("ok|Q2Fy|cGFzc3x3b3Jk"))
        assertNull(CarHotspotStartMain.apConfigSecurity("ok|Q2Fy|cGFzc3x3b3Jk|x"))
        assertTrue(CarHotspotStartMain.apConfigAccepted("ok|Car-Original"))
        assertTrue(CarHotspotStartMain.apConfigAccepted("ok"))
        assertFalse(CarHotspotStartMain.apConfigAccepted("error rejected"))
        assertFalse(CarHotspotStartMain.apConfigAccepted(null))
    }

    @Test fun ssidQuotesAreShellEscaped() {
        val command = CarHotspotStartMain.apConfigCommand("/a/b.apk",
            HotspotCredentials("it's", "pass'word", ManualHotspotSecurity.WPA2))
        assertTrue(command.contains(" 'it'\"'\"'s' 'pass'\"'\"'word' AUTO"))
    }
}
