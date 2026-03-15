package com.example.ransomwaresimulator.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.ransomwaresimulator.databinding.ActivityLockerBinding

/**
 * LockerActivity simulates a fullscreen locker-ransomware overlay.
 *
 * Behavior:
 * - Fullscreen overlay that visually blocks navigation.
 * - Keeps screen awake while visible.
 * - Provides a visible UNLOCK button to dismiss immediately.
 * - Automatically dismisses itself after 60 seconds as a safety fallback.
 *
 * Monitoring:
 *   adb logcat | grep RANSOM_SIM
 */
class LockerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockerBinding
    private val autoDismissHandler = Handler(Looper.getMainLooper())

    private val autoDismissRunnable = Runnable {
        Log.d(TAG, "Locker auto-dismiss after 60 seconds")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        binding = ActivityLockerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d(TAG, "Locker screen activated")

        binding.unlockButton.setOnClickListener {
            Log.d(TAG, "Locker UNLOCK button pressed")
            finish()
        }

        // Auto-dismiss for safety after 60 seconds
        autoDismissHandler.postDelayed(autoDismissRunnable, 60_000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
    }

    override fun onBackPressed() {
        // Block back button to simulate a strong locker.
    }

    companion object {
        private const val TAG = "RANSOM_SIM"
    }
}

