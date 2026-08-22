package pl.yggdrasil.narcissus2.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Klient HTTP na gołym HttpURLConnection.
 *
 * Bez Retrofita i bez OkHttpa: trzy wywołania w całej aplikacji nie
 * uzasadniają dwóch bibliotek i generowania kodu. Serwer stoi w meshu
 * WireGuard, więc bez TLS-a i bez tokenów — mesh jest granicą zaufania.
 */
class SyncClient(private val settings: SyncSettings) {

    /**
     * Wymiana metadanych. Wysyłamy wszystko, co zmieniło się lokalnie od
     * ostatniej synchronizacji (razem z nagrobkami), dostajemy wszystko,
     * co zmieniło się po drugiej stronie.
     */
    suspend fun exchange(changed: JSONArray, since: Long): Result<Response> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("device", settings.deviceId)
                    .put("since", since)
                    .put("sessions", changed)

                val conn = open("/sync", "POST")
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val text = conn.readBody()
                val json = JSONObject(text)

                Response(
                    serverNow = json.getLong("now"),
                    sessions = json.getJSONArray("sessions"),
                    /** Identyfikatory śladów, których serwer jeszcze nie ma. */
                    wantTracks = json.optJSONArray("wantTracks") ?: JSONArray(),
                )
            }
        }

    suspend fun uploadTrack(sessionId: String, file: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) return@runCatching

                val conn = open("/track/$sessionId", "PUT")
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-ndjson")
                file.inputStream().use { input ->
                    conn.outputStream.use { output -> input.copyTo(output) }
                }
                conn.readBody()
                Unit
            }
        }

    suspend fun downloadTrack(sessionId: String, target: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = open("/track/$sessionId", "GET")
                if (conn.responseCode == 404) return@runCatching

                target.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Unit
            }
        }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(settings.serverUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 20_000
        }

    private fun HttpURLConnection.readBody(): String {
        if (responseCode !in 200..299) {
            val err = errorStream?.bufferedReader()?.readText().orEmpty()
            throw IllegalStateException("HTTP $responseCode: ${err.take(200)}")
        }
        return inputStream.bufferedReader().use { it.readText() }
    }

    data class Response(
        val serverNow: Long,
        val sessions: JSONArray,
        val wantTracks: JSONArray,
    )
}
