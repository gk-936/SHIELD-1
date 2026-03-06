package com.dearmoon.shield

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * GuideScreenAdapter — RecyclerView.Adapter for ViewPager2.
 *
 * 3 screen types:
 *  STANDARD     → item_guide_screen.xml
 *  HOW_IT_WORKS → inline with item_guide_screen.xml but renders the 3-row icons
 *  CONTROLS     → item_guide_controls.xml
 */
class GuideScreenAdapter(
    private val screens: List<GuideScreen>,
    private val onLetGoClick: () -> Unit
) : RecyclerView.Adapter<GuideScreenAdapter.GuideViewHolder>() {

    enum class ScreenType { STANDARD, HOW_IT_WORKS, CONTROLS }

    data class GuideScreen(
        val stepIndex:    Int,
        val iconResId:    Int,
        val iconSizeDp:   Int,
        val glowColor:    Int,
        val badgeText:    String?,
        val badgeColor:   Int,
        val mainText:     String,
        val mainTextSize: Float,
        val subText:      String,
        val noteText:     String?,
        val noteColor:    Int,
        val isLastScreen: Boolean,
        val screenType:   ScreenType
    )

    override fun getItemViewType(position: Int): Int = when (screens[position].screenType) {
        ScreenType.CONTROLS     -> 1
        ScreenType.HOW_IT_WORKS -> 2
        else                    -> 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuideViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout   = when (viewType) {
            1    -> R.layout.item_guide_controls
            else -> R.layout.item_guide_screen
        }
        return GuideViewHolder(inflater.inflate(layout, parent, false))
    }

    override fun getItemCount() = screens.size

    override fun onBindViewHolder(holder: GuideViewHolder, position: Int) {
        holder.bind(screens[position], onLetGoClick)
    }

    // ── ViewHolder ───────────────────────────────────────────────────────────

    class GuideViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        fun bind(screen: GuideScreen, onLetGoClick: () -> Unit) {
            when (screen.screenType) {
                ScreenType.CONTROLS     -> bindControls()
                ScreenType.HOW_IT_WORKS -> bindHowItWorks(screen)
                else                    -> bindStandard(screen, onLetGoClick)
            }
        }

        // ── Standard screen ──────────────────────────────────────────────────

        private fun bindStandard(screen: GuideScreen, onLetGoClick: () -> Unit) {
            val ctx = itemView.context

            // Badge
            val badge = itemView.findViewById<TextView>(R.id.badge)
            if (screen.badgeText != null) {
                badge.visibility = View.VISIBLE
                badge.text       = screen.badgeText
                val bg = badge.background.mutate() as? GradientDrawable
                bg?.setColor(screen.badgeColor)
            } else {
                badge.visibility = View.GONE
            }

            // Icon
            val stepIcon = itemView.findViewById<ImageView>(R.id.stepIcon)
            if (screen.iconResId != 0) {
                stepIcon.visibility = View.VISIBLE
                stepIcon.setImageResource(screen.iconResId)
                val dp = (screen.iconSizeDp * ctx.resources.displayMetrics.density).toInt()
                stepIcon.layoutParams = stepIcon.layoutParams.also {
                    it.width  = dp
                    it.height = dp
                }
                // We disable software tinting to let our new premium duotone cyber vectors shine with their natural colors.
                stepIcon.clearColorFilter()
            } else {
                stepIcon.visibility = View.GONE
            }

            // Main text
            val mainTv = itemView.findViewById<TextView>(R.id.mainText)
            mainTv.text     = screen.mainText
            mainTv.textSize = screen.mainTextSize

            // Sub text
            val subTv = itemView.findViewById<TextView>(R.id.subText)
            subTv.text = screen.subText

            // Note text
            val noteTv = itemView.findViewById<TextView>(R.id.noteText)
            if (screen.noteText != null) {
                noteTv.visibility = View.VISIBLE
                noteTv.text       = screen.noteText
                noteTv.setTextColor(screen.noteColor)
            } else {
                noteTv.visibility = View.GONE
            }

            // "How it works" rows are gone on STANDARD screens
            hideHowItWorksRows()

            // "Let's Go" pill button for last screen
            val letsGoBtn = itemView.findViewById<com.dearmoon.shield.ui.ScrambleTextButton>(R.id.letsGoButton)
            letsGoBtn?.let {
                it.visibility = if (screen.isLastScreen) View.VISIBLE else View.GONE
                if (screen.isLastScreen) {
                    it.setOnClickListener { onLetGoClick() }
                }
            }
        }

        // ── How It Works screen ──────────────────────────────────────────────

        private fun bindHowItWorks(screen: GuideScreen) {
            // Hide standard-only views
            itemView.findViewById<TextView?>(R.id.badge)?.visibility = View.GONE
            itemView.findViewById<ImageView?>(R.id.stepIcon)?.visibility = View.GONE
            itemView.findViewById<TextView?>(R.id.noteText)?.visibility  = View.GONE
            itemView.findViewById<View?>(R.id.letsGoButton)?.visibility  = View.GONE

            val mainTv = itemView.findViewById<TextView>(R.id.mainText)
            mainTv.text     = screen.mainText
            mainTv.textSize = screen.mainTextSize

            val subTv = itemView.findViewById<TextView>(R.id.subText)
            subTv.text = ""

            // Show how-it-works rows
            showHowItWorksRows()
        }

        private fun showHowItWorksRows() {
            itemView.findViewById<View?>(R.id.howItWorksContainer)?.visibility = View.VISIBLE
        }

        private fun hideHowItWorksRows() {
            itemView.findViewById<View?>(R.id.howItWorksContainer)?.visibility = View.GONE
        }

        // ── Controls screen ──────────────────────────────────────────────────

        private fun bindControls() {
            // Nothing additional needed — layout is fully static XML
        }
    }
}
