package io.github.raphaelbleier.glasshouse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Headless setup over adb (only the shell may send this, see the DUMP permission in the manifest):
//   adb shell am broadcast -n <package>/.ConfigReceiver --es server http://192.168.1.10:32400 --es token XXXX \
//       --es screensaver_url https://example.org/dashboard --ei screensaver_minutes 15 --ez never_sleep true
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = Prefs(c)
        i.getStringExtra("server")?.let { p.server = it.trimEnd('/') }
        i.getStringExtra("token")?.let { p.token = it }
        i.getStringExtra("server_name")?.let { p.serverName = it }
        i.getStringExtra("screensaver_url")?.let { p.screensaverUrl = it }
        if (i.hasExtra("screensaver_minutes")) p.screensaverMinutes = i.getIntExtra("screensaver_minutes", 15)
        if (i.hasExtra("never_sleep")) p.neverSleep = i.getBooleanExtra("never_sleep", false)
        if (i.hasExtra("home_override")) p.homeOverride = i.getBooleanExtra("home_override", true)
        SystemSetup.apply(c)
    }
}
