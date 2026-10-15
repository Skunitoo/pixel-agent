package pl.pixelagent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** One-time onboarding and emergency controls. Commands are entered through ✦ overlay. */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var client: ChatGptClient
    private lateinit var status: TextView
    private val history = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = ChatGptClient(this)
        window.statusBarColor = Color.rgb(13, 23, 38)
        val shell = ScrollView(this).apply { setBackgroundColor(Color.rgb(247, 249, 253)) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(30))
        }
        shell.addView(layout)
        fun text(value: String, size: Float, muted: Boolean = false) {
            layout.addView(TextView(this).apply {
                this.text = value
                textSize = size
                setTextColor(if (muted) Color.rgb(102, 118, 137) else Color.rgb(23, 41, 60))
                setPadding(0, dp(6), 0, dp(12))
            })
        }
        fun button(value: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                this.text = value
                setOnClickListener { action() }
            })
        }
        text("✦ Pixel Agent", 28f)
        text("v1.2  ·  Android Autopilot  ·  ChatGPT OAuth", 14f, true)
        text("Nie otwieraj tej aplikacji, żeby wpisywać polecenia. Korzystaj z pływającego przycisku ✦, który pojawia się nad telefonem.", 17f)
        text("Agent zaczyna w aplikacji widocznej na ekranie i sam może otwierać kolejne aplikacje. Nie ma wyboru aplikacji startowej.", 15f)

        text("Pierwsza konfiguracja", 19f)
        button("1. Włącz usługę Dostępności") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Continue with ChatGPT") {
            report("Uruchamiam logowanie OpenAI...")
            worker.execute {
                try {
                    client.signIn(
                        openBrowser = { url -> runOnUiThread {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }},
                        report = { report(it) }
                    )
                } catch (e: Exception) {
                    report("OAuth: " + (e.message ?: e.javaClass.simpleName).take(220))
                }
            }
        }
        button("Sprawdź połączenie z OpenAI") {
            worker.execute {
                try {
                    report("Model: " + client.infer("Odpowiedz dokładnie: Pixel Agent działa.").take(240))
                } catch (e: Exception) {
                    report("Błąd modelu: " + (e.message ?: e.javaClass.simpleName).take(220))
                }
            }
        }
        text("Codzienne używanie", 19f)
        text("1. Otwórz dowolną obsługiwaną aplikację.\n2. Dotknij ✦ i wpisz polecenie.\n3. Naciśnij Uruchom.\n4. Autopilot działa, a czerwony STOP AI pozwala natychmiast zakończyć zadanie.", 15f)
        button("STOP - przerwij zadanie") {
            PhoneService.current?.stopOverlayAgent()
            report("Zatrzymanie zlecone.")
        }
        button("Wyloguj lokalnie") {
            PhoneService.current?.stopOverlayAgent()
            client.signOut()
            report("Lokalne tokeny usunięte.")
        }

        text("Prywatność i ograniczenia", 19f)
        text("Odczytane informacje z ekranu są wysyłane do modelu OpenAI. Nie uruchamiaj agenta na wrażliwych ekranach. Banki, brokerzy, portfele, hasła i aplikacje zdrowotne są wyłączane filtrami, które nie dają absolutnej gwarancji. Wysyłka do rozmowy z botem Folk jest jednorazowa - agent nie ponawia jej po niepewnym wyniku.", 14f, true)
        text("Status", 19f)
        status = TextView(this).apply {
            text = if (client.isSignedIn()) "ChatGPT połączony." else "Wymagane logowanie ChatGPT."
            textSize = 14f
            setTextColor(Color.rgb(30, 48, 68))
            setPadding(dp(13), dp(12), dp(13), dp(12))
            setTextIsSelectable(true)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
        }
        layout.addView(status)
        setContentView(shell)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun report(message: String) {
        runOnUiThread {
            history.append(message.take(320)).append("\n\n")
            if (history.length > 5500) history.delete(0, 3000)
            if (::status.isInitialized) status.text = history.toString().takeLast(2800)
        }
    }
}
