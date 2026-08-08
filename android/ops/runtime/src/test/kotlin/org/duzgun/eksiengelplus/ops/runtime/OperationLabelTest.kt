package org.duzgun.eksiengelplus.ops.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.junit.Test

/**
 * The nick a run is named after, and the two places it has to survive: the
 * stored request while the run is live, and the summary once the request is
 * gone. Rendering needs a Context and is exercised on device; everything that
 * decides *what* to render is here.
 */
class OperationLabelTest {

    private fun request(
        source: BanSource,
        nick: String? = null,
        title: String? = null,
        nicks: List<String> = emptyList(),
    ) = OperationRequest(
        source = source,
        mode = BanMode.BAN,
        authorNick = nick,
        titleSlug = title,
        nicks = nicks,
    )

    @Test fun `single, fav and follow are all named after the author`() {
        for (source in listOf(BanSource.SINGLE, BanSource.FAV, BanSource.FOLLOW)) {
            assertThat(OperationLabel.target(request(source, nick = "coh"))).isEqualTo("coh")
        }
    }

    @Test fun `a title run is named after the title`() {
        assertThat(OperationLabel.target(request(BanSource.TITLE, title = "pena")))
            .isEqualTo("pena")
    }

    @Test fun `a one-name list is named after that name`() {
        assertThat(OperationLabel.target(request(BanSource.LIST, nicks = listOf("coh"))))
            .isEqualTo("coh")
    }

    @Test fun `a many-name list names none of them`() {
        // Picking one of forty would misdescribe the run.
        assertThat(OperationLabel.target(request(BanSource.LIST, nicks = listOf("a", "b"))))
            .isNull()
    }

    @Test fun `a sweep over everything has no subject`() {
        assertThat(OperationLabel.target(request(BanSource.UNDOBANALL, nick = "coh"))).isNull()
    }

    @Test fun `a blank nick is treated as no nick, not as empty brackets`() {
        assertThat(OperationLabel.target(request(BanSource.SINGLE, nick = "  "))).isNull()
    }

    @Test fun `the nick comes back out of a stored request`() {
        val json = Json.encodeToString(
            OperationRequest.serializer(),
            request(BanSource.FAV, nick = "coh"),
        )
        assertThat(OperationLabel.targetFromRequest(json)).isEqualTo("coh")
    }

    @Test fun `a request that will not parse loses the nick, not the label`() {
        assertThat(OperationLabel.targetFromRequest("{not json")).isNull()
        assertThat(OperationLabel.targetFromRequest(null)).isNull()
    }

    @Test fun `the summary round-trips the nick into history`() {
        assertThat(OperationLabel.targetFromSummary(OperationLabel.summaryJson("coh")))
            .isEqualTo("coh")
    }

    @Test fun `a run with no nick writes a summary that still parses`() {
        val json = OperationLabel.summaryJson(null)
        assertThat(json).isEqualTo("{}")
        assertThat(OperationLabel.targetFromSummary(json)).isNull()
    }

    @Test fun `rows archived before this existed simply have no nick`() {
        // Everything already in completed_operation holds "{}".
        assertThat(OperationLabel.targetFromSummary("{}")).isNull()
        assertThat(OperationLabel.targetFromSummary("not json")).isNull()
        assertThat(OperationLabel.targetFromSummary(null)).isNull()
    }
}
