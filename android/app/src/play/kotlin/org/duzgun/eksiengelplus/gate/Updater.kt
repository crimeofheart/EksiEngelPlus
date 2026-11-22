package org.duzgun.eksiengelplus.gate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.duzgun.eksiengelplus.R

/**
 * Google Play delivers the Play build's updates (ported from OpenOTT's
 * StoreUpdate). Güncelle runs Play's own full-screen update flow; without Play,
 * or before Play offers the version, the store page opens instead. This build
 * never downloads or installs anything itself: Play forbids it.
 */
internal object Updater {
    private const val PLAY_STORE = "com.android.vending"
    private const val REQUEST_UPDATE = 0x0717

    /** True when Google Play installed this copy, so Play is where the update comes from. */
    fun fromStore(context: Context): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer == PLAY_STORE
    }.getOrDefault(false)

    /** Whether Play offered an update at the last look; null when Play could not be asked. */
    @Volatile
    var lastKnownAvailable: Boolean? = null
        private set

    suspend fun refreshAvailability(context: Context) {
        if (!fromStore(context)) return
        lastKnownAvailable = info(context)?.let(::ready)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun start(activity: Activity, gate: UpdateGate, status: (String) -> Unit) {
        status(activity.getString(R.string.gate_opening_store))
        val info = if (fromStore(activity)) info(activity) else null
        if (info != null && ready(info) && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            val started = runCatching {
                @Suppress("DEPRECATION")
                AppUpdateManagerFactory.create(activity).startUpdateFlowForResult(
                    info,
                    activity,
                    AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE),
                    REQUEST_UPDATE,
                )
            }.getOrDefault(false)
            if (started) return
        }
        openStorePage(activity)
    }

    private fun ready(info: AppUpdateInfo): Boolean =
        info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE ||
            info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS

    private suspend fun info(context: Context): AppUpdateInfo? = runCatching {
        suspendCancellableCoroutine<AppUpdateInfo?> { continuation ->
            AppUpdateManagerFactory.create(context).appUpdateInfo
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
    }.getOrNull()

    private fun openStorePage(activity: Activity) {
        val pkg = activity.packageName
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage(PLAY_STORE))
        }.recoverCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }
}
