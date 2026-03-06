package com.dearmoon.shield.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator  // android.view.animation — NOT androidx
import android.widget.FrameLayout

/**
 * FluidMenuView
 *
 * Floating expandable bottom menu — single hamburger circle in collapsed state,
 * 6 circle buttons in a horizontal row when expanded.
 *
 * Usage in XML (bottom of ConstraintLayout):
 *
 *   <com.dearmoon.shield.ui.FluidMenuView
 *       android:id="@+id/fluidMenu"
 *       android:layout_width="match_parent"
 *       android:layout_height="80dp"
 *       app:layout_constraintBottom_toBottomOf="parent"
 *       app:layout_constraintStart_toStartOf="parent"
 *       app:layout_constraintEnd_toEndOf="parent"/>
 *
 * Then in Activity:
 *   fluidMenu.setup(this, listOf(...MenuItem...))
 */
class FluidMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Public API ────────────────────────────────────────────────────────────

    data class MenuItem(
        val id: String,
        val label: String,
        val iconResId: Int,
        val isCenter: Boolean = false,
        val onClick: () -> Unit
    )

    var isExpanded: Boolean = false
        private set

    // ── Child views ───────────────────────────────────────────────────────────

    private lateinit var hamburgerButton: MenuCircleView
    private var menuButtons: List<MenuCircleView> = emptyList()
    private val buttonGapDp = 6   // 6dp gap — 5×52 + 1×56 + 5×6 = 346dp fits 360dp+ screens

    // ── setup() — call once from Activity after setContentView ───────────────

    fun setup(context: Context, items: List<MenuItem>) {
        removeAllViews()

        // Hamburger button
        hamburgerButton = MenuCircleView(
            context,
            isHamburger = true,
            label = "Menu"
        ).apply {
            onClick = { toggleMenu() }
        }
        addView(hamburgerButton)

        // Menu item buttons
        menuButtons = items.map { item ->
            MenuCircleView(
                context,
                isCenter  = item.isCenter,
                iconResId = item.iconResId,
                label     = item.label
            ).apply {
                onClick = item.onClick
                visibility = View.GONE
                alpha  = 0f
                scaleX = 0.8f
                scaleY = 0.8f
            }
        }
        menuButtons.forEach { addView(it) }
    }

    // ── Layout — position buttons manually ───────────────────────────────────

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!::hamburgerButton.isInitialized) return

        val w = right - left
        val h = bottom - top

        //햄버거 button — centred
        val hb = hamburgerButton
        val hbL = (w - hb.measuredWidth) / 2
        val hbT = (h - hb.measuredHeight) / 2
        hb.layout(hbL, hbT, hbL + hb.measuredWidth, hbT + hb.measuredHeight)

        // Menu row — centred horizontally, vertically centred in the container
        val gap = dp(buttonGapDp)
        val visible = menuButtons.filter { it.visibility != View.GONE }
        if (visible.isEmpty()) return

        val totalW = visible.sumOf { it.measuredWidth } + gap * (visible.size - 1)
        var x = (w - totalW) / 2
        visible.forEach { btn ->
            val btnT = (h - btn.measuredHeight) / 2
            btn.layout(x, btnT, x + btn.measuredWidth, btnT + btn.measuredHeight)
            x += btn.measuredWidth + gap
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Measure all children at their natural size
        if (::hamburgerButton.isInitialized) {
            hamburgerButton.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        menuButtons.forEach {
            it.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
    }

    // ── Expand / Collapse ─────────────────────────────────────────────────────

    fun toggleMenu() {
        if (!isExpanded) expandMenu() else collapseMenu()
    }

    fun expandMenu() {
        isExpanded = true

        // Step 1: hamburger fades out + scales down
        hamburgerButton.animate()
            .alpha(0f).scaleX(0.8f).scaleY(0.8f)
            .setDuration(200)
            .withEndAction {
                hamburgerButton.visibility = View.GONE

                // Make all visible + positioned FIRST so onLayout can measure them
                menuButtons.forEach { btn ->
                    btn.visibility = View.VISIBLE
                    btn.alpha  = 0f
                    btn.scaleX = 0f   // start fully collapsed (not 0.8) for true burst-out feel
                    btn.scaleY = 0f
                }
                requestLayout()  // measure + position all buttons in a single pass

                // Step 2: CENTER-OUTWARD burst
                // Delay = distance from centre index × 80ms
                // e.g. 5 buttons [0,1,2,3,4]: centre=2
                //   btn2 → delay 0ms   (centre)
                //   btn1, btn3 → delay 80ms
                //   btn0, btn4 → delay 160ms
                val centerIndex = menuButtons.size / 2
                menuButtons.forEachIndexed { index, btn ->
                    val distFromCenter = Math.abs(index - centerIndex)
                    val delay = distFromCenter * 80L

                    btn.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(delay)
                        .setDuration(350)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
            }.start()
    }

    fun collapseMenu() {
        isExpanded = false

        // All buttons fade out simultaneously
        menuButtons.forEach { btn ->
            btn.animate()
                .alpha(0f).scaleX(0.8f).scaleY(0.8f)
                .setDuration(200)
                .withEndAction { btn.visibility = View.GONE }
                .start()
        }

        // Hamburger fades in after 150ms
        postDelayed({
            hamburgerButton.visibility = View.VISIBLE
            hamburgerButton.alpha  = 0f
            hamburgerButton.scaleX = 0.8f
            hamburgerButton.scaleY = 0.8f
            hamburgerButton.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }, 150L)
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
