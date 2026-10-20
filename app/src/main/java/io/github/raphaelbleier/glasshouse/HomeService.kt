package io.github.raphaelbleier.glasshouse

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

// Fire OS won't let another app be the launcher, so whenever Amazon's home screen comes up, show ours instead.
class HomeService : AccessibilityService() {
    override fun onServiceConnected() = goHome()

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (Prefs(this).homeOverride && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.packageName == "com.amazon.tv.launcher" && e.className?.contains("Home") == true
        ) goHome()
    }

    override fun onInterrupt() {}

    private fun goHome() = startActivity(
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )
}
