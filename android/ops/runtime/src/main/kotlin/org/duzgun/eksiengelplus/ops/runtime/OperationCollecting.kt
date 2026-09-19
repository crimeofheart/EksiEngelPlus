package org.duzgun.eksiengelplus.ops.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How many targets the running operation has gathered so far.
 *
 * A run spends its first minutes walking follower or relation pages, and until
 * that finishes there is no target list, so nothing to count against: the row
 * read "0 / 0 · 0 başarılı · 0 başarısız" for the whole collection phase. On a
 * large account that is indistinguishable from a run that is stuck, which is
 * exactly how it was read -- and the buttons underneath it did nothing, which
 * confirmed the diagnosis.
 *
 * In memory, like [OperationWaits] and the command bus, and for the same reason:
 * it describes a worker, and is worth precisely as long as that worker lives. A
 * count restored from disk would describe a collection phase that no longer
 * exists.
 */
class OperationCollecting {

    private val _found = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Targets gathered per operation. Absent means the run has a real total. */
    val found: StateFlow<Map<String, Int>> = _found.asStateFlow()

    fun set(operationId: String, found: Int) {
        _found.value = _found.value + (operationId to found)
    }

    fun clear(operationId: String) {
        _found.value = _found.value - operationId
    }
}
