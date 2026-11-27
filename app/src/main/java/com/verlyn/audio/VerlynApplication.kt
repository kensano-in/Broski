package com.verlyn.audio

import android.app.Application
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class VerlynApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { writer ->
                    PrintWriter(writer).use { printer ->
                        printer.println("Thread: ${thread.name}")
                        printer.println("Exception: ${error.javaClass.name}")
                        printer.println("Message: ${error.message ?: "(no message)"}")
                        error.printStackTrace(printer)
                    }
                }.toString()
                getSharedPreferences(CRASH_PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_LAST_CRASH, trace)
                    .putLong(KEY_CRASH_TIME, System.currentTimeMillis())
                    .commit()
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    companion object {
        const val CRASH_PREFS = "verlyn_crash_reports"
        const val KEY_LAST_CRASH = "last_crash_trace"
        const val KEY_CRASH_TIME = "last_crash_time"
    }
}
