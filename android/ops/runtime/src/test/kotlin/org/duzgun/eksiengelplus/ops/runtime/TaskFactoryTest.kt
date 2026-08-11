package org.duzgun.eksiengelplus.ops.runtime

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.duzgun.eksiengelplus.eksi.client.RelationClient
import org.duzgun.eksiengelplus.eksi.client.ScrapeClient
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.duzgun.eksiengelplus.ops.engine.RelationListTask
import org.duzgun.eksiengelplus.ops.engine.TargetRunner
import org.duzgun.eksiengelplus.ops.runtime.di.OpsModule
import org.junit.Test

/**
 * Which list each source walks.
 *
 * No server: the wiring is the thing under test, and it decides the list before
 * a request is ever made. That is also how the defect survived — every piece of
 * a date-based run read correctly, and the factory quietly handed it the blocked
 * list whatever the user picked.
 */
class TaskFactoryTest {

    private val http = OkHttpClient()
    private val base = { "https://example.invalid" }
    private val factory = OpsModule.taskFactory(
        TargetRunner(RelationClient(http, base), ScrapeClient(http, baseUrlProvider = base)),
        ScrapeClient(http, baseUrlProvider = base),
    )

    private fun listWalkedBy(request: OperationRequest): TargetType? =
        (factory.create(request) as? RelationListTask)?.listOf

    private fun dateBased(list: TargetType?) = OperationRequest(
        source = BanSource.DATE_BASED_BULK,
        mode = BanMode.UNDOBAN,
        targetType = TargetType.MUTE,
        relationListOf = list,
    )

    @Test fun `the muted source walks the muted list`() {
        // The defect: this read the blocked list and then sent removerelation
        // r=u for people who had never been muted.
        assertThat(listWalkedBy(dateBased(TargetType.MUTE))).isEqualTo(TargetType.MUTE)
    }

    @Test fun `the blocked source walks the blocked list`() {
        assertThat(listWalkedBy(dateBased(TargetType.USER))).isEqualTo(TargetType.USER)
    }

    /** A run checkpointed before the field existed began against the blocked list. */
    @Test fun `a request with no list falls back to the blocked list`() {
        assertThat(listWalkedBy(dateBased(null))).isEqualTo(TargetType.USER)
    }

    /**
     * The author-list source never reaches this branch: it is resolved to nicks
     * and sent as LIST, which is what the shared backend's ban_source means.
     */
    @Test fun `an author-list run is a list run, not a relation-list walk`() {
        val task = factory.create(
            OperationRequest(
                source = BanSource.LIST,
                mode = BanMode.UNDOBAN,
                targetType = TargetType.USER,
                nicks = listOf("someone"),
            ),
        )
        assertThat(task).isNotInstanceOf(RelationListTask::class.java)
    }
}
