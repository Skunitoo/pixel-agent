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

class PhoneService : AccessibilityService() {
    companion object {
        @Volatile var current: PhoneService? = null
            private set
    }
    private val handler = Handler(Looper.getMainLooper())
    private val windowManagerRef by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

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


    // v0.6: each step remains user-authorized, Chrome-only, limited to scroll/back.
    fun approveMultiStep(action: String, step: Int, done: (Boolean, Boolean) -> Unit) {
        handler.post {
            if (rootInActiveWindow?.packageName?.toString() != "com.android.chrome") {
                done(false, false)
                return@post
            }
            if (action !in setOf("SCROLL_DOWN", "SCROLL_UP", "BACK")) {
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
