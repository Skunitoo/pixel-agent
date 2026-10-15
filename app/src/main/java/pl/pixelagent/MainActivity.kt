package pl.pixelagent

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private lateinit var ai: ChatGptClient
    private lateinit var status: TextView
    private lateinit var goalInput: EditText
    private lateinit var appSpinner: Spinner
    private lateinit var budgetSpinner: Spinner
    private var apps: List<AgentApp> = emptyList()
    private val generation = AtomicInteger(0)
    private val history = ArrayList<String>()
    private var isRunning = false
    private var maxSteps = 20
    private var currentGoal = ""
    private var waitingCount = 0
    private var loadingCount = 0
    private var unchangedCount = 0
    private var previousHash = ""
    private val timeline = StringBuilder()
    private val local by lazy { getSharedPreferences("agent_local_history", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ai = ChatGptClient(this)
        apps = discoverApps()
        val root = ScrollView(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 30, 26, 40)
        }
        root.addView(panel)

        fun title(text: String, size: Float = 16f) {
            panel.addView(TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(Color.rgb(34, 45, 62))
                setPadding(0, 8, 0, 10)
            })
        }
        fun button(text: String, onClick: () -> Unit) {
            panel.addView(Button(this).apply {
                this.text = text
                setOnClickListener { onClick() }
            })
        }

        title("PIXEL AGENT 1.0", 25f)
        title("Osobisty agent na Androida. Odczytuje interfejs wybranych aplikacji i wykonuje zatwierdzone działania. Bez serwera, roota i klucza API.")
        title("1. Połączenie z ChatGPT")
        button(if (ai.isSignedIn()) "CHATGPT POŁĄCZONY - ZALOGUJ PONOWNIE"
            else "CONTINUE WITH CHATGPT") {
            log("Otwieram logowanie OpenAI...")
            network.execute {
                try {
                    ai.signIn(
                        openBrowser = { url ->
                            runOnUiThread {
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)))
                                } catch (e: Exception) {
                                    log("Przeglądarka OAuth: " + message(e))
                                }
                            }
                        },
                        report = { log(it) }
                    )
                } catch (e: Exception) { log("OAuth: " + message(e)) }
            }
        }
        button("USTAWIENIA DOSTĘPNOŚCI") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        title("2. Wybierz aplikację początkową")
        appSpinner = Spinner(this)
        val labels = apps.map { it.label + " (" + it.packageName + ")" }
        appSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, labels)
        panel.addView(appSpinner)
        val defaultIndex = apps.indexOfFirst { it.packageName == "com.android.chrome" }
        if (defaultIndex >= 0) appSpinner.setSelection(defaultIndex)

        title("3. Polecenie")
        goalInput = EditText(this).apply {
            minLines = 3
            maxLines = 7
            setSingleLine(false)
            setText("Otwórz Chrome, znajdź artykuł o Androidzie w Wikipedii i podaj tytuł, a potem zakończ.")
            hint = "Np. Otwórz YouTube, wyszukaj kanał o technologii i otwórz pierwszy wynik"
        }
        panel.addView(goalInput)
        title("Limit działań")
        budgetSpinner = Spinner(this)
        budgetSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("12 kroków", "20 kroków", "30 kroków"))
        budgetSpinner.setSelection(1)
        panel.addView(budgetSpinner)

        button("URUCHOM AGENTA") { startAgent() }
        button("STOP - ZATRZYMAJ NATYCHMIAST") { stopAgent("Zatrzymano ręcznie.") }
        button("TEST POŁĄCZENIA CHATGPT") {
            network.execute {
                try { log("OpenAI: " + ai.infer("Odpowiedz dokładnie: Pixel Agent 1.0 działa.").take(250)) }
                catch (e: Exception) { log("Model: " + message(e)) }
            }
        }
        button("WYLOGUJ CHATGPT NA TELEFONIE") {
            if (isRunning) {
                log("Najpierw zatrzymaj zadanie.")
            } else {
                ai.signOut()
                log("Lokalne tokeny usunięte.")
            }
        }
        title("BEZPIECZEŃSTWO: każda akcja wymaga zgody. Wysyłane do OpenAI są opisy widocznych elementów wybranej aplikacji. Agent odmawia obsługi aplikacji finansowych, menedżerów haseł i pól z hasłami. Nie wpisuj poufnych danych w polecenie.", 13f)
        title("Dziennik działania")
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(34, 45, 62))
            setTextIsSelectable(true)
            setPadding(8, 18, 8, 25)
        }
        panel.addView(status)
        setContentView(root)
        timeline.append(local.getString("last_log", "")?.takeLast(3000).orEmpty())
        if (timeline.isEmpty()) log("Gotowy. Znaleziono aplikacji: " + apps.size)
        else status.text = timeline.toString()
    }

    @Suppress("DEPRECATION")
    private fun discoverApps(): List<AgentApp> {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(probe, 0)
        val found = matches.mapNotNull {
            val pkg = it.activityInfo?.packageName ?: return@mapNotNull null
            val name = it.loadLabel(packageManager).toString().trim()
            if (name.isBlank() || AgentProtocol.forbidden(pkg, name)) null
            else AgentApp(name, pkg)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        return found
    }

    private fun launch(app: AgentApp): Boolean {
        if (AgentProtocol.forbidden(app.packageName, app.label)) return false
        return try {
            val intent = packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            true
        } catch (e: Exception) {
            log("Nie otwarto " + app.label + ": " + message(e))
            false
        }
    }

    private fun startAgent() {
        if (isRunning) { log("Agent już działa."); return }
        val svc = AgentAccessibilityService.instance
        if (svc == null) { log("Włącz Pixel Agent w ustawieniach Dostępności."); return }
        if (!ai.isSignedIn()) { log("Najpierw połącz ChatGPT."); return }
        val goal = goalInput.text.toString().trim()
        if (goal.length !in 5..700) {
            log("Polecenie musi mieć od 5 do 700 znaków."); return
        }
        val app = apps.getOrNull(appSpinner.selectedItemPosition)
        if (app == null) { log("Wybierz dostępną aplikację."); return }
        maxSteps = when (budgetSpinner.selectedItemPosition) { 0 -> 12; 2 -> 30; else -> 20 }
        val gen = generation.incrementAndGet()
        isRunning = true
        currentGoal = goal
        waitingCount = 0
        loadingCount = 0
        unchangedCount = 0
        previousHash = ""
        history.clear()
        timeline.clear()
        log("START: " + app.label + ". Maksymalnie " + maxSteps + " kroków.")
        svc.showStatus("Uruchamiam " + app.label) { runOnUiThread { stopAgent("STOP na nakładce.") } }
        if (!launch(app)) { finish("Nie można uruchomić aplikacji."); return }
        main.postDelayed({ next(gen, 1) }, 1600)
    }

    private fun next(gen: Int, step: Int) {
        if (!active(gen)) return
        if (step > maxSteps) { finish("Limit kroków osiągnięty. Sprawdź rezultat."); return }
        val svc = AgentAccessibilityService.instance
        if (svc == null) { finish("Usługa Dostępności została wyłączona."); return }
        val screen = svc.observe()
        if (screen == null) {
            if (loadingCount++ < 8) {
                svc.showStatus("Czekam na widoczny interfejs " + (loadingCount) + "/8") {
                    runOnUiThread { stopAgent("STOP na nakładce.") }
                }
                main.postDelayed({ next(gen, step) }, 950)
            } else finish("Nie mogę odczytać okna lub aplikacja jest chroniona.")
            return
        }
        loadingCount = 0
        val currentApp = apps.firstOrNull { it.packageName == screen.packageName }
        if (currentApp == null || AgentProtocol.forbidden(screen.packageName, currentApp.label)) {
            finish("Przełączono na aplikację poza listą dozwolonych. Zatrzymano.")
            return
        }
        if (screen.fingerprint == previousHash) {
            unchangedCount++
        } else unchangedCount = 0
        previousHash = screen.fingerprint
        if (unchangedCount >= 4) {
            finish("Ekran nie zmienia się od kilku kroków. Zatrzymano zamiast powtarzać działania.")
            return
        }
        val label = currentApp.label + " | " + step + "/" + maxSteps
        log("Analizuję " + label + "; elementów: " + screen.nodes.size)
        svc.showStatus("Analiza: " + label) {
            runOnUiThread { stopAgent("STOP na nakładce.") }
        }
        val input = AgentProtocol.prompt(currentGoal, screen, apps, history, step, maxSteps)
        network.execute {
            try {
                val response = ai.infer(input)
                val command = AgentProtocol.parse(response)
                runOnUiThread {
                    if (!active(gen)) return@runOnUiThread
                    val problem = AgentProtocol.validate(command, screen, apps)
                    if (problem != null) {
                        finish("Niebezpieczna lub błędna akcja: " + problem)
                        return@runOnUiThread
                    }
                    log("AI: " + command.action +
                        (if (command.target >= 0) " [" + command.target + "]" else "") +
                        " | " + command.reason)
                    when (command.action) {
                        "STOP" -> finish("Wynik AI: " + (command.result.ifBlank { command.reason }).take(1200))
                        "WAIT" -> {
                            if (++waitingCount > 3) {
                                finish("AI zbyt długo czeka bez działania.")
                            } else {
                                history.add("WAIT: czekanie na zmianę")
                                main.postDelayed({ next(gen, step + 1) }, 1500)
                            }
                        }
                        else -> {
                            waitingCount = 0
                            svc.showStatus("Zatwierdź: " + command.action) {
                                runOnUiThread { stopAgent("STOP na nakładce.") }
                            }
                            svc.approve(command, screen, step) { outcome ->
                                runOnUiThread {
                                    if (!active(gen)) return@runOnUiThread
                                    log("Android: " + outcome.detail)
                                    if (!outcome.approved || !outcome.successful) {
                                        finish("Nie wykonano kroku: " + outcome.detail)
                                        return@runOnUiThread
                                    }
                                    if (command.action == "OPEN_APP") {
                                        val chosen = apps.firstOrNull {
                                            it.label.equals(command.app, true) ||
                                                it.packageName == command.app
                                        }
                                        if (chosen == null || !launch(chosen)) {
                                            finish("Nie otwarto wskazanej aplikacji.")
                                            return@runOnUiThread
                                        }
                                    }
                                    if (command.action == "SEARCH_WEB") {
                                        val uri = android.net.Uri.parse("https://www.google.com/search")
                                            .buildUpon().appendQueryParameter("q", command.text).build()
                                        try {
                                            val web = Intent(Intent.ACTION_VIEW, uri)
                                            if (apps.any { it.packageName == "com.android.chrome" }) {
                                                web.setPackage("com.android.chrome")
                                            }
                                            startActivity(web)
                                        } catch (error: Exception) {
                                            finish("Nie uruchomiono wyszukiwania: " + message(error))
                                            return@runOnUiThread
                                        }
                                    }
                                    history.add(command.action +
                                        (if (command.action == "OPEN_APP") " " + command.app else "") +
                                        ": przyjęte przez Androida")
                                    if (history.size > 18) history.removeAt(0)
                                    main.postDelayed({ next(gen, step + 1) },
                                        if (command.action == "OPEN_APP") 1750 else 1250)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (active(gen)) finish("Błąd OpenAI/planera: " + message(e))
                }
            }
        }
    }

    private fun active(gen: Int): Boolean = isRunning && generation.get() == gen

    private fun stopAgent(reason: String) {
        if (isRunning) {
            generation.incrementAndGet()
            isRunning = false
        }
        AgentAccessibilityService.instance?.hideAll()
        log(reason)
    }

    private fun finish(reason: String) {
        isRunning = false
        generation.incrementAndGet()
        AgentAccessibilityService.instance?.hideAll()
        log("KONIEC: " + reason)
    }

    private fun log(msg: String) {
        runOnUiThread {
            timeline.append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                .format(java.util.Date()))
                .append("  ").append(msg.take(330)).append("\n\n")
            if (timeline.length > 13000) timeline.delete(0, timeline.length - 9500)
            if (::status.isInitialized) status.text = timeline.toString().takeLast(8500)
            local.edit().putString("last_log", timeline.toString().takeLast(8500)).apply()
        }
    }

    private fun message(e: Exception): String =
        (e.message ?: e.javaClass.simpleName).take(220)

    override fun onDestroy() {
        generation.incrementAndGet()
        isRunning = false
        AgentAccessibilityService.instance?.hideAll()
        network.shutdownNow()
        super.onDestroy()
    }
}
