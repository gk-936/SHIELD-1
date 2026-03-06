package com.dearmoon.shield

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.dearmoon.shield.ui.WavyUnderlineTextView

/**
 * WelcomeActivity — shown ONCE on first launch only.
 *
 * Flow:
 *  cold launch → WelcomeActivity (launcher) → checks "shown_welcome" pref
 *    - Already shown  → immediately forward to SplashActivity (no setContentView)
 *    - First time     → play Namaste animation → user taps "Get Started"
 *                       → set flag → forward to SplashActivity
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "shield_prefs"
        private const val KEY_SHOWN = "shown_welcome"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. IMMERSIVE STATUS BAR (Edge-to-Edge)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        // Ensure status bar icons are dynamically light since background is a dark purple/orange gradient
        val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val alreadyShown = prefs.getBoolean(KEY_SHOWN, false)

        if (alreadyShown) {
            // Fast-path: go straight to splash, never inflate a layout
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_welcome)

        val namasteView   = findViewById<WavyUnderlineTextView>(R.id.namasteText)
        val subtitleView  = findViewById<WavyUnderlineTextView>(R.id.namasteSubtitle)
        val continueButton = findViewById<com.dearmoon.shield.ui.ScrambleTextButton>(R.id.continueButton)

        // ── Animations ──────────────────────────────────────────────────────

        // Both views start after the first layout pass (post ensures width > 0)
        namasteView.post {
            namasteView.startAnimation()

            // Subtitle cascades in 400ms later — text finishes at 600ms,
            // subtitle starts before that for a flowing feel
            subtitleView.postDelayed({ subtitleView.startAnimation() }, 400L)
        }

        // ── Navigation & Pulse Animation ─────────────────────────────────────

        // Continue button starts invisible; fades in after underline finishes
        continueButton.alpha = 0f
        continueButton.postDelayed({
            continueButton.animate()
                .alpha(1f)
                .setDuration(400L)
                .withEndAction {
                    // 2. MODERN "GET STARTED" BUTTON (Pulse scale animation)
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(
                        continueButton,
                        android.animation.PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.04f, 1.0f),
                        android.animation.PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.04f, 1.0f)
                    ).apply {
                        duration = 1800
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        repeatMode = android.animation.ValueAnimator.RESTART
                        start()
                    }
                    continueButton.startScramble()
                }
                .start()
        }, 1600L)

        // ── Click Action ────────────────────────────────────────────────────

        continueButton.setOnClickListener {
            prefs.edit().putBoolean(KEY_SHOWN, true).apply()

            // Route to guide on first run; skip to splash on repeat runs
            val guideShown = prefs.getBoolean("shown_guide", false)
            if (!guideShown) {
                val intent = Intent(this, UserGuideActivity::class.java)
                intent.putExtra(UserGuideActivity.EXTRA_FROM_SETTINGS, false)
                startActivity(intent)
            } else {
                startActivity(Intent(this, SplashActivity::class.java))
            }
            overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            finish()
        }
    }
}
