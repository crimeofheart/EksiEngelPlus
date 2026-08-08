package org.duzgun.eksiengelplus.feature.lists

import kotlin.coroutines.cancellation.CancellationException

/**
 * runCatching, minus the one thing it must never catch.
 *
 * runCatching catches Throwable, and CancellationException is a Throwable, so
 * closing a screen mid-import reported "işlem başarısız: Job was cancelled" --
 * the coroutine machinery's own bookkeeping shown to the user as a failed
 * operation, for work they cancelled by leaving.
 *
 * Cancellation is not a failure and swallowing it is worse than the wrong
 * message: a scope whose CancellationException is absorbed never finishes
 * unwinding, so the code after it runs inside a job that is already dead.
 */
internal inline fun <T> runCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
