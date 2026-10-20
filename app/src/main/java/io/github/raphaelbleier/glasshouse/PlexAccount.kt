package io.github.raphaelbleier.glasshouse

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Plex sign-in via plex.tv/link (PIN flow) and server discovery
class PlexAccount(private val clientId: String) {
    data class Pin(val id: Long, val code: String)
    data class Server(val name: String, val machineId: String, val token: String, val connections: List<String>)

    fun createPin(): Pin {
        val o = JSONObject(request("https://plex.tv/api/v2/pins?strong=false", "POST"))
        return Pin(o.getLong("id"), o.getString("code"))
    }

    fun token(pin: Pin): String? =
        JSONObject(request("https://plex.tv/api/v2/pins/${pin.id}")).optString("authToken").takeIf { it.isNotEmpty() && it != "null" }

    fun servers(token: String): List<Server> {
        val arr = JSONArray(request("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=0", token = token))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("provides").contains("server") }
            .map { r ->
                val conns = r.optJSONArray("connections") ?: JSONArray()
                // Local connections first: they skip the internet round trip
                val uris = (0 until conns.length()).map { conns.getJSONObject(it) }
                    .sortedBy { if (it.optBoolean("local")) 0 else 1 }
                    .map { it.getString("uri") }
                Server(r.getString("name"), r.getString("clientIdentifier"), r.optString("accessToken", token), uris)
            }
    }

    // First connection that answers, or null
    fun reachable(server: Server): String? = server.connections.firstOrNull { uri ->
        runCatching { request("$uri/identity", token = server.token, timeout = 3000) }.isSuccess
    }

    private fun request(url: String, method: String = "GET", token: String? = null, timeout: Int = 8000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("X-Plex-Product", "Glasshouse")
        c.setRequestProperty("X-Plex-Client-Identifier", clientId)
        c.setRequestProperty("X-Plex-Device-Name", Build.MODEL)
        c.setRequestProperty("X-Plex-Platform", "Android")
        if (token != null) c.setRequestProperty("X-Plex-Token", token)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}
