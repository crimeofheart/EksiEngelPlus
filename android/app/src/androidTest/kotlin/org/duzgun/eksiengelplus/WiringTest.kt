package org.duzgun.eksiengelplus

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.testing.TestListenableWorkerBuilder
import org.duzgun.eksiengelplus.feature.lists.ListSyncWorker
import org.duzgun.eksiengelplus.ops.runtime.OperationWorker
import org.duzgun.eksiengelplus.ops.runtime.OpsNotifier
import org.duzgun.eksiengelplus.ops.runtime.TelemetryWorker
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * That the parts are connected, not that they work.
 *
 * Every serious defect this app has hit was of one shape: code that existed,
 * passed its own tests, and was wired to nothing. HiltWorkerFactory was never
 * installed, so no worker could be constructed and nothing WorkManager
 * scheduled ever ran. OperationCommandReceiver was declared in no manifest, so
 * the notification's buttons were dropped silently. None of it was reachable by
 * a unit test, because none of it is about a unit.
 */
class WiringTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The app must supply a WorkerFactory.
     *
     * Without one, WorkManager falls back to the default, which can only call a
     * two-argument constructor -- and every worker here is a @HiltWorker with
     * dependencies. The failure is silent: work enqueues, reports a queued
     * state, and dies the moment it is picked up.
     */
    @Test fun theApplicationSuppliesAWorkerFactory() {
        val app = context.applicationContext as Configuration.Provider

        assertThat(app.workManagerConfiguration.workerFactory).isNotNull()
    }

    /** And that factory must actually be able to build the app's workers. */
    @Test fun everyWorkerCanBeConstructed() {
        val factory = (context.applicationContext as Configuration.Provider)
            .workManagerConfiguration.workerFactory

        // Built through WorkManager's own test builder, so this exercises the
        // same path the runtime uses rather than a hand-made WorkerParameters.
        val ops = TestListenableWorkerBuilder<OperationWorker>(context)
            .setWorkerFactory(factory)
            .build()
        assertThat(ops).isNotNull()

        val sync = TestListenableWorkerBuilder<ListSyncWorker>(context)
            .setWorkerFactory(factory)
            .build()
        assertThat(sync).isNotNull()

        val telemetry = TestListenableWorkerBuilder<TelemetryWorker>(context)
            .setWorkerFactory(factory)
            .build()
        assertThat(telemetry).isNotNull()
    }

    /**
     * Anything the app sends an intent to has to be declared.
     *
     * An explicit broadcast to an undeclared receiver is dropped with no
     * exception and no log, which is how Duraklat and Durdur came to do nothing
     * at all.
     */
    @Test fun everyDispatchedComponentIsDeclared() {
        val components = listOf(
            "org.duzgun.eksiengelplus.ops.runtime.OperationCommandReceiver" to Kind.RECEIVER,
            "org.duzgun.eksiengelplus.feature.lists.ListsActivity" to Kind.ACTIVITY,
            "org.duzgun.eksiengelplus.feature.lists.AuthorListActivity" to Kind.ACTIVITY,
            "org.duzgun.eksiengelplus.feature.lists.OperationsActivity" to Kind.ACTIVITY,
            "org.duzgun.eksiengelplus.feature.settings.SettingsActivity" to Kind.ACTIVITY,
            "org.duzgun.eksiengelplus.BrowserActivity" to Kind.ACTIVITY,
        )

        for ((name, kind) in components) {
            val component = ComponentName(context.packageName, name)
            val declared = runCatching {
                when (kind) {
                    Kind.RECEIVER -> context.packageManager.getReceiverInfo(component, 0)
                    Kind.ACTIVITY -> context.packageManager.getActivityInfo(component, 0)
                }
            }.isSuccess

            assertWithMessage("$name is not declared in the merged manifest")
                .that(declared)
                .isTrue()
        }
    }

    private enum class Kind { RECEIVER, ACTIVITY }

    /**
     * The permissions the foreground service and its notification need.
     *
     * Undeclared, setForeground throws and a run dies before its first action.
     */
    @Test fun theForegroundServicePermissionsAreDeclared() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()

        assertThat(requested).containsAtLeast(
            "android.permission.INTERNET",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.POST_NOTIFICATIONS",
        )
    }

    /**
     * The Göster button must land somewhere.
     *
     * The notification and the Snackbar both fire an implicit intent, and the
     * screen that answers it lives in another module -- deliberately, since
     * :feature:lists depends on :ops:runtime and not the reverse. Nothing checks
     * the two spellings agree at compile time, and a mismatch is invisible: the
     * button appears, is tapped, and does nothing at all.
     */
    @Test fun theShowOperationsActionResolvesToAnActivity() {
        val intent = android.content.Intent(OpsNotifier.ACTION_SHOW_OPERATIONS)
            .setPackage(context.packageName)

        val resolved = context.packageManager.resolveActivity(intent, 0)

        assertWithMessage(
            "No activity answers ${OpsNotifier.ACTION_SHOW_OPERATIONS}. The intent-filter " +
                "in feature/lists AndroidManifest.xml must match the constant.",
        ).that(resolved).isNotNull()
    }
}
