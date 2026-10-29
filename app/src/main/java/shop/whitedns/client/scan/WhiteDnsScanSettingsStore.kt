package shop.whitedns.client.scan

import android.content.Context
import shop.whitedns.client.model.WhiteDnsScanDefaults
import shop.whitedns.client.model.WhiteDnsScanState
import shop.whitedns.client.model.WhiteDnsScanStatus

class WhiteDnsScanSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun loadWorkerCount(): Int {
        return preferences
            .getInt(KeyWorkerCount, WhiteDnsScanDefaults.DefaultWorkerCount)
            .coerceAtLeast(1)
    }

    fun saveWorkerCount(workerCount: Int) {
        preferences.edit()
            .putInt(KeyWorkerCount, workerCount.coerceAtLeast(1))
            .apply()
    }

    fun loadConnectionProfileId(): String {
        return preferences.getString(KeyConnectionProfileId, null).orEmpty()
    }

    fun saveConnectionProfileId(profileId: String) {
        preferences.edit()
            .putString(KeyConnectionProfileId, profileId)
            .apply()
    }

    fun loadHandledResultSessionId(initialState: WhiteDnsScanState): String {
        val saved = preferences.getString(KeyHandledResultSessionId, null)
        val handled = initialHandledScanSessionId(saved, initialState)
        // One-time migration protects user edits from a legacy completed scan.
        // Once initialized, a new scan that completes while the UI is closed
        // still differs from this persisted marker and can autosave on reopen.
        if (saved == null) saveHandledResultSessionId(handled)
        return handled
    }

    fun saveHandledResultSessionId(sessionId: String) {
        preferences.edit().putString(KeyHandledResultSessionId, sessionId).apply()
    }

    private companion object {
        const val PreferencesName = "white_dns_scan_settings"
        const val KeyWorkerCount = "worker_count"
        const val KeyConnectionProfileId = "connection_profile_id"
        const val KeyHandledResultSessionId = "handled_result_session_id"
    }
}

internal fun initialHandledScanSessionId(saved: String?, initialState: WhiteDnsScanState): String =
    saved ?: initialState.sessionId.takeIf { initialState.status == WhiteDnsScanStatus.Completed }.orEmpty()
