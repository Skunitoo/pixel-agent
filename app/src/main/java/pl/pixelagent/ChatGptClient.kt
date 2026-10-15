package pl.pixelagent

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

class ChatGptClient(context: Context) {
    private val store = SecureStore(context.applicationContext)
    private val random = SecureRandom()

    fun isSignedIn(): Boolean = store.getSession()?.optString("client_id")?.isNotBlank() == true

    fun signOut() { store.signOut() }

    private fun randomUrlSafe(size: Int = 32): String {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun signIn(openBrowser: (String) -> Unit, report: (String) -> Unit) {
        val existing = store.getSession()
        val existingClientId = existing?.optString("client_id")?.takeIf { it.isNotBlank() }
        val state = randomUrlSafe()
        val nonce = randomUrlSafe()
        val verifier = randomUrlSafe(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 180000
            val redirectUri = "http://127.0.0.1:" + listener.localPort + "/auth/callback"
            val url = Uri.parse("https://auth.openai.com/api/accounts/authorize").buildUpon()
                .appendQueryParameter("client_id", existingClientId ?: "dynamic_agent_client")
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                .appendQueryParameter("resource", "https://api.openai.com/v1")
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("ext_agent_host_id", store.stableHostId())
                .apply {
                    if (existingClientId == null) appendQueryParameter("agent_name_hint", "Pixel Agent")
                }
                .build()
                .toString()
            openBrowser(url)
            val callback = listener.accept().use { socket ->
                socket.soTimeout = 10000
                val reader = socket.getInputStream().bufferedReader()
                val request = reader.readLine() ?: throw IllegalStateException("Brak adresu callback.")
                // The loopback endpoint accepts only our exact callback path.
                val requestTarget = request.split(" ").getOrNull(1) ?: ""
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                }
                val valid = requestTarget == "/auth/callback" || requestTarget.startsWith("/auth/callback?")
                val reply = if (valid)
                    "<html><body><h2>Pixel Agent</h2><p>Możesz wrócić do aplikacji.</p></body></html>"
                else "<html><body>Not found</body></html>"
                val bytes = reply.toByteArray(Charsets.UTF_8)
                val status = if (valid) "200 OK" else "404 Not Found"
                socket.getOutputStream().write(
                    ("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: " + bytes.size +
                        "\r\n\r\n").toByteArray(Charsets.US_ASCII)
                )
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
                if (!valid) throw IllegalStateException("Nieznany callback logowania.")
                Uri.parse("http://127.0.0.1" + requestTarget)
            }
            require(callback.getQueryParameter("state") == state) { "Nieprawidłowy OAuth state." }
            if (callback.getQueryParameter("error") != null)
                throw IllegalStateException("Autoryzacja odrzucona przez użytkownika lub dostawcę.")
            val code = callback.getQueryParameter("code")
                ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Nie otrzymano kodu OAuth.")
            val returnedId = callback.getQueryParameter("client_id")
            val clientId = if (existingClientId != null) {
                require(returnedId == null || returnedId == existingClientId) { "Zmieniono client_id." }
                existingClientId
            } else returnedId?.takeIf { it.startsWith("oaiapp_") }
                ?: throw IllegalStateException("Nie otrzymano wydanego identyfikatora klienta.")
            report("Kod odebrany. Weryfikuję podpis OpenAI i uprawnienia...")
            val token = OpenAiHttp.json(
                "https://auth.openai.com/api/accounts/oauth/token",
                method = "POST",
                body = OpenAiHttp.form(
                    mapOf(
                        "grant_type" to "authorization_code",
                        "client_id" to clientId,
                        "code" to code,
                        "code_verifier" to verifier,
                        "redirect_uri" to redirectUri,
                        "resource" to "https://api.openai.com/v1"
                    )
                ),
                contentType = "application/x-www-form-urlencoded"
            )
            val idToken = token.optString("id_token")
            require(idToken.isNotBlank()) { "Brak ID tokenu." }
            val claims = JwtVerifier.verifyIdToken(idToken, clientId, nonce)
            val subject = claims.getString("sub")
            val priorSubject = existing?.optString("sub")?.takeIf { it.isNotEmpty() }
            require(priorSubject == null || priorSubject == subject) { "Wybrano inne konto ChatGPT." }
            val scopes = token.optString("scope").split(" ").filter { it.isNotBlank() }
            require(scopes.contains("chatgpt.tokens.use.direct")) {
                "Nie przyznano uprawnienia do korzystania z planu ChatGPT."
            }
            val access = token.optString("access_token")
            val refresh = token.optString("refresh_token")
            require(access.isNotBlank() && refresh.isNotBlank()) { "Brak kompletnych tokenów." }
            val saved = JSONObject()
                .put("client_id", clientId)
                .put("sub", subject)
                .put("email", claims.optString("email"))
                .put("scope", token.getString("scope"))
                .put("id_token", idToken)
                .put("access_token", access)
                .put("refresh_token", refresh)
                .put("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000L)
            store.saveSession(saved)
            report("ChatGPT połączony: " + claims.optString("email", "konto OpenAI") + ". Teraz uruchom test odpowiedzi.")
        }
    }

    @Synchronized private fun accessToken(): String {
        val record = store.getSession()
            ?: throw IllegalStateException("Najpierw użyj Continue with ChatGPT.")
        require(record.optString("scope").split(" ").contains("chatgpt.tokens.use.direct")) {
            "Brak zgody na korzystanie z planu."
        }
        if (System.currentTimeMillis() + 60000L < record.optLong("expires_at")) {
            return record.getString("access_token")
        }
        val updated = OpenAiHttp.json(
            "https://auth.openai.com/api/accounts/oauth/token",
            method = "POST",
            body = OpenAiHttp.form(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to record.getString("client_id"),
                    "refresh_token" to record.getString("refresh_token"),
                    "resource" to "https://api.openai.com/v1"
                )
            ),
            contentType = "application/x-www-form-urlencoded"
        )
        val newAccess = updated.optString("access_token")
        val newRefresh = updated.optString("refresh_token")
        require(newAccess.isNotBlank() && newRefresh.isNotBlank()) { "Niekompletny refresh OpenAI." }
        record.put("access_token", newAccess)
            .put("refresh_token", newRefresh)
            .put("expires_at", System.currentTimeMillis() + updated.optLong("expires_in", 3600) * 1000L)
        if (updated.has("scope")) record.put("scope", updated.getString("scope"))
        if (updated.has("id_token")) record.put("id_token", updated.getString("id_token"))
        store.saveSession(record)
        return newAccess
    }

    fun infer(prompt: String): String {
        val token = accessToken()
        val models = OpenAiHttp.json("https://api.openai.com/v1/models", bearer = token)
            .optJSONArray("models") ?: throw IllegalStateException("Brak katalogu modeli konta.")
        var chosen: String? = null
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            if (model.optString("visibility") != "list") continue
            val name = model.optString("slug")
            if (name.isNotBlank()) { chosen = name; break }
        }
        val slug = chosen ?: throw IllegalStateException("Brak modeli dopuszczonych do wyświetlenia.")
        return OpenAiHttp.sseText(token, slug, prompt)
    }
}
