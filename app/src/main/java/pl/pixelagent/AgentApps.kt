package pl.pixelagent

import android.content.Context

object AgentApps {
    const val PREFERENCES = "pixel_agent_configuration"
    private const val ALLOWED_KEY = "allowed_apps"

    val supported = listOf(
        "Chrome" to "com.android.chrome",
        "Telegram" to "org.telegram.messenger",
        "YouTube" to "com.google.android.youtube",
        "Mapy" to "com.google.android.apps.maps",
        "Gmail" to "com.google.android.gm",
        "Instagram" to "com.instagram.android",
        "WhatsApp" to "com.whatsapp",
        "Wiadomości" to "com.google.android.apps.messaging",
        "Pliki" to "com.google.android.apps.nbu.files",
        "Ustawienia" to "com.android.settings"
    )

    // Both apps were deliberately used with Pixel Agent in previous revisions.
    private val defaults = setOf("com.android.chrome", "org.telegram.messenger")

    fun allowed(context: Context): Set<String> {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(ALLOWED_KEY, null)
        return ((stored ?: defaults) intersect supported.map { it.second }.toSet()).toSet()
    }

    fun save(context: Context, selected: Set<String>) {
        val cleaned = selected intersect supported.map { it.second }.toSet()
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putStringSet(ALLOWED_KEY, cleaned).commit())
    }

    fun name(packageName: String): String = supported.firstOrNull {
        it.second == packageName
    }?.first ?: packageName
}
