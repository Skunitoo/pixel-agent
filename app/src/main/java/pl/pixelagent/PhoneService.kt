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
