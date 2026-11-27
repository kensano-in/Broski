package com.verlyn.audio

import android.os.SystemClock

/** Shared 400ms guard for volume changes initiated by VERLYN itself. */
object VerlynVolumeChangeGuard {
    @Volatile private var lastProgrammaticChangeAt = 0L

    fun markProgrammaticChange() {
        lastProgrammaticChangeAt = SystemClock.elapsedRealtime()
    }

    fun shouldIgnore(now: Long = SystemClock.elapsedRealtime()): Boolean =
        now - lastProgrammaticChangeAt in 0L..400L
}
