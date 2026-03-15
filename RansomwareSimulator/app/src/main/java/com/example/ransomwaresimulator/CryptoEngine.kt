package com.example.ransomwaresimulator

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * CryptoEngine simulates crypto-ransomware behavior in a controlled and reversible way.
 *
 * SAFETY:
 * - All operations are restricted to the baseDir passed in (expected: /sdcard/ransom_test).
 * - Callers must ensure that baseDir.absolutePath == "/sdcard/ransom_test".
 *
 * Encryption scheme:
 * - Simple XOR with a fixed key so everything is fully reversible.
 *
 * Testing files (from a host shell):
 *   adb shell mkdir -p /sdcard/ransom_test
 *   adb shell "echo test1 > /sdcard/ransom_test/file1.txt"
 *   adb shell "echo test2 > /sdcard/ransom_test/file2.txt"
 *
 * Monitoring behavior:
 *   adb logcat | grep RANSOM_SIM
 */
class CryptoEngine(
    private val baseDir: File,
    private val highSpeed: Boolean
) {

    private val xorKey: Byte = 0x5A

    fun encryptAllFiles() {
        if (!baseDir.exists() || !baseDir.isDirectory) {
            Log.d(TAG, "Base directory does not exist: ${baseDir.absolutePath}")
            return
        }

        val files = collectTargetFiles(baseDir)
        if (files.isEmpty()) {
            Log.d(TAG, "No files to encrypt in ${baseDir.absolutePath}")
            return
        }

        Log.d(TAG, "Crypto simulation started. highSpeed=$highSpeed, files=${files.size}")

        if (highSpeed) {
            runInParallel(files)
        } else {
            runSequential(files)
        }
    }

    fun restoreAllFiles() {
        if (!baseDir.exists() || !baseDir.isDirectory) {
            Log.d(TAG, "Base directory does not exist: ${baseDir.absolutePath}")
            return
        }

        val lockedFiles = collectLockedFiles(baseDir)
        if (lockedFiles.isEmpty()) {
            Log.d(TAG, "No .locked files to restore in ${baseDir.absolutePath}")
            return
        }

        Log.d(TAG, "Restore simulation started. files=${lockedFiles.size}")
        for (file in lockedFiles) {
            try {
                restoreFile(file)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to restore ${file.absolutePath}", t)
            }
        }
        Log.d(TAG, "Restore completed")
    }

    private fun runSequential(files: List<File>) {
        for (file in files) {
            try {
                encryptFile(file)
                // 20–50 ms delay between files to simulate gradual encryption
                val delay = Random.nextLong(20L, 51L)
                Thread.sleep(delay)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to encrypt ${file.absolutePath}", t)
            }
        }
    }

    private fun runInParallel(files: List<File>) {
        val executor = Executors.newFixedThreadPool(4)
        for (file in files) {
            executor.execute {
                try {
                    encryptFile(file)
                    // Tiny delay to still show rapid activity while not blocking threads too much
                    Thread.sleep(5L)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to encrypt ${file.absolutePath}", t)
                }
            }
        }
        executor.shutdown()
        try {
            executor.awaitTermination(5, TimeUnit.MINUTES)
        } catch (_: InterruptedException) {
        }
    }

    private fun collectTargetFiles(dir: File): List<File> {
        val result = mutableListOf<File>()
        val children = dir.listFiles() ?: return result
        for (child in children) {
            if (child.isDirectory) {
                result += collectTargetFiles(child)
            } else {
                // Skip already locked files
                if (!child.name.endsWith(LOCKED_SUFFIX, ignoreCase = true)) {
                    result += child
                }
            }
        }
        return result
    }

    private fun collectLockedFiles(dir: File): List<File> {
        val result = mutableListOf<File>()
        val children = dir.listFiles() ?: return result
        for (child in children) {
            if (child.isDirectory) {
                result += collectLockedFiles(child)
            } else if (child.name.endsWith(LOCKED_SUFFIX, ignoreCase = true)) {
                result += child
            }
        }
        return result
    }

    @Throws(IOException::class)
    private fun encryptFile(inputFile: File) {
        val parent = inputFile.parentFile ?: return
        val lockedFile = File(parent, inputFile.name + LOCKED_SUFFIX)

        FileInputStream(inputFile).use { fis ->
            FileOutputStream(lockedFile).use { fos ->
                xorStream(fis, fos)
            }
        }

        if (!inputFile.delete()) {
            Log.w(TAG, "Failed to delete original file: ${inputFile.absolutePath}")
        }

        Log.d(TAG, "Encrypted: ${inputFile.absolutePath}")
    }

    @Throws(IOException::class)
    private fun restoreFile(lockedFile: File) {
        val parent = lockedFile.parentFile ?: return
        val originalName = lockedFile.name.removeSuffix(LOCKED_SUFFIX)
        val restoredFile = File(parent, originalName)

        FileInputStream(lockedFile).use { fis ->
            FileOutputStream(restoredFile).use { fos ->
                xorStream(fis, fos)
            }
        }

        if (!lockedFile.delete()) {
            Log.w(TAG, "Failed to delete locked file: ${lockedFile.absolutePath}")
        }

        Log.d(TAG, "Restored: ${restoredFile.absolutePath}")
    }

    @Throws(IOException::class)
    private fun xorStream(input: FileInputStream, output: FileOutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            for (i in 0 until read) {
                buffer[i] = (buffer[i].toInt() xor xorKey.toInt()).toByte()
            }
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    companion object {
        const val TAG = "RANSOM_SIM"
        private const val LOCKED_SUFFIX = ".locked"
    }
}

