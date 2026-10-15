package pl.pixelagent

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

object OpenAiHttp {
    private const val TIMEOUT = 30000
    fun request(url: String, method: String = "GET", bearer: String? = null, body: String? = null, contentType: String? = null): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = 90000
            setRequestProperty("Accept", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer " + bearer)
            if (contentType != null) setRequestProperty("Content-Type", contentType)
        }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        if (conn.responseCode !in 200..299) {
            val msg = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            throw IllegalStateException("HTTP " + conn.responseCode + " " + msg.take(300))
        }
        return conn
    }

    fun json(url: String, method: String = "GET", bearer: String? = null, body: String? = null, contentType: String? = null): JSONObject {
        val conn = request(url, method, bearer, body, contentType)
        return try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }

    fun form(params: Map<String, String>): String =
        params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    fun sseText(token: String, model: String, text: String): String {
        val input = org.json.JSONArray().put(
            JSONObject().put("role", "user").put("content", text)
        )
        val payload = JSONObject().put("model", model).put("input", input)
            .put("store", false).put("stream", true)
        val conn = request(
            "https://api.openai.com/v1/responses", "POST", token,
            payload.toString(), "application/json"
        )
        return try {
            val answer = StringBuilder()
            var completed = false
            var failure: String? = null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.substring(6)
                    if (data == "[DONE]") continue
                    val event = try { JSONObject(data) } catch (_: Exception) { continue }
                    when (event.optString("type")) {
                        "response.output_text.delta" -> answer.append(event.optString("delta"))
                        "response.completed" -> completed = true
                        "response.failed", "response.incomplete" -> {
                            failure = event.optJSONObject("response")?.optJSONObject("error")?.optString("code")
                                ?: event.optString("type")
                            break
                        }
                        "error" -> {
                            failure = event.optJSONObject("error")?.optString("code") ?: "stream_error"
                            break
                        }
                    }
                }
            }
            if (failure != null) throw IllegalStateException("OpenAI stream: " + failure)
            if (!completed) throw IllegalStateException("Strumień zakończony bez response.completed")
            if (answer.isEmpty()) throw IllegalStateException("Model zwrócił pustą odpowiedź")
            answer.toString()
        } finally { conn.disconnect() }
    }
}
