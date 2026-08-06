package org.duzgun.eksiengelplus

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * No UI yet -- android-foundations ships layers, not screens. This exists so the
 * Hilt graph is constructed and validated at build time from the first commit,
 * rather than being retrofitted once something already depends on it.
 */
@HiltAndroidApp
class EksiEngelPlusApp : Application()
