// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * MotoScreen v12 · Thai pump prices (Bangchak first, PTT/OR as a fallback) for Gasohol 95 and E20.
 *
 * Neither company documents a public API, so the parser is deliberately tolerant: it walks the whole
 * JSON (also JSON hidden inside string fields), finds the entries whose name is Gasohol 95 / E20 and
 * reads the fields whose key says today / tomorrow. If it can't, it says so and logs a sample — it
 * never shows an old number as today's price (the card always says when it was fetched).
 */
object FuelPrice {
    enum class Fuel(val label: Int) { G95(R.string.fuel_g95), E20(R.string.fuel_e20) }

    data class Price(val today: Double?, val tomorrow: Double?)
    data class Snapshot(val prices: Map<Fuel, Price>, val source: String, val at: Long)

    private const val BANGCHAK = "https://oil-price.bangchak.co.th/ApiOilPrice2/th"
    private const val PTTOR = "https://orapiweb.pttor.com/api/oilprice/LatestOilPrice"
    private const val HOUR = 18
    private const val CHANNEL = "fuel_price"
    private const val NOTIF_ID = 4311

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "fuel-price").apply { isDaemon = true } }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("motoscreen_fuel", Context.MODE_PRIVATE)

    // ---- settings ----
    fun primary(ctx: Context): Fuel = runCatching { Fuel.valueOf(prefs(ctx).getString("primary", Fuel.G95.name)!!) }.getOrDefault(Fuel.G95)
    fun setPrimary(ctx: Context, f: Fuel) = prefs(ctx).edit().putString("primary", f.name).apply()
    /** Tank size in litres; 0 = not set yet (the card then doesn't guess a full-tank cost). */
    fun tankLitres(ctx: Context): Double = prefs(ctx).getFloat("tank", 0f).toDouble()
    fun setTankLitres(ctx: Context, l: Double) = prefs(ctx).edit().putFloat("tank", l.toFloat()).apply()
    fun notifyOn(ctx: Context) = prefs(ctx).getBoolean("notify", true)
    fun setNotifyOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("notify", on).apply(); schedule(ctx) }

    // ---- cache ----
    fun cached(ctx: Context): Snapshot? {
        val p = prefs(ctx)
        val at = p.getLong("at", 0L).takeIf { it > 0 } ?: return null
        val map = Fuel.values().associateWith { f ->
            Price(p.getFloat("${f.name}_t", -1f).takeIf { it > 0 }?.toDouble(), p.getFloat("${f.name}_n", -1f).takeIf { it > 0 }?.toDouble())
        }.filterValues { it.today != null }
        if (map.isEmpty()) return null
        return Snapshot(map, p.getString("src", "") ?: "", at)
    }

    private fun save(ctx: Context, s: Snapshot) {
        val e = prefs(ctx).edit().putLong("at", s.at).putString("src", s.source)
        for (f in Fuel.values()) {
            val pr = s.prices[f]
            e.putFloat("${f.name}_t", (pr?.today ?: -1.0).toFloat()).putFloat("${f.name}_n", (pr?.tomorrow ?: -1.0).toFloat())
        }
        e.apply()
    }

    @Volatile private var lastTryAt = 0L

    /** Refresh in the background when the cache is older than [maxAgeMs]; [done] runs on the fetch thread. */
    fun refreshIfStale(ctx: Context, maxAgeMs: Long = 3 * 60 * 60_000L, done: ((Snapshot?) -> Unit)? = null) {
        val c = cached(ctx)
        val now = System.currentTimeMillis()
        if (c != null && now - c.at < maxAgeMs) { done?.invoke(c); return }
        if (now - lastTryAt < 5 * 60_000L) { done?.invoke(c); return }
        lastTryAt = now
        val app = ctx.applicationContext
        io.execute { done?.invoke(fetchNow(app)) }
    }

    /** Blocking fetch: Bangchak, then PTT/OR. Null when neither gave a usable price. */
    fun fetchNow(ctx: Context): Snapshot? {
        for ((name, url) in listOf("บางจาก" to BANGCHAK, "ปตท." to PTTOR)) {
            try {
                val body = AppHttp.getText(url)
                val prices = parse(body)
                if (prices.isNotEmpty()) {
                    val s = Snapshot(prices, name, System.currentTimeMillis())
                    save(ctx, s)
                    LogBus.log("[FUEL] $name: " + prices.entries.joinToString { "${it.key}=${it.value.today}→${it.value.tomorrow}" })
                    return s
                }
                LogBus.log("[FUEL] $name: no Gasohol 95 / E20 in reply · sample=${body.take(300).replace('\n', ' ')}")
            } catch (e: Exception) {
                LogBus.log("[FUEL] $name failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    // ---- tolerant parser ----
    internal fun parse(body: String): Map<Fuel, Price> {
        val out = HashMap<Fuel, Price>()
        val root: Any = runCatching { JSONArray(body) }.getOrNull() ?: runCatching { JSONObject(body) }.getOrNull() ?: return out
        walk(root, out, 0)
        return out
    }

    private fun walk(node: Any?, out: HashMap<Fuel, Price>, depth: Int) {
        if (depth > 8 || node == null) return
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out, depth + 1)
            is JSONObject -> {
                entry(node)?.let { (f, p) -> if (out[f] == null) out[f] = p }
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), out, depth + 1)
            }
            is String -> {
                val t = node.trim()
                if ((t.startsWith("[") || t.startsWith("{")) && t.length > 10) {
                    walk(runCatching { JSONArray(t) }.getOrNull() ?: runCatching { JSONObject(t) }.getOrNull(), out, depth + 1)
                }
            }
        }
    }

    private fun fuelOf(name: String): Fuel? {
        val n = name.lowercase(Locale.ROOT)
        val gasohol = n.contains("แก๊สโซฮอล์") || n.contains("แก๊สโซฮอล") || n.contains("gasohol")
        if (!gasohol) return null
        return when {
            n.contains("e20") -> Fuel.E20
            n.contains("e85") -> null
            n.contains("95") -> Fuel.G95
            else -> null
        }
    }

    private fun entry(o: JSONObject): Pair<Fuel, Price>? {
        var fuel: Fuel? = null
        var today: Double? = null
        var tomorrow: Double? = null
        var plain: Double? = null
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val kl = k.lowercase(Locale.ROOT)
            val v = o.opt(k)
            if (v is String && fuel == null && (kl.contains("name") || kl.contains("product") || kl.contains("oil"))) fuel = fuelOf(v)
            val num = when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null } ?: continue
            if (num < 5 || num > 200) continue        // a baht-per-litre price, not an id or a difference
            when {
                kl.contains("dif") || kl.contains("change") || kl.contains("yesterday") -> {}
                kl.contains("tomorrow") || kl.contains("next") -> tomorrow = num
                kl.contains("today") || kl.contains("now") || kl.contains("current") -> today = num
                kl.contains("price") -> plain = num
            }
        }
        val f = fuel ?: return null
        val t = today ?: plain ?: return null
        return f to Price(t, tomorrow)
    }

    // ---- 18:00 daily check: notify only when tomorrow's price differs ----
    private fun nextFire(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    fun ensureScheduled(ctx: Context) {
        val app = ctx.applicationContext
        val existing = PendingIntent.getBroadcast(app, 0, Intent(app, FuelPriceReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (existing == null) schedule(app)
    }

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(app, 0, Intent(app, FuelPriceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        if (!notifyOn(app)) return
        try { am.setWindow(AlarmManager.RTC_WAKEUP, nextFire(), 20 * 60_000L, pi) } catch (e: Exception) {
            LogBus.log("[FUEL] schedule failed: $e")
        }
    }

    internal fun eveningCheck(ctx: Context) {
        val s = fetchNow(ctx) ?: return
        val f = primary(ctx)
        val p = s.prices[f] ?: s.prices.values.firstOrNull() ?: return
        val today = p.today ?: return
        val tomorrow = p.tomorrow ?: return
        val diff = tomorrow - today
        if (kotlin.math.abs(diff) < 0.005) return
        val name = ctx.getString(f.label)
        val text = ctx.getString(
            if (diff > 0) R.string.fuel_notif_up else R.string.fuel_notif_down,
            name, String.format(Locale.US, "%.2f", kotlin.math.abs(diff)), String.format(Locale.US, "%.2f", tomorrow),
        )
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.fuel_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(R.string.fuel_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(s.source)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
        LogBus.log("[FUEL] notified: $text")
    }
}

class FuelPriceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread({
            try {
                if (intent.action == null) FuelPrice.eveningCheck(ctx.applicationContext)
            } catch (e: Exception) {
                LogBus.log("[FUEL] evening check failed: $e")
            } finally {
                FuelPrice.schedule(ctx)   // tomorrow 18:00 (also after a reboot / app update)
                pending.finish()
            }
        }, "fuel-evening").start()
    }
}
