package com.shilapi.xcertplay.network

import android.content.Context
import android.content.ContextWrapper
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.system.Os
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.system.exitProcess

/**
 * Shell-UID entry point that [CarHotspotAdbFallback] starts through the head unit's adb with app_process. An app can no
 * longer start tethering itself ("No field mService" on Android 10, "NoSuchMethodException … startTethering" on 12,
 * #616), while the shell may, and holds the tethering permission it checks. Starts Wi-Fi tethering with the car's
 * saved hotspot configuration, never a new one; the opted-in "apconfig" subcommand is the one
 * exception, writing the manual hotspot credentials on Android 8.0-10 as root and on Android 11+
 * as shell (see CarHotspotConfigWrite), and prints only the protocol line. Android 7.0-10 call
 * IConnectivityManager; Android 11 moved tethering to TetheringManager.
 */
object CarHotspotStartMain {
    internal const val HEADER = "DIPLAY_HOTSPOT_START_V1"
    internal const val APCONFIG_HEADER = "DIPLAY_HOTSPOT_APCONFIG_V1"
    private const val APCONFIG_COMMAND = "apconfig"
    private const val DEADLINE_MS = 10_000L
    private const val RESULT_WAIT_MS = 5_000L
    private const val TETHERING_WIFI = 0
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val SYSTEM_UID = 1000
    private const val SYSTEM_PACKAGE = "android"
    private const val NO_RESULT = Int.MIN_VALUE

    @JvmStatic fun main(args: Array<String>) {
        val apConfig = args.firstOrNull() == APCONFIG_COMMAND
        // A stuck Binder call must not leave a privileged helper running.
        Thread({ Thread.sleep(DEADLINE_MS); exitProcess(2) }, "hotspot-start-helper-deadline")
            .apply { isDaemon = true; start() }
        demoteToSystem()
        val result = try {
            when {
                apConfig -> apConfig(args.drop(1).toTypedArray())
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> startWithTetheringManager()
                else -> startWithConnectivity()
            }
        } catch (error: Throwable) {
            "error ${CarHotspotTethering.describe(error)}"
        }
        println("${if (apConfig) APCONFIG_HEADER else HEADER}|$result")
        exitProcess(0)
    }

    private fun service(name: String): IBinder? = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, name) as? IBinder

    /** A root adbd must demote to the system uid before the binder calls: the wifi service asks
     * PackageManager, where uid 0 holds nothing while the platform-signed system uid holds the
     * signature OVERRIDE_WIFI_CONFIG and every tethering permission. */
    private fun demoteToSystem() {
        if (Os.getuid() != 0) return
        val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
        val intType = Int::class.javaPrimitiveType
        os.javaClass.getMethod("setgid", intType).invoke(os, SYSTEM_UID)
        os.javaClass.getMethod("setuid", intType).invoke(os, SYSTEM_UID)
    }

    /** The package the service binds to the calling uid; the system uid pairs with "android". */
    private fun callingPackage(): String =
        if (Os.getuid() == SYSTEM_UID) SYSTEM_PACKAGE else SHELL_PACKAGE

    /** DiPlay already validated the credentials; the shell writes the saved AP configuration. */
    private fun apConfig(args: Array<String>): String {
        val (ssid, passphrase, band) = args
        val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("wifi") ?: return "unsupported") ?: return "unsupported"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) apConfigR(wifi, ssid, passphrase, band)
        else apConfigLegacy(wifi, ssid, passphrase, band)
    }

    private fun apConfigR(wifi: Any, ssid: String, passphrase: String, band: String): String {
        val configuration = Class.forName("android.net.wifi.SoftApConfiguration")
        val builder = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            .getDeclaredConstructor().newInstance()
        val builderType = builder.javaClass
        builderType.getMethod("setSsid", String::class.java).invoke(builder, ssid)
        if (passphrase.isNotEmpty()) {
            builderType.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                .invoke(builder, passphrase, staticInt(configuration, "SECURITY_TYPE_WPA2_PSK"))
        }
        if (band == ManualHotspotBand.GHZ_5.name) {
            // The builder default is 2.4 GHz; only a 5 GHz request is written.
            builderType.getMethod("setBand", Int::class.javaPrimitiveType)
                .invoke(builder, staticInt(configuration, "BAND_5GHZ"))
        }
        val config = builderType.getMethod("build").invoke(builder)
        val method = wifi.javaClass.methods.firstOrNull {
            it.name == "setSoftApConfiguration" &&
                it.parameterTypes.contentEquals(arrayOf(config.javaClass, String::class.java))
        } ?: return "unsupported"
        return if (method.invoke(wifi, config, callingPackage()) as? Boolean == true) "ok" else "error rejected"
    }

    /** 8.0-10 reject the write for every uid but root: the signature OVERRIDE_WIFI_CONFIG gate
     * passes only uid 0, so this path works when adbd runs as root and fails cleanly otherwise. */
    private fun apConfigLegacy(wifi: Any, ssid: String, passphrase: String, band: String): String {
        val credentials = HotspotCredentials(ssid, passphrase,
            if (passphrase.isEmpty()) ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2,
            if (band == ManualHotspotBand.GHZ_5.name) ManualHotspotBand.GHZ_5 else ManualHotspotBand.AUTO)
        val config = WifiConfiguration().apply { CarHotspotConfigWrite.applyTo(this, credentials) }
        val configuration = arrayOf(WifiConfiguration::class.java)
        val method = wifi.javaClass.methods.firstOrNull {
            it.name == "setWifiApConfiguration" &&
                it.parameterTypes.contentEquals(arrayOf(WifiConfiguration::class.java, String::class.java))
        } ?: wifi.javaClass.methods.firstOrNull {
            it.name == "setWifiApConfiguration" && it.parameterTypes.contentEquals(configuration)
        } ?: return "unsupported"
        val written = (if (method.parameterTypes.size == 2) method.invoke(wifi, config, callingPackage())
            else method.invoke(wifi, config)) as? Boolean == true
        val saved = wifi.javaClass.methods.firstOrNull {
            it.name == "getWifiApConfiguration" && it.parameterTypes.isEmpty()
        }?.invoke(wifi) as? WifiConfiguration
        return when {
            saved != null -> if (CarHotspotConfigWrite.matches(saved, credentials)) "ok" else "error mismatch"
            written -> "ok"
            else -> "error rejected"
        }
    }

    private fun staticInt(type: Class<*>, field: String): Int =
        type.getDeclaredField(field).get(null) as Int

    private fun startWithConnectivity(): String {
        val binder = service("connectivity") ?: return "unsupported"
        val service = Class.forName("android.net.IConnectivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return "unsupported"
        val method = service.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.contentEquals(arrayOf(
                Int::class.javaPrimitiveType, ResultReceiver::class.java, Boolean::class.javaPrimitiveType, String::class.java))
        } ?: return "unsupported"
        val result = Answer()
        method.invoke(service, TETHERING_WIFI, object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) = result.set(resultCode)
        }, false, callingPackage())
        return result.await()
    }

    private fun startWithTetheringManager(): String {
        val connector = service("tethering") ?: return "unsupported"
        val managerType = Class.forName("android.net.TetheringManager")
        val manager = managerType.getDeclaredConstructor(Context::class.java, Supplier::class.java)
            .apply { isAccessible = true }
            .newInstance(ShellContext(), Supplier<IBinder> { connector })
        val builder = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        val request = builder.javaClass.getMethod("build").invoke(builder)
        val callbackType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val result = Answer()
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> { result.set(0); null }
                "onTetheringFailed" -> { result.set(args?.firstOrNull() as? Int ?: -1); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "DiPlayStartTetheringCallback"
                else -> null
            }
        }
        managerType.getMethod("startTethering", request.javaClass, Executor::class.java, callbackType)
            .invoke(manager, request, Executor { it.run() }, callback)
        return result.await()
    }

    /** The calls the tethering service checks: the caller's package for its uid, no attribution tag. */
    private class ShellContext : ContextWrapper(null) {
        override fun getPackageName(): String = callingPackage()
        override fun getOpPackageName(): String = callingPackage()
        override fun getAttributionTag(): String? = null
    }

    /** "requested" when no result arrived in time, else "result N" (0 is TETHER_ERROR_NO_ERROR). */
    private class Answer {
        private val code = AtomicInteger(NO_RESULT)
        private val answered = CountDownLatch(1)

        fun set(value: Int) {
            code.set(value)
            answered.countDown()
        }

        fun await(): String {
            answered.await(RESULT_WAIT_MS, TimeUnit.MILLISECONDS)
            return if (code.get() == NO_RESULT) "requested" else "result ${code.get()}"
        }
    }

    fun command(apk: String): String {
        val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${CarHotspotStartMain::class.java.name}"
    }

    /** The helper's reply, or null when its protocol line is missing; other output is ignored. */
    fun parse(output: String?): String? {
        val prefix = "$HEADER|"
        return output?.lineSequence()?.map(String::trim)?.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    /** "requested" when the call returned without a result yet; "result 0" is TETHER_ERROR_NO_ERROR. */
    fun accepted(reply: String?): Boolean = reply == "requested" || reply == "result 0"

    fun apConfigCommand(apk: String, credentials: HotspotCredentials): String {
        fun quoted(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        return "${command(apk)} $APCONFIG_COMMAND ${quoted(credentials.ssid)} ${quoted(credentials.passphrase)} ${credentials.band.name}"
    }

    /** The apconfig helper's reply, or null when its protocol line is missing. */
    fun parseApConfig(output: String?): String? {
        val prefix = "$APCONFIG_HEADER|"
        return output?.lineSequence()?.map(String::trim)?.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    fun apConfigAccepted(reply: String?): Boolean = reply == "ok"
}
