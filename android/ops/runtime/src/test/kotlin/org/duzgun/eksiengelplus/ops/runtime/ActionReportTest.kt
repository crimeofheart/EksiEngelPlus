package org.duzgun.eksiengelplus.ops.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.duzgun.eksiengelplus.datastore.EksiConfig
import org.duzgun.eksiengelplus.model.BanMode
import org.duzgun.eksiengelplus.model.BanSource
import org.duzgun.eksiengelplus.model.TargetType
import org.duzgun.eksiengelplus.ops.engine.OperationRequest
import org.junit.Test

/**
 * The report the backend will accept.
 *
 * Every assertion here is a field `WriteActionViewSerializer` requires or a shape
 * `CollectActionDataSerializer` imposes. The previous body satisfied neither --
 * flat instead of nested, and missing the user the whole record is keyed to -- so
 * these are the checks that would have caught it.
 */
class ActionReportTest {

    private fun body(
        request: OperationRequest = OperationRequest(
            source = BanSource.FAV,
            mode = BanMode.BAN,
            targetType = TargetType.USER,
            authorNick = "coh",
            authorId = 42,
        ),
        config: EksiConfig = EksiConfig(),
        nick: String = "someone",
        userId: Long = 7,
        logLines: List<String> = emptyList(),
        targets: List<Pair<String, Long>> = listOf("victim" to 99L),
    ): JsonObject = Json.parseToJsonElement(
        ActionReport.body(
            request = request,
            config = config,
            nick = nick,
            userId = userId,
            version = "0.1.7",
            userAgent = "test-agent",
            plannedAction = 10,
            performedAction = 8,
            successfulAction = 7,
            isEarlyStopped = true,
            logLines = logLines,
            targets = targets,
        ),
    ).jsonObject

    private val JsonObject.action get() = this["action"]!!.jsonObject

    @Test fun `the body nests under action and action_config`() {
        // CollectActionDataSerializer has exactly these two keys; a flat object is
        // rejected before a single field is examined.
        val b = body()
        assertThat(b.keys).containsExactly("action", "action_config")
    }

    @Test fun `the reporting user is present, which is what keys the record`() {
        val user = body().action["eksi_engel_user"]!!.jsonObject
        assertThat(user["eksisozluk_name"]!!.jsonPrimitive.content).isEqualTo("someone")
        assertThat(user["eksisozluk_id"]!!.jsonPrimitive.content).isEqualTo("7")
    }

    @Test fun `the client is named, so an app run is not read as an extension one`() {
        assertThat(body().action["client"]!!.jsonPrimitive.content).isEqualTo("ANDROID")
    }

    @Test fun `every field the serializer requires is present`() {
        val a = body().action
        // Action's non-null model fields, minus the auto date and the pk.
        assertThat(a.keys).containsAtLeast(
            "eksi_engel_user", "version", "user_agent", "ban_source", "ban_mode",
            "author_list", "author_list_size", "planned_action", "performed_action",
            "successful_action", "is_early_stopped", "log_level",
        )
    }

    @Test fun `the counters are the run's own, not the request's`() {
        val a = body()
        assertThat(a.action["planned_action"]!!.jsonPrimitive.content).isEqualTo("10")
        assertThat(a.action["performed_action"]!!.jsonPrimitive.content).isEqualTo("8")
        assertThat(a.action["successful_action"]!!.jsonPrimitive.content).isEqualTo("7")
        assertThat(a.action["is_early_stopped"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test fun `lookup ids are sent as the seeded primary keys`() {
        val a = body().action
        // 0007_seed_lookup_data.py: FAV is BanSource 2, BAN is BanMode 1.
        assertThat(a["ban_source"]!!.jsonPrimitive.content).isEqualTo(BanSource.FAV.pk.toString())
        assertThat(a["ban_mode"]!!.jsonPrimitive.content).isEqualTo(BanMode.BAN.pk.toString())
    }

    @Test fun `a fav run names the entry's author`() {
        val fav = body().action["fav_author"]!!.jsonObject
        assertThat(fav["eksisozluk_name"]!!.jsonPrimitive.content).isEqualTo("coh")
    }

    @Test fun `a half-known user is null rather than half-filled`() {
        // createEksiSozlukUser returns null without both halves; the serializer
        // treats a name with no id as invalid, not as unknown.
        val a = body(
            request = OperationRequest(
                source = BanSource.SINGLE,
                mode = BanMode.BAN,
                authorNick = "coh",
                authorId = null,
            ),
        ).action
        assertThat(a["fav_author"]).isEqualTo(JsonNull)
    }

    @Test fun `logs ride along when the user allows them`() {
        val a = body(logLines = listOf("one", "two")).action
        assertThat(a["log"]!!.jsonPrimitive.content).isEqualTo("one\ntwo")
        assertThat(a["log_level"]!!.jsonPrimitive.content).isEqualTo("2")
    }

    @Test fun `logs are withheld when the user turned them off`() {
        val a = body(config = EksiConfig(sendLog = false), logLines = listOf("one")).action
        assertThat(a["log"]).isEqualTo(JsonNull)
        assertThat(a["log_level"]!!.jsonPrimitive.content).isEqualTo("1")
    }

    @Test fun `an oversized log is truncated below the serializer's limit`() {
        val a = body(logLines = listOf("x".repeat(50_000))).action
        assertThat(a["log"]!!.jsonPrimitive.content.length).isAtMost(10_000)
    }

    @Test fun `the settings snapshot goes with it`() {
        val cfg = Json.parseToJsonElement(
            ActionReport.body(
                request = OperationRequest(source = BanSource.SINGLE, mode = BanMode.BAN),
                config = EksiConfig(enableMute = false, sendData = true),
                nick = "n", userId = 1, version = "v", userAgent = "ua",
                plannedAction = 0, performedAction = 0, successfulAction = 0,
                isEarlyStopped = false, logLines = emptyList(), targets = emptyList(),
            ),
        ).jsonObject["action_config"]!!.jsonObject
        assertThat(cfg["enable_mute"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(cfg["send_data"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test fun `everyone the run acted on is reported, in plaintext`() {
        // android-persistence records this as a weighed decision: the admin's
        // most_banned views rank by nick and id, so an empty list would make
        // every Android run invisible to them.
        val list = body(targets = listOf("a" to 1L, "b" to 2L)).action
        assertThat(list["author_list_size"]!!.jsonPrimitive.content).isEqualTo("2")
        val first = list["author_list"]!!.jsonArray[0].jsonObject
        assertThat(first["eksisozluk_name"]!!.jsonPrimitive.content).isEqualTo("a")
        assertThat(first["eksisozluk_id"]!!.jsonPrimitive.content).isEqualTo("1")
    }

    @Test fun `a run against nobody reports an empty list, not a missing one`() {
        val a = body(targets = emptyList()).action
        assertThat(a["author_list"]!!.toString()).isEqualTo("[]")
        assertThat(a["author_list_size"]!!.jsonPrimitive.content).isEqualTo("0")
    }
}
