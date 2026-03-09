package com.dearmoon.shield

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.dearmoon.shield.analysis.RansomwareDnaProfile
import com.dearmoon.shield.analysis.RansomwareDnaProfiler
import com.dearmoon.shield.analysis.ShieldPdfReportGenerator
import com.dearmoon.shield.analysis.TimelineEventType
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// IncidentViewModel — shared between Activity and both Fragments.
// The Activity populates profile after DB build; Fragments observe changes.
// ─────────────────────────────────────────────────────────────────────────────
class IncidentViewModel : ViewModel() {
    val profile = MutableLiveData<RansomwareDnaProfile?>()
}

// ─────────────────────────────────────────────────────────────────────────────
// IncidentActivity
// ─────────────────────────────────────────────────────────────────────────────
class IncidentActivity : AppCompatActivity() {

    private val TAG = "SHIELD_INCIDENT"
    private lateinit var viewModel: IncidentViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Edge-to-Edge Immersive Status Bar ───────────────────────────────
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false   // dark bg → white icons
        insetsController.isAppearanceLightNavigationBars = false
        // ───────────────────────────────────────────────────────────────────

        // Load layout defined in activity_incident.xml
        setContentView(R.layout.activity_incident)

        // Apply insets so toolbar clears the status bar
        val root = findViewById<android.view.View>(R.id.incidentRoot)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, insets.top, 0, insets.bottom)
            windowInsets
        }

        // HUD Header navigation and styling
        val backBtn = findViewById<ImageView>(R.id.hudBackBtn)
        backBtn.setOnClickListener { finish() }

        val hudTitle = findViewById<TextView>(R.id.hudTitle)
        hudTitle.typeface = Typeface.MONOSPACE

        // Premium gradient text shader — applied after layout so width is known
        hudTitle.post {
            val textWidth = hudTitle.paint.measureText(hudTitle.text.toString())
            val gradient = LinearGradient(
                0f, 0f, textWidth, 0f,
                intArrayOf(
                    Color.parseColor("#00C8FF"),   // cyan start
                    Color.parseColor("#E0F7FF"),   // near-white center
                    Color.parseColor("#00C8FF")    // cyan end
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            hudTitle.paint.shader = gradient
            hudTitle.invalidate()
        }

        // Give the title a solid background matching the screen/dark background
        // so it cleanly overlaps the decorative divider line behind it
        hudTitle.setBackgroundColor(Color.parseColor("#01162b"))

        animateHudTitle(hudTitle)

        // Tab + ViewPager2 wiring
        val viewPager = findViewById<ViewPager2>(R.id.incidentViewPager)
        val tabLayout = findViewById<TabLayout>(R.id.incidentTabLayout)
        val tabButtonRow = findViewById<LinearLayout>(R.id.tabButtonRow)

        // ── Build custom centered toggle buttons ────────────────────────────
        val density = resources.displayMetrics.density
        val tabTitles = arrayOf("⏱  TIMELINE", "🧬  DNA REPORT")
        val buttons = mutableListOf<TextView>()

        for (i in tabTitles.indices) {
            val btn = TextView(this).apply {
                text = tabTitles[i]
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (22 * density).toInt(), (10 * density).toInt(),
                    (22 * density).toInt(), (10 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also {
                    it.marginStart = (6 * density).toInt()
                    it.marginEnd = (6 * density).toInt()
                }
                isClickable = true
                isFocusable = true
            }
            buttons.add(btn)
            tabButtonRow.addView(btn)

            btn.setOnClickListener {
                viewPager.setCurrentItem(i, true)
            }
        }

        // Style the buttons based on selection state
        fun updateButtonStyles(selectedIndex: Int) {
            for ((idx, btn) in buttons.withIndex()) {
                val isSelected = idx == selectedIndex
                val bg = GradientDrawable().apply {
                    cornerRadius = 24 * density
                    if (isSelected) {
                        setColor(Color.parseColor("#1A00C8FF"))
                        setStroke((1.5f * density).toInt(), Color.parseColor("#00C8FF"))
                    } else {
                        setColor(Color.parseColor("#10FFFFFF"))
                        setStroke((1f * density).toInt(), Color.parseColor("#2A3A5A"))
                    }
                }
                btn.background = bg
                btn.setTextColor(
                    if (isSelected) Color.parseColor("#00C8FF")
                    else Color.parseColor("#6B7A94")
                )
                // Subtle scale for selected
                btn.animate().scaleX(if (isSelected) 1.05f else 1f)
                    .scaleY(if (isSelected) 1.05f else 1f)
                    .setDuration(200).start()
            }
        }

        // Initial state
        updateButtonStyles(0)

        // Listen for page changes to update button styles
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtonStyles(position)
            }
        })

        // Obtain shared ViewModel
        viewModel = ViewModelProvider(this)[IncidentViewModel::class.java]

        // Read attack-window parameters from Intent (set by MainActivity)
        val attackWindowStart  = intent.getLongExtra("attackWindowStart",  0L)
        val attackWindowEnd    = intent.getLongExtra("attackWindowEnd",    0L)
        val compositeScore     = intent.getIntExtra ("compositeScore",     0)
        val entropyScore       = intent.getIntExtra ("entropyScore",       0)
        val kldScore           = intent.getIntExtra ("kldScore",           0)
        val sprtAcceptedH1     = intent.getBooleanExtra("sprtAcceptedH1",  false)
        val restoredFileCount  = intent.getIntExtra ("restoredFileCount",  0)

        // ── Data Sanitization & Conditional UI Logic ────────────────────────────
        val onRansomwareDetected = attackWindowStart > 0L && compositeScore > 0

        val tabButtonContainer = findViewById<FrameLayout>(R.id.tabButtonContainer)
        val hudHeaderContainer = findViewById<View>(R.id.hudHeaderContainer)
        val threatAlertHeader = findViewById<View>(R.id.threatAlertHeader)

        if (!onRansomwareDetected) {
            // Standard Terminal View (Buttons & Threat Alert Hidden, Header Shown)
            tabButtonContainer.visibility = View.GONE
            hudHeaderContainer.visibility = View.VISIBLE
            threatAlertHeader.visibility = View.GONE
            // Only show Timeline (1 page), disable swiping
            viewPager.adapter = IncidentPagerAdapter(this, 1)
            viewPager.isUserInputEnabled = false
            viewModel.profile.value = null
        } else {
            // Ransomware Detected: UI transitioned, buttons & threat alert activated, HUD hidden
            tabButtonContainer.visibility = View.VISIBLE
            hudHeaderContainer.visibility = View.GONE
            threatAlertHeader.visibility = View.VISIBLE
            // Show both pages (Timeline + DNA Report), enable swiping
            viewPager.adapter = IncidentPagerAdapter(this, 2)
            viewPager.isUserInputEnabled = true
            // Bind to hidden TabLayout for ViewPager2 sync
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = if (position == 0) "TIMELINE" else "DNA REPORT"
            }.attach()

            Log.d(TAG, "Building profile — window=[$attackWindowStart,$attackWindowEnd]")
            
            // Build profile on IO without dummy values
            lifecycleScope.launch(Dispatchers.IO) {
                val profile = RansomwareDnaProfiler.buildProfile(
                    context           = this@IncidentActivity,
                    attackWindowStart = attackWindowStart,
                    attackWindowEnd   = attackWindowEnd,
                    compositeScore    = compositeScore,
                    entropyScore      = entropyScore,
                    kldScore          = kldScore,
                    sprtAcceptedH1    = sprtAcceptedH1,
                    restoredFileCount = restoredFileCount
                )
                withContext(Dispatchers.Main) {
                    viewModel.profile.value = profile
                    Log.d(TAG, "Profile posted to ViewModel — family=${profile.attackFamily.name}")
                }
            }
        }
    }

    private fun showNoAttackState() {
        val viewPager = findViewById<ViewPager2>(R.id.incidentViewPager)
        val tabLayout = findViewById<TabLayout>(R.id.incidentTabLayout)
        
        // Hide tabs and content
        tabLayout.visibility = View.GONE
        viewPager.visibility = View.GONE
        
        // Add centered message
        val parent = viewPager.parent as LinearLayout
        val noAttackView = TextView(this).apply {
            text = "No attacks detected yet.\nSHIELD is actively monitoring."
            setTextColor(Color.parseColor("#8892A4"))
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        parent.addView(noAttackView)
    }

    private fun animateHudTitle(title: TextView) {
        val finalString = "INCIDENT REPORT"
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#\$%&"
        
        // Initial setup
        title.text = ""
        title.alpha = 0f
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            title.letterSpacing = 0.6f // Expand wide initially
        }

        // 1. Fade up and compress letter spacing to final premium tracking
        val alphaAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            addUpdateListener { title.alpha = it.animatedValue as Float }
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            ValueAnimator.ofFloat(0.6f, 0.18f).apply {
                duration = 1000
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { title.letterSpacing = it.animatedValue as Float }
                start()
            }
        }

        // 2. Futuristic decrypt / scrambling typing effect
        var currentStep = 0
        val decodeDuration = 800L
        val updateInterval = 30L
        val updates = (decodeDuration / updateInterval).toInt()
        val stepsPerChar = updates / finalString.length
        val decHandler = Handler(Looper.getMainLooper())

        val decodeRunnable = object : Runnable {
            override fun run() {
                if (currentStep <= updates) {
                    val revealedChars = (currentStep / stepsPerChar.coerceAtLeast(1)).coerceAtMost(finalString.length)
                    val sb = java.lang.StringBuilder()
                    sb.append(finalString.substring(0, revealedChars))
                    for (i in revealedChars until finalString.length) {
                        sb.append(chars.random())
                    }
                    title.text = sb.toString()
                    currentStep++
                    decHandler.postDelayed(this, updateInterval)
                } else {
                    title.text = finalString

                    // Re-apply gradient shader on final text
                    val textWidth = title.paint.measureText(finalString)
                    val gradient = LinearGradient(
                        0f, 0f, textWidth, 0f,
                        intArrayOf(
                            Color.parseColor("#00C8FF"),
                            Color.parseColor("#E0F7FF"),
                            Color.parseColor("#00C8FF")
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    title.paint.shader = gradient
                    
                    // Endless pulsing neon glow — stronger for premium feel
                    ValueAnimator.ofFloat(12f, 24f, 12f).apply {
                        duration = 2500
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener { 
                            title.setShadowLayer(it.animatedValue as Float, 0f, 0f, Color.parseColor("#00C8FF"))
                            // Keep gradient shader alive through shadow updates
                            title.paint.shader = gradient
                        }
                        start()
                    }
                }
            }
        }
        
        alphaAnimator.start()
        decHandler.postDelayed(decodeRunnable, 150) // Slight delay so the fade starts first
    }

    // ── ViewPager2 adapter — page count driven by ransomware state ─────────
    private inner class IncidentPagerAdapter(
        activity: AppCompatActivity,
        private val pageCount: Int = 2
    ) : FragmentStateAdapter(activity) {
        override fun getItemCount() = pageCount
        override fun createFragment(position: Int): Fragment =
            if (position == 0) TimelineFragment() else DnaReportFragment()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TAB 1: TimelineFragment — TERMINAL STYLE
// Displays attack timeline as an in-app terminal with typing animation.
// ─────────────────────────────────────────────────────────────────────────────
class TimelineFragment : Fragment() {

    private val viewModel: IncidentViewModel by activityViewModels()
    private lateinit var terminalTextView: TextView
    private lateinit var cursorView: View
    private lateinit var spinner: ProgressBar
    private lateinit var terminalFrame: LinearLayout
    private lateinit var terminalBody: FrameLayout
    private lateinit var topContainer: FrameLayout
    private lateinit var bottomContainer: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ENGLISH)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            weightSum = 1f
            setBackgroundColor(Color.TRANSPARENT)
        }

        topContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.40f
            )
        }

        bottomContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                0.60f
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        val particleSystem = com.dearmoon.shield.ui.ParticleSystemView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        bottomContainer.addView(particleSystem)

        val screenWidthPx = ctx.resources.displayMetrics.widthPixels

        // ── Ubuntu-style terminal rectangle ──────────────────────────────────
        // Starts as a compact, fixed-size, centered console box.
        // showAttackTimeline() will expand it to full-screen when needed.
        terminalFrame = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val frameBg = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#1C1C1C"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#3C3C3C"))
            }
            background = frameBg
            // Fixed width (85% of screen), WRAP_CONTENT height, centered
            layoutParams = FrameLayout.LayoutParams(
                (screenWidthPx * 0.85f).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 10 * density)
                }
            }
        }

        // ── Ubuntu top bar (title bar with window controls) ──────────────────
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
            val barBg = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D"))
                // Only top corners rounded
                cornerRadii = floatArrayOf(
                    10 * density, 10 * density, // top-left
                    10 * density, 10 * density, // top-right
                    0f, 0f,                     // bottom-right
                    0f, 0f                      // bottom-left
                )
            }
            background = barBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Window control dots (close, minimize, maximize)
        val dotColors = intArrayOf(Color.parseColor("#FF5F56"), Color.parseColor("#FFBD2E"), Color.parseColor("#27C93F"))
        for (c in dotColors) {
            val dot = View(ctx).apply {
                val size = (11 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = (7 * density).toInt() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                }
            }
            topBar.addView(dot)
        }

        // Title label
        val titleTv = TextView(ctx).apply {
            text = "  shield@defense: ~/incident"
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleTv)

        terminalFrame.addView(topBar)

        // ── Terminal body (scrollable text area) ─────────────────────────────
        // Fixed height: ~160dp for compact 4-6 line console look.
        // showAttackTimeline() will switch this to weight-based fill.
        val fixedBodyHeight = (160 * density).toInt()
        terminalBody = FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#300A24")) // Ubuntu default purple-brown
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                fixedBodyHeight
            )
        }

        // Scanline overlay
        val scanlineView = object : View(ctx) {
            private val paint = Paint().apply {
                color = Color.parseColor("#06FFFFFF")
                strokeWidth = 1f
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                var y = 0f
                while (y < height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, paint)
                    y += 3 * density
                }
            }
        }
        scanlineView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val scrollView = ScrollView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = true
            isFillViewport = false
        }

        val terminalContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        }

        terminalTextView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#33FF33")) // Green terminal text
            setLineSpacing(4 * density, 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        terminalContainer.addView(terminalTextView)

        // Blinking cursor
        cursorView = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#33FF33"))
            layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), (14 * density).toInt()).also {
                it.topMargin = (2 * density).toInt()
            }
            visibility = View.GONE
        }
        terminalContainer.addView(cursorView)

        scrollView.addView(terminalContainer)
        terminalBody.addView(scrollView)
        terminalBody.addView(scanlineView)

        terminalFrame.addView(terminalBody)

        // Spinner
        spinner = ProgressBar(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.VISIBLE
        }

        topContainer.addView(terminalFrame)
        topContainer.addView(spinner)

        root.addView(topContainer)
        root.addView(bottomContainer)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Start cursor blinking
        startCursorBlink()

        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            spinner.visibility = View.GONE
            if (profile == null || profile.timelineEvents.isEmpty()) {
                // Standard Terminal View Logic Map: Protection active/inactive -> standard view
                showNoAttacksTerminal()
            } else {
                showAttackTimeline(profile)
            }
        }
    }

    private fun startCursorBlink() {
        cursorView.visibility = View.VISIBLE
        val blinkAnimator = ObjectAnimator.ofFloat(cursorView, "alpha", 1f, 0f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        blinkAnimator.start()
    }

    private fun isShieldActive(): Boolean {
        return try {
            requireContext().getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
                .getBoolean("shield_active", false)
        } catch (e: Exception) {
            false
        }
    }

    // ── Compute how many monospace chars fit in the terminal ────────────────
    private fun getTermCharWidth(): Int {
        return try {
            val ctx = requireContext()
            val density = ctx.resources.displayMetrics.density
            val screenWidthPx = ctx.resources.displayMetrics.widthPixels
            // Terminal has 12dp margin on each side of frame + 14dp padding inside
            val termPaddingPx = ((12 + 12 + 14 + 14) * density).toInt()
            val availableWidthPx = screenWidthPx - termPaddingPx
            // Monospace at 11sp — approx 6.6px per char at 1x density
            val charWidthPx = 6.6f * density
            (availableWidthPx / charWidthPx).toInt().coerceIn(30, 80)
        } catch (e: Exception) {
            38 // fallback
        }
    }

    // ── Dynamic line helpers ─────────────────────────────────────────────
    private fun line(ch: Char, w: Int): String = ch.toString().repeat(w)
    private fun centeredTitle(title: String, w: Int): String {
        val pad = ((w - title.length) / 2).coerceAtLeast(0)
        return " ".repeat(pad) + title
    }

    private fun showNoAttacksTerminal() {
        // Compact fixed-size terminal centered on screen
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val screenWidthPx = ctx.resources.displayMetrics.widthPixels
        val fixedBodyHeight = (160 * density).toInt()

        // 40% top split layout ensures it stays up
        topContainer.visibility = View.VISIBLE
        topContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            0.40f
        )
        bottomContainer.visibility = View.VISIBLE

        terminalFrame.layoutParams = FrameLayout.LayoutParams(
            (screenWidthPx * 0.85f).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        // Fixed height body — fits 4-6 lines, scrolls if overflow
        terminalBody.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            fixedBodyHeight
        )

        val active = isShieldActive()
        val lines = mutableListOf<TermLine>()
        val w = getTermCharWidth()

        // Terminal Output exactly matching requirement (no redundant double lines)
        if (active) {
            lines.add(TermLine("Shield defense active", "#00E676", 80))
            lines.add(TermLine(line('─', w), "#4E9A58"))
            lines.add(TermLine("No ransomware detected.", "#AAAAAA"))
        } else {
            lines.add(TermLine("Shield defense inactive", "#FF5F56", 80))
            lines.add(TermLine(line('─', w), "#FF5F56"))
            lines.add(TermLine("Turn on to protect the files.", "#AAAAAA"))
        }

        typeTerminalLines(lines)
    }

    private fun showDemoTimeline() {
        val lines = mutableListOf<TermLine>()
        val w = getTermCharWidth()

        // Boot header
        lines.addAll(buildBootHeader(w))

        // Command
        lines.add(TermLine("shield@defense:~$ shield --incident --trace", "#33FF33", 60))
        lines.add(TermLine("", "#33FF33"))

        // Init lines
        lines.add(TermLine("[init] Loading forensic modules ··· OK", "#AAAAAA"))
        lines.add(TermLine("[init] Profile ID: demo-8f3a-b721", "#AAAAAA"))
        lines.add(TermLine("[init] Timestamp: 07 Mar 2026 19:22 IST", "#AAAAAA"))
        lines.add(TermLine("[init] Attack window: 47.33s", "#AAAAAA"))
        lines.add(TermLine("[init] Classification: RANSOMWARE", "#FF6B6B"))
        lines.add(TermLine("", "#33FF33"))

        // Section header — single dynamic line
        lines.add(TermLine(line('═', w), "#00C8FF"))
        lines.add(TermLine(centeredTitle("FORENSIC  ATTACK  TIMELINE", w), "#00C8FF", 70))
        lines.add(TermLine(line('═', w), "#00C8FF"))
        lines.add(TermLine("", "#33FF33"))

        // Event 1: First Signal
        lines.add(TermLine("  ┌─ 19:22:04.112 " + line('─', (w - 20).coerceAtLeast(4)), "#FFB300"))
        lines.add(TermLine("  │  ⚡ FIRST SIGNAL", "#FFB300", 70))
        lines.add(TermLine("  │  Entropy spike: 7.91 bits/byte", "#C0C0C0"))
        lines.add(TermLine("  │  Location: /sdcard/Downloads/", "#888888"))
        lines.add(TermLine("  │  Source: file_system_events", "#666666"))
        lines.add(TermLine("  │", "#444444"))

        // Event 2
        lines.add(TermLine("  ├─ 19:22:11.738 " + line('─', (w - 20).coerceAtLeast(4)), "#FF6B6B"))
        lines.add(TermLine("  │  📁 FILE ENCRYPTED", "#FF6B6B"))
        lines.add(TermLine("  │  document_2024.pdf → .pdf.locked", "#C0C0C0"))
        lines.add(TermLine("  │  Size: 2.4 MB │ SHA256: a3f8..d1e2", "#888888"))
        lines.add(TermLine("  │  Source: file_system_events", "#666666"))
        lines.add(TermLine("  │", "#444444"))

        // Event 3
        lines.add(TermLine("  ├─ 19:22:15.204 " + line('─', (w - 20).coerceAtLeast(4)), "#FF6B6B"))
        lines.add(TermLine("  │  📁 FILE ENCRYPTED", "#FF6B6B"))
        lines.add(TermLine("  │  family_photo.jpg → .jpg.locked", "#C0C0C0"))
        lines.add(TermLine("  │  Size: 4.1 MB │ SHA256: f72b..93a7", "#888888"))
        lines.add(TermLine("  │  Source: file_system_events", "#666666"))
        lines.add(TermLine("  │", "#444444"))

        // Event 4: Honeyfile
        lines.add(TermLine("  ├─ 19:22:18.501 " + line('─', (w - 20).coerceAtLeast(4)), "#FF3B3B"))
        lines.add(TermLine("  │  🍯 HONEYFILE TRIGGERED", "#FF3B3B", 70))
        lines.add(TermLine("  │  Trap: trap_receipt.pdf", "#C0C0C0"))
        lines.add(TermLine("  │  Verdict: RANSOMWARE CONFIRMED", "#FF3B3B"))
        lines.add(TermLine("  │  Trigger delay: 14.4s", "#888888"))
        lines.add(TermLine("  │  Source: honeyfile_events", "#666666"))
        lines.add(TermLine("  │", "#444444"))

        // Event 5: High Risk
        lines.add(TermLine("  ├─ 19:22:19.003 " + line('─', (w - 20).coerceAtLeast(4)), "#FF3B3B"))
        lines.add(TermLine("  │  🚨 THREAT LEVEL CRITICAL", "#FF3B3B", 70))
        lines.add(TermLine("  │  Composite score: 87/100", "#C0C0C0"))
        lines.add(TermLine("  │  Entropy ··· 35/40 ████████▓░", "#FFB300"))
        lines.add(TermLine("  │  KLD ······· 27/30 █████████░", "#FFB300"))
        lines.add(TermLine("  │  SPRT ······ ACCEPT H1", "#FFB300"))
        lines.add(TermLine("  │  Source: detection_results", "#666666"))
        lines.add(TermLine("  │", "#444444"))

        // Event 6: Network
        lines.add(TermLine("  ├─ 19:22:19.340 " + line('─', (w - 20).coerceAtLeast(4)), "#00C8FF"))
        lines.add(TermLine("  │  🔒 C2 CONNECTION BLOCKED", "#00C8FF", 70))
        lines.add(TermLine("  │  → 185.220.101.34:443 (TOR)", "#C0C0C0"))
        lines.add(TermLine("  │  Protocol: TLS 1.3", "#888888"))
        lines.add(TermLine("  │  Source: network_log", "#666666"))
        lines.add(TermLine("  │", "#444444"))

        // Event 7: VPN
        lines.add(TermLine("  ├─ 19:22:20.117 " + line('─', (w - 20).coerceAtLeast(4)), "#00C8FF"))
        lines.add(TermLine("  │  🛡️ VPN INTERCEPTION ACTIVE", "#00C8FF"))
        lines.add(TermLine("  │  NetworkGuardService engaged", "#C0C0C0"))
        lines.add(TermLine("  │  Outbound traffic filtered", "#888888"))
        lines.add(TermLine("  │  Source: network_log", "#666666"))
        lines.add(TermLine("  │", "#444444"))

        // Event 8: Process
        lines.add(TermLine("  ├─ 19:22:22.890 " + line('─', (w - 20).coerceAtLeast(4)), "#00C8FF"))
        lines.add(TermLine("  │  💀 PROCESS TERMINATED", "#00C8FF", 70))
        lines.add(TermLine("  │  PID 12847: com.suspicious.crypt", "#C0C0C0"))
        lines.add(TermLine("  │  Signal: SIGKILL (9)", "#888888"))
        lines.add(TermLine("  │  Source: process_events", "#666666"))
        lines.add(TermLine("  │", "#444444"))

        // Event 9: Restore + progress bar
        lines.add(TermLine("  ├─ 19:22:25.001 " + line('─', (w - 20).coerceAtLeast(4)), "#00E676"))
        lines.add(TermLine("  │  🔄 RESTORATION INITIATED", "#00E676"))
        lines.add(TermLine("  │  Engine: SnapshotRestore v2", "#C0C0C0"))
        lines.add(TermLine("  │  Source: restore_events", "#666666"))
        lines.add(TermLine("  │", "#444444"))
        lines.add(TermLine("  │  [██░░░░░░░░]   7%  2/14", "#00E676", 100))
        lines.add(TermLine("  │  [████░░░░░░]  29%  4/14", "#00E676", 80))
        lines.add(TermLine("  │  [█████░░░░░]  50%  7/14", "#00E676", 80))
        lines.add(TermLine("  │  [███████░░░]  71% 10/14", "#00E676", 80))
        lines.add(TermLine("  │  [██████████] 100% 14/14", "#00E676", 100))
        lines.add(TermLine("  │", "#444444"))

        // Event 10: Complete
        lines.add(TermLine("  └─ 19:22:51.442 " + line('─', (w - 20).coerceAtLeast(4)), "#00E676"))
        lines.add(TermLine("     ✅ ALL FILES RESTORED", "#00E676", 80))
        lines.add(TermLine("     14/14 recovered · Data loss: NONE", "#C0C0C0"))
        lines.add(TermLine("     Source: restore_events", "#666666"))
        lines.add(TermLine("", "#33FF33"))

        // Summary — clean, no box borders
        lines.add(TermLine(line('═', w), "#00C8FF"))
        lines.add(TermLine(centeredTitle("INCIDENT SUMMARY", w), "#00C8FF", 70))
        lines.add(TermLine(line('─', w), "#444444"))
        lines.add(TermLine("  Events logged ····· 9", "#AAAAAA"))
        lines.add(TermLine("  Threat score ······ 87/100", "#FF3B3B"))
        lines.add(TermLine("  Detection time ···· 14.9s", "#00E676"))
        lines.add(TermLine("  Files at risk ····· 14", "#FFB300"))
        lines.add(TermLine("  Files restored ···· 14/14", "#00E676"))
        lines.add(TermLine("  Data loss ········· NONE", "#00E676"))
        lines.add(TermLine("  C2 blocked ········ 1", "#00C8FF"))
        lines.add(TermLine("  Procs killed ······ 1", "#00C8FF"))
        lines.add(TermLine("  Verdict ··········· CONTAINED", "#00E676", 80))
        lines.add(TermLine(line('═', w), "#00C8FF"))
        lines.add(TermLine("", "#33FF33"))
        lines.add(TermLine("shield@defense:~$ _", "#33FF33"))

        typeTerminalLines(lines)
    }

    private fun showAttackTimeline(profile: RansomwareDnaProfile) {
        // Expand terminal to full-screen fill for attack data
        bottomContainer.visibility = View.GONE
        topContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        )

        val density = requireContext().resources.displayMetrics.density
        terminalFrame.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).also {
            val margin = (12 * density).toInt()
            it.setMargins(margin, margin, margin, margin)
        }
        terminalBody.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        )
        // Restore padding for scrollable attack content
        val termContainer = (terminalBody.getChildAt(0) as? ScrollView)
            ?.getChildAt(0) as? LinearLayout
        termContainer?.setPadding(
            (14 * density).toInt(), (10 * density).toInt(),
            (14 * density).toInt(), (40 * density).toInt()
        )

        val lines = mutableListOf<TermLine>()
        val w = getTermCharWidth()
        
        // Boot header
        lines.addAll(buildBootHeader(w))

        // Command
        lines.add(TermLine("shield@defense:~$ shield --incident --trace", "#33FF33", 60))
        lines.add(TermLine("", "#33FF33"))

        // Init
        lines.add(TermLine("[init] Forensic modules ··· OK", "#AAAAAA"))
        lines.add(TermLine("[init] Profile: ${profile.profileId.take(16)}", "#AAAAAA"))
        lines.add(TermLine("[init] Window: ${formatDuration(profile.attackDurationSeconds)}", "#AAAAAA"))
        lines.add(TermLine("[init] Type: ${profile.attackFamily.displayName.uppercase()}", "#FF6B6B"))
        lines.add(TermLine("", "#33FF33"))

        // Timeline header
        lines.add(TermLine(line('═', w), "#00C8FF"))
        lines.add(TermLine(centeredTitle("FORENSIC ATTACK TIMELINE", w), "#00C8FF", 70))
        lines.add(TermLine(line('═', w), "#00C8FF"))
        lines.add(TermLine("", "#33FF33"))

        // Events
        for ((index, event) in profile.timelineEvents.withIndex()) {
            val color = when (event.eventType) {
                TimelineEventType.FIRST_SIGNAL        -> "#FFB300"
                TimelineEventType.FILE_MODIFIED       -> "#FF6B6B"
                TimelineEventType.HONEYFILE_HIT       -> "#FF3B3B"
                TimelineEventType.HIGH_RISK_ALERT     -> "#FF3B3B"
                TimelineEventType.NETWORK_BLOCKED     -> "#00C8FF"
                TimelineEventType.VPN_ACTIVATED       -> "#00C8FF"
                TimelineEventType.PROCESS_KILLED      -> "#00C8FF"
                TimelineEventType.RESTORE_STARTED     -> "#00E676"
                TimelineEventType.RESTORE_COMPLETE    -> "#00E676"
            }
            val typeLabel = when (event.eventType) {
                TimelineEventType.FIRST_SIGNAL        -> "⚡ FIRST SIGNAL"
                TimelineEventType.FILE_MODIFIED       -> "📁 FILE ENCRYPTED"
                TimelineEventType.HONEYFILE_HIT       -> "🍯 HONEYFILE TRIGGERED"
                TimelineEventType.HIGH_RISK_ALERT     -> "🚨 THREAT LEVEL CRITICAL"
                TimelineEventType.NETWORK_BLOCKED     -> "🔒 C2 BLOCKED"
                TimelineEventType.VPN_ACTIVATED       -> "🛡️ VPN ACTIVE"
                TimelineEventType.PROCESS_KILLED      -> "💀 PROC KILLED"
                TimelineEventType.RESTORE_STARTED     -> "🔄 RESTORE START"
                TimelineEventType.RESTORE_COMPLETE    -> "✅ RESTORED"
            }

            val isFirst = index == 0
            val isLast = index == profile.timelineEvents.size - 1
            val connector = if (isFirst) "┌" else if (isLast) "└" else "├"
            val pipe = if (isLast) " " else "│"
            val ts = timeFmt.format(Date(event.timestamp))
            val dashLen = (w - 20).coerceAtLeast(4)

            lines.add(TermLine("  $connector─ $ts " + line('─', dashLen), color))
            lines.add(TermLine("  $pipe  $typeLabel", color, 60))
            lines.add(TermLine("  $pipe  ${event.description}", "#C0C0C0"))
            lines.add(TermLine("  $pipe  Source: ${event.sourceTable}", "#666666"))
            if (!isLast) {
                lines.add(TermLine("  │", "#444444"))
            }
        }

        lines.add(TermLine("", "#33FF33"))

        // Summary
        val dataLoss = when {
            profile.filesRestoredCount >= profile.filesEncryptedEstimate -> "NONE"
            profile.filesRestoredCount > 0 -> "PARTIAL"
            else -> "SIGNIFICANT"
        }
        val dataLossColor = when (dataLoss) {
            "NONE" -> "#00E676"
            "PARTIAL" -> "#FFB300"
            else -> "#FF3B3B"
        }
        val verdict = if (dataLoss == "NONE") "CONTAINED" else if (dataLoss == "PARTIAL") "MITIGATED" else "CRITICAL"
        val verdictColor = if (dataLoss == "NONE") "#00E676" else if (dataLoss == "PARTIAL") "#FFB300" else "#FF3B3B"

        lines.add(TermLine(line('═', w), "#00C8FF"))
        lines.add(TermLine(centeredTitle("INCIDENT SUMMARY", w), "#00C8FF", 70))
        lines.add(TermLine(line('─', w), "#444444"))
        lines.add(TermLine("  Events ·· ${profile.timelineEvents.size}", "#AAAAAA"))
        lines.add(TermLine("  Score ···· ${profile.normalizedScore}/100", if (profile.normalizedScore >= 70) "#FF3B3B" else "#FFB300"))
        lines.add(TermLine("  Detect ··· ${profile.detectionTimeSeconds}s", "#00E676"))
        lines.add(TermLine("  At risk ·· ${profile.totalFilesAtRisk}", "#FFB300"))
        lines.add(TermLine("  Restored · ${profile.filesRestoredCount}/${profile.filesEncryptedEstimate}", "#00E676"))
        lines.add(TermLine("  Data loss · $dataLoss", dataLossColor))
        lines.add(TermLine("  Verdict ·· $verdict", verdictColor, 80))
        lines.add(TermLine(line('═', w), "#00C8FF"))
        lines.add(TermLine("", "#33FF33"))
        lines.add(TermLine("shield@defense:~$ _", "#33FF33"))

        typeTerminalLines(lines)
    }

    // ── Boot header — compact, screen-fitted ─────────────────────────────
    private fun buildBootHeader(w: Int): List<TermLine> {
        val lines = mutableListOf<TermLine>()

        lines.add(TermLine("", "#33FF33"))
        lines.add(TermLine("  ╔═╗ SHIELD", "#00C8FF", 20))
        lines.add(TermLine("  ╚═╝ Ransomware Defense System v1.0", "#666666", 20))
        lines.add(TermLine("  " + line('─', w - 2), "#333333"))
        lines.add(TermLine("  [sys] Kernel ····· shield-ebpf 5.15", "#555555"))
        lines.add(TermLine("  [sys] Watchdog ··· pid 1 · ALIVE", "#555555"))
        lines.add(TermLine("  [sys] Engines ···· 3/3 LOADED", "#555555"))
        lines.add(TermLine("  [sys] Honeyfiles · 5 traps deployed", "#555555"))
        lines.add(TermLine("  [sys] Network ···· VPN intercept OK", "#555555"))
        lines.add(TermLine("  " + line('─', w - 2), "#333333"))
        lines.add(TermLine("", "#33FF33"))

        return lines
    }

    // ── TermLine data class with optional custom delay ────────────────────
    private data class TermLine(
        val text: String,
        val color: String,
        val delayMs: Long = 35L  // default speed
    )

    private fun typeTerminalLines(lines: List<TermLine>) {
        val ssb = SpannableStringBuilder()
        var delay = 0L

        for ((i, line) in lines.withIndex()) {
            handler.postDelayed({
                if (!isAdded) return@postDelayed
                val start = ssb.length
                ssb.append(line.text)
                ssb.append("\n")
                ssb.setSpan(
                    ForegroundColorSpan(Color.parseColor(line.color)),
                    start, start + line.text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                terminalTextView.text = ssb

                // Auto-scroll to bottom
                val scrollView = terminalTextView.parent?.parent as? ScrollView
                scrollView?.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }, delay)
            delay += line.delayMs
        }
    }

    private fun padEnd(s: String, length: Int): String {
        return if (s.length >= length) s else s + " ".repeat(length - s.length)
    }

    private fun formatDuration(seconds: Long): String {
        return if (seconds > 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TAB 2: DnaReportFragment — Redesigned with expandable sections
// ─────────────────────────────────────────────────────────────────────────────
class DnaReportFragment : Fragment() {

    private val TAG = "SHIELD_DNA_FRAGMENT"
    private val viewModel: IncidentViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val scrollView = ScrollView(requireContext()).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
        }
        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 64)
        }
        scrollView.addView(contentLayout)

        // Show a spinner until the profile arrives
        val spinner = ProgressBar(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = 80 }
        }
        contentLayout.addView(spinner)

        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            contentLayout.removeAllViews()
            if (profile == null) {
                val density = requireContext().resources.displayMetrics.density
                
                val scanningCont = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    setPadding(0, (120 * density).toInt(), 0, 0)
                }
                
                scanningCont.addView(ProgressBar(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = (16 * density).toInt() }
                })
                
                scanningCont.addView(TextView(requireContext()).apply {
                    text = "Scanning system anomalies...\nAwaiting forensic profile generation."
                    textSize = 14f
                    setTextColor(Color.parseColor("#00C8FF"))
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                    setLineSpacing(6 * density, 1f)
                })
                
                contentLayout.addView(scanningCont)
                return@observe
            }
            buildReport(contentLayout, profile)
        }

        return scrollView
    }

    // ── Build the full report into a LinearLayout ────────────────────────────
    private fun buildReport(root: LinearLayout, p: RansomwareDnaProfile) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        // ── Hero header card ──────────────────────────────────────────────────
        val heroCard = FrameLayout(ctx).apply {
            val bg = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#0D1520"),
                    Color.parseColor("#0A1628"),
                    Color.parseColor("#101828")
                )
            )
            background = bg
            setPadding((24 * density).toInt(), (28 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (2 * density).toInt() }
        }

        val heroContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val familyColor = String.format("#%06X", 0xFFFFFF and p.attackFamily.severityColor)

        // Threat score ring area
        val scoreRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Score circle
        val scoreCircle = FrameLayout(ctx).apply {
            val size = (72 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = (20 * density).toInt() }
            val circleBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((3 * density).toInt(), Color.parseColor(familyColor))
                setColor(Color.parseColor("#0D1520"))
            }
            background = circleBg
        }
        val scoreText = TextView(ctx).apply {
            text = "${p.normalizedScore}"
            textSize = 24f
            setTextColor(Color.parseColor(familyColor))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        scoreCircle.addView(scoreText)
        scoreRow.addView(scoreCircle)

        // Family name + badge
        val familyInfo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        familyInfo.addView(TextView(ctx).apply {
            text = p.attackFamily.displayName
            textSize = 20f
            setTextColor(Color.parseColor(familyColor))
            setTypeface(null, Typeface.BOLD)
        })
        familyInfo.addView(TextView(ctx).apply {
            text = "Threat Score: ${p.normalizedScore}/100"
            textSize = 12f
            setTextColor(Color.parseColor("#8892A4"))
            setPadding(0, (4 * density).toInt(), 0, (6 * density).toInt())
        })

        // Severity badge
        val badgeBg = GradientDrawable().apply {
            cornerRadius = 4 * density
            setColor(Color.parseColor(familyColor))
        }
        familyInfo.addView(TextView(ctx).apply {
            text = "  ${p.confidenceLevel}  ·  ${p.getRiskSeverityLabel()}  "
            textSize = 10f
            setTextColor(Color.parseColor("#0A0E1A"))
            setTypeface(null, Typeface.BOLD)
            background = badgeBg
            setPadding((10 * density).toInt(), (3 * density).toInt(), (10 * density).toInt(), (3 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        scoreRow.addView(familyInfo)
        heroContent.addView(scoreRow)
        heroCard.addView(heroContent)
        root.addView(heroCard)

        // ── Key Metrics — Horizontal scroll of stat pills ──────────────────────
        val metricsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (2 * density).toInt() }
            setBackgroundColor(Color.parseColor("#0D1520"))
        }
        val metricsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }
        metricsRow.addView(buildMetricPill("${p.detectionTimeSeconds}s", "DETECTION", "#00E676"))
        metricsRow.addView(buildMetricPill("${p.filesRestoredCount}", "PROTECTED", "#00C8FF"))
        metricsRow.addView(buildMetricPill(formatRupees(p.estimatedRansomRupees), "RISK", "#FFB300"))
        metricsRow.addView(buildMetricPill("${p.totalFilesAtRisk}", "AT RISK", "#FF6B6B"))
        metricsScroll.addView(metricsRow)
        root.addView(metricsScroll)

        // ── Expandable section cards ──────────────────────────────────────────

        // Section: Incident Classification (collapsed by default)
        root.addView(buildExpandableSection(
            "INCIDENT CLASSIFICATION", "#00C8FF",
            listOf(
                Pair("Attack Family", p.attackFamily.displayName),
                Pair("CERT-In Category", p.attackFamily.certInCategory),
                Pair("Primary Detector", p.primaryDetector),
                Pair("SPRT Decision", if (p.sprtAcceptedH1) "ACCEPT H1" else "ACCEPT H0"),
                Pair("Entropy Score", "${p.entropyScore}/40"),
                Pair("KLD Score", "${p.kldScore}/30")
            )
        ))

        // Section: Target Profile
        root.addView(buildExpandableSection(
            "TARGET PROFILE", "#FFB300",
            listOf(
                Pair("Files at Risk", p.totalFilesAtRisk.toString()),
                Pair("Extensions", p.targetedExtensions.joinToString(", ").ifEmpty { "N/A" }),
                Pair("Priority", p.targetPriority),
                Pair("Speed", "%.1f files/min".format(p.encryptionSpeedFilesPerMin))
            )
        ))

        // Section: Network Activity (conditional)
        if (p.c2AttemptDetected) {
            root.addView(buildExpandableSection(
                "NETWORK ACTIVITY", "#FF3B3B",
                listOf(
                    Pair("C2 Blocked", p.c2BlockedCount.toString()),
                    Pair("Ports", p.portsTargeted.joinToString(", ").ifEmpty { "N/A" }),
                    Pair("Tor Network", if (p.torAttemptDetected) "DETECTED" else "NOT DETECTED")
                )
            ))
        }

        // Section: Damage Assessment
        val dataLoss = when {
            !p.dataLossOccurred || p.filesRestoredCount >= p.filesEncryptedEstimate -> "NONE"
            p.filesRestoredCount > 0 -> "PARTIAL"
            else -> "SIGNIFICANT"
        }
        root.addView(buildExpandableSection(
            "DAMAGE ASSESSMENT", "#FF6B6B",
            listOf(
                Pair("Files Modified", p.filesEncryptedEstimate.toString()),
                Pair("Files Restored", p.filesRestoredCount.toString()),
                Pair("Data Loss", dataLoss),
                Pair("Ransom Demand", formatRupees(p.estimatedRansomRupees))
            )
        ))

        // Section: Attribution (conditional)
        if (p.suspectPackage != null) {
            root.addView(buildExpandableSection(
                "ATTRIBUTION", "#E040FB",
                listOf(
                    Pair("Package", p.suspectPackage),
                    Pair("App Name", p.suspectAppName ?: "UNKNOWN")
                )
            ))
        }

        // ── Export: Glassmorphic PDF Button ───────────────────────────────────
        root.addView(buildGlassPdfButton(p))
    }

    // ── Metric Pill builder ──────────────────────────────────────────────────
    private fun buildMetricPill(value: String, label: String, hexColor: String): LinearLayout {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#141E30"))
                setStroke(1, Color.parseColor("#1E2D45"))
            }
            background = bg
            setPadding((18 * density).toInt(), (12 * density).toInt(), (18 * density).toInt(), (12 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (10 * density).toInt() }

            addView(TextView(ctx).apply {
                text = value
                textSize = 18f
                setTextColor(Color.parseColor(hexColor))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = label
                textSize = 8f
                setTextColor(Color.parseColor("#6B7A90"))
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
        }
    }

    // ── Expandable Section builder ───────────────────────────────────────────
    private fun buildExpandableSection(
        title: String,
        accentColor: String,
        data: List<Pair<String, String>>,
        expanded: Boolean = false
    ): LinearLayout {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val sectionRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (2 * density).toInt() }
        }

        // Header bar
        val headerBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
        }

        // Accent dot
        val accentDot = View(ctx).apply {
            val size = (8 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = (12 * density).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(accentColor))
            }
        }
        headerBar.addView(accentDot)

        val titleTv = TextView(ctx).apply {
            text = title
            textSize = 11f
            setTextColor(Color.parseColor(accentColor))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerBar.addView(titleTv)

        // Chevron arrow
        val chevron = TextView(ctx).apply {
            text = if (expanded) "▼" else "▶"
            textSize = 12f
            setTextColor(Color.parseColor("#6B7A90"))
        }
        headerBar.addView(chevron)

        sectionRoot.addView(headerBar)

        // Content area
        val contentArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1520"))
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
            visibility = if (expanded) View.VISIBLE else View.GONE
        }

        for (kv in data) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }

            // Separator line
            row.addView(View(ctx).apply {
                val lineH = (1 * density).toInt().coerceAtLeast(1)
                // This is a vertical separator styled as a thin left border
                layoutParams = LinearLayout.LayoutParams((2 * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT).also {
                    it.marginEnd = (10 * density).toInt()
                }
                setBackgroundColor(Color.parseColor(accentColor))
                alpha = 0.3f
            })

            row.addView(TextView(ctx).apply {
                text = kv.first
                textSize = 11f
                setTextColor(Color.parseColor("#6B7A90"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = kv.second
                textSize = 11f
                setTextColor(Color.parseColor("#E8EAF0"))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            })
            contentArea.addView(row)
        }

        sectionRoot.addView(contentArea)

        // Toggle expand/collapse
        headerBar.setOnClickListener {
            if (contentArea.visibility == View.VISIBLE) {
                contentArea.visibility = View.GONE
                chevron.text = "▶"
            } else {
                contentArea.visibility = View.VISIBLE
                chevron.text = "▼"
            }
        }

        return sectionRoot
    }

    // ── Glassmorphic PDF Export Button ────────────────────────────────────────
    private fun buildGlassPdfButton(profile: RansomwareDnaProfile): FrameLayout {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        val buttonContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (72 * density).toInt()
            ).also {
                it.topMargin = (24 * density).toInt()
                it.bottomMargin = (24 * density).toInt()
                it.marginStart = (24 * density).toInt()
                it.marginEnd = (24 * density).toInt()
            }
        }

        // Glass background
        val glassBg = GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(Color.parseColor("#2000C8FF")) // Semi-transparent cyan
            setStroke((1.5f * density).toInt(), Color.parseColor("#4000C8FF"))
        }

        val glassButton = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glassBg
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            elevation = 8 * density

            isClickable = true
            isFocusable = true
        }

        // Download icon — drawn as a simple arrow using text
        val downloadIcon = TextView(ctx).apply {
            text = "⬇"
            textSize = 22f
            setTextColor(Color.parseColor("#00C8FF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (14 * density).toInt() }
        }

        // Animate the download icon — bouncing up and down
        val bounceAnimator = ObjectAnimator.ofFloat(downloadIcon, "translationY", 0f, -10 * density, 0f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        bounceAnimator.start()

        val buttonText = TextView(ctx).apply {
            text = "EXPORT PDF REPORT"
            textSize = 14f
            setTextColor(Color.parseColor("#E8F0FF"))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.1f
        }

        glassButton.addView(downloadIcon)
        glassButton.addView(buttonText)

        // Shimmer/glow overlay
        val shimmerView = object : View(ctx) {
            private var shimmerX = -200f
            private val shimmerPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 200f, 0f,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.parseColor("#2000C8FF"),
                        Color.parseColor("#4000E5FF"),
                        Color.parseColor("#2000C8FF"),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.save()
                canvas.translate(shimmerX, 0f)
                canvas.drawRect(0f, 0f, 200f, height.toFloat(), shimmerPaint)
                canvas.restore()
            }

            fun startShimmer() {
                val animator = ValueAnimator.ofFloat(-200f, width.toFloat() + 200f).apply {
                    duration = 2500
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        shimmerX = it.animatedValue as Float
                        invalidate()
                    }
                }
                animator.start()
            }
        }
        shimmerView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // Clip the shimmer to the rounded rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            shimmerView.clipToOutline = true
            shimmerView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 16 * density)
                }
            }
        }

        shimmerView.post { shimmerView.startShimmer() }

        glassButton.setOnClickListener {
            // Press animation
            val scaleDown = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(glassButton, "scaleX", 1f, 0.95f),
                    ObjectAnimator.ofFloat(glassButton, "scaleY", 1f, 0.95f)
                )
                duration = 100
            }
            val scaleUp = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(glassButton, "scaleX", 0.95f, 1f),
                    ObjectAnimator.ofFloat(glassButton, "scaleY", 0.95f, 1f)
                )
                duration = 100
            }
            scaleDown.start()
            handler.postDelayed({ scaleUp.start() }, 120)
            handler.postDelayed({ exportPdf(profile) }, 250)
        }

        buttonContainer.addView(glassButton)
        buttonContainer.addView(shimmerView)

        return buttonContainer
    }

    private val handler = Handler(Looper.getMainLooper())

    // ── Export: PDF ──────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun exportPdf(profile: RansomwareDnaProfile) {
        val progress = ProgressDialog(requireContext()).apply {
            setMessage("Generating PDF report…")
            setCancelable(false)
            show()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ShieldPdfReportGenerator.generatePdf(requireContext(), profile)
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(requireContext(), "Report saved to Documents/", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "PDF export complete")
                }
                // Also fire share intent
                ShieldPdfReportGenerator.generateAndShare(requireContext(), profile)
            } catch (e: Exception) {
                Log.e(TAG, "exportPdf failed", e)
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(requireContext(), "PDF export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI builder helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildText(text: String, sizeSp: Float, hexColor: String) =
        TextView(requireContext()).apply {
            this.text = text
            textSize  = sizeSp
            setTextColor(Color.parseColor(hexColor))
            setPadding(0, 4, 0, 4)
        }

    private fun spacer(dp: Int) = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (dp * resources.displayMetrics.density).toInt()
        )
    }

    private fun formatRupees(amount: Long): String = if (amount >= 100_000L)
        "₹${amount / 100_000}.${(amount % 100_000) / 10_000}L"
    else "₹$amount"
}
