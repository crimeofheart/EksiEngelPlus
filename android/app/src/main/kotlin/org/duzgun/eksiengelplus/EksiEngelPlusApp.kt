package org.duzgun.eksiengelplus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Installs the Hilt graph and, with it, the WorkManager factory.
 *
 * [Configuration.Provider] is not optional here. Every worker in the app is a
 * @HiltWorker with injected dependencies, and the default WorkerFactory cannot
 * construct one -- it can only call a two-argument constructor. Without this the
 * jobs still enqueue and WorkManager still reports them, but each one fails the
 * moment it is picked up, so a Refresh does nothing and an operation never
 * starts, with nothing on screen to say why.
 *
 * The manifest removes WorkManagerInitializer so this configuration is the one
 * that takes effect; leaving the default initializer in place would build
 * WorkManager before Hilt could supply the factory.
 */
@HiltAndroidApp
class EksiEngelPlusApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
