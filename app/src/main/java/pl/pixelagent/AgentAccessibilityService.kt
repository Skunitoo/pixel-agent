package pl.pixelagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class AgentAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var safetyPanel: LinearLayout? = null
    private var approvalPanel: LinearLayout? = null
    private var stopRequest: (() -> Unit)? = null
    private var isAwaiting = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        hideAll()
        stopRequest = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun hideAll() {
        main.post {
            try { safetyPanel?.let { wm.removeView(it) } } catch (_: Exception) {}
            try { approvalPanel?.let { wm.removeView(it) } } catch (_: Exception) {}
            safetyPanel = null
            approvalPanel = null
            isAwaiting = false
        }
    }

    fun showStatus(status: String, onStop: () -> Unit) {
        main.post {
            stopRequest = onStop
            if (safetyPanel == null) {
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(0xed18293b.toInt())
                    setPadding(14, 8, 14, 8)
                }
                val label = TextView(this).apply {
                    id = android.R.id.text1
                    setTextColor(-1)
                    textSize = 13f
                }
                panel.addView(label, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                panel.addView(Button(this).apply {
                    text = "STOP"
                    setOnClickListener { stopRequest?.invoke() }
                })
                val layout = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP
                    y = (resources.displayMetrics.density * 65).toInt()
                }
                try { wm.addView(panel, layout); safetyPanel = panel }
                catch (_: Exception) {}
            }
            safetyPanel?.findViewById<TextView>(android.R.id.text1)?.text =
                "PIXEL AGENT 1.0\n" + status.take(105)
        }
    }

    // All agent observations are restricted to the currently visible app.
    // Editable values, passwords and secure input are never transmitted.
    fun observe(): AgentScreen? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == "pl.pixelagent.private") return null
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        if (AgentProtocol.forbidden(pkg, appLabel)) return null
        return collect(root).first
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        if (node.isPassword || node.isEditable) {
            return (node.hintText?.toString() ?: node.viewIdResourceName
                ?: "Pole tekstowe").replace(Regex("\\s+"), " ").take(90)
        }
        val direct = (node.text ?: node.contentDescription ?: node.hintText)
            ?.toString()?.trim().orEmpty()
        if (direct.isNotEmpty()) return direct.replace(Regex("\\s+"), " ").take(90)
        if (!node.isClickable) return ""
        var examined = 0
        fun descend(parent: AccessibilityNodeInfo, depth: Int): String {
            if (depth > 3 || examined++ > 25) return ""
            for (i in 0 until minOf(12, parent.childCount)) {
                val child = parent.getChild(i) ?: continue
                if (child.isPassword || child.isEditable) continue
                val text = (child.text ?: child.contentDescription)?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) return text.replace(Regex("\\s+"), " ").take(90)
                val nested = descend(child, depth + 1)
                if (nested.isNotEmpty()) return nested
            }
            return ""
        }
        return descend(node, 0)
    }

    private fun collect(root: AccessibilityNodeInfo): Pair<AgentScreen, List<AccessibilityNodeInfo>> {
        val targets = ArrayList<AgentNode>()
        val refs = ArrayList<AccessibilityNodeInfo>()
        val visible = LinkedHashSet<String>()
        var examined = 0
        val h = resources.displayMetrics.heightPixels
        val w = resources.displayMetrics.widthPixels
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 18 || examined++ >= 380) return
            if (!node.isVisibleToUser || node.isPassword) return
            val label = labelOf(node)
            val editable = node.isEditable ||
                node.className?.toString()?.contains("EditText", true) == true
            if (!editable && label.isNotBlank() && visible.size < 55)
                visible.add(label.take(96))
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val onScreen = bounds.left >= 0 && bounds.top >= 0 &&
                bounds.width() >= 12 && bounds.height() >= 12 &&
                bounds.left < w && bounds.top < h
            if (targets.size < 45 && onScreen && node.isEnabled &&
                (node.isClickable || editable)) {
                val name = if (label.isNotBlank()) label else
                    if (editable) "Pole tekstowe" else ""
                if (name.isNotBlank()) {
                    targets.add(AgentNode(
                        targets.size, name, if (editable) "INPUT" else "TAP",
                        Rect(bounds), node.viewIdResourceName.orEmpty(), editable))
                    refs.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        val txt = visible.take(48).joinToString(" | ").take(2200)
        val key = root.packageName.toString() + "|" + txt.take(550) +
            targets.joinToString("|") { it.label + ":" + it.bounds.toShortString() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).take(8)
            .joinToString("") { "%02x".format(it) }
        return AgentScreen(root.packageName.toString(), txt, targets, hash) to refs
    }

    private fun matchedTarget(snapshot: AgentScreen, id: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != snapshot.packageName) return null
        val (fresh, refs) = collect(root)
        val expected = snapshot.nodes.firstOrNull { it.id == id } ?: return null
        val got = fresh.nodes.firstOrNull { it.id == id } ?: return null
        if (expected.label != got.label || expected.kind != got.kind ||
            expected.viewId != got.viewId) return null
        val a = expected.bounds
        val b = got.bounds
        if (kotlin.math.abs(a.left - b.left) > 20 ||
            kotlin.math.abs(a.top - b.top) > 20 ||
            kotlin.math.abs(a.right - b.right) > 20 ||
            kotlin.math.abs(a.bottom - b.bottom) > 20) return null
        return refs.getOrNull(id)
    }

    private fun execute(
        action: AgentCommand,
        screen: AgentScreen?,
        done: (AgentResult) -> Unit
    ) {
        val t = action.action
        if (t == "OPEN_APP" || t == "SEARCH_WEB") {
            done(AgentResult(true, true, "Zatwierdzono otwarcie aplikacji."))
            return
        }
        val root = rootInActiveWindow
        if (screen == null || root?.packageName?.toString() != screen.packageName) {
            done(AgentResult(true, false, "Zmieniła się aktywna aplikacja."))
            return
        }
        when (t) {
            "TAP" -> {
                val node = matchedTarget(screen, action.target)
                val ok = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                done(AgentResult(true, ok, if (ok) "Tap przyjęty przez Androida." else
                    "Nie udało się kliknąć wybranego elementu."))
            }
            "PRESS_ENTER" -> {
                val node = matchedTarget(screen, action.target)
                if (node == null || node.isPassword || !node.isEditable) {
                    done(AgentResult(true, false, "Nie można nacisnąć Enter w chronionym polu."))
                    return
                }
                val ok = node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
                done(AgentResult(true, ok, if (ok) "Enter przyjęty przez aplikację." else
                    "Aplikacja nie udostępnia akcji IME Enter. Spróbuj SEARCH_WEB."))
            }
            "TYPE" -> {
                val node = matchedTarget(screen, action.target)
                if (node == null || node.isPassword || !node.isEditable) {
                    done(AgentResult(true, false, "Pole zmieniło się lub jest chronione."))
                    return
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        action.text)
                }
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                done(AgentResult(true, ok, if (ok) "Wpisano tekst w pole." else
                    "Pole odrzuciło wpisywanie."))
            }
            "BACK", "HOME" -> {
                val ok = performGlobalAction(if (t == "HOME")
                    GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
                done(AgentResult(true, ok, if (ok) "Akcja systemowa przyjęta." else
                    "Android odrzucił akcję systemową."))
            }
            "SCROLL_UP", "SCROLL_DOWN" -> {
                val forward = t == "SCROLL_DOWN"
                fun findScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
                    if (depth > 16 || node.isPassword) return null
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        val found = findScrollable(child, depth + 1)
                        if (found != null) return found
                    }
                    return if (node.isScrollable && node.isVisibleToUser) node else null
                }
                val scroller = findScrollable(root, 0)
                val nativeAction = if (forward)
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (scroller?.performAction(nativeAction) == true) {
                    done(AgentResult(true, true, "Wykonano przewinięcie."))
                    return
                }
                val width = resources.displayMetrics.widthPixels.toFloat()
                val height = resources.displayMetrics.heightPixels.toFloat()
                val path = Path().apply {
                    moveTo(width * .52f, height * if (forward) .78f else .32f)
                    lineTo(width * .52f, height * if (forward) .32f else .78f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 490L)).build()
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        done(AgentResult(true, true, "Gest przewinięcia zakończony."))
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        done(AgentResult(true, false, "Android przerwał gest."))
                    }
                }, main)
                if (!accepted) done(AgentResult(true, false, "Android nie przyjął gestu."))
            }
            else -> done(AgentResult(true, false, "Nieobsługiwana akcja."))
        }
    }

    fun approve(
        action: AgentCommand,
        observed: AgentScreen?,
        step: Int,
        done: (AgentResult) -> Unit
    ) {
        main.post {
            if (isAwaiting) {
                done(AgentResult(false, false, "Inna akcja czeka na zatwierdzenie."))
                return@post
            }
            isAwaiting = true
            val node = observed?.nodes?.firstOrNull { it.id == action.target }
            val details = when (action.action) {
                "TAP" -> "Kliknięcie: " + (node?.label ?: "nieznany element")
                "TYPE" -> "Wpisanie tekstu: " + action.text.take(140)
                "OPEN_APP" -> "Otwórz aplikację: " + action.app
                "SEARCH_WEB" -> "Wyszukaj w Chrome: " + action.text.take(160)
                "PRESS_ENTER" -> "Enter w polu: " + (node?.label ?: "nieznane")
                else -> action.action
            }
            val elevated = AgentProtocol.elevated(action, observed)
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xf01d2b3d.toInt())
                setPadding(18, 24, 18, 16)
            }
            panel.addView(TextView(this).apply {
                text = "Pixel Agent 1.0 | krok " + step +
                    "\n" + details + "\n" + action.reason.take(110) +
                    if (elevated) "\nWYMAGA PODWÓJNEGO POTWIERDZENIA" else "\nPotwierdź działanie"
                setTextColor(-1)
                textSize = 16f
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            var confirmations = 0
            val allow = Button(this).apply { text = "ZEZWÓL" }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM }
            val finished = AtomicBoolean(false)
            val timeout = Runnable {
                if (finished.compareAndSet(false, true)) {
                    try { wm.removeView(panel) } catch (_: Exception) {}
                    approvalPanel = null; isAwaiting = false
                    done(AgentResult(false, false, "Brak zgody przez 90 sekund."))
                }
            }
            fun close(approved: Boolean) {
                if (!finished.compareAndSet(false, true)) return
                main.removeCallbacks(timeout)
                try { wm.removeView(panel) } catch (_: Exception) {}
                approvalPanel = null
                isAwaiting = false
                if (!approved) {
                    done(AgentResult(false, false, "Użytkownik odrzucił działanie."))
                } else execute(action, observed, done)
            }
            row.addView(Button(this).apply {
                text = "STOP"
                setOnClickListener { close(false); stopRequest?.invoke() }
            })
            allow.setOnClickListener {
                if (elevated && confirmations++ == 0) {
                    allow.text = "POTWIERDŹ PONOWNIE"
                } else close(true)
            }
            row.addView(allow)
            panel.addView(row)
            try {
                wm.addView(panel, params)
                approvalPanel = panel
                main.postDelayed(timeout, 90000L)
            } catch (e: Exception) {
                isAwaiting = false
                done(AgentResult(false, false, "Nie wyświetlono zgody: " + e.javaClass.simpleName))
            }
        }
    }
}
