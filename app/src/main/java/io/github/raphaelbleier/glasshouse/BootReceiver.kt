package io.github.raphaelbleier.glasshouse

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

// Fire OS resets the sleep timeout, the screensaver and accessibility services at boot; re-apply ours.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        SystemSetup.apply(c)
        // Fire OS clears accessibility services a few seconds after boot, so apply again later
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            SystemSetup.apply(c)
            pending.finish()
        }, 30_000)
    }
}

// Writes the system settings behind the launcher options. Needs, once via adb:
//   adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS
object SystemSetup {
    fun isFireTv(c: Context) = c.packageManager.hasSystemFeature("amazon.hardware.fire_tv")

    fun canWrite(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun apply(c: Context) {
        if (!canWrite(c)) return
        val p = Prefs(c)
        val r = c.contentResolver
        val service = ComponentName(c, HomeService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotEmpty() && it != service }
        val wanted = if (p.homeOverride && isFireTv(c)) enabled + service else enabled
        Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, wanted.joinToString(":"))
        if (wanted.isNotEmpty()) Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

        if (p.screensaverUrl.isNotEmpty()) {
            Settings.Secure.putString(r, "screensaver_components", ComponentName(c, WallboardDream::class.java).flattenToShortString())
            Settings.Secure.putInt(r, "screensaver_enabled", 1)
            // The system table needs WRITE_SETTINGS on top (adb shell appops set <package> WRITE_SETTINGS allow)
            runCatching { Settings.System.putInt(r, Settings.System.SCREEN_OFF_TIMEOUT, p.screensaverMinutes * 60_000) }
        }
        if (p.neverSleep) Settings.Secure.putInt(r, "sleep_timeout", -1)
    }
}
