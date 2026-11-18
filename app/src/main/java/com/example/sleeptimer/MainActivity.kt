package com.example.sleeptimer

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private val BG1 = 0xFF0E0F1F.toInt()
private val BG2 = 0xFF1D1646.toInt()
private val BG3 = 0xFF2B1B5E.toInt()
private val ACCENT = 0xFF7C5CFF.toInt()
private val GREEN = 0xFF3DDC97.toInt()
private val ORANGE = 0xFFFFB020.toInt()
private val RED = 0xFFFF5C7A.toInt()
private val MUTED = 0xB3FFFFFF.toInt()
private val GLASS = 0x1AFFFFFF.toInt()
private val GLASS2 = 0x2EFFFFFF.toInt()

private enum class Perm(val title: String, val hint: String, val manual: Boolean = false) {
    ADMIN("Блокировка экрана", "Права администратора устройства"),
    NOTIF("Уведомления", "Обратный отсчёт в шторке"),
    BATTERY("Работа в фоне", "Исключение из оптимизации батареи"),
    AUTOSTART("Автозапуск · HyperOS", "Включи переключатель вручную", true),
    POWER("Экономия энергии · HyperOS", "Выбери «Нет ограничений»", true)
}

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var permRows: LinearLayout
    private lateinit var permBadge: TextView
    private lateinit var grantBtn: TextView
    private lateinit var setupBox: LinearLayout
    private lateinit var runBox: LinearLayout
    private lateinit var timeText: TextView
    private lateinit var subText: TextView
    private lateinit var seek: SeekBar
    private lateinit var chips: List<TextView>
    private lateinit var countdown: TextView
    private lateinit var endText: TextView

    private var minutes = 30
    private var wizard = false
    private val tried = mutableSetOf<Perm>()

    private val isXiaomi: Boolean
        get() = listOf(Build.MANUFACTURER, Build.BRAND).any {
            it.equals("xiaomi", true) || it.equals("redmi", true) || it.equals("poco", true)
        }

    private val tick = object : Runnable {
        override fun run() { updateTimer(); ui.postDelayed(this, 500) }
    }

    // ---------- lifecycle ----------

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("ui", MODE_PRIVATE)
        minutes = prefs.getInt("last_minutes", 30)
        setupWindow()
        buildUi()
        setMinutes(minutes)
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        ui.removeCallbacks(tick)
        ui.post(tick)
        if (wizard) ui.postDelayed({ continueWizard() }, 350)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    // ---------- permissions ----------

    private fun perms(): List<Perm> =
        if (isXiaomi) Perm.values().toList() else listOf(Perm.ADMIN, Perm.NOTIF, Perm.BATTERY)

    private fun isAdmin() = getSystemService(DevicePolicyManager::class.java)
        .isAdminActive(ComponentName(this, AdminReceiver::class.java))

    private fun granted(p: Perm): Boolean = when (p) {
        Perm.ADMIN -> isAdmin()
        Perm.NOTIF -> getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        Perm.BATTERY -> getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        Perm.AUTOSTART -> prefs.getBoolean("m_autostart", false)
        Perm.POWER -> prefs.getBoolean("m_power", false)
    }

    private fun appDetails() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun open(vararg intents: Intent) {
        for (i in intents) {
            try { startActivity(i); return } catch (e: Exception) { /* пробуем следующий */ }
        }
        Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
    }

    private fun request(p: Perm) {
        when (p) {
            Perm.ADMIN -> open(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Нужно, чтобы блокировать экран по таймеру")
            })
            Perm.NOTIF -> {
                if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notif_asked", false)) {
                    prefs.edit().putBoolean("notif_asked", true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                } else {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName), appDetails())
                }
            }
            Perm.BATTERY -> open(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails())
            Perm.AUTOSTART -> {
                prefs.edit().putBoolean("m_autostart", true).apply()
                open(Intent().setComponent(ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")), appDetails())
            }
            Perm.POWER -> {
                prefs.edit().putBoolean("m_power", true).apply()
                open(Intent().setComponent(ComponentName("com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                    .putExtra("package_name", packageName)
                    .putExtra("package_label", "Таймер сна"), appDetails())
            }
        }
    }

    /** Мастер: по очереди открывает нужные экраны, на возврате продолжает со следующего. */
    private fun grantAll() { wizard = true; tried.clear(); continueWizard() }

    private fun continueWizard() {
        if (!wizard) return
        val next = perms().firstOrNull { !granted(it) && it !in tried }
        if (next == null) {
            wizard = false
            refreshPerms()
            val all = perms().all { granted(it) }
            Toast.makeText(this, if (all) "Всё готово ✓" else "Часть разрешений не выдана", Toast.LENGTH_SHORT).show()
            return
        }
        tried.add(next)
        request(next)
    }

    private fun refreshPerms() {
        val list = perms()
        val okCount = list.count { granted(it) }
        permRows.removeAllViews()
        list.forEach { permRows.addView(permRow(it)) }
        val ready = isAdmin()
        permBadge.text = "$okCount из ${list.size}"
        permBadge.setTextColor(if (okCount == list.size) GREEN else if (ready) ORANGE else RED)
        if (okCount == list.size) {
            grantBtn.text = "Все разрешения выданы ✓"
            grantBtn.background = rounded(0x333DDC97, 20)
            grantBtn.setTextColor(GREEN)
            grantBtn.isEnabled = false
        } else {
            grantBtn.text = "Выдать все разрешения"
            grantBtn.background = rounded(ACCENT, 20)
            grantBtn.setTextColor(Color.WHITE)
            grantBtn.isEnabled = true
        }
    }

    private fun permRow(p: Perm): View {
        val ok = granted(p)
        val color = if (ok) GREEN else if (p == Perm.ADMIN) RED else ORANGE
        val dot = View(this).apply {
            background = oval(color)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(14) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(label(p.title, 16f, Color.WHITE, true))
            addView(label(p.hint, 13f, MUTED))
        }
        val tag = label(if (ok) (if (p.manual) "Отмечено" else "Готово") else "Выдать", 13f, color, true)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(dot); addView(texts); addView(tag)
            if (!ok || p.manual) setOnClickListener { request(p) }
        }
    }

    // ---------- timer ----------

    private fun setMinutes(m: Int) {
        minutes = m.coerceIn(1, 360)
        timeText.text = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
        val prog = ((minutes - 5) / 5).coerceIn(0, 35)
        if (seek.progress != prog) seek.progress = prog
        chips.forEach {
            val sel = (it.tag as Int) == minutes
            it.background = rounded(if (sel) ACCENT else GLASS2, 14)
        }
        updateTimer()
    }

    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun updateTimer() {
        val now = System.currentTimeMillis()
        val end = TimerCtl.endAt(this)
        val running = end > now
        runBox.visibility = if (running) View.VISIBLE else View.GONE
        setupBox.visibility = if (running) View.GONE else View.VISIBLE
        if (running) {
            val s = (end - now + 999) / 1000
            countdown.text = String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            endText.text = "Экран заблокируется в ${clock(end)}"
        } else {
            subText.text = "заблокируется в ${clock(now + minutes * 60_000L)}"
            if (end != 0L && end <= now - 10_000) TimerCtl.clear(this)
        }
    }

    private fun onStart() {
        if (!isAdmin()) {
            Toast.makeText(this, "Сначала выдай доступ к блокировке экрана", Toast.LENGTH_SHORT).show()
            request(Perm.ADMIN)
            return
        }
        prefs.edit().putInt("last_minutes", minutes).apply()
        TimerCtl.start(this, minutes)
        updateTimer()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun label(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun lp(w: Int, h: Int, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun button(t: String, bg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 17f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        background = rounded(bg, 20)
        setPadding(0, dp(16), 0, dp(16))
        layoutParams = lp(MATCH, WRAP, 16)
        setOnClickListener { onClick() }
    }

    private fun roundBtn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = oval(GLASS2)
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        setOnClickListener { onClick() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(GLASS, 28)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = lp(MATCH, WRAP, 16)
    }

    private fun setupWindow() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            window.statusBarColor = BG1
            window.navigationBarColor = BG3
        }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG1, BG2, BG3))
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        scroll.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            val a = if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars())
                intArrayOf(i.left, i.top, i.right, i.bottom)
            } else intArrayOf(0, 0, 0, 0)
            col.setPadding(dp(20) + a[0], dp(20) + a[1], dp(20) + a[2], dp(28) + a[3])
            ins
        }
        col.setPadding(dp(20), dp(28), dp(20), dp(28))

        // Заголовок
        col.addView(label("Таймер сна", 32f, Color.WHITE, true))
        col.addView(label("Остановит музыку и заблокирует экран", 14f, MUTED).apply {
            layoutParams = lp(WRAP, WRAP, 4)
        })

        // Карточка таймера
        val timerCard = card()
        timeText = label("", 60f).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER; includeFontPadding = false
        }
        subText = label("", 14f, MUTED).apply { gravity = Gravity.CENTER }
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(timeText); addView(subText)
        }
        val timeRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(roundBtn("−") { setMinutes(minutes - 1) })
            addView(mid)
            addView(roundBtn("+") { setMinutes(minutes + 1) })
        }
        seek = SeekBar(this).apply {
            max = 35
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(GLASS2)
            layoutParams = lp(MATCH, WRAP, 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) setMinutes(5 + p * 5)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        chips = listOf(15, 30, 45, 60, 90).map { v ->
            label("$v", 15f, Color.WHITE, true).apply {
                tag = v; gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    marginStart = dp(3); marginEnd = dp(3)
                }
                setOnClickListener { setMinutes(v) }
            }
        }
        val chipsRow = LinearLayout(this).apply {
            layoutParams = lp(MATCH, WRAP, 16)
            chips.forEach { addView(it) }
        }
        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(timeRow); addView(seek)
            addView(label("Быстрый выбор, минут", 12f, MUTED).apply {
                gravity = Gravity.CENTER; layoutParams = lp(MATCH, WRAP, 16)
            })
            addView(chipsRow)
            addView(button("Запустить таймер", ACCENT) { onStart() })
        }

        countdown = label("", 56f).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER; includeFontPadding = false
        }
        endText = label("", 14f, MUTED).apply { gravity = Gravity.CENTER }
        val extend = button("+5 мин", GLASS2) {
            val end = TimerCtl.endAt(this)
            if (end > System.currentTimeMillis()) { TimerCtl.startAt(this, end + 5 * 60_000L); updateTimer() }
        }.apply { layoutParams = lp(0, WRAP, 0, 1f).apply { marginEnd = dp(6) } }
        val cancel = button("Отменить", 0xFFB3283F.toInt()) {
            TimerCtl.clear(this); updateTimer()
        }.apply { layoutParams = lp(0, WRAP, 0, 1f).apply { marginStart = dp(6) } }
        runBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(label("Осталось", 14f, MUTED).apply { gravity = Gravity.CENTER })
            addView(countdown); addView(endText)
            addView(LinearLayout(context).apply {
                layoutParams = lp(MATCH, WRAP, 24)
                addView(extend); addView(cancel)
            })
        }
        timerCard.addView(setupBox); timerCard.addView(runBox)
        col.addView(timerCard)

        // Карточка разрешений
        val permCard = card()
        permBadge = label("", 14f, ORANGE, true)
        permCard.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Разрешения", 20f, Color.WHITE, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(permBadge)
        })
        permRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permCard.addView(permRows)
        grantBtn = button("Выдать все разрешения", ACCENT) { grantAll() }
        permCard.addView(grantBtn)
        if (isXiaomi) {
            permCard.addView(label(
                "HyperOS: включи автозапуск и режим «Нет ограничений» — иначе система может " +
                "прервать таймер. Эти два пункта Android не позволяет проверить автоматически, " +
                "поэтому они отмечаются после открытия настроек.", 12f, MUTED
            ).apply { layoutParams = lp(WRAP, WRAP, 14) })
        }
        col.addView(permCard)

        setContentView(scroll)
        scroll.requestApplyInsets()
    }
}
