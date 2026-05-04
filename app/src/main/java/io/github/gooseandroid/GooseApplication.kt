package io.github.gooseandroid

import android.app.Application
import android.util.Log

/**
 * Application class that installs a global uncaught exception handler.
 * This prevents silent crashes and ensures errors are logged for debugging.
 */
class GooseApplication : Application() {

    companion object {
        private const val TAG = "GooseApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Install global crash handler that logs the error
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
            Log.e(TAG, "Stack trace: ${throwable.stackTraceToString()}")

            // Log to our LogCollector so the user can see it in the Logs screen
            try {
                io.github.gooseandroid.ui.doctor.LogCollector.addLine(
                    "CRASH: ${throwable.javaClass.simpleName}: ${throwable.message}"
                )
                io.github.gooseandroid.ui.doctor.LogCollector.addLine(
                    throwable.stackTraceToString().take(500)
                )
            } catch (_: Exception) {
                // Don't crash in the crash handler
            }

            // Pass to the default handler (which will show the crash dialog or kill the app)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
