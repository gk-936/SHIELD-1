package com.dearmoon.shield

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dearmoon.shield.services.ShieldProtectionService

/**
 * ModeConfirmActivity — Full-screen immersive loading screen shown
 * after the user taps a mode button on MainActivity.
 *
 * Plays the OrbAnimationView breathing animation for 3 seconds
 * then auto-proceeds (or the user can tap to skip early).
 *
 * INTENT EXTRAS:
 *   "mode" → "ROOT" or "STANDARD"
 */
class ModeConfirmActivity : AppCompatActivity() {

    private var hasProceed = false   // idempotency guard

    override fun onCreate(savedInstanceState: Bundle?) {
        // Immersive edge-to-edge — background floods behind status bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor     = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.isAppearanceLightStatusBars     = false
            it.isAppearanceLightNavigationBars = false
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_confirm)

        val mode = intent.getStringExtra("mode") ?: "STANDARD"

        // Set the top label chip text
        val modeLabel: TextView = findViewById(R.id.modeLabel)
        modeLabel.text = when (mode) {
            "ROOT"     -> "ACTIVATING ROOT MODE"
            else       -> "ACTIVATING SHIELD PROTECTION"
        }

        // Set central message text
        val messageText: TextView = findViewById(R.id.messageText)
        messageText.text = when (mode) {
            "ROOT"     -> "Root protocols\nengaging now."
            else       -> "S.H.I.E.L.D. is now\nwatching over you."
        }

        // Animation starts automatically via OrbAnimationView.onAttachedToWindow()

        // Auto-proceed after 3 seconds
        val orbView: View = findViewById(R.id.orbView)
        orbView.postDelayed({ proceedToProtection(mode) }, 3000L)

        // Tap anywhere to skip
        findViewById<View>(R.id.rootView).setOnClickListener {
            proceedToProtection(mode)
        }
    }

    private fun proceedToProtection(mode: String) {
        if (hasProceed) return   // idempotent — only act on first call
        hasProceed = true

        // Persist selected mode
        getSharedPreferences("shield_prefs", MODE_PRIVATE)
            .edit()
            .putString("selected_mode", mode)
            .apply()

        when (mode) {
            "ROOT" -> {
                // Root: go to the info screen (no service start — same as before)
                startActivity(
                    Intent(this, RootModeInfoActivity::class.java)
                )
            }
            else -> {
                // Standard: start VPN / ShieldProtectionService
                val vpnIntent = android.net.VpnService.prepare(this)
                if (vpnIntent != null) {
                    // VPN permission not yet granted — hand back to MainActivity
                    // by finishing here; MainActivity's onResume will pick up
                    setResult(RESULT_OK, Intent().putExtra("mode", mode))
                } else {
                    val svcIntent = Intent(this, ShieldProtectionService::class.java)
                    svcIntent.putExtra("mode", mode)
                    startForegroundService(svcIntent)
                }
            }
        }

        finish()
        overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
    }

    override fun onBackPressed() {
        // Cannot back out of scan — but allow it on long-press
        super.onBackPressed()
        overridePendingTransition(
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
    }
}
