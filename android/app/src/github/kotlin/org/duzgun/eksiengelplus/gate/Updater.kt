package org.duzgun.eksiengelplus.gate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.duzgun.eksiengelplus.R

/**
 * The sideloaded build has no store behind it: it updates from the APK the
 * signed policy names ([ApkInstaller]), or, when the policy names none, from the
 * website. The Play flavor has its own Updater with the same shape.
 */
internal object Updater {

    /** Never installed by a store that could deliver the update. */
    @Suppress("UNUSED_PARAMETER")
    fun fromStore(context: Context): Boolean = false

    /** Unknown without a store. */
    val lastKnownAvailable: Boolean? get() = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun refreshAvailability(context: Context) = Unit

    suspend fun start(activity: Activity, gate: UpdateGate, status: (String) -> Unit) {
        val state = gate.state()
        val url = state.apkUrl
        val sha256 = state.sha256
        if (url == null || sha256 == null) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)))
            return
        }
        if (!ApkInstaller.canInstall(activity)) {
            status(activity.getString(R.string.gate_allow_install))
            runCatching { activity.startActivity(ApkInstaller.permissionIntent(activity)) }
            return
        }
        status(activity.getString(R.string.gate_downloading, 0))
        ApkInstaller.download(activity, url, sha256) { percent ->
            activity.runOnUiThread { status(activity.getString(R.string.gate_downloading, percent)) }
        }.onSuccess { file ->
            if (!ApkInstaller.install(activity, file)) status(activity.getString(R.string.gate_installer_failed))
        }.onFailure { error ->
            status(
                if (error is ApkInstaller.HashMismatch) {
                    activity.getString(R.string.gate_download_invalid)
                } else {
                    activity.getString(R.string.gate_download_failed, error.message ?: error.javaClass.simpleName)
                },
            )
        }
    }

    private const val WEBSITE = "https://eksiengelplus.duzgun.org/"
}
