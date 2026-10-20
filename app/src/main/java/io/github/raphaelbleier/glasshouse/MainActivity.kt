package io.github.raphaelbleier.glasshouse

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val prefs by lazy { Prefs(this) }
    private val plex = mutableStateOf<Plex?>(null)
    private val shelf = mutableStateOf<Shelf?>(null)
    private val rows = mutableStateOf(Triple(true, true, true))
    private val error = mutableStateOf<String?>(null)
    private val apps = mutableStateOf<List<App>>(emptyList())
    private val cache by lazy { File(filesDir, "shelf.json") }
    private var connection = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The backdrop paints every pixel; skip the window's own full-screen fill
        window.setBackgroundDrawable(null)
        // This is the home screen: Back has nowhere to go
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        shelf.value = runCatching { Shelf.fromJson(cache.readText()) }.getOrNull()
        setContent {
            val (onDeck, recent, picks) = rows.value
            val visible = shelf.value?.let {
                Shelf(if (onDeck) it.onDeck else emptyList(), if (recent) it.recent else emptyList(), if (picks) it.picks else emptyList())
            }
            Home(plex.value, visible, error.value, apps.value, ::open, ::launch)
        }

        // Refresh while visible: right away, then every 5 min; retry sooner while the server is unreachable
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                applySettings()
                var retry = 10_000L
                while (true) {
                    if (refresh()) {
                        retry = 10_000L
                        delay(5 * 60_000L)
                    } else {
                        delay(retry)
                        retry = (retry * 2).coerceAtMost(120_000L)
                    }
                }
            }
        }
    }

    // Settings may have changed while the settings screen was open
    private fun applySettings() {
        // Also picks up a freshly granted setup permission without needing a reboot
        SystemSetup.apply(this)
        rows.value = Triple(prefs.showOnDeck, prefs.showRecent, prefs.showPicks)
        apps.value = loadApps()
        val now = "${prefs.server}|${prefs.token}"
        if (now != connection) {
            if (connection.isNotEmpty()) shelf.value = null // another server: drop the old library
            connection = now
            plex.value = prefs.plex(labels())
        }
    }

    private fun labels() = Plex.Labels(
        episode = { s, e -> getString(R.string.episode_label, s, e) },
        hours = { h, m -> getString(R.string.length_hours, h, m) },
        minutes = { m -> getString(R.string.length_minutes, m) },
    )

    private suspend fun refresh(): Boolean {
        val p = plex.value ?: run { error.value = getString(R.string.error_not_connected); return true }
        return runCatching { withContext(Dispatchers.IO) { p.load() } }
            .onSuccess { s ->
                error.value = null
                // Only touch state, cache and prefetch when something changed, so Compose doesn't re-render the rows
                if (s != shelf.value) {
                    shelf.value = s
                    prefs.machineId = p.machineId
                    withContext(Dispatchers.IO) { cache.writeText(s.toJson()) }
                    prefetch(p, s)
                }
            }
            .onFailure { if (shelf.value == null) error.value = getString(R.string.error_unreachable) }
            .isSuccess
    }

    // Warm Coil's disk cache (not memory: 70 backdrops wouldn't fit): all thumbnails, backdrops for the first items of each row
    private fun prefetch(p: Plex, s: Shelf) {
        val loader = imageLoader
        val nearby = (s.onDeck.take(8) + s.recent.take(8) + s.picks.take(8)).toSet()
        s.all().forEach { m ->
            val backdrops = if (m in nearby) listOf(p.image(m.art, 1280, 720), p.image(m.art, 480, 270, blur = 40)) else emptyList()
            (backdrops + listOf(p.image(m.poster, 232, 348), p.image(m.still ?: m.art, 480, 270))).forEach { url ->
                if (url != null) loader.enqueue(ImageRequest.Builder(this).data(url).memoryCachePolicy(CachePolicy.DISABLED).build())
            }
        }
    }

    private fun open(m: Media) {
        val link = plex.value?.deepLink(m) ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PLEX)) }
            .onFailure { launch(PLEX) }
    }

    private fun launch(pkg: String) {
        val intent = when (pkg) {
            LAUNCHER_SETTINGS -> Intent(this, SettingsActivity::class.java)
            // On Fire TV, ACTION_SETTINGS detours through Amazon's home screen, which HomeService would bounce straight back
            SYSTEM_SETTINGS -> if (SystemSetup.isFireTv(this)) Intent(Intent.ACTION_MAIN).setClassName(FIRE_LAUNCHER, "$FIRE_LAUNCHER.ui.MainSettingsActivity")
            else Intent(Settings.ACTION_SETTINGS)
            else -> packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        }
        intent?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        fun icon(d: android.graphics.drawable.Drawable) = d.toBitmap(120, 120).asImageBitmap()
        val chosen = prefs.dockApps ?: tvApps(this).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }.map { it.packageName }
        val dock = chosen.mapNotNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()?.let { App(pkg, pm.getApplicationLabel(it).toString(), icon(pm.getApplicationIcon(it))) }
        }
        val settingsIcon = getDrawable(R.drawable.ic_settings)
        return dock +
            App(LAUNCHER_SETTINGS, getString(R.string.tile_launcher_settings), icon(pm.getApplicationIcon(packageName))) +
            App(SYSTEM_SETTINGS, getString(R.string.tile_system_settings), settingsIcon?.let(::icon))
    }

    companion object {
        const val PLEX = "com.plexapp.android"
        const val FIRE_LAUNCHER = "com.amazon.tv.launcher"
        const val LAUNCHER_SETTINGS = "glasshouse:settings"
        const val SYSTEM_SETTINGS = "glasshouse:system-settings"

        // Every app with a TV launcher entry, except Glasshouse itself
        fun tvApps(c: android.content.Context): List<ApplicationInfo> =
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
                .map { it.activityInfo.applicationInfo }
                .filter { it.packageName != c.packageName }
                .distinctBy { it.packageName }
                .sortedBy { c.packageManager.getApplicationLabel(it).toString().lowercase() }
    }
}
