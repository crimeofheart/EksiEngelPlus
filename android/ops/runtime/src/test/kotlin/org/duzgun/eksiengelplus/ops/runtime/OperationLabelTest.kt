package org.duzgun.eksiengelplus.ops.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.runtime.OperationLabel.Kind
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

    // ------------------------------------------------------------ what it does

    private fun op(
        source: BanSource,
        mode: BanMode = BanMode.BAN,
        target: TargetType = TargetType.USER,
        then: TargetType? = null,
    ) = OperationRequest(source = source, mode = mode, targetType = target, thenApplyTo = then)

    /**
     * The reported bug, on the extension's İşlem durumu: every follow queued as
     * "Engelleme". Here the label named only the audience, so a follow and a
     * block of the same people were the same row.
     */
    @Test fun `every follow audience is a follow`() {
        for (source in listOf(BanSource.SINGLE, BanSource.FAV, BanSource.FOLLOW, BanSource.FOLLOWEES)) {
            assertThat(OperationLabel.kind(op(source, target = TargetType.FOLLOW)))
                .isEqualTo(Kind.FOLLOW)
        }
    }

    @Test fun `mode and target type decide the rest`() {
        assertThat(OperationLabel.kind(op(BanSource.FAV))).isEqualTo(Kind.BLOCK)
        assertThat(OperationLabel.kind(op(BanSource.FAV, target = TargetType.MUTE))).isEqualTo(Kind.MUTE)
        assertThat(OperationLabel.kind(op(BanSource.SINGLE, BanMode.UNDOBAN))).isEqualTo(Kind.UNBLOCK)
        assertThat(OperationLabel.kind(op(BanSource.SINGLE, BanMode.UNDOBAN, TargetType.MUTE)))
            .isEqualTo(Kind.UNMUTE)
        assertThat(OperationLabel.kind(op(BanSource.LIST, BanMode.UNDOBAN, TargetType.FOLLOW)))
            .isEqualTo(Kind.UNFOLLOW)
        assertThat(OperationLabel.kind(op(BanSource.SINGLE, target = TargetType.TITLE))).isEqualTo(Kind.BLOCK)
    }

    @Test fun `unblock-then-follow is a follow`() {
        assertThat(
            OperationLabel.kind(op(BanSource.DATE_BASED_BULK, BanMode.UNDOBAN, TargetType.USER, then = TargetType.FOLLOW)),
        ).isEqualTo(Kind.FOLLOW)
    }

    @Test fun `title runs over the blocked and muted lists go both ways`() {
        val titles = BanSource.BLOCKED_MUTED_TITLES
        assertThat(OperationLabel.kind(op(titles, target = TargetType.TITLE))).isEqualTo(Kind.BLOCK)
        assertThat(OperationLabel.kind(op(titles, BanMode.UNDOBAN, TargetType.TITLE))).isEqualTo(Kind.UNBLOCK)
    }

    @Test fun `sources whose name says what they do carry no kind`() {
        val named = listOf(
            BanSource.UNDOBANALL, BanSource.UNMUTEALL, BanSource.MIGRATE_BLOCKED_TO_MUTED,
            BanSource.BLOCK_MUTED_USERS, BanSource.REFRESH_BLOCKED_LIST,
            BanSource.REFRESH_MUTED_LIST, BanSource.REFRESH_FOLLOWED_LIST,
        )
        for (source in named) assertThat(OperationLabel.kind(op(source, BanMode.UNDOBAN))).isNull()
    }

    @Test fun `the kind comes back out of a stored request, and a bad one has none`() {
        val json = Json.encodeToString(
            OperationRequest.serializer(),
            op(BanSource.FOLLOW, target = TargetType.FOLLOW),
        )
        assertThat(OperationLabel.kindFromRequest(json)).isEqualTo(Kind.FOLLOW)
        assertThat(OperationLabel.kindFromRequest("{not json")).isNull()
        assertThat(OperationLabel.kindFromRequest(null)).isNull()
    }
}
