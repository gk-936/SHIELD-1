package com.dearmoon.shield.features

import android.view.View
import androidx.viewpager2.widget.ViewPager2

class StackedCardTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.apply {
            when {
                position < -1f -> { // card has left completely
                    alpha = 0f
                }
                position <= 0f -> {
                    // CURRENT CARD — animating out upward
                    alpha = 1f
                    
                    translationY = height * position * 1.8f
                    rotationX = position * -15f
                    scaleX = 1f
                    scaleY = 1f
                    elevation = (1f + position) * 8f
                }
                position <= 1f -> {
                    // NEXT CARD — sitting in the deck behind
                    alpha = 1f
                    
                    val density = resources.displayMetrics.density
                    translationY = position * (40f * density)
                    
                    val scale = 1f - position * 0.05f
                    scaleX = scale
                    scaleY = scale
                    
                    rotationX = position * 8f
                    elevation = (1f - position) * 8f
                }
                else -> {
                    alpha = 0f
                }
            }
        }
    }
}
