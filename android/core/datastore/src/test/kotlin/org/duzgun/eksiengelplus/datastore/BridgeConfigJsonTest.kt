package org.duzgun.eksiengelplus.datastore

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The page reads this JSON as plain properties, so a missing field is not a
 * default -- it is undefined, and undefined is falsy.
 *
 * That is not hypothetical: enableMute defaulted to true, the default encoder
 * omitted it, and every menu in the app silently went back to saying "engelle".
 */
class BridgeConfigJsonTest {

    private val fields = EksiConfig.serializer().descriptor.let { d ->
        (0 until d.elementsCount).map { d.getElementName(it) }
    }

    @Test fun `every field survives, whatever its value`() {
        val encoded = Json.parseToJsonElement(BridgeConfigJson.encode(EksiConfig())).jsonObject

        assertThat(encoded.keys).containsAtLeastElementsIn(fields)
    }

    /** The failing case exactly: a value equal to its default must still ship. */
    @Test fun `a value matching its default is not dropped`() {
        val encoded = Json.parseToJsonElement(
            BridgeConfigJson.encode(EksiConfig(enableMute = true)),
        ).jsonObject

        assertThat(encoded).containsKey("enableMute")
        assertThat(encoded["enableMute"].toString()).isEqualTo("true")
    }

    @Test fun `the page can read a flag the user turned off`() {
        val encoded = Json.parseToJsonElement(
            BridgeConfigJson.encode(EksiConfig(enableMute = false)),
        ).jsonObject

        assertThat(encoded["enableMute"].toString()).isEqualTo("false")
    }
}
