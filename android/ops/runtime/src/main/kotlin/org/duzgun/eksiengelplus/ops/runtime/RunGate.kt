package org.duzgun.eksiengelplus.ops.runtime

/**
 * Asked by [OperationWorker] before each run starts.
 *
 * The app binds its update gate (app/.../gate/UpdateGate.kt): a build the
 * maintainer's update policy stopped must not keep blocking or muting people on
 * Ekşi from a queued or resumed run. Bound optionally (`RunGateModule`), so the
 * devharness, which has no gate, blocks nothing.
 */
fun interface RunGate {
    fun blocks(): Boolean
}
