package pl.pixelagent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 45, 30, 35)
        }
        scroll.addView(layout)
        fun text(value: String, size: Float = 16f) {
            layout.addView(TextView(this).apply {
                this.text = value
                textSize = size
                setPadding(0, 8, 0, 12)
            })
        }
        fun button(value: String, onTap: () -> Unit) {
            layout.addView(Button(this).apply {
                this.text = value
                setOnClickListener { onTap() }
            })
        }
        text("PIXEL AGENT", 24f)
        text("v0.4 - test sterowania Chrome bez konta OpenAI. Nie wysyła żadnych danych do internetu.")
        button("1. Otwórz ustawienia Dostępności") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Test Chrome: odczyt + zatwierdzony powrót") {
            val service = PhoneService.current
            if (service == null) {
                report("Włącz usługę Pixel Agent w ustawieniach Dostępności.")
                return@button
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org"))
                    .setPackage("com.android.chrome"))
                report("Uruchomiono Chrome. Za chwilę pojawi się prośba o zgodę.")
                handler.postDelayed({
                    val observed = service.readChrome()
                    if (!observed.first) {
                        report("Odczyt Chrome nieudany: " + observed.second)
                    } else {
                        report("Odczyt Chrome działa: " + observed.second)
                        service.askToGoBack(
                            "Odczytano ekran Chrome. Zezwalasz Pixel Agent na wykonanie Wstecz?",
                            { outcome -> report(outcome) }
                        )
                    }
                }, 2200)
            } catch (error: Exception) {
                report("Nie mogę uruchomić Chrome: " + error.message)
            }
        }
        text("W każdym momencie możesz odmówić. Ten test nie klika w zawartość stron i nie wysyła wiadomości.")
        statusView = TextView(this).apply {
            text = "Status: oczekiwanie"
            textSize = 16f
            setTextIsSelectable(true)
        }
        layout.addView(statusView)
        setContentView(scroll)
    }

    private fun report(message: String) {
        runOnUiThread { statusView.text = "Status: " + message }
    }
}
