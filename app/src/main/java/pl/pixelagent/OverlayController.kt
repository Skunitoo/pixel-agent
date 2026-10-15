package pl.pixelagent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.text.InputType
import kotlin.math.abs

/**
 * Small floating launcher and task composer. All windows belong to the
 * accessibility service and can appear above supported apps without opening
 * the Pixel Agent activity. No text is sent to an AI until RUN is pressed.
 */
class OverlayController(
    private val service: PhoneService,
    private val startTask: (String, String) -> Unit
) {
    private val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val ink = Color.rgb(237, 242, 253)
    private val muted = Color.rgb(160, 178, 198)
    private val panelColor = Color.rgb(17, 27, 43)
    private val fieldColor = Color.rgb(34, 49, 68)
    private val accent = Color.rgb(88, 183, 249)
    private var bubble: TextView? = null
    private var composer: LinearLayout? = null
    private var bubbleOffsetY = dp(225)

    private fun dp(x: Int) = (x * density).toInt()

    private fun background(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    @Suppress("ClickableViewAccessibility")
    fun showBubble() {
        handler.post {
            if (bubble != null || composer != null) return@post
            val glyph = TextView(service).apply {
                text = "✦"
                gravity = Gravity.CENTER
                textSize = 29f
                setTextColor(ink)
                background = background(panelColor, 24, Color.rgb(75, 100, 140))
                elevation = dp(12).toFloat()
                contentDescription = "Otwórz Pixel Agent"
            }
            val params = WindowManager.LayoutParams(
                dp(54), dp(54),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.RIGHT
                x = dp(12)
                y = bubbleOffsetY
            }
            var downY = 0f
            var originY = 0
            var dragged = false
            glyph.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.rawY
                        originY = params.y
                        dragged = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = event.rawY - downY
                        if (abs(delta) > dp(8)) dragged = true
                        if (dragged) {
                            params.y = (originY + delta.toInt()).coerceIn(
                                dp(72), service.resources.displayMetrics.heightPixels - dp(130)
                            )
                            try { manager.updateViewLayout(glyph, params) } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        bubbleOffsetY = params.y
                        if (!dragged) {
                            view.performClick()
                            openComposer()
                        }
                        true
                    }
                    else -> false
                }
            }
            try {
                manager.addView(glyph, params)
                bubble = glyph
            } catch (_: Exception) {}
        }
    }

    fun hideBubble() {
        handler.post {
            val v = bubble ?: return@post
            bubble = null
            try { manager.removeView(v) } catch (_: Exception) {}
        }
    }

    fun closeComposer() {
        handler.post {
            val v = composer ?: return@post
            val input = v.getTag() as? EditText
            if (input != null) {
                val keyboard = service.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                keyboard.hideSoftInputFromWindow(input.windowToken, 0)
            }
            composer = null
            try { manager.removeView(v) } catch (_: Exception) {}
            showBubble()
        }
    }

    private fun openComposer() {
        hideBubble()
        val appBefore = service.launchContextPackage()
        if (appBefore == null || AppCatalog.restricted(appBefore)) {
            service.status("Otwórz aplikację, którą mam obsługiwać, a potem dotknij ✦.")
            showBubble()
            return
        }
        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            background = background(panelColor, 24, Color.rgb(65, 84, 113))
            elevation = dp(18).toFloat()
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(service).apply {
            text = "✦  Pixel Agent"
            textSize = 19f
            setTextColor(ink)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        header.addView(TextView(service).apply {
            text = "×"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(muted)
            contentDescription = "Zamknij nakładkę"
            setOnClickListener { closeComposer() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        content.addView(header)

        content.addView(TextView(service).apply {
            text = "Teraz: " + AppCatalog.displayName(service, appBefore)
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(1), 0, dp(12))
        })


        val editor = EditText(service).apply {
            hint = "Co mam teraz zrobić na telefonie?"
            setHintTextColor(muted)
            setTextColor(ink)
            textSize = 16f
            minLines = 2
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = background(fieldColor, 13)
        }
        content.addView(editor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        content.setTag(editor)

        val controls = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val explanation = TextView(service).apply {
            text = "Autopilot  ·  STOP na ekranie"
            textSize = 11f
            setTextColor(muted)
        }
        controls.addView(explanation, LinearLayout.LayoutParams(0, dp(48), 1f))
        val run = TextView(service).apply {
            text = "Uruchom  ↗"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(15, 29, 45))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = background(accent, 13)
            contentDescription = "Uruchom polecenie"
            setOnClickListener {
                val prompt = editor.text.toString().trim()
                if (prompt.length !in 4..700) {
                    editor.error = "Wpisz polecenie (4-700 znaków)."
                } else {
                    closeComposer()
                    handler.postDelayed({ startTask(prompt, appBefore) }, 350)
                }
            }
        }
        controls.addView(run, LinearLayout.LayoutParams(dp(118), dp(46)))
        content.addView(controls)

        val width = service.resources.displayMetrics.widthPixels - dp(32)
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(78)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try {
            manager.addView(content, params)
            composer = content
            editor.requestFocus()
            handler.postDelayed({
                if (composer === content) {
                    val keyboard = service.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as InputMethodManager
                    keyboard.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 180)
        } catch (_: Exception) {
            showBubble()
        }
    }

    fun destroy() {
        handler.post {
            closeComposer()
            hideBubble()
        }
    }
}
