package com.dearmoon.shield

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.dearmoon.shield.ui.DynamicIslandView

/**
 * UserGuideActivity — full 6-screen onboarding guide.
 *
 * Navigation:
 *  • Only forward (next) via → button; swipe is disabled.
 *  • Screen 6's → button is hidden; adapter shows "Let's Go" pill instead.
 *
 * Entry points:
 *  • WelcomeActivity (fromSettings = false)
 *  • SettingsActivity (fromSettings = true)
 */
class UserGuideActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_SETTINGS = "fromSettings"
        private const val PREFS_NAME   = "shield_prefs"
        private const val KEY_GUIDE    = "shown_guide"
        private const val PROGRESS_TICK_MS  = 150L
        private const val PROGRESS_INCREMENT = 2f   // 100 / (100/2) * 150ms ≈ 7.5s
    }

    private lateinit var viewPager:     ViewPager2
    private lateinit var dynamicIsland: DynamicIslandView
    private lateinit var nextButton:    ImageButton
    private lateinit var proceedCaption: TextView

    private var currentScreen = 0
    private var fromSettings  = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. IMMERSIVE STATUS BAR (Edge-to-Edge)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        // Ensure status bar icons are dynamically light since background is a dark deep security gradient
        val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        setContentView(R.layout.activity_user_guide)

        fromSettings  = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        viewPager     = findViewById(R.id.guidePager)
        dynamicIsland = findViewById(R.id.dynamicIsland)
        nextButton    = findViewById(R.id.nextButton)
        proceedCaption = findViewById(R.id.proceedCaption)

        setupPager()
        setupNextButton()
        updateIslandProgress(0)

        // Dynamically adjust top margin of dynamic island to avoid status bar overlap
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.dynamicIsland)) { view, insets ->
            val topInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            view.translationY = topInset.toFloat()
            insets
        }

        // Dynamically adjust bottom margin of proceed caption and next button to avoid nav bar overlap
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.proceedCaption)) { view, insets ->
            val bottomInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            view.translationY = -bottomInset.toFloat()
            findViewById<android.view.View>(R.id.nextButton).translationY = -bottomInset.toFloat()
            insets
        }
    }

    private fun updateIslandProgress(screenIndex: Int) {
        val total = viewPager.adapter?.itemCount ?: 6
        val progress = (screenIndex.toFloat() / (total - 1).coerceAtLeast(1)) * 100f
        dynamicIsland.setProgress(progress)
        dynamicIsland.setStep(screenIndex)
    }

    private fun setupPager() {
        val screens = buildScreenList()
        val adapter = GuideScreenAdapter(screens) { finishGuide() }
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = false   // no swipe

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentScreen = position
                val isLast = (position == screens.size - 1)
                nextButton.visibility    = if (isLast) View.GONE else View.VISIBLE
                proceedCaption.visibility = if (isLast) View.GONE else View.VISIBLE
                
                if (isLast) {
                    // Trigger scramble + glow pulse when landing on the last page
                    viewPager.post {
                        val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
                        val vh = rv?.findViewHolderForAdapterPosition(position)
                        val btn = vh?.itemView?.findViewById<com.dearmoon.shield.ui.ScrambleTextButton>(R.id.letsGoButton)
                        btn?.restartScramble()

                        // Pulse scale animation: breathes in/out to signal it's tappable
                        btn?.postDelayed({
                            android.animation.ObjectAnimator.ofPropertyValuesHolder(
                                btn,
                                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.04f, 1.0f),
                                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.04f, 1.0f)
                            ).apply {
                                duration = 1600
                                repeatCount = android.animation.ValueAnimator.INFINITE
                                repeatMode  = android.animation.ValueAnimator.RESTART
                                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                                start()
                            }
                        }, 850L) // starts right after the 800ms scramble finishes
                    }
                }
            }
        })
    }

    private fun setupNextButton() {
        nextButton.setOnClickListener {
            if (currentScreen < 5) {
                currentScreen++
                viewPager.currentItem = currentScreen
                updateIslandProgress(currentScreen)
            } else {
                finishGuide()
            }
        }
    }



    // ── Finish ───────────────────────────────────────────────────────────────

    fun finishGuide() {
        if (!fromSettings && !com.dearmoon.shield.data.PrivacyConsentManager.hasConsent(this)) {
            showConsentDialog()
            return
        }
        finishGuideFlow()
    }

    private fun finishGuideFlow() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_GUIDE, true).apply()

        if (!fromSettings) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun showConsentDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_privacy_consent)
        dialog.setCancelable(false)
        dialog.window?.let {
            it.setBackgroundDrawableResource(android.R.color.transparent)
            it.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            val params = it.attributes
            params.width = resources.displayMetrics.widthPixels - (48 * resources.displayMetrics.density).toInt()
            it.attributes = params
        }

        val btnAgree = dialog.findViewById<android.widget.Button>(R.id.btnAgree)
        val btnDecline = dialog.findViewById<android.widget.Button>(R.id.btnDecline)

        btnAgree.setOnClickListener {
            com.dearmoon.shield.data.PrivacyConsentManager.recordConsent(this, true)
            dialog.dismiss()
            finishGuideFlow()
        }

        btnDecline.setOnClickListener {
            com.dearmoon.shield.data.PrivacyConsentManager.recordConsent(this, false)
            dialog.dismiss()
            android.widget.Toast.makeText(this,
                    "SHIELD requires consent to operate. The app will now close.",
                    android.widget.Toast.LENGTH_LONG).show()
            finish()
        }

        dialog.show()
    }

    // ── Screen data builder ──────────────────────────────────────────────────

    private fun buildScreenList(): List<GuideScreenAdapter.GuideScreen> = listOf(

        GuideScreenAdapter.GuideScreen(
            stepIndex    = 0,
            iconResId    = R.mipmap.ic_launcher,
            iconSizeDp   = 80,
            glowColor    = 0x3352B788,          // Mint Luminescence glow
            badgeText    = null,
            badgeColor   = 0,
            mainText     = "Your phone's\nransomware guardian.",
            mainTextSize = 28f,
            subText      = "SHIELD watches for threats 24/7\nand acts before damage occurs.",
            noteText     = null,
            noteColor    = 0,
            isLastScreen = false,
            screenType   = GuideScreenAdapter.ScreenType.STANDARD
        ),

        GuideScreenAdapter.GuideScreen(
            stepIndex    = 1,
            iconResId    = 0,
            iconSizeDp   = 40,
            glowColor    = 0,
            badgeText    = null,
            badgeColor   = 0,
            mainText     = "How It Works",
            mainTextSize = 24f,
            subText      = "",
            noteText     = null,
            noteColor    = 0,
            isLastScreen = false,
            screenType   = GuideScreenAdapter.ScreenType.HOW_IT_WORKS
        ),

        GuideScreenAdapter.GuideScreen(
            stepIndex    = 2,
            iconResId    = R.drawable.ic_guide_key,
            iconSizeDp   = 64,
            glowColor    = 0x3352B788,
            badgeText    = "MODE A",
            badgeColor   = Color.parseColor("#52B788"),
            mainText     = "Deeper protection\nwith root access.",
            mainTextSize = 26f,
            subText      = "Uses kernel-level eBPF monitoring.\nMaximum coverage, zero blind spots.",
            noteText     = "⚠ Requires rooted device",
            noteColor    = Color.parseColor("#FF6B6B"),
            isLastScreen = false,
            screenType   = GuideScreenAdapter.ScreenType.STANDARD
        ),

        GuideScreenAdapter.GuideScreen(
            stepIndex    = 3,
            iconResId    = R.drawable.ic_guide_phone,
            iconSizeDp   = 64,
            glowColor    = 0x3352B788,
            badgeText    = "MODE B",
            badgeColor   = Color.parseColor("#52B788"),
            mainText     = "Full protection,\nno root needed.",
            mainTextSize = 26f,
            subText      = "Works on any Android device using\nfile observers and VPN-based blocking.",
            noteText     = "✓ Works on all devices",
            noteColor    = Color.parseColor("#52B788"),
            isLastScreen = false,
            screenType   = GuideScreenAdapter.ScreenType.STANDARD
        ),

        GuideScreenAdapter.GuideScreen(
            stepIndex    = 4,
            iconResId    = 0,
            iconSizeDp   = 32,
            glowColor    = 0,
            badgeText    = null,
            badgeColor   = 0,
            mainText     = "Your Controls",
            mainTextSize = 24f,
            subText      = "",
            noteText     = null,
            noteColor    = 0,
            isLastScreen = false,
            screenType   = GuideScreenAdapter.ScreenType.CONTROLS
        ),

        GuideScreenAdapter.GuideScreen(
            stepIndex    = 5,
            iconResId    = R.drawable.ic_guide_check,
            iconSizeDp   = 80,
            glowColor    = 0x3352B788,
            badgeText    = null,
            badgeColor   = 0,
            mainText     = "SHIELD is active\nand watching.",
            mainTextSize = 28f,
            subText      = "You'll be alerted instantly\nif anything suspicious happens.",
            noteText     = null,
            noteColor    = 0,
            isLastScreen = true,
            screenType   = GuideScreenAdapter.ScreenType.STANDARD
        )
    )
}
