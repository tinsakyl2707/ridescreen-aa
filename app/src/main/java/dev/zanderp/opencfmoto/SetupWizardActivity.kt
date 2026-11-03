// SPDX-License-Identifier: AGPL-3.0-or-later
// MotoScreen TH (fork of RideScreen AA / OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton

/**
 * MotoScreen TH first-run wizard: one full screen per job, one big button per screen, a ✅ the
 * moment the phone says the job is done, and "step N of 7" on top. Everything here re-uses the
 * app's existing checks/settings screens; the wizard only orders them and explains them in Thai.
 */
class SetupWizardActivity : AppCompatActivity() {

    private class Step(
        val title: Int, val body: Int, val action: Int?,
        /** true / false / null (= the phone can't tell: the rider confirms with "Done"). */
        val check: (SetupWizardActivity) -> Boolean?,
        val run: ((SetupWizardActivity) -> Unit)?,
    )

    private val steps = listOf(
        Step(R.string.wz_welcome_title, R.string.wz_welcome_body, null, { null }, null),
        Step(R.string.wz_aa_title, R.string.wz_aa_body, R.string.wz_aa_btn, { it.aaOk() }, { a ->
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GEARHEAD"))) }
            catch (_: Exception) { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GEARHEAD"))) }
        }),
        Step(R.string.wz_perm_title, R.string.wz_perm_body, R.string.wz_perm_btn, { SetupHelper.permissionsGranted(it) }, { a ->
            val missing = SetupHelper.missingPermissions(a)
            if (missing.isNotEmpty()) ActivityCompat.requestPermissions(a, missing.toTypedArray(), 71)
        }),
        Step(R.string.wz_batt_title, R.string.wz_batt_body, R.string.wz_batt_btn, { it.battOk() }, { a ->
            try {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
                a.startActivity(i)
            } catch (_: Exception) {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
            }
        }),
        Step(R.string.wz_dev_title, R.string.wz_dev_body, R.string.wz_dev_btn, { null }, { a -> HeadUnitServer.openSettings(a) }),
        Step(R.string.wz_bt_title, R.string.wz_bt_body, R.string.wz_bt_btn, { it.btOk() }, { a ->
            a.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }),
        Step(R.string.wz_qr_title, R.string.wz_qr_body, R.string.wz_qr_btn, { BikeMemory.selected(it) != null }, { a ->
            pendingScan = true
            a.finishWizard()
        }),
    )

    private var index = 0
    /** Steps the phone can't verify, confirmed by the rider with "Done". */
    private val confirmed = HashSet<Int>()

    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var statusView: TextView
    private lateinit var actionBtn: MaterialButton
    private lateinit var nextBtn: MaterialButton
    private lateinit var backBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = savedInstanceState?.getInt("i") ?: 0
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val c = { id: Int -> ContextCompat.getColor(this, id) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.bg))
            setPadding(px(24), px(16), px(24), px(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(px(24), b.top + px(16), px(24), b.bottom + px(16))
            insets
        }
        // Top row: step counter + skip.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        progressText = TextView(this).apply { textSize = 13f; setTextColor(c(R.color.brand_gold)) }
        top.addView(progressText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            setText(R.string.wz_skip)
            setTextColor(c(R.color.text_secondary))
            setOnClickListener { finishWizard() }
        })
        root.addView(top)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = steps.size
            progressTintList = android.content.res.ColorStateList.valueOf(c(R.color.brand_gold))
        }
        root.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(6)))

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(28), 0, px(12)) }
        titleView = TextView(this).apply {
            textSize = 26f
            setTextColor(c(R.color.text_primary))
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@SetupWizardActivity, R.font.archivo_black)
        }
        bodyView = TextView(this).apply {
            textSize = 16f
            setTextColor(c(R.color.text_secondary))
            setLineSpacing(px(4).toFloat(), 1f)
            setPadding(0, px(14), 0, 0)
        }
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(px(14), px(12), px(14), px(12))
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(c(R.color.surface)) }
        }
        col.addView(titleView)
        col.addView(bodyView)
        col.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(22) })
        // Language pill on the welcome screen only (same TH | EN toggle as the home screen).
        col.addView(langPill(px(0)).also { it.tag = "lang" }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(40)).apply { topMargin = px(22) })
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        actionBtn = MaterialButton(this).apply {
            minHeight = px(60); textSize = 17f
            setOnClickListener { steps[index].run?.invoke(this@SetupWizardActivity) }
        }
        root.addView(actionBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.wz_back)
            setOnClickListener { if (index > 0) { index--; render() } }
        }
        nextBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setOnClickListener { next() }
        }
        nav.addView(backBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(6) })
        nav.addView(nextBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(6) })
        root.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        setContentView(root)
        LogBus.log("[WIZARD] opened")
        render()
        if (MotoScreen.langSwitching) MotoScreen.fadeIn(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("i", index)
    }

    /** Back from Play Store / settings / the permission prompt: re-check and move on by itself. */
    override fun onResume() {
        super.onResume()
        if (::titleView.isInitialized) render(autoAdvance = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render(autoAdvance = true)
    }

    private var lastOk: Boolean? = null

    private fun render(autoAdvance: Boolean = false) {
        val s = steps[index]
        val ok = s.check(this) ?: if (index in confirmed) true else null
        // Just turned green after the rider came back from doing it → next screen after a beat.
        if (autoAdvance && ok == true && lastOk == false && index < steps.size - 1) {
            lastOk = null
            statusView.postDelayed({ if (!isFinishing) next() }, 700)
        }
        lastOk = ok
        progressText.text = getString(R.string.wz_progress, index + 1, steps.size)
        progressBar.progress = index + 1
        titleView.setText(s.title)
        bodyView.setText(s.body)
        val c = { id: Int -> ContextCompat.getColor(this, id) }
        statusView.visibility = if (index == 0) View.GONE else View.VISIBLE
        when (ok) {
            true -> { statusView.setText(R.string.wz_status_ok); statusView.setTextColor(c(R.color.status_live)) }
            false -> { statusView.setText(R.string.wz_status_todo); statusView.setTextColor(c(R.color.status_busy)) }
            null -> { statusView.setText(R.string.wz_status_manual); statusView.setTextColor(c(R.color.text_secondary)) }
        }
        (statusView.parent as View).findViewWithTag<View>("lang")?.visibility = if (index == 0) View.VISIBLE else View.GONE
        actionBtn.visibility = if (s.action == null) View.GONE else View.VISIBLE
        s.action?.let { actionBtn.setText(it) }
        backBtn.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        nextBtn.setText(
            when {
                index == 0 -> R.string.wz_start
                index == steps.size - 1 -> R.string.wz_finish
                ok == null -> R.string.wz_done_next
                else -> R.string.wz_next
            }
        )
    }

    private fun next() {
        if (steps[index].check(this) == null) confirmed += index
        if (index < steps.size - 1) { index++; lastOk = null; render() } else finishWizard()
    }

    private fun finishWizard() {
        markDone(this)
        LogBus.log("[WIZARD] finished at step ${index + 1}/${steps.size}")
        finish()
    }

    private fun langPill(@Suppress("UNUSED_PARAMETER") pad: Int): View {
        val dp = resources.displayMetrics.density
        val box = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@SetupWizardActivity, R.drawable.ms_lang_track)
            setPadding((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt())
            isClickable = true
        }
        val thumb = View(this).apply { background = ContextCompat.getDrawable(this@SetupWizardActivity, R.drawable.ms_lang_thumb) }
        box.addView(thumb, FrameLayout.LayoutParams((52 * dp).toInt(), FrameLayout.LayoutParams.MATCH_PARENT))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 14f; gravity = Gravity.CENTER
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@SetupWizardActivity, R.font.archivo_black)
        }
        val th = label("ไทย"); val en = label("EN")
        row.addView(th, LinearLayout.LayoutParams((52 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        row.addView(en, LinearLayout.LayoutParams((52 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
        box.addView(row)
        MotoScreen.bindLangToggle(box, thumb, th, en)
        return box
    }

    private fun aaOk(): Boolean = try {
        val v = packageManager.getPackageInfo(GEARHEAD, 0).versionName ?: ""
        !v.contains("stub", ignoreCase = true)
    } catch (_: Exception) { false }

    private fun battOk(): Boolean? = try {
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { null }

    @SuppressLint("MissingPermission")
    private fun btOk(): Boolean? = try {
        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (ad == null || !ad.isEnabled) false
        else ad.bondedDevices.orEmpty().any { d ->
            val n = try { d.name } catch (_: SecurityException) { null } ?: ""
            n.startsWith("ICANTRON", true) || n.startsWith("ZT", true)
        }
    } catch (_: SecurityException) { null } catch (_: Exception) { null }

    companion object {
        private const val GEARHEAD = "com.google.android.projection.gearhead"
        /** Set by the last step: MainActivity opens the QR scanner when it comes back. */
        @Volatile var pendingScan = false

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("motoscreen_th", Context.MODE_PRIVATE)
        fun markDone(ctx: Context) = prefs(ctx).edit().putBoolean("wizard_done", true).apply()

        /** First run only: never finished/skipped the wizard and never projected. */
        fun shouldShow(ctx: Context): Boolean =
            !prefs(ctx).getBoolean("wizard_done", false) && !MotoScreen.everConnected(ctx) && BikeMemory.selected(ctx) == null

        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, SetupWizardActivity::class.java))
    }
}
