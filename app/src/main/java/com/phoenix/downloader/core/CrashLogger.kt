package com.phoenix.downloader.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple in-app crash logger.
 * Catches uncaught exceptions and writes them to filesDir/crash.log.
 * User can export via long-press on the language button.
 */
object CrashLogger {

    private const val CRASH_FILE = "crash.log"
    private const val MAX_SIZE_BYTES = 256 * 1024 // 256KB

    fun init(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
            } catch (_: Exception) {
                // Never crash the crash handler
            }
            // Chain to the default handler (system crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.filesDir, CRASH_FILE)

        // Rotate if too large
        if (file.exists() && file.length() > MAX_SIZE_BYTES) {
            file.delete()
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val entry = buildString {
            appendLine("=== CRASH $timestamp ===")
            appendLine("Thread: ${thread.name}")
            appendLine("App: com.phoenix.downloader")
            appendLine(sw.toString())
            appendLine()
        }

        file.appendText(entry)
    }

    /** Log a non-fatal exception with full stack trace (for diagnostics). */
    fun logNonFatal(context: Context, tag: String, throwable: Throwable) {
        val file = File(context.filesDir, CRASH_FILE)
        if (file.exists() && file.length() > MAX_SIZE_BYTES) {
            file.delete()
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val entry = buildString {
            appendLine("=== NON-FATAL $timestamp [$tag] ===")
            appendLine(sw.toString())
            appendLine()
        }
        file.appendText(entry)
    }

    /** Returns the crash log content, or null if empty/missing. */
    fun getLog(context: Context): String? {
        return try {
            val file = File(context.filesDir, CRASH_FILE)
            if (!file.exists()) return null
            val content = file.readText()
            content.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Clears the crash log. */
    fun clear(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
    }
}
