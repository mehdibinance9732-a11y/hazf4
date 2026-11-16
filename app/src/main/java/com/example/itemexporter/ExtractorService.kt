package com.example.itemexporter

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Floating Start/Stop button that stays on top of every app.
 * Start: reads what is on screen now (and everything the app already holds) and keeps collecting while
 * YOU scroll. Stop: writes the Excel file.
 */
class ExtractorService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var button: TextView? = null

    private var capturing = false
    private var session = CaptureSession()
    private var onPage = true
    private var connected = true
    private var captureScheduled = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!capturing) return
            captureOnce()
            handler.postDelayed(this, 250)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !capturing) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleCapture()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideButton()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        capturing = false
        handler.removeCallbacksAndMessages(null)
        hideButton()
        scope.cancel()
        super.onDestroy()
    }

    /** Throttle: read at most every ~120 ms while the list is moving. */
    private fun scheduleCapture() {
        if (captureScheduled) return
        captureScheduled = true
        handler.postDelayed({ captureScheduled = false; if (capturing) captureOnce() }, 120)
    }

    // ------------------------------------------------------------------ floating button

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        if (button != null) return
        val wm = windowManager ?: return
        val dp = resources.displayMetrics.density

        val tv = TextView(this).apply {
            text = IDLE_LABEL
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((18 * dp).toInt(), (12 * dp).toInt(), (18 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 40 * dp
                setColor(COLOR_IDLE)
            }
            elevation = 8 * dp
        }

        fun params(type: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.btnX(this@ExtractorService)
            y = Prefs.btnY(this@ExtractorService)
        }

        // With the "display over other apps" permission use a normal overlay, otherwise the accessibility
        // overlay (which also floats above other apps).
        val preferred = if (Settings.canDrawOverlays(this))
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        var lp = params(preferred)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; downTime = e.eventTime
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) Prefs.setBtnPos(this, lp.x, lp.y)
                    else if (e.eventTime - downTime > 700) dumpTree()
                    else onButtonClick()
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(tv, lp)
            button = tv
        } catch (_: Exception) {
            try {
                lp = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                wm.addView(tv, lp)
                button = tv
            } catch (_: Exception) {
            }
        }
    }

    private fun hideButton() {
        val tv = button ?: return
        try { windowManager?.removeView(tv) } catch (_: Exception) {}
        button = null
    }

    private fun setLabel(text: String, color: Int) {
        val tv = button ?: return
        tv.text = text
        (tv.background as? GradientDrawable)?.setColor(color)
    }

    private fun refreshLabel() {
        if (!capturing) { setLabel(IDLE_LABEL, COLOR_IDLE); return }
        val n = session.items.size
        val t = session.total?.let { " / $it" } ?: ""
        when {
            !onPage -> setLabel("⏸ خارج از صفحه • $n$t", COLOR_WAIT)
            !connected -> setLabel("⚠ آرام‌تر اسکرول کنید • $n$t", COLOR_WARN)
            else -> setLabel("⏹ توقف • $n$t", COLOR_RUNNING)
        }
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()

    private fun onButtonClick() {
        if (capturing) stopCapture() else startCapture()
    }

    // ------------------------------------------------------------------ capture

    private fun startCapture() {
        session = CaptureSession()
        onPage = true
        connected = true
        capturing = true
        // First read: also take every card the app already keeps in memory (even off-screen), if any.
        captureOnce(everything = true)
        refreshLabel()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 250)
    }

    private fun stopCapture() {
        capturing = false
        handler.removeCallbacks(ticker)
        captureOnce()                      // one last read of what is on screen right now
        save(session)
        refreshLabel()
    }

    private fun captureOnce(everything: Boolean = false) {
        val root = rootInActiveWindow
        if (root == null || !pageMatches(root)) {
            if (onPage) { onPage = false; refreshLabel() }
            return
        }
        if (!onPage) { onPage = true }

        val raw: List<RawNode>
        val parsed: List<Item>
        if (everything) {
            raw = collect(root, onlyVisible = false)
            val all = Parser.parse(raw, Int.MIN_VALUE)
            // sanity: off-screen nodes with empty bounds would give garbage rows -> use the visible read
            val sane = all.isNotEmpty() &&
                all.count { it.name.isNotEmpty() && it.price != null } >= all.size * 0.9
            parsed = if (sane) all else Parser.parse(collect(root), listTop(root))
        } else {
            raw = collect(root)
            parsed = Parser.parse(raw, listTop(root))
        }
        Parser.parseTotal(raw)?.let { session.total = it }
        connected = session.add(parsed)
        refreshLabel()

        if (session.isDone) {
            capturing = false
            handler.removeCallbacks(ticker)
            toast("همه‌ی اقلام خوانده شد")
            save(session)
            refreshLabel()
        }
    }

    private fun listTop(root: AccessibilityNodeInfo): Int {
        val list = findScrollable(root) ?: return 0
        val r = Rect()
        list.getBoundsInScreen(r)
        return r.top
    }

    private fun save(s: CaptureSession) {
        val list = s.items.values.toList()
        if (list.isEmpty()) { toast("داده‌ای استخراج نشد. دکمه را لمس طولانی کنید تا گزارش عیب‌یابی ذخیره شود."); return }
        val total = s.total
        scope.launch {
            try {
                val name = "items_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".xlsx"
                val uri = withContext(Dispatchers.Default) {
                    Storage.save(this@ExtractorService, name, Storage.XLSX_MIME, XlsxWriter.build(list))
                }
                Prefs.setLastUri(this@ExtractorService, uri)
                val shortfall = if (total != null && list.size < total) " (از $total)" else ""
                val incomplete = list.count { !it.complete }
                val warn = if (incomplete > 0) "\n⚠ $incomplete ردیف ناقص" else ""
                val hint = if (total != null && list.size < total) "\nبه کالاهای اسکرول‌نشده برگردید و دوباره شروع کنید." else ""
                toast("${list.size} قلم$shortfall ذخیره شد$warn$hint\nDownload/ItemExporter/$name")
            } catch (e: Exception) {
                toast("خطا در ذخیره: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ tree helpers

    /** One native search (cheap) instead of walking the whole tree. */
    private fun pageMatches(root: AccessibilityNodeInfo): Boolean {
        val t = Prefs.trigger(this)
        val variants = setOf(t, Parser.normalize(t), t.replace('ی', 'ي').replace('ک', 'ك'))
        return variants.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
    }

    private fun collect(root: AccessibilityNodeInfo, onlyVisible: Boolean = true): List<RawNode> {
        val out = ArrayList<RawNode>()
        val r = Rect()
        val screenW = resources.displayMetrics.widthPixels
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60) return
            val ok = if (onlyVisible) n.isVisibleToUser else true
            if (ok) {
                val t = (n.text ?: n.contentDescription)?.toString()
                if (!t.isNullOrBlank()) {
                    n.getBoundsInScreen(r)
                    // off-screen to the side = another tab/page of a pager: not part of this list
                    if (onlyVisible || (r.right > 0 && r.left < screenW)) {
                        out.add(RawNode(t, r.left, r.top, r.right, r.bottom))
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return out
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60) return
            if (n.isScrollable && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                val area = r.width().toLong() * r.height().toLong()
                if (area > bestArea) { bestArea = area; best = n }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return best
    }

    /** Long-press on the button: saves the raw accessibility tree to a text file for debugging. */
    private fun dumpTree() {
        val root = rootInActiveWindow
        if (root == null) { toast("صفحه‌ای برای ذخیره پیدا نشد"); return }
        val sb = StringBuilder()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60) return
            n.getBoundsInScreen(r)
            sb.append("  ".repeat(minOf(d, 30))).append(n.className)
                .append(" id=").append(n.viewIdResourceName)
                .append(" scrollable=").append(n.isScrollable)
                .append(" visible=").append(n.isVisibleToUser)
                .append(" [").append(r.toShortString()).append("]")
                .append(" text=").append(n.text)
                .append(" desc=").append(n.contentDescription)
                .append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        try {
            val name = "dump_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
            Storage.save(this, name, "text/plain", sb.toString().toByteArray(Charsets.UTF_8))
            toast("ساختار صفحه ذخیره شد: Download/ItemExporter/$name")
        } catch (e: Exception) {
            toast("خطا: ${e.message}")
        }
    }

    companion object {
        private const val IDLE_LABEL = "▶ شروع استخراج"
        private const val COLOR_IDLE = 0xFFD81B60.toInt()
        private const val COLOR_RUNNING = 0xFF2E7D32.toInt()
        private const val COLOR_WARN = 0xFFEF6C00.toInt()
        private const val COLOR_WAIT = 0xFF546E7A.toInt()
    }
}
