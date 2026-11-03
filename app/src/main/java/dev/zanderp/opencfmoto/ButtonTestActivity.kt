// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 🎮 Handlebar button test (v13). Press SET / MODE on the bike, a Bluetooth handlebar remote or the
 * helmet intercom: every signal that reaches the phone shows up here at once, with where it came
 * from — Bluetooth media key, the dash's Wi‑Fi link, or a key event straight to the app.
 * Nothing shows = the button only drives the bike's own screen.
 */
class ButtonTestActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private var previous: ((String) -> Unit)? = null
    private var waiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val c = { id: Int -> ContextCompat.getColor(this, id) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
            setPadding(px(20), px(16), px(20), px(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(px(20), b.top + px(16), px(20), b.bottom + px(16)); insets
        }
        root.addView(TextView(this).apply {
            setText(R.string.bt_test_title); textSize = 26f; setTextColor(c(R.color.text_primary))
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@ButtonTestActivity, R.font.archivo_black)
        })
        root.addView(TextView(this).apply {
            setText(R.string.bt_test_how); textSize = 14f; setTextColor(c(R.color.text_secondary))
            setPadding(0, px(8), 0, px(12))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        add(getString(R.string.bt_test_waiting), R.color.text_secondary)
        waiting = true
        previous = LogBus.listener
        LogBus.listener = { line ->
            previous?.invoke(line)
            val src = when {
                line.contains("[BTN] media key") -> getString(R.string.bt_test_src_bt)
                line.contains("cmd=0x") && !line.contains("[PROBE]") -> getString(R.string.bt_test_src_wifi)
                line.contains("[KEY]") || line.contains("[BTN]") && line.contains("gesture") -> getString(R.string.bt_test_src_key)
                else -> null
            }
            if (src != null) runOnUiThread { add("$src · ${line.substringAfter("  ").take(120)}", R.color.text_primary) }
        }
        LogBus.log("[BTN-TEST] opened")
    }

    override fun onDestroy() {
        if (LogBus.listener != null) LogBus.listener = previous
        super.onDestroy()
    }

    /** Keys sent straight to the foreground app (some remotes act like a keyboard). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            val name = KeyEvent.keyCodeToString(event.keyCode)
            LogBus.log("[BTN-TEST] key $name (code=${event.keyCode}) from device ${event.device?.name ?: "?"}")
            add("${getString(R.string.bt_test_src_key)} · $name · ${event.device?.name ?: "?"}", R.color.text_primary)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun add(s: String, color: Int) {
        if (waiting) { list.removeAllViews(); waiting = false }
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        list.addView(TextView(this).apply {
            text = "$t  $s"; textSize = 14f
            setTextColor(ContextCompat.getColor(this@ButtonTestActivity, color))
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }, 0)
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, ButtonTestActivity::class.java))
    }
}
