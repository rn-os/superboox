package com.superboox.quickaccess

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * The heart of SuperBoox. An AccessibilityService is used instead of a plain
 * "draw over other apps" service because:
 *  - TYPE_ACCESSIBILITY_OVERLAY windows sit above everything, including the
 *    BOOX launcher and system dialogs, so the gesture works "on any page";
 *  - BOOX's aggressive background-app freezing leaves accessibility services alone;
 *  - it can run Back/Home/Recents/Screenshot and drive BOOX Notes (see [NoteOpener]).
 */
class QuickAccessService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private lateinit var noteOpener: NoteOpener
    private val handler = Handler(Looper.getMainLooper())

    private var handleView: EdgeHandleView? = null
    private var panel: QuickAccessPanel? = null

    /** Package of the app the user was in before opening the panel. */
    var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        DebugLog.init(this)
        DebugLog.i("service", "connected")
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        noteOpener = NoteOpener(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        attachHandle()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES || pkg.contains("inputmethod")) return
        // Dialogs/toasts from other apps also fire this event; only count real app windows.
        val windowType = runCatching { windows.firstOrNull { it.id == event.windowId }?.type }.getOrNull()
        if ((windowType == null || windowType == AccessibilityWindowInfo.TYPE_APPLICATION) && pkg != foregroundPackage) {
            foregroundPackage = pkg
            DebugLog.i("foreground", "$pkg (${event.className})")
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::prefs.isInitialized) return
        // Rotation / split-screen: re-measure the strip and the panel.
        attachHandle()
        if (panel != null) {
            hidePanel()
            showPanel()
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key == null || key in Prefs.HANDLE_KEYS) attachHandle()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DebugLog.w("service", "unbound (accessibility service turned off or killed)")
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        if (::noteOpener.isInitialized) noteOpener.cancel()
        hidePanel()
        detachHandle()
    }

    // ---- Edge handle ---------------------------------------------------------------------

    private fun attachHandle() {
        detachHandle()
        if (!prefs.handleEnabled) return
        val screen = screenSize()
        val edge = prefs.edge
        val view = EdgeHandleView(this, edge, prefs.trigger, prefs.showHandle) {
            DebugLog.i("gesture", "${prefs.trigger} on $edge edge")
            showPanel()
        }
        val params = overlayParams(
            width = dp(prefs.handleWidthDp.coerceIn(6, 48)),
            height = (screen.second * prefs.handleHeightPct.coerceIn(10, 100) / 100f).toInt(),
            focusable = false,
        ).apply {
            gravity = Gravity.TOP or (if (edge == Edge.RIGHT) Gravity.RIGHT else Gravity.LEFT)
            y = (screen.second * prefs.handleTopPct.coerceIn(0, 90) / 100f).toInt()
        }
        runCatching { wm.addView(view, params) }
            .onSuccess {
                handleView = view
                DebugLog.i("handle", "attached ${params.width}x${params.height}px at y=${params.y} on $edge (screen ${screen.first}x${screen.second})")
            }
            .onFailure {
                DebugLog.e("handle", "attach failed", it)
                Toast.makeText(this, "Couldn't add edge handle: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun detachHandle() {
        handleView?.let { runCatching { wm.removeView(it) } }
        handleView = null
    }

    // ---- Panel ----------------------------------------------------------------------------

    val isPanelShowing: Boolean get() = panel != null

    fun togglePanel() = if (isPanelShowing) hidePanel() else showPanel()

    fun showPanel() {
        if (panel != null) return
        val p = QuickAccessPanel(this, prefs, foregroundPackage)
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable = true)
        runCatching { wm.addView(p.root, params) }
            .onSuccess {
                panel = p
                DebugLog.i("panel", "shown over $foregroundPackage")
            }
            .onFailure {
                DebugLog.e("panel", "show failed", it)
                Toast.makeText(this, "Couldn't open panel: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it.root) } }
        panel = null
    }

    /** Re-render the panel in place (after pinning/unpinning from inside it). */
    fun refreshPanel() {
        if (panel == null) return
        hidePanel()
        showPanel()
    }

    // ---- Actions used by the panel ------------------------------------------------------

    fun systemAction(action: Int) {
        hidePanel()
        // Let the e-ink screen redraw without the panel before screenshots etc.
        handler.postDelayed({
            val ok = performGlobalAction(action)
            DebugLog.i("action", "global action $action -> $ok")
        }, if (action == GLOBAL_ACTION_TAKE_SCREENSHOT) 600L else 50L)
    }

    fun openBooxNote(path: String) {
        hidePanel()
        DebugLog.i("note", "open \"$path\" in ${prefs.notesPackage}")
        noteOpener.open(prefs.notesPackage, path)
    }

    /** Text labels currently visible in BOOX Notes, for "pin a note from this screen". */
    fun notesScreenLabels(): List<String> {
        val pkg = prefs.notesPackage
        val root = runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == pkg } }
        }.getOrNull()
        if (root == null) {
            DebugLog.w("picker", "no $pkg window found; windows=${runCatching { windows.map { "${it.type}:${it.root?.packageName}" } }.getOrNull()}")
            return emptyList()
        }
        val labels = NoteOpener.visibleLabels(root)
        DebugLog.i("picker", "${labels.size} labels in $pkg\n" + DebugReport.describeTree(root))
        return labels
    }

    // ---- Window helpers ----------------------------------------------------------------

    private fun overlayParams(width: Int, height: Int, focusable: Boolean) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        windowAnimations = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun screenWidth(): Int = screenSize().first

    companion object {
        @Volatile
        var instance: QuickAccessService? = null
            private set

        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.onyx.floatingbutton", // BOOX Navigation Ball
        )
    }
}
