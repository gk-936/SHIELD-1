package com.example.ransomwaresimulator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ransomwaresimulator.databinding.ActivityMainBinding
import com.example.ransomwaresimulator.ui.LockerActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * RansomwareSimulator main activity.
 *
 * Features implemented here:
 * - Start Crypto Simulation (single-threaded + optional High Speed Mode with 4 workers).
 * - Start Locker Simulation (fullscreen overlay activity).
 * - Restore Files (.locked -> original) using the same reversible XOR transformation.
 * - High Speed Mode toggle.
 * - TextView showing simulation log lines.
 *
 * Safety:
 * - All file operations are hard-coded to /sdcard/ransom_test.
 * - Runtime storage permissions are required before any simulation runs.
 * - User confirmation dialog appears before starting crypto or locker simulations and before restore.
 *
 * Test data creation (from host machine via adb):
 *
 *   adb shell mkdir -p /sdcard/ransom_test
 *   adb shell "echo test1 > /sdcard/ransom_test/file1.txt"
 *   adb shell "echo test2 > /sdcard/ransom_test/file2.txt"
 *
 * Monitoring simulator behavior:
 *
 *   adb logcat | grep RANSOM_SIM
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val storagePermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = storagePermissions.all { perm -> result[perm] == true }
            if (!allGranted) {
                toast("Storage permissions are required for this simulator.")
            } else {
                toast("Storage permissions granted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
    }

    private fun setupUi() {
        binding.startCryptoButton.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            confirmAndRun(
                title = "Start Crypto Simulation",
                message = "This app simulates ransomware behavior for security testing.\n\n" +
                        "It will encrypt files ONLY inside /sdcard/ransom_test using a reversible method."
            ) {
                startCryptoSimulation()
            }
        }

        binding.startLockerButton.setOnClickListener {
            confirmAndRun(
                title = "Start Locker Simulation",
                message = "This will display a fullscreen overlay that simulates locker ransomware.\n\n" +
                        "You can always exit via the UNLOCK button or automatically after 60 seconds."
            ) {
                startLockerSimulation()
            }
        }

        binding.restoreFilesButton.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            confirmAndRun(
                title = "Restore Files",
                message = "This will attempt to restore all .locked files inside /sdcard/ransom_test."
            ) {
                startRestore()
            }
        }

        binding.highSpeedSwitch.setOnCheckedChangeListener { _, isChecked ->
            appendLog("High Speed Mode: ${if (isChecked) "ENABLED" else "DISABLED"}")
        }

        binding.warningText.setOnClickListener {
            // Quick tap to show an extra reminder toast.
            toast("For controlled cybersecurity testing only. Operates on /sdcard/ransom_test.")
        }
    }

    private fun getBaseDir(): File {
        val externalRoot = Environment.getExternalStorageDirectory()
        return File(externalRoot, "ransom_test")
    }

    private fun startCryptoSimulation() {
        val baseDir = getBaseDir()
        val highSpeed = binding.highSpeedSwitch.isChecked
        appendLog("Crypto simulation requested. baseDir=${baseDir.absolutePath}, highSpeed=$highSpeed")
        Log.d(TAG, "simulation started: crypto, highSpeed=$highSpeed, dir=${baseDir.absolutePath}")

        ioExecutor.execute {
            val engine = CryptoEngine(baseDir, highSpeed)
            engine.encryptAllFiles()
            runOnUiThread {
                appendLog("Crypto simulation finished.")
            }
        }
    }

    private fun startRestore() {
        val baseDir = getBaseDir()
        appendLog("Restore requested. baseDir=${baseDir.absolutePath}")
        Log.d(TAG, "simulation started: restore, dir=${baseDir.absolutePath}")

        ioExecutor.execute {
            val engine = CryptoEngine(baseDir, highSpeed = false)
            engine.restoreAllFiles()
            runOnUiThread {
                appendLog("Restore completed.")
            }
        }
    }

    private fun startLockerSimulation() {
        Log.d(TAG, "Locker simulation started")
        appendLog("Starting locker simulation (fullscreen overlay).")
        val intent = Intent(this, LockerActivity::class.java)
        startActivity(intent)
    }

    private fun ensurePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // On modern Android, media-specific permissions would be preferable,
            // but for this research simulator we use legacy storage permissions as requested.
        }

        val missing = storagePermissions.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        return if (missing.isNotEmpty()) {
            permissionsLauncher.launch(storagePermissions)
            false
        } else {
            true
        }
    }

    private fun confirmAndRun(title: String, message: String, block: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message + "\n\nWARNING: Do not use on real user data.")
            .setPositiveButton("Proceed") { _, _ -> block() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        val current = binding.logTextView.text?.toString() ?: ""
        val newText = if (current.isEmpty()) line else "$current\n$line"
        binding.logTextView.text = newText

        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    companion object {
        const val TAG = "RANSOM_SIM"
    }
}

