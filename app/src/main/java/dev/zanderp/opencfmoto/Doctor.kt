// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen TH (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "App doctor": checks the phone for every cause we have seen block a Zontes 368G connection, and
 * reads the session log for known failure signatures. Each problem comes with a one-tap fix.
 * Everything here is best-effort and read-only: a check that can't be evaluated is shown as "?".
 */
object Doctor {
    private const val GEARHEAD = "com.google.android.projection.gearhead"
    private val OFFICIAL_APPS = listOf("tayo.com.ZontesIntelligence", "net.easyconn", "com.carbit.ride")
    private val DASH_BT_PREFIXES = listOf("ICANTRON", "ZT")

    /** ok = true pass, false fail, null unknown. */
    data class Item(val title: String, val detail: String, val ok: Boolean?, val fixLabel: String?, val fix: ((Activity) -> Unit)?)

    fun show(activity: Activity) {
        val items = try { checks(activity) + logFindings(activity) } catch (e: Exception) {
            LogBus.log("[DOCTOR] check failed: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        val bad = items.count { it.ok == false }
        LogBus.log("[DOCTOR] ${items.size} checks, $bad problem(s): " + items.filter { it.ok == false }.joinToString { it.title })
        val dp = activity.resources.displayMetrics.density
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        val summary = TextView(activity).apply {
            text = if (bad == 0) activity.getString(R.string.doctor_all_ok) else activity.getString(R.string.doctor_found, bad)
            textSize = 15f
            setTextColor(ContextCompat.getColor(activity, if (bad == 0) R.color.status_live else R.color.status_busy))
            setPadding(0, 0, 0, (10 * dp).toInt())
        }
        list.addView(summary)
        // Problems first, then unknowns, then passes.
        for (it in items.sortedBy { i -> when (i.ok) { false -> 0; null -> 1; true -> 2 } }) {
            list.addView(row(activity, it, dp))
        }
        val scroll = ScrollView(activity).apply { addView(list) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.doctor_title)
            .setView(scroll)
            .setPositiveButton(R.string.doctor_close, null)
            .setNeutralButton(R.string.doctor_recheck) { _, _ -> activity.window.decorView.post { show(activity) } }
            .show()
    }

    private fun row(activity: Activity, item: Item, dp: Float): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val mark = when (item.ok) { true -> "✅"; false -> "❌"; null -> "⚠️" }
        box.addView(TextView(activity).apply {
            text = "$mark  ${item.title}"
            textSize = 15f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        })
        if (item.detail.isNotBlank() && item.ok != true) {
            box.addView(TextView(activity).apply {
                text = item.detail
                textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                setPadding((30 * dp).toInt(), (2 * dp).toInt(), 0, 0)
            })
        }
        val fix = item.fix
        if (item.ok != true && fix != null && item.fixLabel != null) {
            box.addView(MaterialButton(activity).apply {
                text = item.fixLabel
                gravity = Gravity.CENTER
                setOnClickListener {
                    try { fix(activity) } catch (_: Exception) {
                        Toast.makeText(activity, R.string.open_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = (30 * dp).toInt()
                topMargin = (4 * dp).toInt()
            })
        }
        return box
    }

    private fun open(activity: Activity, intent: Intent) {
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun appDetails(activity: Activity, pkg: String) =
        open(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    private fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { false }

    /** Ask once (then at most every 3 days) to exempt the app from battery optimisation. */
    fun maybeAskBattery(activity: Activity) {
        try {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
            val prefs = activity.getSharedPreferences("motoscreen_th", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("batt_asked_at", 0L) < 3L * 24 * 60 * 60 * 1000) return
            prefs.edit().putLong("batt_asked_at", now).apply()
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
            )
        } catch (_: Exception) {
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun checks(activity: Activity): List<Item> {
        val s = { id: Int -> activity.getString(id) }
        val out = ArrayList<Item>()

        // 1. Android Auto present and not the 1.2 "stub" that ships on some phones.
        val aaVersion = try { activity.packageManager.getPackageInfo(GEARHEAD, 0).versionName ?: "" } catch (_: Exception) { null }
        val aaOk = aaVersion != null && !aaVersion.contains("stub", ignoreCase = true)
        out += Item(s(R.string.doctor_aa), s(R.string.doctor_aa_fix_desc), aaOk, s(R.string.doctor_open_store)) { a ->
            try { open(a, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GEARHEAD"))) }
            catch (_: Exception) { open(a, Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GEARHEAD"))) }
        }

        // 2. Head unit server (AA 17.4+). Passive probe only: null = can't tell.
        val hus = try { HeadUnitServer.probe(activity) } catch (_: Exception) { null }
        out += Item(s(R.string.doctor_hus), s(R.string.doctor_hus_desc), hus, s(R.string.hus_open)) { a -> HeadUnitServer.openSettings(a) }

        // 3. Phone Wi‑Fi on.
        val wifiOn = try {
            (activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
        } catch (_: Exception) { null }
        out += Item(s(R.string.doctor_wifi), s(R.string.doctor_wifi_desc), wifiOn, s(R.string.doctor_open_settings)) { a ->
            open(a, Intent(Settings.Panel.ACTION_WIFI))
        }

        // 4. Bluetooth on and bonded with the dash (ICANTRON_BT-xxxx on the 368G, ZT… on others).
        var btOk: Boolean? = null
        try {
            val mgr = activity.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = mgr.adapter
            btOk = if (adapter == null || !adapter.isEnabled) false else {
                val names = adapter.bondedDevices.orEmpty().mapNotNull { d -> try { d.name } catch (_: SecurityException) { null } }
                names.any { n -> DASH_BT_PREFIXES.any { p -> n.startsWith(p, ignoreCase = true) } }
            }
        } catch (_: SecurityException) { btOk = null } catch (_: Exception) { btOk = null }
        out += Item(s(R.string.doctor_bt), s(R.string.doctor_bt_desc), btOk, s(R.string.doctor_open_settings)) { a ->
            open(a, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        // 5. Battery: unrestricted so ColorOS / MIUI don't kill the projection.
        val pm = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val battOk = try { pm.isIgnoringBatteryOptimizations(activity.packageName) } catch (_: Exception) { null }
        out += Item(s(R.string.doctor_batt), s(R.string.doctor_batt_desc), battOk, s(R.string.doctor_allow)) { a ->
            try {
                open(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
            } catch (_: Exception) { appDetails(a, a.packageName) }
        }

        // 6. Display over other apps: needed to resume on its own with the phone in a pocket.
        val overlayOk = try { Settings.canDrawOverlays(activity) } catch (_: Exception) { null }
        out += Item(s(R.string.doctor_overlay), s(R.string.doctor_overlay_desc), overlayOk, s(R.string.doctor_allow)) { a ->
            // Android 13+ greys this switch out for sideloaded apps ("restricted setting"): it only
            // unlocks from App info ▸ ⋮ ▸ Allow restricted settings, so go there and say how.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                Toast.makeText(a, R.string.doctor_overlay_restricted, Toast.LENGTH_LONG).show()
                appDetails(a, a.packageName)
            } else {
                open(a, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
            }
        }

        // 7. Official / EasyConnection apps grab the same dash ports.
        val official = OFFICIAL_APPS.firstOrNull { installed(activity, it) }
        out += Item(s(R.string.doctor_official), s(R.string.doctor_official_desc), official == null, s(R.string.doctor_force_stop)) { a ->
            if (official != null) appDetails(a, official)
        }
        return out
    }

    /** Known failure signatures from the live session log (what we diagnosed by hand on a 368G). */
    private fun logFindings(activity: Activity): List<Item> {
        val log = try { LogBus.snapshot() } catch (_: Exception) { "" }
        if (log.isBlank()) return emptyList()
        val tail = log.takeLast(60_000)
        val s = { id: Int -> activity.getString(id) }
        val out = ArrayList<Item>()
        val dashGotVideo = tail.contains("sent frame #")
        if (tail.contains("Android Auto sent no video") && tail.contains("connection lost") && !dashGotVideo) {
            out += Item(s(R.string.doctor_log_aa_stall), s(R.string.doctor_log_aa_stall_desc), false, s(R.string.doctor_open_aa_app)) { a ->
                appDetails(a, GEARHEAD)
            }
        }
        if (tail.contains("bonded=0")) {
            out += Item(s(R.string.doctor_log_bt), s(R.string.doctor_log_bt_desc), false, s(R.string.doctor_open_settings)) { a ->
                open(a, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }
        if (Regex("Wi-Fi join unavailable").findAll(tail).count() >= 2 && !tail.contains("Wi-Fi re-acquired") && !tail.contains("Wi-Fi joined")) {
            out += Item(s(R.string.doctor_log_wifi), s(R.string.doctor_log_wifi_desc), false, null, null)
        }
        if (tail.contains("head unit server never answered") || tail.contains("isn't responding")) {
            out += Item(s(R.string.doctor_hus), s(R.string.doctor_hus_desc), false, s(R.string.hus_open)) { a -> HeadUnitServer.openSettings(a) }
        }
        return out
    }
}
