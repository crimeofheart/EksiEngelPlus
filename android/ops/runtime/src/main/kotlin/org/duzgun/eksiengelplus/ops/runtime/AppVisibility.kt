package org.duzgun.eksiengelplus.ops.runtime

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether any of the app's screens is showing.
 *
 * What the foreground budget needs to know and had no way to: bringing the app
 * to the foreground resets the platform's dataSync timer, so time spent with the
 * app open costs nothing. [ForegroundBudget.releaseForeground] existed for that
 * and nothing ever called it, so a run billed every minute the user watched it
 * and parked for the day hours before the platform would have stopped it.
 *
 * The process's lifecycle rather than any one activity's: the browser, İşlem
 * durumu and settings are all "the app" to the platform, and moving between
 * them must not read as leaving.
 */
@Singleton
class AppVisibility @Inject constructor() {

    private val state = MutableStateFlow(
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
    )

    val visible: StateFlow<Boolean> = state.asStateFlow()

    init {
        // addObserver insists on the main thread, and this is built wherever the
        // worker is: on WorkManager's executor.
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    state.value = event.targetState.isAtLeast(Lifecycle.State.STARTED)
                },
            )
        }
    }
}
