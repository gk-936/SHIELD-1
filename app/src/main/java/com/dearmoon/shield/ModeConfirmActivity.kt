package com.dearmoon.shield

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dearmoon.shield.services.ShieldProtectionService

/**
 * ModeConfirmActivity — Immersive 80% bottom sheet that slides up from below.
 *
 * The outer root is fully transparent so only the inner 80% sheet is visible.
 * On entry: slides up from bottom (slide_up_from_bottom.xml).
 * After 3 seconds (or tap): proceeds to protection then slides back down.
 */
class ModeConfirmActivity : AppCompatActivity() {

    private var hasProceed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called BEFORE super.onCreate() and setContentView()

        // 1. Make the window itself translucent so the top 20% gap shows through
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        // 2. Edge-to-edge + transparent bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor     = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.isAppearanceLightStatusBars     = false
            it.isAppearanceLightNavigationBars = false
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_confirm)

        // 3. Programmatically set inner sheet to 80% of screen height
        val displayHeight = resources.displayMetrics.heightPixels
        val sheetHeight   = (displayHeight * 0.80f).toInt()
        val sheet: View   = findViewById(R.id.rootView)
        sheet.layoutParams = (sheet.layoutParams as ViewGroup.LayoutParams).also {
            it.height = sheetHeight
        }

        val mode = intent.getStringExtra("mode") ?: "STANDARD"

        // 4. Text content matching reference photo style
        val modeLabel: TextView = findViewById(R.id.modeLabel)
        modeLabel.text = when (mode) {
            "ROOT" -> "ROOT MODE"
            else   -> "SHIELD MODE"
        }

        val messageText: TextView = findViewById(R.id.messageText)
        messageText.text = when (mode) {
            "ROOT" -> "Root protocols\nengaging now."
            else   -> "S.H.I.E.L.D. is now\nwatching over you."
        }

        // 5. Animation starts automatically via OrbAnimationView.onAttachedToWindow()

        // 6. Auto-proceed after 4 seconds (2s cone expand + 2s compression play)
        val orbView: View = findViewById(R.id.orbView)
        orbView.postDelayed({ proceedToProtection(mode) }, 4200L)

        // 7. Tap anywhere to skip
        sheet.setOnClickListener { proceedToProtection(mode) }
        // Also allow tapping the transparent area to dismiss
        findViewById<View>(R.id.outerRoot).setOnClickListener { proceedToProtection(mode) }
    }

    private fun proceedToProtection(mode: String) {
        if (hasProceed) return
        hasProceed = true

        getSharedPreferences("shield_prefs", MODE_PRIVATE)
            .edit()
            .putString("selected_mode", mode)
            .apply()

        when (mode) {
            "ROOT" -> startActivity(Intent(this, RootModeInfoActivity::class.java))
            else   -> {
                val vpnIntent = android.net.VpnService.prepare(this)
                if (vpnIntent != null) {
                    setResult(RESULT_OK, Intent().putExtra("mode", mode))
                } else {
                    val svcIntent = Intent(this, ShieldProtectionService::class.java)
                    svcIntent.putExtra("mode", mode)
                    startForegroundService(svcIntent)
                }
            }
        }

        finish()
        // Slide back down on exit
        overridePendingTransition(
            0,  // no enter anim for what's behind us
            R.anim.slide_down_to_bottom
        )
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        overridePendingTransition(0, R.anim.slide_down_to_bottom)
    }
}
