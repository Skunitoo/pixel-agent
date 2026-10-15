package pl.pixelagent

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import org.json.JSONArray
import org.json.JSONObject

object JwtVerifier {
    private fun decode(input: String): ByteArray = Base64.decode(
        input, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
    fun verifyIdToken(idToken: String, clientId: String, nonce: String): JSONObject {
        val parts = idToken.split(".")
        require(parts.size == 3) { "Niepoprawny ID token." }
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Nieobsługiwany podpis ID tokenu." }
        val kid = header.optString("kid")
        require(kid.isNotBlank()) { "Brak identyfikatora klucza." }
        val keys = OpenAiHttp.json("https://auth.openai.com/.well-known/jwks.json").getJSONArray("keys")
        var chosen: JSONObject? = null
        for (i in 0 until keys.length()) {
            val key = keys.getJSONObject(i)
            if (key.optString("kid") == kid && key.optString("kty") == "RSA") {
                chosen = key; break
            }
        }
        val key = chosen ?: throw IllegalStateException("Klucz podpisu OpenAI nie został znaleziony.")
        val pub = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, decode(key.getString("n"))),
                BigInteger(1, decode(key.getString("e"))))
        )
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(pub)
        signature.update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
        require(signature.verify(decode(parts[2]))) { "Podpis ID tokenu nieprawidłowy." }
        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        require(claims.optString("iss") == "https://auth.openai.com") { "Nieprawidłowy issuer." }
        val aud = claims.opt("aud")
        val matchesAud = when (aud) {
            is String -> aud == clientId
            is JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
            else -> false
        }
        require(matchesAud) { "ID token wystawiony dla innego klienta." }
        val now = System.currentTimeMillis() / 1000L
        require(claims.optLong("exp", 0) > now - 5) { "ID token wygasł." }
        require(claims.optLong("iat", Long.MAX_VALUE) <= now + 60) { "Nieprawidłowy czas ID tokenu." }
        require(claims.optString("nonce") == nonce) { "Błędny nonce." }
        require(claims.optString("sub").isNotBlank()) { "Brak tożsamości OpenAI." }
        return claims
    }
}
