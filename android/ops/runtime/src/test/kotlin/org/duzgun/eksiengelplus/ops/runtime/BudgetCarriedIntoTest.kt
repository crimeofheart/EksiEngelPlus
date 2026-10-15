package org.duzgun.eksiengelplus.ops.runtime

import com.google.common.truth.Truth.assertThat
import org.duzgun.eksiengelplus.database.OperationCheckpointEntity
import org.duzgun.eksiengelplus.ops.engine.OperationState
import org.junit.Test

/**
 * How much foreground time a run starts with already spent.
 *
 * Wrong in one direction, a long run never finishes: the continuation booked
 * after the budget ran out resumed with the budget still spent, parked before
 * its first action, and booked another -- every day, for good, holding the
 * queue behind it. Wrong in the other, a paused run would earn a fresh five
 * hours from a tap on Duraklat.
 */
class BudgetCarriedIntoTest {

    private val fiveHours = ForegroundBudget.DEFAULT_SOFT_BUDGET_MS

    private fun row(state: OperationState, used: Long) = OperationCheckpointEntity(
        operationId = "op",
        type = "LIST",
        state = state.name,
        cursorJson = "{}",
        processed = 0,
        total = 0,
        successful = 0,
        failed = 0,
        startedAt = 0,
        updatedAt = 0,
        workRequestId = null,
        fgsMillisUsed = used,
    )

    private fun budgetFor(row: OperationCheckpointEntity?) =
        ForegroundBudget(clock = { 0L }).apply { resume(budgetCarriedInto(row)) }

    @Test fun `a run parked on the budget starts over when it comes back`() {
        val parked = row(OperationState.PAUSED_BUDGET, used = fiveHours + 1_000)

        assertThat(budgetCarriedInto(parked)).isEqualTo(0)
        assertThat(budgetFor(parked).isExhausted()).isFalse()
    }

    @Test fun `any other parked run keeps what it spent`() {
        listOf(
            OperationState.PAUSED,
            OperationState.PAUSED_AUTH,
            OperationState.INTERRUPTED,
            OperationState.RUNNING,
        ).forEach { state ->
            assertThat(budgetCarriedInto(row(state, used = 3_600_000))).isEqualTo(3_600_000)
        }
    }

    @Test fun `a run with no row yet starts at nothing`() {
        assertThat(budgetCarriedInto(null)).isEqualTo(0)
    }
}
