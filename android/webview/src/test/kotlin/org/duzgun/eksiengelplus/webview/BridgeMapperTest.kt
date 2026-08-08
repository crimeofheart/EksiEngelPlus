package org.duzgun.eksiengelplus.webview

import com.google.common.truth.Truth.assertThat
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.junit.Test

/**
 * The bridge boundary. The page is injected into a third-party site that can
 * change under us, so a malformed payload must fail visibly rather than start
 * the wrong operation on the wrong person.
 */
class BridgeMapperTest {

    @Test fun `a single block maps through`() {
        val r = BridgeMapper.toRequest(
            EnqueuePayload(
                banSource = 1, banMode = 1, targetType = 1,
                authorName = "someone", authorId = 42,
            ),
        )!!
        assertThat(r.source).isEqualTo(BanSource.SINGLE)
        assertThat(r.mode).isEqualTo(BanMode.BAN)
        assertThat(r.targetType).isEqualTo(TargetType.USER)
        assertThat(r.authorNick).isEqualTo("someone")
        assertThat(r.authorId).isEqualTo(42)
    }

    @Test fun `mute is carried as a target type, not a separate source`() {
        val r = BridgeMapper.toRequest(
            EnqueuePayload(banSource = 1, banMode = 1, targetType = 3, authorName = "x"),
        )!!
        assertThat(r.targetType).isEqualTo(TargetType.MUTE)
    }

    @Test fun `the entry id is recovered from the url when not sent explicitly`() {
        // scrapingHandler.js:130 takes the trailing digit run.
        val r = BridgeMapper.toRequest(
            EnqueuePayload(banSource = 2, banMode = 1, entryUrl = "https://eksisozluk.com/entry/14065731"),
        )!!
        assertThat(r.entryId).isEqualTo(14065731)
    }

    @Test fun `a fav run carries the entry's author, which is what names it`() {
        // The run targets the favouriters, but the nick on screen has to be the
        // author whose entry was clicked -- otherwise three queued "favlayanlar"
        // rows are the same row three times.
        val r = BridgeMapper.toRequest(
            EnqueuePayload(banSource = 2, banMode = 1, authorName = "coh", entryId = 7),
        )!!
        assertThat(r.authorNick).isEqualTo("coh")
    }

    @Test fun `an explicit entry id wins over the url`() {
        val r = BridgeMapper.toRequest(
            EnqueuePayload(
                banSource = 2, banMode = 1, entryId = 99,
                entryUrl = "https://eksisozluk.com/entry/14065731",
            ),
        )!!
        assertThat(r.entryId).isEqualTo(99)
    }

    @Test fun `the last-24h specifier becomes lastDayOnly`() {
        val r = BridgeMapper.toRequest(
            EnqueuePayload(
                banSource = 6, banMode = 1, titleName = "slug", titleId = 5, timeSpecifier = 1,
            ),
        )!!
        assertThat(r.lastDayOnly).isTrue()
        assertThat(r.titleSlug).isEqualTo("slug")
    }

    @Test fun `the all specifier does not`() {
        val r = BridgeMapper.toRequest(
            EnqueuePayload(banSource = 6, banMode = 1, titleName = "s", titleId = 5, timeSpecifier = 5),
        )!!
        assertThat(r.lastDayOnly).isFalse()
    }

    @Test fun `an unknown ban source is rejected rather than guessed`() {
        assertThat(BridgeMapper.toRequest(EnqueuePayload(banSource = 99, banMode = 1))).isNull()
    }

    @Test fun `a missing mode is rejected`() {
        assertThat(BridgeMapper.toRequest(EnqueuePayload(banSource = 1))).isNull()
    }

    @Test fun `an absent target type defaults to user rather than failing`() {
        val r = BridgeMapper.toRequest(EnqueuePayload(banSource = 1, banMode = 1, authorName = "x"))!!
        assertThat(r.targetType).isEqualTo(TargetType.USER)
    }

    @Test fun `every source the bridge can send maps`() {
        // The six the engine currently serves; the rest arrive with later changes.
        listOf(1, 2, 3, 4, 5, 6).forEach { pk ->
            assertThat(BridgeMapper.toRequest(EnqueuePayload(banSource = pk, banMode = 1))).isNotNull()
        }
    }
}
