package org.duzgun.eksiengelplus.eksi.parser

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test

/**
 * Shapes observed on a real device during android-spike. No committed HTML for
 * these -- capturing them would embed a live session's content in the repo -- so
 * the payloads here are transcribed from the recorded device output.
 */
class JsonShapeTest {

    @Test
    fun `populated relation list parses with 25 items`() {
        val items = (1..25).joinToString(",") {
            """{"Id":$it,"Nick":{"Value":"user $it"}}"""
        }
        val json = """{"Relations":{"IsLast":false,"Items":[$items]}}"""
        val r = EksiJson.decodeFromString(RelationListResponse.serializer(), json)
        assertThat(r.relations.isLast).isFalse()
        assertThat(r.relations.items).hasSize(25)
        assertThat(r.relations.items.first().nick.value).isEqualTo("user 1")
    }

    @Test
    fun `empty relation list still yields a parseable envelope`() {
        val r = EksiJson.decodeFromString(
            RelationListResponse.serializer(),
            """{"Relations":{"IsLast":true,"Items":[]}}""",
        )
        assertThat(r.relations.isLast).isTrue()
        assertThat(r.relations.items).isEmpty()
    }

    @Test
    fun `unknown fields are ignored`() {
        // removerelation already returns an undocumented `count`; the site adds
        // fields without notice, so parsing must tolerate it.
        val r = EksiJson.decodeFromString(
            RelationListResponse.serializer(),
            """{"Relations":{"IsLast":true,"Items":[],"Total":0},"Extra":"x"}""",
        )
        assertThat(r.relations.items).isEmpty()
    }

    @Test
    fun `follower array parses and defaults the boolean flags`() {
        val json = """[{"Id":7,"Nick":{"Value":"0 derece"},"IsFollowCurrentUser":true,"IsBuddy":false},
                       {"Id":8,"Nick":{"Value":"solo"}}]"""
        val list = EksiJson.decodeFromString(ListSerializer(FollowUser.serializer()), json)
        assertThat(list).hasSize(2)
        assertThat(list[0].nick.value).isEqualTo("0 derece")
        assertThat(list[0].isFollowCurrentUser).isTrue()
        assertThat(list[1].isBuddy).isFalse()
    }

    @Test
    fun `empty follower page is the pagination terminator`() {
        val list = EksiJson.decodeFromString(ListSerializer(FollowUser.serializer()), "[]")
        assertThat(list).isEmpty()
    }
}
