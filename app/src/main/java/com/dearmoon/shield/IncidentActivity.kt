package com.dearmoon.shield

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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

        // Load layout defined in activity_incident.xml
        setContentView(R.layout.activity_incident)

        // Toolbar back navigation
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.incidentToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Tab + ViewPager2 wiring
        val viewPager = findViewById<ViewPager2>(R.id.incidentViewPager)
        val tabLayout = findViewById<TabLayout>(R.id.incidentTabLayout)

        viewPager.adapter = IncidentPagerAdapter(this)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (position == 0) "TIMELINE" else "DNA REPORT"
        }.attach()

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

        Log.d(TAG, "Building profile — window=[$attackWindowStart,$attackWindowEnd] score=$compositeScore")

        // Build profile on IO
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

    // ── ViewPager2 adapter — two static fragments ──────────────────────────
    private inner class IncidentPagerAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment =
            if (position == 0) TimelineFragment() else DnaReportFragment()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TAB 1: TimelineFragment
// Displays profile.timelineEvents as a vertical RecyclerView.
// ─────────────────────────────────────────────────────────────────────────────
class TimelineFragment : Fragment() {

    private val viewModel: IncidentViewModel by activityViewModels()
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var spinner: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Build layout programmatically — FrameLayout root with RecyclerView, empty state, spinner
        val root = FrameLayout(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#0A0E1A"))
        }

        spinner = ProgressBar(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.VISIBLE
        }

        recyclerView = RecyclerView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(requireContext())
            visibility = View.GONE
        }

        emptyText = TextView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            text      = "No attack events recorded"
            textSize  = 14f
            setTextColor(Color.parseColor("#8892A4"))
            gravity   = Gravity.CENTER
            visibility = View.GONE
        }

        root.addView(recyclerView)
        root.addView(spinner)
        root.addView(emptyText)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            spinner.visibility = View.GONE
            if (profile == null || profile.timelineEvents.isEmpty()) {
                emptyText.visibility  = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility    = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter    = TimelineAdapter(profile.timelineEvents)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TimelineAdapter — binds TimelineEvents to item_timeline_event.xml rows
// ─────────────────────────────────────────────────────────────────────────────
class TimelineAdapter(
    private val events: List<RansomwareDnaProfile.TimelineEvent>
) : RecyclerView.Adapter<TimelineAdapter.ViewHolder>() {

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ENGLISH)

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorBar:   View     = view.findViewById(R.id.timelineColorBar)
        val dot:        View     = view.findViewById(R.id.timelineDot)
        val description: TextView = view.findViewById(R.id.timelineDescription)
        val sourceTable: TextView = view.findViewById(R.id.timelineSourceTable)
        val timestamp:   TextView = view.findViewById(R.id.timelineTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timeline_event, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = events.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]

        // Colour mapping matches the PDF spec step 7
        val color = when (event.eventType) {
            TimelineEventType.FIRST_SIGNAL                                      -> Color.parseColor("#FFB300") // AMBER
            TimelineEventType.FILE_MODIFIED, TimelineEventType.HONEYFILE_HIT,
            TimelineEventType.HIGH_RISK_ALERT                                   -> Color.parseColor("#FF3B3B") // RED
            TimelineEventType.NETWORK_BLOCKED, TimelineEventType.VPN_ACTIVATED,
            TimelineEventType.PROCESS_KILLED                                    -> Color.parseColor("#00C8FF") // CYAN
            TimelineEventType.RESTORE_STARTED, TimelineEventType.RESTORE_COMPLETE -> Color.parseColor("#00E676") // GREEN
        }

        holder.colorBar.setBackgroundColor(color)
        holder.dot.background.setTint(color)
        holder.description.text  = event.description
        holder.sourceTable.text  = event.sourceTable
        holder.timestamp.text    = timeFmt.format(Date(event.timestamp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TAB 2: DnaReportFragment
// Scrollable report with 8 sections and 2 export buttons.
// ─────────────────────────────────────────────────────────────────────────────
class DnaReportFragment : Fragment() {

    private val TAG = "SHIELD_DNA_FRAGMENT"
    private val viewModel: IncidentViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val scrollView = ScrollView(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#0A0E1A"))
        }
        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 64)
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
                contentLayout.addView(buildText("No profile data available.", 14f, "#8892A4"))
                return@observe
            }
            buildReport(contentLayout, profile)
        }

        return scrollView
    }

    // ── Build the full report into a LinearLayout ────────────────────────────
    private fun buildReport(root: LinearLayout, p: RansomwareDnaProfile) {

        // ── Section 1: Attack summary header ──────────────────────────────────
        val familyColor = String.format("#%06X", 0xFFFFFF and p.attackFamily.severityColor)
        root.addView(buildText(p.attackFamily.displayName, 26f, familyColor).also {
            (it as TextView).setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(buildText("${p.compositeScore}/130  ·  Composite Risk Score", 14f, "#E8EAF0"))
        root.addView(buildBadge("Confidence: ${p.confidenceLevel}  |  ${p.getRiskSeverityLabel()}"))
        root.addView(spacer(16))

        // ── Section 2: Stat row ───────────────────────────────────────────────
        root.addView(buildSectionTitle("KEY METRICS"))
        val statRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        statRow.addView(buildStatCard("${p.detectionTimeSeconds}s",      "DETECTION TIME",    "#00E676", 1))
        statRow.addView(buildStatCard(p.filesRestoredCount.toString(),   "FILES PROTECTED",   "#00C8FF", 1))
        statRow.addView(buildStatCard(formatRupees(p.estimatedRansomRupees), "FINANCIAL RISK","#FFB300", 1))
        root.addView(statRow)
        root.addView(spacer(16))

        // ── Section 3: Classification card ────────────────────────────────────
        root.addView(buildSectionTitle("INCIDENT CLASSIFICATION"))
        val classCard = buildInfoCard()
        addKV(classCard, "Attack Family",   p.attackFamily.displayName)
        addKV(classCard, "CERT-In Category", p.attackFamily.certInCategory)
        addKV(classCard, "Primary Detector", p.primaryDetector)
        addKV(classCard, "SPRT Decision",   if (p.sprtAcceptedH1) "ACCEPT H1" else "ACCEPT H0")
        addKV(classCard, "Entropy Score",   "${p.entropyScore}/40")
        addKV(classCard, "KLD Score",       "${p.kldScore}/30")
        root.addView(classCard)
        root.addView(spacer(16))

        // ── Section 4: Target profile card ────────────────────────────────────
        root.addView(buildSectionTitle("TARGET PROFILE"))
        val targetCard = buildInfoCard()
        addKV(targetCard, "Files at Risk",   p.totalFilesAtRisk.toString())
        addKV(targetCard, "Extensions",      p.targetedExtensions.joinToString(", ").ifEmpty { "N/A" })
        addKV(targetCard, "Priority",        p.targetPriority)
        addKV(targetCard, "Speed",           "%.1f files/min".format(p.encryptionSpeedFilesPerMin))
        root.addView(targetCard)
        root.addView(spacer(16))

        // ── Section 5: Network card — only when C2 detected ───────────────────
        if (p.c2AttemptDetected) {
            root.addView(buildSectionTitle("NETWORK ACTIVITY", "#FF3B3B"))
            val netCard = buildInfoCard()
            addKV(netCard, "C2 Blocked",    p.c2BlockedCount.toString())
            addKV(netCard, "Ports",          p.portsTargeted.joinToString(", ").ifEmpty { "N/A" })
            addKV(netCard, "Tor Network",    if (p.torAttemptDetected) "DETECTED" else "NOT DETECTED")
            root.addView(netCard)
            root.addView(spacer(16))
        }

        // ── Section 6: Damage card ────────────────────────────────────────────
        root.addView(buildSectionTitle("DAMAGE ASSESSMENT"))
        val damageCard = buildInfoCard()
        val dataLoss = when {
            !p.dataLossOccurred || p.filesRestoredCount >= p.filesEncryptedEstimate -> "NONE"
            p.filesRestoredCount > 0 -> "PARTIAL"
            else -> "SIGNIFICANT"
        }
        addKV(damageCard, "Files Modified", p.filesEncryptedEstimate.toString())
        addKV(damageCard, "Files Restored", p.filesRestoredCount.toString())
        addKV(damageCard, "Data Loss",       dataLoss)
        addKV(damageCard, "Ransom Demand",   formatRupees(p.estimatedRansomRupees))
        root.addView(damageCard)
        root.addView(spacer(16))

        // ── Section 7: Attribution card — only when suspect known ─────────────
        if (p.suspectPackage != null) {
            root.addView(buildSectionTitle("ATTRIBUTION"))
            val attrCard = buildInfoCard()
            addKV(attrCard, "Package",   p.suspectPackage)
            addKV(attrCard, "App Name",  p.suspectAppName ?: "UNKNOWN")
            root.addView(attrCard)
            root.addView(spacer(16))
        }

        // ── Section 8: Export buttons ─────────────────────────────────────────
        root.addView(buildSectionTitle("EXPORT"))

        val btnPdf = Button(requireContext()).apply {
            text                = "EXPORT PDF"
            setTextColor(Color.parseColor("#0A0E1A"))
            backgroundTintList  = android.content.res.ColorStateList.valueOf(Color.parseColor("#00C8FF"))
            letterSpacing       = 0.05f
            layoutParams    = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 12 }
        }
        btnPdf.setOnClickListener { exportPdf(p) }
        root.addView(btnPdf)

        val btnCert = Button(requireContext()).apply {
            text               = "EXPORT CERT-In TEXT"
            setTextColor(Color.parseColor("#E8EAF0"))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A2744"))
            letterSpacing      = 0.05f
            layoutParams   = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnCert.setOnClickListener { exportCertText(p) }
        root.addView(btnCert)
    }

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

    // ── Export: CERT-In text ─────────────────────────────────────────────────
    private fun exportCertText(profile: RansomwareDnaProfile) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir  = requireContext()
                    .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: requireContext().filesDir
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "SHIELD_Report_${profile.profileId}.txt")
                FileWriter(file).use { it.write(profile.toCertInText()) }

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "CERT-In Report — ${profile.profileId}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "CERT-In report ready", Toast.LENGTH_LONG).show()
                    startActivity(Intent.createChooser(shareIntent, "Share CERT-In Report"))
                    Log.d(TAG, "CERT-In text export complete")
                }
            } catch (e: Exception) {
                Log.e(TAG, "exportCertText failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "CERT-In export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI builder helpers (programmatic views — no extra layout files needed)
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildText(text: String, sizeSp: Float, hexColor: String) =
        TextView(requireContext()).apply {
            this.text = text
            textSize  = sizeSp
            setTextColor(Color.parseColor(hexColor))
            setPadding(0, 4, 0, 4)
        }

    private fun buildSectionTitle(title: String, hexColor: String = "#00C8FF") =
        TextView(requireContext()).apply {
            text     = title
            textSize = 11f
            setTextColor(Color.parseColor(hexColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 6)
            letterSpacing = 0.1f
        }

    private fun buildBadge(label: String) = TextView(requireContext()).apply {
        text      = label
        textSize  = 10f
        setTextColor(Color.parseColor("#0A0E1A"))
        setBackgroundColor(Color.parseColor("#00C8FF"))
        setPadding(16, 6, 16, 6)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = 6 }
    }

    private fun buildStatCard(value: String, label: String, hexColor: String, weight: Int) =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101828"))
            setPadding(16, 20, 16, 20)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight.toFloat())
                .also { it.marginEnd = 6 }
            addView(TextView(context).apply {
                text = value; textSize = 20f
                setTextColor(Color.parseColor(hexColor))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = label; textSize = 9f
                setTextColor(Color.parseColor("#8892A4"))
            })
        }

    private fun buildInfoCard() = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#101828"))
        setPadding(20, 16, 20, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun addKV(parent: LinearLayout, key: String, value: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        row.addView(TextView(requireContext()).apply {
            text = key
            textSize = 11f
            setTextColor(Color.parseColor("#8892A4"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(requireContext()).apply {
            text = value
            textSize = 11f
            setTextColor(Color.parseColor("#E8EAF0"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        })
        parent.addView(row)
    }

    private fun spacer(dp: Int) = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (dp * resources.displayMetrics.density).toInt()
        )
    }

    private fun formatRupees(amount: Long): String = if (amount >= 100_000L)
        "Rs.${amount / 100_000}.${(amount % 100_000) / 10_000}L"
    else "Rs.$amount"
}
