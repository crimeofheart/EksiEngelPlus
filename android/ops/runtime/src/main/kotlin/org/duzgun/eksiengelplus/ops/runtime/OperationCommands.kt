package org.duzgun.eksiengelplus.ops.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * The only path from UI (or a notification action) into a running operation.
 *
 * A mailbox rather than a method call, because the caller and the worker are
 * often in different places: a notification tap arrives in a BroadcastReceiver
 * with no reference to the coroutine doing the work, and may arrive when no
 * screen exists at all. The worker reads this at each checkpoint, which is the
 * same cooperative model as resumableOperation.js -- just with a durable mailbox
 * instead of an in-memory flag.
 */
enum class OperationCommand { PAUSE, STOP }

interface OperationCommandBus {
    fun post(operationId: String, command: OperationCommand)
    fun peek(operationId: String): OperationCommand?
    fun clear(operationId: String)
}

/**
 * In-memory implementation.
 *
 * Sufficient because a command only has to survive as long as the worker it
 * targets: if the process dies, the operation is reconciled at startup and the
 * user is asked again rather than silently inheriting a stale pause. Persisting
 * commands would mean a PAUSE posted seconds before a crash quietly applying to
 * a run the user later chose to resume.
 */
class InMemoryCommandBus : OperationCommandBus {
    private val commands = ConcurrentHashMap<String, OperationCommand>()

    override fun post(operationId: String, command: OperationCommand) {
        // STOP outranks PAUSE: a user who asked to stop after pausing means stop.
        commands.compute(operationId) { _, existing ->
            if (existing == OperationCommand.STOP) existing else command
        }
    }

    override fun peek(operationId: String): OperationCommand? = commands[operationId]

    override fun clear(operationId: String) { commands.remove(operationId) }
}
