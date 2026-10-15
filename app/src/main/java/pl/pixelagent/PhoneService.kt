package pl.pixelagent

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView


// Target snapshot: candidate identity must be re-verified immediately before action.
data class ChromeTarget(val id: Int, val label: String, val box: android.graphics.Rect)
data class ChromeActionSnapshot(val text: String, val targets: List<ChromeTarget>)

class PhoneService : AccessibilityService() {
    companion object {
        @Volatile var current: PhoneService? = null
            private set
    }
    private val handler = Handler(Looper.getMainLooper())
    private val windowManagerRef by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private var progressView: TextView? = null
    private var hideProgress: Runnable? = null
    fun showAgentNotice(message: String, duration: Long = 90000L) {
        handler.post {
            hideProgress?.let { handler.removeCallbacks(it) }
            var view = progressView
            if (view == null) {
                view = TextView(this).apply {
                    setBackgroundColor(0xe51d314a.toInt())
                    setTextColor(-1)
                    textSize = 14f
                    setPadding(20, 12, 20, 12)
                }
                val layout = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP
                    y = (resources.displayMetrics.density * 75).toInt()
                }
                try { windowManagerRef.addView(view, layout); progressView = view }
                catch (_: Exception) { return@post }
            }
            view.text = message.take(300)
            val timeout = Runnable { clearAgentNotice() }
            hideProgress = timeout
            handler.postDelayed(timeout, duration)
        }
    }
    fun clearAgentNotice() {
        handler.post {
            hideProgress?.let { handler.removeCallbacks(it) }
            hideProgress = null
            val view = progressView ?: return@post
            progressView = null
            try { windowManagerRef.removeView(view) } catch (_: Exception) {}
        }
    }


    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    fun readChrome(): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return false to "Brak aktywnego okna."
        if (root.packageName?.toString() != "com.android.chrome") {
            return false to "Aktywna aplikacja to " + root.packageName
        }
        var count = 0
        var visibleElements = 0
        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 15 || count >= 200) return
            count++
            if (!node.isPassword && (node.isClickable || node.isEditable || node.text != null)) {
                visibleElements++
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }
        traverse(root, 0)
        return true to ("rozpoznano " + count + " elementów, w tym " + visibleElements + " interaktywnych lub tekstowych.")
    }


    // Only the explicitly launched Chrome test page is sent to the model.
    // Never collect passwords, editable fields, or text from other apps.
    fun readChromeForAi(): Pair<Boolean, String> {
        val root = rootInActiveWindow ?: return false to "Brak aktywnego okna"
        if (root.packageName?.toString() != "com.android.chrome") return false to "To nie Chrome"
        val found = ArrayList<String>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || visited >= 120 || found.size >= 24) return
            visited++
            if (!node.isPassword && !node.isEditable) {
                val content = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
                if (content.isNotEmpty() && !content.contains("http") && content.length < 120)
                    found.add(content.take(65))
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return true to (if (found.isEmpty()) "Test Chrome: brak etykiet" else found.joinToString(" | ").take(900))
    }



    // Returns only visible, non-editable elements of Chrome. Never scans other packages.
    fun inspectChromeActions(): ChromeActionSnapshot? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != "com.android.chrome") return null
        val found = LinkedHashSet<String>()
        val candidates = ArrayList<ChromeTarget>()
        var count = 0
        val minTop = (resources.displayMetrics.density * 95).toInt()
        val ban = Regex(
            "^(login|log in|sign in|sign up|konto|profil|wyloguj|zaloguj|rejestracja|" +
            "usuń|delete|send|wyślij|submit|potwierdź|purchase|checkout|kup|zamów|płać|" +
            "ustawienia|settings|download|pobierz|zapisz|save|udostępnij|share|akceptuję)",
            RegexOption.IGNORE_CASE
        )
        fun describe(node: AccessibilityNodeInfo): String {
            val raw = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (raw.isNotBlank()) return raw.replace(Regex("\\s+"), " ").take(74)
            // Link text may be several levels below a clickable parent.
            var visited = 0
            fun descend(parent: AccessibilityNodeInfo, depth: Int): String {
                if (depth > 3 || visited++ > 25) return ""
                for (i in 0 until minOf(parent.childCount, 10)) {
                    val child = parent.getChild(i) ?: continue
                    if (child.isPassword || child.isEditable) continue
                    val t = (child.text ?: child.contentDescription)?.toString()?.trim().orEmpty()
                    if (t.isNotBlank()) return t.replace(Regex("\\s+"), " ").take(74)
                    val nested = descend(child, depth + 1)
                    if (nested.isNotBlank()) return nested
                }
                return ""
            }
            return descend(node, 0)
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int, inSensitiveNode: Boolean) {
            if (depth > 14 || count++ > 220 || candidates.size >= 22 && found.size >= 35) return
            if (!node.isVisibleToUser) return
            val sensitive = inSensitiveNode || node.isPassword || node.isEditable ||
                node.className?.toString()?.contains("EditText", true) == true
            if (sensitive) return
            val caption = describe(node)
            if (caption.isNotBlank() && !caption.contains("http", true) && found.size < 35) {
                found.add(caption.take(70))
            }
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (node.isClickable && node.isEnabled && caption.isNotBlank() &&
                !caption.contains("http", true) && !ban.containsMatchIn(caption) &&
                rect.top > minTop && rect.width() > 28 && rect.height() > 15 &&
                rect.bottom < resources.displayMetrics.heightPixels &&
                candidates.size < 22
            ) {
                candidates.add(ChromeTarget(candidates.size, caption, rect))
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1, sensitive)
            }
        }
        visit(root, 0, false)
        return ChromeActionSnapshot(
            "Tekst widoczny: " + found.take(25).joinToString(" | ").take(1000),
            candidates
        )
    }

    private fun clickCheckedTarget(expected: ChromeActionSnapshot, id: Int): Boolean {
        val target = expected.targets.firstOrNull { it.id == id } ?: return false
        val fresh = inspectChromeActions() ?: return false
        // The entire list must match, not only a potentially recycled ID.
        if (fresh.targets != expected.targets) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.android.chrome") return false
        val matches = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 15 || matches.size > 2 || node.isPassword || node.isEditable) return
            if (node.isClickable && node.isEnabled && node.isVisibleToUser) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (bounds == target.box) {
                    val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
                    if (label == target.label || label.isBlank()) {
                        matches.add(node)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
        if (matches.size != 1) return false
        return matches[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // v0.7: only permitted Chrome actions, each requiring separate approval.
    fun approveMultiStep(action: String, step: Int, snapshot: ChromeActionSnapshot, done: (Boolean, Boolean) -> Unit) {
        handler.post {
            if (rootInActiveWindow?.packageName?.toString() != "com.android.chrome") {
                done(false, false)
                return@post
            }
            if (action !in setOf("SCROLL_DOWN", "SCROLL_UP", "BACK") &&
                !(action.startsWith("TAP:") && action.substringAfter(":").toIntOrNull() in snapshot.targets.map { it.id })
            ) {
                done(false, false)
                return@post
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(25, 30, 25, 24)
                setBackgroundColor(0xff152032.toInt())
            }
            panel.addView(TextView(this).apply {
                text = "PIXEL AGENT - krok " + step + "/5\nAI wybrało: " + action +
                    "\nCzy zatwierdzasz? Odmowa zatrzymuje zadanie."
                textSize = 17f
                setTextColor(-1)
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM }
            var ended = false
            fun finish(allow: Boolean) {
                if (ended) return
                ended = true
                try { windowManagerRef.removeView(panel) } catch (_: Exception) {}
                if (!allow || rootInActiveWindow?.packageName?.toString() != "com.android.chrome") {
                    done(false, false)
                    return
                }
                if (action == "BACK") {
                    done(true, performGlobalAction(GLOBAL_ACTION_BACK))
                    return
                }
                if (action.startsWith("TAP:")) {
                    done(true, clickCheckedTarget(snapshot, action.substringAfter(":").toInt()))
                    return
                }
                val root = rootInActiveWindow
                fun findScrollable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                    if (node == null || depth > 15) return null
                    if (node.isScrollable && node.isVisibleToUser && !node.isEditable) return node
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index)
                        val found = findScrollable(child, depth + 1)
                        if (found != null) return found
                    }
                    return null
                }
                val scrollable = findScrollable(root, 0)
                val actionType = if (action == "SCROLL_DOWN")
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                val ok = scrollable?.performAction(actionType) ?: false
                if (ok) done(true, true)
                else {
                    // Chrome sometimes exposes no scrollable node: fall back to a swipe.
                    val w = resources.displayMetrics.widthPixels.toFloat()
                    val h = resources.displayMetrics.heightPixels.toFloat()
                    val path = android.graphics.Path().apply {
                        moveTo(w * 0.5f, h * if (action == "SCROLL_DOWN") 0.76f else 0.30f)
                        lineTo(w * 0.5f, h * if (action == "SCROLL_DOWN") 0.30f else 0.76f)
                    }
                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 420))
                        .build()
                    done(true, dispatchGesture(gesture, null, null))
                }
            }
            row.addView(Button(this).apply {
                text = "STOP"
                setOnClickListener { finish(false) }
            })
            row.addView(Button(this).apply {
                text = "WYKONAJ"
                setOnClickListener { finish(true) }
            })
            panel.addView(row)
            try { windowManagerRef.addView(panel, params) }
            catch (_: Exception) { done(false, false) }
        }
    }

    fun askToGoBack(description: String, done: (String) -> Unit) {
        handler.post {
            if (rootInActiveWindow?.packageName?.toString() != "com.android.chrome") {
                done("Chrome nie jest już na pierwszym planie. Nie wykonano działania.")
                return@post
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(25, 30, 25, 24)
                setBackgroundColor(0xff152032.toInt())
            }
            panel.addView(TextView(this).apply {
                text = description
                textSize = 17f
                setTextColor(-1)
                setPadding(0, 0, 0, 15)
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.BOTTOM }
            var finished = false
            fun complete(allow: Boolean) {
                if (finished) return
                finished = true
                try { windowManagerRef.removeView(panel) } catch (_: Exception) {}
                if (allow) {
                    val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                    done(if (ok) "Zatwierdzono i wykonano Wstecz. Test zakończony." else "Nie udało się wykonać Wstecz.")
                } else {
                    done("Odmówiono - nie wykonano Wstecz.")
                }
            }
            row.addView(Button(this).apply {
                text = "ODMÓW"
                setOnClickListener { complete(false) }
            })
            row.addView(Button(this).apply {
                text = "ZATWIERDŹ"
                setOnClickListener { complete(true) }
            })
            panel.addView(row)
            try { windowManagerRef.addView(panel, params) }
            catch (error: Exception) { done("Błąd nakładki: " + error.message) }
        }
    }
}
