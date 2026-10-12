package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * The car's own ambient-light music rhythm, running while CarPlay music plays.
 *
 * On a 2024 Tang (DiLink 5, Android 12) the rhythm lives in system_server
 * (`com.android.server.audio.visualizer`). A music app calls BYD's `IAudioService.startAudioOutput(package, token)`,
 * and system_server starts a Visualizer on the output mix and sends colours to the lamp ECU, following whatever
 * plays, until `stopAudioOutput` or the token's process dies. It starts only for BYD's own music apps (its media
 * centre, Kuwo, Kugou, NetEase, QQ Music), so the helper names the media centre. The car's ambient-light menu
 * keeps the colours and effects, and its music rhythm switch must be on.
 *
 * An app may not make that call, so [BydCarMusicRhythmTool] does, as the shell user over DiPlay's existing network
 * ADB access, which this never asks for.
 */
object BydCarMusicRhythm {
    private const val TAG = "DiPlay-CarRhythm"
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "diplay-car-rhythm").apply { isDaemon = true } }
    @Volatile private var wanted = false
    private var playing = false
    private var adb: LocalAdb? = null
    private var shell: LocalAdb.InteractiveShell? = null

    /** Starts or stops the car's rhythm; repeated calls with the same state do nothing. */
    fun setPlaying(context: Context, value: Boolean) {
        if (wanted == value) return
        wanted = value
        val app = context.applicationContext
        executor.execute { apply(app) }
    }

    /** Whether DiPlay's ADB access works and this head unit's audio service has the call; nothing is started. */
    fun checkSupport(context: Context): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        val app = context.applicationContext
        executor.execute {
            val supported = runCatching {
                LocalAdb(AdbKeys.load(app)).use { client ->
                    client.connect(mayAsk = false) == LocalAdb.Access.READY &&
                        BydAmbientLightWorkerProtocol.reply(client.shell(command(app, "--probe"))) == "supported"
                }
            }.getOrDefault(false)
            Log.i(TAG, "check supported=$supported")
            result.complete(supported)
        }
        return result
    }

    private fun apply(context: Context) {
        if (wanted == playing) return
        val reply = if (wanted) {
            val open = shell ?: open(context) ?: return
            BydAmbientLightWorkerProtocol.reply(open.exchangeBounded("start"))
        } else {
            // A helper already gone has stopped it: its stdin closed, and the head unit drops a dead token.
            shell?.let { BydAmbientLightWorkerProtocol.reply(it.exchangeBounded("stop")) } ?: "stopped"
        }
        when (reply) {
            "started" -> playing = true
            "stopped" -> playing = false
            else -> {
                Log.w(TAG, "helper replied $reply")
                close()
                playing = false
            }
        }
        Log.i(TAG, "car music rhythm ${if (playing) "on" else "off"}")
    }

    private fun open(context: Context): LocalAdb.InteractiveShell? {
        close()
        val client = LocalAdb(AdbKeys.load(context)).also { adb = it }
        if (client.connect(mayAsk = false) != LocalAdb.Access.READY) {
            Log.w(TAG, "no adb access")
            close()
            return null
        }
        val opened = client.openShell(command(context, "--stdin")) ?: run { close(); return null }
        if (BydAmbientLightWorkerProtocol.reply(opened.exchangeBounded("ping", 10_000)) != "ready") {
            Log.w(TAG, "helper not ready")
            opened.close()
            close()
            return null
        }
        shell = opened
        return opened
    }

    private fun close() {
        runCatching { shell?.close() }
        shell = null
        runCatching { adb?.close() }
        adb = null
    }

    private fun command(context: Context, mode: String): String {
        val apk = "'" + context.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        return "CLASSPATH=$apk app_process /system/bin ${BydCarMusicRhythmTool::class.java.name} $mode"
    }
}

/**
 * Runs under the head unit's adb shell through app_process, not in DiPlay. `--probe` prints `supported` when the
 * audio service has BYD's start/stop calls. `--stdin` serves one request per line (`ping`, `start`, `stop`), each
 * answered with one line and `done`; stdin closing stops the rhythm.
 */
object BydCarMusicRhythmTool {
    /** BYD's media centre: the head unit starts its music rhythm only for its own music apps. */
    const val MEDIA_CENTRE = "com.byd.mediacenter"

    private class Audio(val service: Any, val start: Method, val stop: Method)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.firstOrNull()) {
                "--probe" -> println(if (runCatching { audio() }.isSuccess) "supported" else "unsupported")
                "--stdin" -> serve()
            }
        } catch (error: Throwable) {
            println("error ${(error.cause ?: error).javaClass.simpleName}")
            println("done")
        } finally {
            System.out.flush()
            System.exit(0)
        }
    }

    private fun audio(): Audio {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        val service = checkNotNull(Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder))
        return Audio(service,
            service.javaClass.getMethod("startAudioOutput", String::class.java, IBinder::class.java),
            service.javaClass.getMethod("stopAudioOutput", String::class.java, IBinder::class.java))
    }

    private fun serve() {
        val audio = audio()
        val token = Binder()
        var started = false
        val input = System.`in`.bufferedReader()
        while (true) {
            val reply = when ((input.readLine() ?: break).trim()) {
                "ping" -> "ready"
                "start" -> {
                    if (!started) audio.start.invoke(audio.service, MEDIA_CENTRE, token)
                    started = true
                    "started"
                }
                "stop" -> {
                    if (started) audio.stop.invoke(audio.service, MEDIA_CENTRE, token)
                    started = false
                    "stopped"
                }
                else -> "error request"
            }
            println(reply)
            println("done")
            System.out.flush()
        }
        if (started) audio.stop.invoke(audio.service, MEDIA_CENTRE, token)
    }
}
