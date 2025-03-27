package org.thoughtcrime.securesms.util

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

private val TAG: String = VersionDataFetcher::class.java.simpleName
private val REFRESH_TIME_MS = 4.hours.inWholeMilliseconds

/**
 * This class has been modified to disable update checks from Session Manager.
 * The app is now fully decoupled from the original Session app.
 */
@Singleton
class VersionDataFetcher @Inject constructor(
    private val prefs: TextSecurePreferences
) {
    private val handler = Handler(Looper.getMainLooper())
    private val fetchVersionData = Runnable {
        // Version checks are disabled
        Log.i(TAG, "Version checks disabled - app is decoupled from Session Manager")
        prefs.setLastVersionCheck()
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Stub implementation that doesn't actually check for updates.
     * Keeps the same interface to avoid breaking application architecture.
     */
    @JvmOverloads
    fun startTimedVersionCheck(
        delayMillis: Long = REFRESH_TIME_MS + prefs.getLastVersionCheck() - System.currentTimeMillis()
    ) {
        // Do nothing - version checks are disabled
        Log.d(TAG, "Update checks disabled")
    }

    fun stopTimedVersionCheck() {
        handler.removeCallbacks(fetchVersionData)
    }
}
