package com.dearmoon.shield.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton
import java.util.Random

class ScrambleTextButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    private val CHAR_SET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val DURATION_MS = 800L
    private val TICK_MS = 40L
    private val STEPS = DURATION_MS / TICK_MS

    private var targetText: String = ""
    private var displayText: String = ""
    private var isAnimating = false
    private var currentStep = 0
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()

    private var baseText: String = ""

    init {
        baseText = text.toString().trim()
        targetText = "[ $baseText ]"
        displayText = targetText
        text = displayText
    }

    fun startScramble() {
        if (isAnimating) return
        isAnimating = true
        currentStep = 0
        handler.post(scrambleTick)
    }

    private val scrambleTick = object : Runnable {
        override fun run() {
            val progress = currentStep.toFloat() / STEPS.toFloat()
            val sb = StringBuilder()

            sb.append("[ ")
            for (i in baseText.indices) {
                when {
                    baseText[i] == ' ' -> sb.append(' ')
                    progress * baseText.length > i -> sb.append(baseText[i])
                    else -> sb.append(CHAR_SET[random.nextInt(CHAR_SET.length)])
                }
            }

            if (currentStep < STEPS) {
                sb.append(" \u2588 ]") // Terminal block cursor
            } else {
                sb.append(" ]")
            }

            displayText = sb.toString()
            text = displayText

            currentStep++

            if (currentStep <= STEPS) {
                handler.postDelayed(this, TICK_MS)
            } else {
                text = targetText
                isAnimating = false
            }
        }
    }

    fun stopScramble() {
        handler.removeCallbacks(scrambleTick)
        isAnimating = false
        text = targetText
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopScramble()
    }

    fun restartScramble() {
        stopScramble()
        startScramble()
    }
}
