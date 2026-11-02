// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen TH (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 🔬 Dash explorer (technician tool, long-press the 🩺 tile). READ-ONLY reconnaissance of the dash
 * on its own Wi‑Fi: which TCP ports it listens on and what each one says first. It never uploads,
 * writes or sends a command to the dash's control protocol — the only thing ever sent is a plain
 * "GET /" to ports that are not the known EasyConn / media ports, to identify web or FTP servers.
 * The aim: find the update/theme channel the 368G uses, before anything is ever flashed.
 */
object DashExplorer {
    private const val DASH = "192.168.3.1"
    /** Ours / EasyConn's: never poke these, only report them. */
    private val KNOWN = setOf(10920, 10921, 10922, 10930, 8200)

    fun show(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ms_explorer_title)
            .setMessage(R.string.ms_explorer_intro)
            .setPositiveButton(R.string.ms_explorer_start) { _, _ -> run(activity) }
            .setNegativeButton(R.string.doctor_close, null)
            .show()
    }

    private fun findNetwork(ctx: Context): Network? {
        BikeLink.currentNetwork?.let { return it }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@firstOrNull false
            val lp = cm.getLinkProperties(n) ?: return@firstOrNull false
            lp.linkAddresses.any { it.address.hostAddress?.startsWith("192.168.3.") == true }
        }
    }

    private fun run(activity: Activity) {
        val status = TextView(activity).apply {
            val dp = resources.displayMetrics.density
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), 0)
            textSize = 14f
            text = activity.getString(R.string.ms_explorer_running, 0)
        }
        val dlg = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ms_explorer_title)
            .setView(status)
            .setCancelable(false)
            .show()
        Thread({
            val net = findNetwork(activity)
            LogBus.log("[EXPLORE] start · network=${net ?: "default"}")
            val open = java.util.concurrent.ConcurrentSkipListSet<Int>()
            val done = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(96)
            for (port in 1..65535) pool.execute {
                try {
                    if (port in KNOWN) return@execute
                    val s = net?.socketFactory?.createSocket() ?: Socket()
                    s.use { it.connect(InetSocketAddress(DASH, port), 250) }
                    open.add(port)
                } catch (_: Exception) {
                } finally {
                    val d = done.incrementAndGet()
                    if (d % 2048 == 0) activity.runOnUiThread {
                        status.text = activity.getString(R.string.ms_explorer_running, d * 100 / 65535)
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.MINUTES)
            val report = StringBuilder()
            report.append("skipped (EasyConn/media): ").append(KNOWN.joinToString()).append('\n')
            report.append("open ports: ").append(open.joinToString()).append('\n')
            for (port in open) report.append(banner(net, port)).append('\n')
            val text = report.toString().trim()
            text.lines().forEach { LogBus.log("[EXPLORE] $it") }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                dlg.dismiss()
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.ms_explorer_title)
                    .setMessage(if (open.isEmpty()) activity.getString(R.string.ms_explorer_none) else text)
                    .setPositiveButton(R.string.doctor_close, null)
                    .show()
            }
        }, "dash-explore").start()
    }

    /** What the port says by itself in 1.5 s; for unknown ports, also the answer to a plain GET /. */
    private fun banner(net: Network?, port: Int): String {
        if (port in KNOWN) return "  :$port (EasyConn/media — not touched)"
        return try {
            val s = net?.socketFactory?.createSocket() ?: Socket()
            s.use { sock ->
                sock.connect(InetSocketAddress(DASH, port), 800)
                sock.soTimeout = 1500
                val buf = ByteArray(300)
                var n = try { sock.getInputStream().read(buf) } catch (_: Exception) { -1 }
                var how = "says"
                if (n <= 0) {
                    sock.getOutputStream().write("GET / HTTP/1.0\r\nHost: $DASH\r\n\r\n".toByteArray())
                    sock.getOutputStream().flush()
                    n = try { sock.getInputStream().read(buf) } catch (_: Exception) { -1 }
                    how = "GET/ →"
                }
                if (n <= 0) "  :$port silent"
                else "  :$port $how " + String(buf, 0, n, Charsets.ISO_8859_1)
                    .replace(Regex("[^\\x20-\\x7e]"), "·").take(200)
            }
        } catch (e: Exception) {
            "  :$port ${e.javaClass.simpleName}"
        }
    }
}
