package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.util.concurrent.atomic.AtomicReference

/** The credentials a write should store; the manual hotspot fields stay the single source of truth. */
data class HotspotCredentials(
    val ssid: String,
    val passphrase: String,
    val security: ManualHotspotSecurity,
    val band: ManualHotspotBand = ManualHotspotBand.AUTO,
)

/**
 * Writes the car's saved hotspot configuration before starting it. Android 7.1 writes from the app:
 * the WifiConfiguration class is public, only WifiManager.setWifiApConfiguration is hidden, and its
 * gate is CHANGE_WIFI_STATE alone. Android 11+ writes through the shell helper, because the shell
 * holds NETWORK_SETTINGS on 11-12 and OVERRIDE_WIFI_CONFIG since 13, while 8.0-10 block both the
 * app and the shell with the signature OVERRIDE_WIFI_CONFIG permission. WPA3 hotspots are never
 * downgraded.
 */
object CarHotspotConfigWrite {
    enum class Result(val diagnostic: String) {
        WRITTEN("car hotspot configuration written"),
        SKIPPED("car hotspot configuration write skipped"),
        UNSUPPORTED_SDK("this Android version does not allow writing the car hotspot configuration"),
        ADB_UNAVAILABLE("ADB access is missing for the car hotspot configuration write"),
        FAILED("the car hotspot configuration write failed"),
    }

    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk == Build.VERSION_CODES.N_MR1 || sdk >= Build.VERSION_CODES.R

    fun write(
        context: Context,
        credentials: HotspotCredentials,
        deadline: Long = Long.MAX_VALUE,
        isCancelled: () -> Boolean = { false },
        sdk: Int = Build.VERSION.SDK_INT,
        log: (String) -> Unit,
    ): Result {
        if (!supported(sdk)) {
            log("car hotspot config: unsupported Android $sdk")
            return Result.UNSUPPORTED_SDK
        }
        if (credentials.security != ManualHotspotSecurity.OPEN && credentials.security != ManualHotspotSecurity.WPA2) {
            log("car hotspot config: a ${credentials.security} hotspot is not rewritten")
            return Result.SKIPPED
        }
        if (listOf(credentials.ssid, credentials.passphrase).any { it.contains('\n') || it.contains('\r') }) {
            log("car hotspot config: credentials contain a line break")
            return Result.SKIPPED
        }
        return if (sdk <= Build.VERSION_CODES.N_MR1) writeWithApp(context, credentials, log)
        else writeWithAdb(context, credentials, deadline, isCancelled, log)
    }

    /** Android 7.1 stores raw (unquoted) SSID and passphrase, like the framework's own default AP configuration. */
    internal fun writeApp(
        credentials: HotspotCredentials,
        newConfig: () -> WifiConfiguration,
        write: (WifiConfiguration) -> Boolean?,
        readBack: () -> WifiConfiguration?,
        log: (String) -> Unit,
    ): Result = try {
        val config = newConfig().apply { applyTo(this, credentials) }
        if (write(config) == false) {
            log("car hotspot config: the framework rejected the configuration")
            Result.FAILED
        } else {
            val saved = readBack()
            when {
                saved == null -> {
                    log("car hotspot config: no configuration to read back")
                    Result.FAILED
                }
                matches(saved, credentials) -> Result.WRITTEN
                else -> {
                    log("car hotspot config: readback does not match the written credentials")
                    Result.FAILED
                }
            }
        }
    } catch (error: Exception) {
        log("car hotspot config: app write failed ${CarHotspotTethering.describe(error)}")
        Result.FAILED
    }

    private fun writeWithApp(context: Context, credentials: HotspotCredentials, log: (String) -> Unit): Result {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return Result.FAILED.also { log("car hotspot config: no Wi-Fi manager") }
        val managerType = WifiManager::class.java
        return writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { config ->
                managerType.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
                    .invoke(wifi, config) as? Boolean
            },
            readBack = { managerType.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration },
            log = log)
    }

    internal fun applyTo(config: WifiConfiguration, credentials: HotspotCredentials) {
        config.SSID = credentials.ssid
        if (credentials.security == ManualHotspotSecurity.WPA2) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
            config.preSharedKey = credentials.passphrase
        }
        if (credentials.band == ManualHotspotBand.GHZ_5) {
            // apBand was removed from the public SDK in API 27 but exists on 7.1.
            val type = WifiConfiguration::class.java
            config.javaClass.getField("apBand").set(config, type.getField("AP_BAND_5GHZ").getInt(null))
        }
    }

    internal fun matches(saved: WifiConfiguration?, credentials: HotspotCredentials): Boolean {
        if (saved == null) return false
        val sameKey = when (credentials.security) {
            ManualHotspotSecurity.WPA2 -> saved.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK) &&
                saved.preSharedKey == credentials.passphrase
            ManualHotspotSecurity.OPEN -> saved.allowedKeyManagement.cardinality() == 0 ||
                saved.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
            else -> false
        }
        return sameKey && saved.SSID == credentials.ssid
    }

    private fun writeWithAdb(
        context: Context,
        credentials: HotspotCredentials,
        deadline: Long,
        isCancelled: () -> Boolean,
        log: (String) -> Unit,
    ): Result {
        val client = AtomicReference<LocalAdb?>()
        val outcome = AtomicReference<Result?>()
        CarHotspotAdbFallback.bounded(deadline, isCancelled,
            abort = { client.get()?.cancelPendingOperations() }) {
            val adb = LocalAdb(AdbKeys.load(context))
            client.set(adb)
            adb.use {
                val access = if (isCancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) null
                    else adb.connect(mayAsk = false)
                if (access != null && access != LocalAdb.Access.READY) log("car hotspot config: ADB access=$access")
                if (access != LocalAdb.Access.READY) {
                    outcome.set(Result.ADB_UNAVAILABLE)
                    false
                } else {
                    val timeout = ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1_000, 30_000).toInt()
                    outcome.set(writeAdb(adb::shell, context.applicationInfo.sourceDir, credentials, timeout, log))
                    outcome.get() == Result.WRITTEN
                }
            }
        }
        return outcome.get() ?: Result.FAILED.also { log("car hotspot config: ADB write did not finish") }
    }

    internal fun writeAdb(
        shell: (String, Int) -> String?,
        apk: String,
        credentials: HotspotCredentials,
        timeoutMillis: Int,
        log: (String) -> Unit,
    ): Result {
        val reply = CarHotspotStartMain.parseApConfig(
            shell(CarHotspotStartMain.apConfigCommand(apk, credentials), timeoutMillis))
        return if (CarHotspotStartMain.apConfigAccepted(reply)) Result.WRITTEN
        else Result.FAILED.also { log("car hotspot config: shell write failed: ${reply ?: "no reply"}") }
    }
}
