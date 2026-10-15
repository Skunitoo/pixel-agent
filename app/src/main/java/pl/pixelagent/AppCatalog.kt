package pl.pixelagent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/** Discovers eligible installed apps for private, sideloaded multi-app automation. */
object AppCatalog {
    data class App(val label: String, val pkg: String)
    private val excluded = Regex(
        "(?i)(^pl\\.pixelagent|\\.inputmethod|keyboard|systemui|\\.launcher|" +
        "bank|wallet|payment|paypal|revolut|zen\\.com|xtb|broker|trading|" +
        "binance|kraken|coinbase|metamask|password|authenticator|" +
        "bitwarden|lastpass|onepassword|pl\\.pkobp|pl\\.pekao|" +
        "ingbank|alior|santander|health|medical|gov\\.pl|mobywatel)"
    )
    private val sensitiveNames = Regex(
        "(?i)(bank|wallet|portfel|płatno|payment|finanse|finance|" +
        "broker|trading|giełda|krypto|crypto|authenticator|password|" +
        "hasła|zdrowie|health|medical|medyczn)"
    )
    private val fallback = listOf(
        App("ChatGPT", "com.openai.chatgpt"),
        App("Telegram", "org.telegram.messenger"),
        App("Chrome", "com.android.chrome"),
        App("YouTube", "com.google.android.youtube"),
        App("Gmail", "com.google.android.gm"),
        App("Instagram", "com.instagram.android"),
        App("WhatsApp", "com.whatsapp"),
        App("Google Maps", "com.google.android.apps.maps"),
        App("Ustawienia", "com.android.settings")
    )
    fun restricted(pkg: String) = excluded.containsMatchIn(pkg)

    // UI callers only read a fast immutable snapshot; PackageManager discovery
    // runs once on a worker thread, never on Android's accessibility/UI thread.
    @Volatile private var cachedApps: List<App> = fallback
    private val discoveryStarted = AtomicBoolean(false)

    fun preload(context: Context) {
        if (!discoveryStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            try {
                cachedApps = discover(appContext)
            } catch (_: Exception) {
                cachedApps = fallback
            }
        }, "PixelAgent-AppDiscovery").apply {
            isDaemon = true
            start()
        }
    }

    fun installed(context: Context): List<App> = cachedApps

    // IMPORTANT: This function must only be called from preload's worker thread.
    private fun discover(context: Context): List<App> {
        val pm = context.packageManager
        val result = ArrayList<App>()
        try {
            val infos = if (Build.VERSION.SDK_INT >= 33)
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (info in infos) {
                val pkg = info.packageName
                if (restricted(pkg)) continue
                val label = try { pm.getApplicationLabel(info).toString().trim() }
                    catch (_: Exception) { pkg }
                if (label.isBlank() || sensitiveNames.containsMatchIn(label)) continue
                if (pkg != "com.android.settings" && pm.getLaunchIntentForPackage(pkg) == null) continue
                result.add(App(label.take(60), pkg))
            }
        } catch (_: Exception) {}
        for (item in fallback) {
            if (result.none { it.pkg == item.pkg } && !restricted(item.pkg)) {
                try {
                    if (item.pkg == "com.android.settings" ||
                        pm.getLaunchIntentForPackage(item.pkg) != null) result.add(item)
                } catch (_: Exception) {}
            }
        }
        return result.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun canLaunch(context: Context, pkg: String): Boolean =
        !restricted(pkg) && installed(context).any { it.pkg == pkg }

    fun allowedPackages(context: Context, current: String?): Set<String> {
        val apps = installed(context).map { it.pkg }.toMutableSet()
        if (current != null && !restricted(current)) apps.add(current)
        return apps
    }
    fun displayName(context: Context, pkg: String): String =
        installed(context).firstOrNull { it.pkg == pkg }?.label ?: pkg

    fun availableForModel(context: Context, goal: String, current: String?): String {
        val words = goal.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }.toSet()
        return installed(context).sortedByDescending {
            (if (it.pkg == current) 300 else 0) +
            (if (it.pkg == "com.openai.chatgpt") 100 else 0) +
            (if (it.pkg == "org.telegram.messenger") 80 else 0) +
            words.count { w -> it.label.lowercase().contains(w) || it.pkg.contains(w) } * 20
        }.take(110).joinToString("\n") { it.label + " => " + it.pkg }.take(8000)
    }
}
