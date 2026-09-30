package com.aura.update

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aura.data.model.UpdateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateViewModel-helper"

/**
 * UpdateRepository — single source of truth for update state across the app.
 *
 * Shared between [SettingsScreen] (manual "Check for updates" button) and
 * [HomeScreen] (update banner). Both collect [updateInfo] from this singleton.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateChecker: UpdateChecker,
    private val updateManager: UpdateManager
) {
    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    /** Called from Settings screen "Check for updates" button. */
    suspend fun checkNow() {
        if (_isChecking.value) return
        _isChecking.value = true
        try {
            val info = updateChecker.checkForUpdate()
            _updateInfo.value = info
            Log.i(TAG, "Manual check: ${if (info?.isUpdateAvailable == true) "update available!" else "up to date"}")
        } finally {
            _isChecking.value = false
        }
    }

    /** Called when user taps "Install Update" in the UI. */
    fun installUpdate() {
        val info = _updateInfo.value ?: return
        if (!info.isUpdateAvailable) return
        val url = info.apkDownloadUrl ?: run {
            Log.w(TAG, "No APK asset in release — directing to GitHub page")
            return
        }
        updateManager.downloadAndInstall(url, info.latestVersion)
    }

    /** Publish update info found by the background WorkManager job. */
    fun setUpdateInfo(info: UpdateInfo) {
        _updateInfo.value = info
    }

    /** Dismiss the update banner (user tapped "Later"). */
    fun dismissUpdate() {
        _updateInfo.value = _updateInfo.value?.copy(isUpdateAvailable = false)
    }
}
