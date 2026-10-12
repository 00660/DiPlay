package com.shilapi.xcertplay.network

import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Shell-UID entry point that [CarHotspotAdbFallback] starts through the head unit's adb with app_process on
 * Android 7.0–10. An app can no longer reach ConnectivityManager's hidden startTethering there ("No field mService",
 * #616), while the shell may call it and holds the tethering permission it checks. Starts Wi-Fi tethering with the
 * car's saved hotspot configuration, never a new one, and prints only the protocol line.
 */
object CarHotspotStartMain {
    internal const val HEADER = "DIPLAY_HOTSPOT_START_V1"
    private const val DEADLINE_MS = 10_000L
    private const val RESULT_WAIT_MS = 5_000L
    private const val TETHERING_WIFI = 0
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val NO_RESULT = Int.MIN_VALUE

    @JvmStatic fun main(args: Array<String>) {
        // A stuck Binder call must not leave a privileged helper running.
        Thread({ Thread.sleep(DEADLINE_MS); exitProcess(2) }, "hotspot-start-helper-deadline")
            .apply { isDaemon = true; start() }
        val result = try { start() } catch (error: Throwable) { "error ${CarHotspotTethering.describe(error)}" }
        println("$HEADER|$result")
        exitProcess(0)
    }

    private fun start(): String {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "connectivity") as? IBinder
            ?: return "unsupported"
        val service = Class.forName("android.net.IConnectivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return "unsupported"
        val method = service.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.contentEquals(arrayOf(
                Int::class.javaPrimitiveType, ResultReceiver::class.java, Boolean::class.javaPrimitiveType, String::class.java))
        } ?: return "unsupported"
        val code = AtomicInteger(NO_RESULT)
        val answered = CountDownLatch(1)
        method.invoke(service, TETHERING_WIFI, object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                code.set(resultCode)
                answered.countDown()
            }
        }, false, SHELL_PACKAGE)
        answered.await(RESULT_WAIT_MS, TimeUnit.MILLISECONDS)
        return if (code.get() == NO_RESULT) "requested" else "result ${code.get()}"
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
}
