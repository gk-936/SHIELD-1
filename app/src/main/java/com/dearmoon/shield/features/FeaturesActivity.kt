package com.dearmoon.shield.features

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import com.dearmoon.shield.R

class FeaturesActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var dotsLayout: LinearLayout
    private val dots = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-Edge immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_features)

        findViewById<View>(R.id.btnClose).setOnClickListener {
            onBackPressed()
        }

        viewPager = findViewById(R.id.viewPager)
        dotsLayout = findViewById(R.id.dotsIndicatorLayout)

        val items = listOf(
            FeatureItem(R.drawable.ic_dna, "Ransomware DNA Profiling", "Every attack leaves a unique genetic signature. SHIELD identifies the family, behaviour and origin."),
            FeatureItem(R.drawable.ic_timeline, "Attack Timeline", "See exactly what happened, when it happened, and which files were targeted — second by second."),
            FeatureItem(R.drawable.ic_eye, "Root Mode Detection", "Kernel-level eBPF monitoring catches threats before they touch a single file."),
            FeatureItem(R.drawable.ic_guide_check, "Auto Recovery", "SHIELD restores encrypted files automatically using cryptographic snapshots. Zero data loss."),
            FeatureItem(R.drawable.ic_file, "CERT-In Auto Report", "Generates a submission-ready incident report satisfying India's mandatory 6-hour reporting window.")
        )

        viewPager.adapter = FeaturesAdapter(items)
        viewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        viewPager.offscreenPageLimit = 3
        
        val density = resources.displayMetrics.density
        val pageMargin = (40 * density).toInt()

        viewPager.clipChildren = false
        viewPager.clipToPadding = false
        viewPager.setPadding(0, 0, 0, pageMargin)
        
        (viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)?.clipChildren = false

        viewPager.setPageTransformer(StackedCardTransformer())

        setupDots(items.size)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
            }
        })
    }

    private fun setupDots(count: Int) {
        val density = resources.displayMetrics.density
        val size = (6 * density).toInt()
        val margin = (4 * density).toInt()

        for (i in 0 until count) {
            val dot = View(this)
            
            val bg = android.graphics.drawable.GradientDrawable()
            bg.shape = android.graphics.drawable.GradientDrawable.OVAL
            bg.setColor(Color.parseColor("#333333"))
            dot.background = bg
            
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(margin, 0, margin, 0)
            dot.layoutParams = params
            
            dotsLayout.addView(dot)
            dots.add(dot)
        }
        if (count > 0) updateDots(0)
    }

    private fun updateDots(position: Int) {
        val density = resources.displayMetrics.density
        val activeSize = (8 * density).toInt()
        val inactiveSize = (6 * density).toInt()

        for (i in dots.indices) {
            val dot = dots[i]
            val isActive = (i == position)
            
            val targetSize = if (isActive) activeSize else inactiveSize
            val startSize = dot.layoutParams.width

            if (startSize != targetSize) {
                val animator = ValueAnimator.ofInt(startSize, targetSize)
                animator.duration = 200
                animator.addUpdateListener { anim ->
                    val value = anim.animatedValue as Int
                    val lp = dot.layoutParams
                    lp.width = value
                    lp.height = value
                    dot.layoutParams = lp
                }
                animator.start()
            }
            
            val bg = dot.background as android.graphics.drawable.GradientDrawable
            bg.setColor(Color.parseColor(if (isActive) "#FFFFFF" else "#333333"))
        }
    }
}
