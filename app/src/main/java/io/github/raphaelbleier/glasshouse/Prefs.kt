package io.github.raphaelbleier.glasshouse

import android.content.Context
import java.util.UUID

// Everything the user can change in the launcher settings
class Prefs(c: Context) {
    private val p = c.getSharedPreferences("glasshouse", Context.MODE_PRIVATE)

    var server: String?
        get() = p.getString("server", null)
        set(v) = p.edit().putString("server", v).apply()
    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()
    var serverName: String?
        get() = p.getString("serverName", null)
        set(v) = p.edit().putString("serverName", v).apply()
    var machineId: String
        get() = p.getString("machineId", "") ?: ""
        set(v) = p.edit().putString("machineId", v).apply()

    // Identifies this install towards plex.tv; must stay stable
    val clientId: String
        get() = p.getString("clientId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("clientId", it).apply() }

    var showOnDeck: Boolean
        get() = p.getBoolean("showOnDeck", true)
        set(v) = p.edit().putBoolean("showOnDeck", v).apply()
    var showRecent: Boolean
        get() = p.getBoolean("showRecent", true)
        set(v) = p.edit().putBoolean("showRecent", v).apply()
    var showPicks: Boolean
        get() = p.getBoolean("showPicks", true)
        set(v) = p.edit().putBoolean("showPicks", v).apply()

    // null until the user picks apps: then the dock shows every installed TV app
    var dockApps: List<String>?
        get() = p.getString("dockApps", null)?.split(',')?.filter { it.isNotEmpty() }
        set(v) = p.edit().putString("dockApps", v?.joinToString(",")).apply()

    var screensaverUrl: String
        get() = p.getString("screensaverUrl", "") ?: ""
        set(v) = p.edit().putString("screensaverUrl", v.trim()).apply()
    var screensaverMinutes: Int
        get() = p.getInt("screensaverMinutes", 15)
        set(v) = p.edit().putInt("screensaverMinutes", v).apply()
    var neverSleep: Boolean
        get() = p.getBoolean("neverSleep", false)
        set(v) = p.edit().putBoolean("neverSleep", v).apply()
    var homeOverride: Boolean
        get() = p.getBoolean("homeOverride", true)
        set(v) = p.edit().putBoolean("homeOverride", v).apply()

    fun plex(labels: Plex.Labels): Plex? {
        val s = server ?: return null
        val t = token ?: return null
        return Plex(s, t, labels, machineId)
    }
}
