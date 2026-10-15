package pl.pixelagent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var client: ChatGptClient
    private lateinit var statusText: TextView
    private lateinit var promptField: EditText
    private lateinit var appChooser: Spinner
    private var agent: AgentRuntime? = null
    private val history = StringBuilder()
    private val apps = listOf(
        "Chrome" to "com.android.chrome",
        "YouTube" to "com.google.android.youtube",
        "Mapy Google" to "com.google.android.apps.maps",
        "Gmail" to "com.google.android.gm",
        "Telegram" to "org.telegram.messenger",
        "Instagram" to "com.instagram.android",
        "WhatsApp" to "com.whatsapp",
        "Wiadomości Google" to "com.google.android.apps.messaging",
        "Pliki Google" to "com.google.android.apps.nbu.files",
        "Ustawienia Androida" to "com.android.settings"
    )
    private val checks = LinkedHashMap<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = ChatGptClient(this)
        window.statusBarColor = Color.rgb(16, 24, 37)
        val shell = ScrollView(this).apply { setBackgroundColor(Color.rgb(247, 249, 252)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 36, 30, 46)
        }
        shell.addView(content)
        fun heading(text: String, size: Float) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(Color.rgb(18, 33, 52))
                setPadding(0, 10, 0, 10)
            })
        }
        fun paragraph(text: String) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.rgb(77, 93, 110))
                setPadding(0, 4, 0, 12)
            })
        }
        fun button(text: String, action: () -> Unit) {
            content.addView(Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            })
        }

        heading("PIXEL AGENT", 27f)
        paragraph("Wersja 1.1 | nakładka ✦ nad ekranem | ChatGPT OAuth")
        paragraph("Aby wydać polecenie, naciśnij pływający przycisk ✦ w dowolnej dozwolonej aplikacji. Ustawienia i pierwsze logowanie wykonujesz tutaj. Autopilot pracuje sam, a STOP AI pozostaje na ekranie.")

        heading("1. Połączenie i uprawnienia", 19f)
        button("Włącz usługę Dostępności") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Continue with ChatGPT") {
            showStatus("Otwieram autoryzację OpenAI...")
            worker.execute {
                try {
                    client.signIn(
                        openBrowser = { url -> runOnUiThread {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } },
                        report = { showStatus(it) }
                    )
                } catch (error: Exception) {
                    showStatus("OAuth: " + (error.message ?: error.javaClass.simpleName).take(250))
                }
            }
        }
        button("Wyloguj się z ChatGPT") {
            agent?.stop()
            agent = null
            client.signOut()
            showStatus("Lokalne tokeny usunięte.")
        }
        heading("2. Aplikacje dostępne dla agenta", 19f)
        paragraph("Odczyt ekranów dozwolonych aplikacji trafia do modelu OpenAI. Chrome i Telegram są włączone na start. Zmiany zapisują się automatycznie.")
        for ((name, pkg) in apps) {
            val box = CheckBox(this).apply {
                text = name
                textSize = 15f
                isChecked = pkg in AgentApps.allowed(this@MainActivity)
            }
            checks[pkg] = box
            box.setOnCheckedChangeListener { _, _ ->
                AgentApps.save(this@MainActivity,
                    checks.filterValues { it.isChecked }.keys.toSet())
            }
            content.addView(box)
        }
        heading("3. Polecenie", 19f)
        paragraph("Wybierz aplikację startową:")
        appChooser = Spinner(this)
        appChooser.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, apps.map { it.first }
        )
        content.addView(appChooser)
        promptField = EditText(this).apply {
            hint = "Np. Otwórz artykuł o kosmosie i streść, co widać."
            setText("Otwórz w Chrome Wikipedię i znajdź artykuł o sztucznej inteligencji.")
            minLines = 3
            maxLines = 6
            setSingleLine(false)
            setPadding(16, 18, 16, 18)
            setBackgroundColor(Color.WHITE)
        }
        content.addView(promptField)
        button("URUCHOM AGENTA") {
            val service = PhoneService.current
            if (service == null) {
                showStatus("Włącz Pixel Agent w Dostępności Androida.")
                return@button
            }
            if (!client.isSignedIn()) {
                showStatus("Najpierw połącz ChatGPT.")
                return@button
            }
            val goal = promptField.text.toString().trim()
            if (goal.length !in 4..700) {
                showStatus("Polecenie musi mieć 4-700 znaków.")
                return@button
            }
            val authorized = checks.filterValues { it.isChecked }.keys.toSet()
            val selected = apps[appChooser.selectedItemPosition].second
            if (selected !in authorized) {
                showStatus("Zaznacz wybraną aplikację na liście dozwolonych.")
                return@button
            }
            agent?.stop()
            history.clear()
            showStatus("Uruchamiam sesję.")
            agent = AgentRuntime(this, client, service, authorized, selected, goal, true, false) {
                showStatus(it)
            }
            agent?.start()
        }
        button("STOP - NATYCHMIAST ZATRZYMAJ") {
            agent?.stop()
            agent = null
            PhoneService.current?.stopOverlayAgent()
            PhoneService.current?.closeOverlays()
            showStatus("Zatrzymano polecenie.")
        }
        paragraph("Autopilot wykonuje proste kroki sam. Wysyłanie wiadomości jest automatyczne wyłącznie dla rozpoznanej rozmowy z botem Folk. Inne działania wrażliwe mogą wymagać zgody. Płatności, trading, hasła i 2FA pozostają wyłączone. STOP AI jest widoczny nad aplikacjami.")
        heading("Dziennik wykonania", 19f)
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(30, 45, 64))
            setTextIsSelectable(true)
            setPadding(12, 12, 12, 20)
            text = if (client.isSignedIn()) "ChatGPT połączony." else "Nie połączono z ChatGPT."
        }
        content.addView(statusText)
        setContentView(shell)
    }

    private fun showStatus(message: String) {
        runOnUiThread {
            history.append(message.take(380)).append("\n\n")
            if (history.length > 6500) history.delete(0, history.length - 4000)
            if (::statusText.isInitialized) statusText.text = history.toString().takeLast(3400)
        }
    }
}
