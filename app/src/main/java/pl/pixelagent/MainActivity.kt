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
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private lateinit var ai: ChatGptClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ai = ChatGptClient(this)
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 40, 30, 32)
        }
        scroll.addView(layout)
        fun description(value: String, size: Float = 16f) {
            layout.addView(TextView(this).apply {
                text = value; textSize = size
                setPadding(0, 8, 0, 12)
            })
        }
        fun action(label: String, block: () -> Unit) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener { block() }
            })
        }

        description("PIXEL AGENT", 25f)
        description("v0.5 - ChatGPT OAuth oraz decyzja AI na podstawie ekranu Chrome. Wysyłamy do OpenAI wyłącznie dane z testu, nie hasła ani dane innych aplikacji.")
        action("1. Włącz Dostępność") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        action("2. Continue with ChatGPT") {
            report("Uruchamiam bezpieczne logowanie OAuth w przeglądarce...")
            background.execute {
                try {
                    ai.signIn(
                        openBrowser = { uri -> runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } },
                        report = { report(it) }
                    )
                } catch (error: Exception) { report("Logowanie: " + safeError(error)) }
            }
        }
        action("3. Test odpowiedzi OpenAI") {
            report("Sprawdzam uprawnienia i model...")
            background.execute {
                try {
                    val answer = ai.infer("Odpowiedz dokładnie: Pixel Agent działa.")
                    report("OpenAI: " + answer.take(700))
                } catch (error: Exception) { report("OpenAI: " + safeError(error)) }
            }
        }
        action("4. Test AI na Chrome (jedna zatwierdzana akcja)") {
            val service = PhoneService.current
            if (service == null) {
                report("Włącz usługę Pixel Agent w Dostępności.")
                return@action
            }
            report("Otwieram stronę testową Chrome...")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org")).setPackage("com.android.chrome"))
                handler.postDelayed({
                    val observation = service.readChromeForAi()
                    if (!observation.first) {
                        report("Nie odczytano Chrome: " + observation.second)
                    } else {
                        report("Chrome odczytany. Pytam model, czy wykonać Wstecz...")
                        background.execute {
                            try {
                                val decision = ai.infer(
                                    "Jesteś kontrolerem Androida w pojedynczym teście. Dostępne działania to BACK albo STOP. " +
                                    "Cel użytkownika: wrócić ze strony testowej Chrome do Pixel Agent. " +
                                    "Widzisz wyłącznie te publiczne elementy strony: " + observation.second +
                                    ". Odpowiedz WYŁĄCZNIE BACK lub STOP."
                                ).trim()
                                if (decision == "BACK") {
                                    service.askToGoBack(
                                        "AI wybrało BACK. Czy zatwierdzasz wykonanie Wstecz w Chrome?",
                                        { result -> report("Decyzja AI: BACK. " + result) }
                                    )
                                } else {
                                    report("AI nie wybrało BACK (wynik: " + decision.take(80) + "). Żadna akcja nie zostanie wykonana.")
                                }
                            } catch (error: Exception) { report("AI Chrome: " + safeError(error)) }
                        }
                    }
                }, 2500)
            } catch (error: Exception) { report("Nie można uruchomić Chrome: " + safeError(error)) }
        }
        action("5. Wyloguj lokalnie") {
            ai.signOut()
            report("Lokalne tokeny zostały usunięte. Zdalne upoważnienie może pozostać aktywne w ustawieniach ChatGPT.")
        }
        description("Ten build nie obsługuje innych aplikacji i nie wykonuje operacji bez zatwierdzenia. Przed próbą logowania możesz wyłączyć usługę dostępności.")
        statusView = TextView(this).apply {
            text = if (ai.isSignedIn()) "Status: zapisane połączenie ChatGPT" else "Status: niepołączony z ChatGPT"
            textSize = 16f
            setTextIsSelectable(true)
        }
        layout.addView(statusView)
        setContentView(scroll)
    }

    private fun report(message: String) {
        runOnUiThread {
            statusView.text = "Status: " + message
        }
    }
    private fun safeError(error: Exception): String =
        (error.message ?: error.javaClass.simpleName).take(350)
}
